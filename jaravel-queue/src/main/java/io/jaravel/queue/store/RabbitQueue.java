package io.jaravel.queue.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueuedJob;

/**
 * RabbitMQ 队列驱动(docs/10 §1.5)。基于官方 {@code amqp-client}。
 *
 * <h2>队列布局</h2>
 * <pre>
 * {prefix}{queue}         主队列(durable)
 * {prefix}{queue}:delay   延迟队列(durable;x-dead-letter 指回主队列)
 * </pre>
 * 延迟用「per-message TTL + 死信」:消息在 delay 队列里到期后由 broker
 * 投回主队列。**已知限制**:同一条延迟队列里,先入的长 TTL 消息会挡住
 * 后入的短 TTL 消息(head-of-line)。重试 backoff 通常是递增的
 * (10s → 60s → 300s),这个顺序下不会触发;真要任意乱序延迟,应该
 * 按 TTL 分桶或使用 delayed-message 插件。
 *
 * <h2>pop 与 ack 的语义差异</h2>
 * <p>{@code pop} 用 {@code basicGet(..., false)},消息保持 unacked;
 * {@code delete} = ack,{@code release} = 发布更新后的 payload 再 ack
 * 原消息(不用 requeue:原 payload 里的 attempts 是旧的)。
 *
 * <p><b>没有可见性超时</b>:{@code retryAfter} 被忽略,{@code touch}
 * 只检查这条消息是否还在本实例的 unacked 表里。worker 进程死掉时
 * 连接断开,broker 立刻重新投递;但 worker **还活着只是卡住**时,
 * 消息会一直 unacked。这是 AMQP 的模型,不是可以在这里补的。
 *
 * <p><b>崩溃重投不增加 attempts</b>:attempts 存在 payload 里,而我们
 * 不能在 broker 上原地修改消息;worker 崩在 release 之前时,重投的
 * payload 还是旧计数。要严格计数需要外的尝试存储(Redis/DB),
 * 当前版本不做 —— 文档里写明。
 *
 * <p><b>size 不含 unacked</b>:主队列的 ready + 延迟队列的消息数;
 * 已被本实例取走、尚未 ack 的不在其中。clear 只 purge ready 消息,
 * unacked 仍然在飞。
 *
 * <h2>线程模型</h2>
 * <p>AMQP 的 {@link Channel} 不是线程安全的,而 worker 的 pop / delete /
 * release 来自不同线程(虚拟线程任务)。这里用一把锁串行化所有命令,
 * 并用 publisher confirms 等 broker 确认。代价是吞吐上限,正确性优先。
 */
public class RabbitQueue implements QueueStore, AutoCloseable {

    public static final String DEFAULT_PREFIX = "jaravel:queue:";

    private final String uri;
    private final String prefix;
    private final JobSerializer serializer;

    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> declared = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> inFlight = new ConcurrentHashMap<>();

    private Connection connection;
    private Channel channel;
    private volatile boolean closed;

    public RabbitQueue(String uri, JobSerializer serializer) {
        this(uri, serializer, DEFAULT_PREFIX);
    }

    public RabbitQueue(String uri, JobSerializer serializer, String prefix) {
        this.uri = uri;
        this.prefix = prefix == null ? "" : prefix;
        this.serializer = serializer;
    }

    @Override public String name() { return "rabbitmq"; }

    // ================= 连接 =================

    private Channel ch() {
        try {
            if (channel != null && channel.isOpen()) return channel;
        } catch (RuntimeException ignored) {
            // isOpen 在某些代理实现上会抛;按断开处理
        }

        if (closed) throw new IllegalStateException("RabbitQueue 已关闭");

        closeQuietly();
        inFlight.clear();
        declared.clear();

        try {
            var factory = new ConnectionFactory();
            factory.setUri(uri);
            connection = factory.newConnection();
            channel = connection.createChannel();
            channel.confirmSelect();
            return channel;
        } catch (IOException | TimeoutException | java.net.URISyntaxException
                 | java.security.NoSuchAlgorithmException
                 | java.security.KeyManagementException e) {
            throw new IllegalStateException("""
                    连不上 RabbitMQ(检查 queue.connections.<name>.uri)。
                      %s
                      本地可以用 docker run -d -p 5672:5672 rabbitmq:3-management""".formatted(
                    e.getMessage()), e);
        }
    }

    private String queueName(String queue) {
        return prefix + queue;
    }

    private String delayName(String queue) {
        return prefix + queue + ":delay";
    }

    private void declare(String queue, Channel c) {
        String name = queueName(queue);
        if (declared.contains(name)) return;

        try {
            c.queueDeclare(name, true, false, false, null);
            c.queueDeclare(delayName(queue), true, false, false, Map.of(
                    "x-dead-letter-exchange", "",
                    "x-dead-letter-routing-key", name));
            declared.add(name);
        } catch (IOException e) {
            throw new IllegalStateException("声明 RabbitMQ 队列失败:" + name, e);
        }
    }

    // ================= 入队 =================

    @Override
    public String push(QueuedJob job) {
        lock.lock();
        try {
            Channel c = ch();
            declare(job.queue(), c);

            long now = System.currentTimeMillis();
            long availableAt = job.availableAt() == null ? now : job.availableAt().toEpochMilli();
            long delay = Math.max(0, availableAt - now);

            publish(c, job.queue(), serializer.encode(job), job.uuid(), delay);
            return job.uuid();
        } finally {
            lock.unlock();
        }
    }

    private void publish(Channel c, String queue, String payload, String uuid, long delayMs) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        var builder = new AMQP.BasicProperties.Builder()
                .deliveryMode(2)                 // persistent
                .contentType("application/json")
                .messageId(uuid);
        if (delayMs > 0) builder.expiration(String.valueOf(delayMs));
        AMQP.BasicProperties props = builder.build();

        try {
            if (delayMs > 0) {
                c.basicPublish("", delayName(queue), props, body);
            } else {
                c.basicPublish("", queueName(queue), props, body);
            }
            c.waitForConfirmsOrDie(5_000);
        } catch (IOException | TimeoutException e) {
            throw new IllegalStateException("发布 RabbitMQ 消息失败:" + uuid, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 RabbitMQ 确认时被中断:" + uuid, e);
        }
    }

    // ================= 取出 =================

    @Override
    public Optional<QueuedJob> pop(String queue, java.time.Duration retryAfter) {
        lock.lock();
        try {
            Channel c = ch();
            declare(queue, c);

            GetResponse response;
            try {
                response = c.basicGet(queueName(queue), false);
            } catch (IOException e) {
                throw new IllegalStateException("从 RabbitMQ 取任务失败:" + queue, e);
            }
            if (response == null) return Optional.empty();

            long tag = response.getEnvelope().getDeliveryTag();
            String payload = new String(response.getBody(), StandardCharsets.UTF_8);
            QueuedJob job;
            try {
                QueuedJob decoded = serializer.decode(payload);
                job = decoded.withAttempts(decoded.attempts() + 1);
            } catch (RuntimeException e) {
                // 坏 payload 不能留在队列里无限重投
                try {
                    c.basicNack(tag, false, false);
                } catch (IOException ignored) {
                    // 连接可能已经断了;消息会在重连后重投
                }
                throw new IllegalStateException("""
                        RabbitMQ 上的一条任务无法解析 —— 已丢弃,避免无限重投。
                          payload 可能来自另一个版本,或被手工改过。
                          %s""".formatted(e.getMessage()), e);
            }

            inFlight.put(job.uuid(), tag);
            return Optional.of(job);
        } finally {
            lock.unlock();
        }
    }

    // ================= 完成 / 放回 =================

    @Override
    public void delete(QueuedJob job) {
        lock.lock();
        try {
            Long tag = inFlight.remove(job.uuid());
            if (tag == null) return;

            try {
                ch().basicAck(tag, false);
            } catch (IOException e) {
                throw new IllegalStateException("ack RabbitMQ 消息失败:" + job.uuid(), e);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(QueuedJob job, java.time.Duration delay) {
        lock.lock();
        try {
            Long tag = inFlight.get(job.uuid());
            String payload = serializer.encode(job);
            long delayMs = delay == null ? 0 : Math.max(0, delay.toMillis());

            try {
                Channel c = ch();
                declare(job.queue(), c);
                // 先发布更新后的 payload(attempts/exceptions 都在里面),
                // 确认成功后再 ack 原消息。反过来做会在发布失败时丢任务。
                publish(c, job.queue(), payload, job.uuid(), delayMs);

                inFlight.remove(job.uuid());
                if (tag != null) c.basicAck(tag, false);
            } catch (IOException | RuntimeException e) {
                // 发布失败(或 ack 失败):把原消息还回去(requeue),不让任务消失
                inFlight.remove(job.uuid());
                if (tag != null) {
                    try {
                        ch().basicNack(tag, false, true);
                    } catch (IOException ignored) {
                        // 连接断了;broker 会重投
                    }
                }
                if (e instanceof IOException io) {
                    throw new IllegalStateException("ack RabbitMQ 消息失败:" + job.uuid(), io);
                }
                throw (RuntimeException) e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean touch(QueuedJob job, java.time.Duration retryAfter) {
        // AMQP 没有「按消息的保留超时」;消息要么在本通道的 unacked 表里,
        // 要么连接已断开、broker 会重投。touch 只回答前者。
        return inFlight.containsKey(job.uuid());
    }

    // ================= 其它 =================

    @Override
    public long size(String queue) {
        lock.lock();
        try {
            Channel c = ch();
            declare(queue, c);
            long ready = c.queueDeclarePassive(queueName(queue)).getMessageCount();
            long delayed = c.queueDeclarePassive(delayName(queue)).getMessageCount();
            return ready + delayed;
        } catch (IOException e) {
            throw new IllegalStateException("读取 RabbitMQ 队列长度失败:" + queue, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear(String queue) {
        lock.lock();
        try {
            Channel c = ch();
            declare(queue, c);
            c.queuePurge(queueName(queue));
            c.queuePurge(delayName(queue));
        } catch (IOException e) {
            throw new IllegalStateException("清空 RabbitMQ 队列失败:" + queue, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            closeQuietly();
            inFlight.clear();
            declared.clear();
        } finally {
            lock.unlock();
        }
    }

    private void closeQuietly() {
        try {
            if (channel != null && channel.isOpen()) channel.close();
        } catch (Exception ignored) {
            // 关闭路径不抛
        }
        try {
            if (connection != null && connection.isOpen()) connection.close();
        } catch (Exception ignored) {
            // 同上
        }
        channel = null;
        connection = null;
    }
}
