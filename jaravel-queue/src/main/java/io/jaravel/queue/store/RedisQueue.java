package io.jaravel.queue.store;

import java.time.Duration;
import java.util.Optional;

import io.jaravel.cache.store.RedisStore;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueuedJob;
import io.jaravel.support.Clock;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Redis 队列驱动(docs/10 §3)。**原子 pop 用 Lua**,不用
 * 「先读再删」—— 后者是两个 worker 拿到同一条任务的最常见原因,
 * 而非幂等任务(扣款、发邮件)被执行两次是生产事故。
 *
 * <h2>键布局(每个队列一套,前缀隔离)</h2>
 * <pre>
 * {p}queue:{name}:pending    list  —— 待取,RPUSH 入、LPOP 出(FIFO)
 * {p}queue:{name}:delayed    zset  —— 延迟任务,score = 可取时刻
 * {p}queue:{name}:reserved   zset  —— 已保留,score = 保留时刻
 * {p}queue:{name}:payloads   hash  —— uuid → 任务 JSON
 * {p}queue:{name}:attempts   hash  —— uuid → 尝试次数(权威值)
 * </pre>
 *
 * <h2>为什么 attempts 单独存一份</h2>
 * <p>payload 里的 attempts 会随 release 重写,但**崩溃的 worker 来不及
 * release** —— 如果只依赖 payload,一条崩溃 3 次的任务每次重取都会
 * 从旧 payload 的 attempts 开始,重试上限永远用不完。所以 pop 时
 * {@code HINCRBY} 一份独立的计数器,并以它为准。
 *
 * <h2>可见性超时</h2>
 * <p>{@link #pop} 的第一件事是回收 {@code reserved} 里超过
 * {@code retryAfter} 的条目(worker 崩了),再把到期的 delayed 移进
 * pending —— 三步在**同一个 Lua 脚本**里,不会有两个 worker 同时
 * 回收或同时取到同一条。
 *
 * <h2>连接</h2>
 * <p>复用 {@link RedisStore} 的惰性连接(URI 解析、首用才连、close);
 * 队列键空间与缓存键空间互不相干。不用它的人不必拉 Lettuce。
 */
public class RedisQueue implements QueueStore, AutoCloseable {

    public static final String DEFAULT_PREFIX = "jaravel:queue:";

    private final RedisStore connection;
    private final boolean ownsConnection;
    private final JobSerializer serializer;
    private final String prefix;

    /** 自己管理连接;第一次操作时才连 */
    public RedisQueue(String uri, JobSerializer serializer) {
        this(uri, serializer, DEFAULT_PREFIX);
    }

    public RedisQueue(String uri, JobSerializer serializer, String prefix) {
        this(new RedisStore(uri), serializer, prefix, true);
    }

    /** 复用外部连接(测试/多个驱动共享);close 不会关它 */
    public RedisQueue(RedisStore connection, JobSerializer serializer, String prefix) {
        this(connection, serializer, prefix, false);
    }

    private RedisQueue(RedisStore connection, JobSerializer serializer,
                       String prefix, boolean ownsConnection) {
        this.connection = connection;
        this.serializer = serializer;
        this.prefix = prefix == null ? "" : prefix;
        this.ownsConnection = ownsConnection;
    }

    @Override public String name() { return "redis"; }

    private RedisCommands<String, String> commands() {
        return connection.raw();
    }

    /** {@code prefix + queue + ":" + part} */
    private String key(String queue, String part) {
        return prefix + queue + ":" + part;
    }

    private String pending(String queue)  { return key(queue, "pending"); }
    private String delayed(String queue)  { return key(queue, "delayed"); }
    private String reserved(String queue) { return key(queue, "reserved"); }
    private String payloads(String queue) { return key(queue, "payloads"); }
    private String attempts(String queue) { return key(queue, "attempts"); }

    // ================= 入队 =================

    @Override
    public String push(QueuedJob job) {
        long now = Clock.millis();
        long availableAt = job.availableAt() == null ? now : job.availableAt().toEpochMilli();
        long delay = Math.max(0, availableAt - now);

        commands().eval(PUSH, ScriptOutputType.INTEGER,
                new String[]{payloads(job.queue()), attempts(job.queue()),
                             delayed(job.queue()), pending(job.queue())},
                job.uuid(),
                serializer.encode(job),
                String.valueOf(job.attempts()),
                String.valueOf(availableAt),
                String.valueOf(delay));

        return job.uuid();
    }

    // ================= 取出 =================

    @Override
    public Optional<QueuedJob> pop(String queue, Duration retryAfter) {
        long now = Clock.millis();
        long retry = retryAfter == null
                ? Duration.ofSeconds(60).toMillis()
                : Math.max(1, retryAfter.toMillis());

        String pending = pending(queue);
        String reserved = reserved(queue);
        String delayed = delayed(queue);
        String attempts = attempts(queue);
        String payloads = payloads(queue);

        // 正常情况下第一轮就拿到;只有遇到「payload 被清掉了但队列里
        // 还留着 uuid」的孤儿才会重试
        for (int i = 0; i < 3; i++) {
            // 单个字符串而不是多元素数组:不同 Lettuce/Redis 组合对
            // MULTI 的展开方式有过差异,而 uuid 里不可能出现 "|"
            String popped = commands().eval(
                    POP, ScriptOutputType.VALUE,
                    new String[]{pending, reserved, delayed, attempts},
                    String.valueOf(now), String.valueOf(now - retry));

            if (popped == null) return Optional.empty();

            int sep = popped.lastIndexOf('|');
            String uuid = popped.substring(0, sep);
            int attempt = Integer.parseInt(popped.substring(sep + 1));
            String payload = commands().hget(payloads, uuid);

            if (payload == null) {
                // 孤儿:reserved 里占了位,但任务数据已经没了
                commands().zrem(reserved, uuid);
                commands().hdel(attempts, uuid);
                continue;
            }
            return Optional.of(serializer.decode(payload).withAttempts(attempt));
        }
        return Optional.empty();
    }

    // ================= 完成 / 放回 / 续约 =================

    @Override
    public void delete(QueuedJob job) {
        commands().eval(DELETE, ScriptOutputType.INTEGER,
                new String[]{reserved(job.queue()), pending(job.queue()),
                             delayed(job.queue()), payloads(job.queue()),
                             attempts(job.queue())},
                job.uuid());
    }

    @Override
    public void release(QueuedJob job, Duration delay) {
        long now = Clock.millis();
        long wait = delay == null ? 0 : Math.max(0, delay.toMillis());
        long availableAt = now + wait;

        commands().eval(RELEASE, ScriptOutputType.INTEGER,
                new String[]{reserved(job.queue()), payloads(job.queue()),
                             attempts(job.queue()), delayed(job.queue()),
                             pending(job.queue())},
                job.uuid(),
                serializer.encode(job),
                String.valueOf(job.attempts()),
                String.valueOf(availableAt),
                String.valueOf(wait));
    }

    @Override
    public boolean touch(QueuedJob job, Duration retryAfter) {
        Object updated = commands().eval(TOUCH, ScriptOutputType.INTEGER,
                new String[]{reserved(job.queue())},
                job.uuid(), String.valueOf(Clock.millis()));
        return updated instanceof Number n && n.longValue() == 1L;
    }

    // ================= 其它 =================

    @Override
    public long size(String queue) {
        return commands().llen(pending(queue))
             + commands().zcard(delayed(queue))
             + commands().zcard(reserved(queue));
    }

    @Override
    public void clear(String queue) {
        commands().del(pending(queue), delayed(queue), reserved(queue),
                       payloads(queue), attempts(queue));
    }

    @Override
    public void close() {
        if (ownsConnection) connection.close();
    }

    // ================= Lua =================

    /**
     * 入队:payload + attempts + (延迟 ZSET / 待取 list) 一次写。
     */
    private static final String PUSH = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
            if tonumber(ARGV[5]) > 0 then
              redis.call('ZADD', KEYS[3], ARGV[4], ARGV[1])
            else
              redis.call('RPUSH', KEYS[4], ARGV[1])
            end
            return 1
            """;

    /**
     * 取一条:先回收失联的保留,再移入到期的延迟任务,最后原子地
     * 从 pending 取一条并放进 reserved。
     *
     * <pre>
     * KEYS: pending / reserved / delayed / attempts
     * ARGV: now / staleBefore
     * </pre>
     */
    private static final String POP = """
            local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[2])
            for _, id in ipairs(stale) do
              redis.call('ZREM', KEYS[2], id)
              redis.call('RPUSH', KEYS[1], id)
            end
            local due = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', ARGV[1])
            for _, id in ipairs(due) do
              redis.call('ZREM', KEYS[3], id)
              redis.call('RPUSH', KEYS[1], id)
            end
            local uuid = redis.call('LPOP', KEYS[1])
            if not uuid then
              return nil
            end
            local attempts = redis.call('HINCRBY', KEYS[4], uuid, 1)
            redis.call('ZADD', KEYS[2], ARGV[1], uuid)
            return uuid .. '|' .. attempts
            """;

    /**
     * 放回:重写 payload(异常次数/链上剩余环都在里面),移出 reserved,
     * 再进 delayed 或 pending。
     *
     * <pre>
     * KEYS: reserved / payloads / attempts / delayed / pending
     * ARGV: uuid / payload / attempts / availableAt / delayMillis
     * </pre>
     */
    private static final String RELEASE = """
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
            redis.call('HSET', KEYS[3], ARGV[1], ARGV[3])
            if tonumber(ARGV[5]) > 0 then
              redis.call('ZADD', KEYS[4], ARGV[4], ARGV[1])
            else
              redis.call('RPUSH', KEYS[5], ARGV[1])
            end
            return 1
            """;

    /**
     * 续约:只在 uuid **仍处于 reserved** 时更新保留时刻。
     *
     * <pre>KEYS: reserved;ARGV: uuid / now</pre>
     */
    private static final String TOUCH = """
            local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if not exists then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            return 1
            """;

    /**
     * 删除:五个键里的所有痕迹。
     *
     * <pre>KEYS: reserved / pending / delayed / payloads / attempts;ARGV: uuid</pre>
     */
    private static final String DELETE = """
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('LREM', KEYS[2], 0, ARGV[1])
            redis.call('ZREM', KEYS[3], ARGV[1])
            redis.call('HDEL', KEYS[4], ARGV[1])
            redis.call('HDEL', KEYS[5], ARGV[1])
            return 1
            """;
}
