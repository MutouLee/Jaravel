package io.jaravel.queue.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.cache.store.RedisStore;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;
import io.jaravel.support.Clock;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * 失败任务的 redis 存储(docs/10 §1.7)。
 *
 * <p>键布局:
 * <pre>
 * {p}uuids         hash  uuid → id(同一个 uuid 的失败覆盖,不追加)
 * {p}order         zset  id,score = failed_at(列表倒序与 prune 用)
 * {p}id            自增 id 计数器
 * {p}job:{id}      hash   一条失败记录的字段(含完整堆栈)
 * </pre>
 *
 * <p>与 {@link DatabaseFailedJobStore} 同一语义:uuid 唯一、覆盖写、
 * prune 只按时间清。堆栈截断逻辑共用(64k)。
 *
 * <p>连接生命周期借用 {@link RedisStore}(惰性连接、URI、关闭);
 * 队列的失败记录与缓存键空间互不相干。
 */
public class RedisFailedJobStore implements FailedJobStore, AutoCloseable {

    public static final String DEFAULT_PREFIX = "jaravel:failed:";

    private final RedisStore connection;
    private final boolean ownsConnection;
    private final JobSerializer serializer;
    private final String prefix;

    public RedisFailedJobStore(String uri, JobSerializer serializer) {
        this(uri, serializer, DEFAULT_PREFIX);
    }

    public RedisFailedJobStore(String uri, JobSerializer serializer, String prefix) {
        this(new RedisStore(uri, prefix), serializer, prefix, true);
    }

    /** 复用连接(测试/多个驱动共享);close 不会关它 */
    public RedisFailedJobStore(RedisStore connection, JobSerializer serializer, String prefix) {
        this(connection, serializer, prefix, false);
    }

    private RedisFailedJobStore(RedisStore connection, JobSerializer serializer,
                                String prefix, boolean ownsConnection) {
        this.connection = connection;
        this.serializer = serializer;
        this.prefix = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix;
        this.ownsConnection = ownsConnection;
    }

    private RedisCommands<String, String> commands() {
        return connection.raw();
    }

    private String uuids() { return prefix + "uuids"; }
    private String order() { return prefix + "order"; }
    private String counter() { return prefix + "id"; }
    private String jobKey(long id) { return prefix + "job:" + id; }

    // ================= 记录 =================

    @Override
    public long record(QueuedJob job, String connectionName, Throwable cause) {
        long failedAt = Clock.millis();

        Long id = (Long) commands().eval(RECORD, ScriptOutputType.INTEGER,
                new String[]{uuids(), order(), counter()},
                job.uuid(), connectionName, job.queue(),
                job.displayName() == null ? "" : job.displayName(),
                serializer.encode(job),
                DatabaseFailedJobStore.stackTraceOf(cause),
                String.valueOf(failedAt),
                prefix + "job:");

        return id == null ? -1 : id;
    }

    private static final String RECORD = """
            local existing = redis.call('HGET', KEYS[1], ARGV[1])
            local id
            if existing then
              id = existing
            else
              id = redis.call('INCR', KEYS[3])
              redis.call('HSET', KEYS[1], ARGV[1], id)
            end
            local jobKey = ARGV[8] .. id
            redis.call('HSET', jobKey,
              'uuid', ARGV[1],
              'connection', ARGV[2],
              'queue', ARGV[3],
              'display_name', ARGV[4],
              'payload', ARGV[5],
              'exception', ARGV[6],
              'failed_at', ARGV[7])
            redis.call('ZADD', KEYS[2], ARGV[7], id)
            return id
            """;

    // ================= 查询 =================

    @Override
    public Optional<FailedJob> find(long id) {
        return read(id);
    }

    @Override
    public List<FailedJob> all() {
        List<String> ids = commands().zrevrange(order(), 0, -1);
        var out = new ArrayList<FailedJob>(ids.size());
        for (String raw : ids) {
            try {
                read(Long.parseLong(raw)).ifPresent(out::add);
            } catch (NumberFormatException ignored) {
                // 坏成员 = 跳过,不让一条脏数据阻断整个列表
            }
        }
        return out;
    }

    @Override
    public long count() {
        return commands().zcard(order());
    }

    // ================= 删除 =================

    @Override
    public boolean forget(long id) {
        Map<String, String> fields = commands().hgetall(jobKey(id));
        if (fields.isEmpty()) return false;

        String uuid = fields.get("uuid");
        commands().del(jobKey(id));
        commands().zrem(order(), String.valueOf(id));
        if (uuid != null) commands().hdel(uuids(), uuid);
        return true;
    }

    @Override
    public int flush() {
        int before = (int) count();
        connection.flush();       // 前缀范围内,不会清掉别的应用的数据
        return before;
    }

    @Override
    public int prune(Duration olderThan) {
        long cutoff = Clock.now().minus(olderThan).toEpochMilli();
        List<String> ids = commands().zrangebyscore(order(), "-inf", String.valueOf(cutoff));

        int removed = 0;
        for (String raw : ids) {
            try {
                if (forget(Long.parseLong(raw))) removed++;
            } catch (NumberFormatException ignored) {
                // 同上
            }
        }
        return removed;
    }

    // ================= 内部 =================

    private Optional<FailedJob> read(long id) {
        Map<String, String> fields = commands().hgetall(jobKey(id));
        if (fields.isEmpty() || !fields.containsKey("uuid")) return Optional.empty();

        return Optional.of(new FailedJob(
                id,
                fields.get("uuid"),
                fields.get("connection"),
                fields.get("queue"),
                fields.get("display_name"),
                fields.get("payload"),
                fields.get("exception"),
                Instant.ofEpochMilli(Long.parseLong(fields.getOrDefault("failed_at", "0")))));
    }

    @Override
    public void close() {
        if (ownsConnection) connection.close();
    }
}
