package io.jaravel.queue.store;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueuedJob;

/**
 * SQS 队列驱动(docs/10 §1.5)。AWS SDK v2,同步客户端。
 *
 * <h2>与 SPI 的映射</h2>
 * <ul>
 *   <li>{@code pop} = {@code ReceiveMessage} + {@code VisibilityTimeout};
 *       unacked 消息在可见性超时后自动重投 —— 这是三者里与
 *       {@code retryAfter} 语义最贴合的;</li>
 *   <li>{@code delete} = {@code DeleteMessage};</li>
 *   <li>{@code release} = 发一条更新后的 payload + 删原消息(不原地改消息,
 *       attempts / exceptions 才不会丢);</li>
 *   <li>{@code touch} = {@code ChangeMessageVisibility};receipt 失效时返回
 *       false —— 与其它驱动的「任务已经不属于我了」一致。</li>
 * </ul>
 *
 * <h2>SQS 的硬限制(文档必须写清)</h2>
 * <ul>
 *   <li><b>延迟最大 15 分钟</b>({@code DelaySeconds} ≤ 900)。更长的
 *       backoff 会被**截断到 900 秒** —— 不会丢任务,但节奏与配置不同;</li>
 *   <li>可见性超时最大 12 小时;</li>
 *   <li><b>标准队列不保证顺序</b>(要严格 FIFO 请用 .fifo 队列,当前不做);</li>
 *   <li>至少一次投递 —— 消费者崩在 delete 之前会重投,任务可能执行两次;</li>
 *   <li>消息体最大 256KB;</li>
 *   <li>{@code size} 是近似值(visible + delayed),不含 in-flight;
 *       {@code clear} 的 purge 最多需要 60 秒才完全生效;</li>
 *   <li>payload 里的 attempts 在「可见性超时重投」时不会增加 ——
 *       与 RabbitMQ 同一限制,严格计数需要额外的尝试存储。</li>
 * </ul>
 *
 * <h2>队列命名</h2>
 * SQS 只允许字母、数字、下划线与连字符,最长 80。queue 名会被
 * **规整**(非法字符换成连字符并按 80 截断);不同 queue 名截断后
 * 可能撞名,超过 80 的队列名请自己缩短。
 */
public class SqsQueue implements QueueStore, AutoCloseable {

    public static final String DEFAULT_PREFIX = "jaravel-queue-";

    private static final int MAX_DELAY_SECONDS = 900;
    private static final int MAX_VISIBILITY_SECONDS = 12 * 60 * 60;

    private final SqsClient client;
    private final boolean ownsClient;
    private final JobSerializer serializer;
    private final String prefix;

    private final Map<String, String> queueUrls = new ConcurrentHashMap<>();
    private final Map<String, String> receipts = new ConcurrentHashMap<>();

    public SqsQueue(SqsClient client, JobSerializer serializer, String prefix) {
        this(client, false, serializer, prefix);
    }

    private SqsQueue(SqsClient client, boolean ownsClient,
                     JobSerializer serializer, String prefix) {
        this.client = client;
        this.ownsClient = ownsClient;
        this.serializer = serializer;
        this.prefix = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix;
    }

    /**
     * 建一个自己管理的客户端。
     *
     * @param endpoint 空 = 走 AWS 默认端点(LocalStack/ElasticMQ 时填
     *                 {@code http://127.0.0.1:9324})
     */
    public static SqsQueue connect(String endpoint, String region,
                                   String accessKey, String secretKey,
                                   JobSerializer serializer, String prefix) {
        var builder = SqsClient.builder()
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .httpClientBuilder(UrlConnectionHttpClient.builder());

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (accessKey != null && !accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey == null ? "" : secretKey)));
        }
        return new SqsQueue(builder.build(), true, serializer, prefix);
    }

    @Override public String name() { return "sqs"; }

    // ================= 队列 =================

    private String sqsName(String queue) {
        String name = (prefix + queue).replaceAll("[^A-Za-z0-9_-]", "-");
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    private String queueUrl(String queue) {
        return queueUrls.computeIfAbsent(queue, q -> {
            String name = sqsName(q);
            try {
                return client.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(name).build()).queueUrl();
            } catch (QueueDoesNotExistException e) {
                try {
                    return client.createQueue(CreateQueueRequest.builder()
                            .queueName(name)
                            .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "30"))
                            .build()).queueUrl();
                } catch (QueueNameExistsException raced) {
                    return client.getQueueUrl(GetQueueUrlRequest.builder()
                            .queueName(name).build()).queueUrl();
                }
            }
        });
    }

    // ================= 入队 =================

    @Override
    public String push(QueuedJob job) {
        long now = System.currentTimeMillis();
        long availableAt = job.availableAt() == null ? now : job.availableAt().toEpochMilli();
        send(job.queue(), serializer.encode(job), job.uuid(), delaySeconds(availableAt - now));
        return job.uuid();
    }

    private void send(String queue, String payload, String uuid, int delaySeconds) {
        client.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl(queue))
                .messageBody(payload)
                .delaySeconds(delaySeconds)
                .build());
    }

    private static int delaySeconds(long delayMs) {
        if (delayMs <= 0) return 0;
        long seconds = (delayMs + 999) / 1000;
        return (int) Math.min(MAX_DELAY_SECONDS, seconds);
    }

    // ================= 取出 =================

    @Override
    public Optional<QueuedJob> pop(String queue, Duration retryAfter) {
        var response = client.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl(queue))
                .maxNumberOfMessages(1)
                .visibilityTimeout(visibilitySeconds(retryAfter))
                .waitTimeSeconds(0)
                .build());

        if (response.messages().isEmpty()) return Optional.empty();

        Message message = response.messages().get(0);
        String receipt = message.receiptHandle();
        QueuedJob job;
        try {
            QueuedJob decoded = serializer.decode(message.body());
            job = decoded.withAttempts(decoded.attempts() + 1);
        } catch (RuntimeException e) {
            // 坏 payload 不能留在队列里无限重投
            deleteReceipt(queue, receipt);
            throw new IllegalStateException("""
                    SQS 上的一条任务无法解析 —— 已丢弃,避免无限重投。
                      payload 可能来自另一个版本,或被手工改过。
                      %s""".formatted(e.getMessage()), e);
        }

        receipts.put(job.uuid(), receipt);
        return Optional.of(job);
    }

    private static int visibilitySeconds(Duration retryAfter) {
        long seconds = retryAfter == null ? 60 : Math.max(1, retryAfter.toSeconds());
        return (int) Math.min(MAX_VISIBILITY_SECONDS, seconds);
    }

    // ================= 完成 / 放回 =================

    @Override
    public void delete(QueuedJob job) {
        String receipt = receipts.remove(job.uuid());
        if (receipt != null) deleteReceipt(job.queue(), receipt);
    }

    private void deleteReceipt(String queue, String receipt) {
        try {
            client.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl(queue))
                    .receiptHandle(receipt)
                    .build());
        } catch (SqsException ignored) {
            // receipt 失效 = 消息已经被别人接手;让它重投/由对方处理
        }
    }

    @Override
    public void release(QueuedJob job, Duration delay) {
        String receipt = receipts.remove(job.uuid());
        int delaySeconds = delaySeconds(delay == null ? 0 : delay.toMillis());

        // 先发更新后的 payload,再删原消息。反过来会在发送失败时丢任务;
        // 发送成功但删除失败只会造成一次重复投递(at-least-once)。
        send(job.queue(), serializer.encode(job), job.uuid(), delaySeconds);
        if (receipt != null) deleteReceipt(job.queue(), receipt);
    }

    @Override
    public boolean touch(QueuedJob job, Duration retryAfter) {
        String receipt = receipts.get(job.uuid());
        if (receipt == null) return false;

        try {
            client.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl(job.queue()))
                    .receiptHandle(receipt)
                    .visibilityTimeout(visibilitySeconds(retryAfter))
                    .build());
            return true;
        } catch (SqsException e) {
            // receipt 失效 / 消息已经不属于本消费者
            receipts.remove(job.uuid(), receipt);
            return false;
        }
    }

    // ================= 其它 =================

    @Override
    public long size(String queue) {
        var attributes = client.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl(queue))
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)
                .build()).attributes();

        long visible = parse(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        long delayed = parse(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED));
        return visible + delayed;
    }

    private static long parse(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void clear(String queue) {
        try {
            client.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl(queue)).build());
        } catch (SqsException e) {
            // PurgeQueueInProgress(60 秒内重复 purge)可以忽略;其它错误要说清
            if (!e.getMessage().contains("PurgeQueueInProgress")) throw e;
        }
    }

    @Override
    public void close() {
        if (ownsClient) client.close();
    }
}
