package io.jaravel.queue.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.cache.store.RedisStore;
import io.jaravel.queue.Batch;
import io.jaravel.queue.BatchRepository;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;
import io.jaravel.support.Clock;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * 批次状态的 redis 存储(docs/10 §1.5)。
 *
 * <p>键布局:
 * <pre>
 * {p}index          zset  batch id,score = created_at(recent 倒序)
 * {p}batch:{id}     hash  一个批次的全部状态
 * </pre>
 *
 * <h2>计数与回调闸门都是原子的</h2>
 * <p>与 {@link DatabaseBatchRepository} 同一分工:
 * <ul>
 *   <li>计数用 Lua 原子读改写(pending / failed / failed_job_ids),不会丢更新;</li>
 *   <li>「回调只跑一次」由 {@link #markFinished} 的
 *       {@code HSETNX-ish} 语义保证 —— 只有把 finished_at 从空改成
 *       时间戳的那一次返回 true,并发下不会有两个 worker 同时拿到 true。</li>
 * </ul>
 * 计数可以近似,**回调不能重复**。
 */
public class RedisBatchRepository implements BatchRepository, AutoCloseable {

    public static final String DEFAULT_PREFIX = "jaravel:batch:";

    private final RedisStore connection;
    private final boolean ownsConnection;
    private final JobSerializer serializer;
    private final String prefix;

    public RedisBatchRepository(String uri, JobSerializer serializer) {
        this(uri, serializer, DEFAULT_PREFIX);
    }

    public RedisBatchRepository(String uri, JobSerializer serializer, String prefix) {
        this(new RedisStore(uri, prefix), serializer, prefix, true);
    }

    /** 复用连接(测试/多个驱动共享);close 不会关它 */
    public RedisBatchRepository(RedisStore connection, JobSerializer serializer, String prefix) {
        this(connection, serializer, prefix, false);
    }

    private RedisBatchRepository(RedisStore connection, JobSerializer serializer,
                                 String prefix, boolean ownsConnection) {
        this.connection = connection;
        this.serializer = serializer;
        this.prefix = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix;
        this.ownsConnection = ownsConnection;
    }

    private RedisCommands<String, String> commands() {
        return connection.raw();
    }

    private String index() { return prefix + "index"; }
    private String key(String id) { return prefix + "batch:" + id; }

    // ================= 建立 =================

    @Override
    public Batch create(String id, String name, int totalJobs, boolean allowsFailures,
                        java.util.Map<Kind, QueuedJob> callbacks) {
        if (commands().exists(key(id)) > 0) {
            throw new IllegalStateException("批次 id 重复:" + id);
        }

        Instant now = Clock.now();
        var fields = new LinkedHashMap<String, String>();
        fields.put("id", id);
        fields.put("name", name == null ? "" : name);
        fields.put("total_jobs", String.valueOf(totalJobs));
        fields.put("pending_jobs", String.valueOf(totalJobs));
        fields.put("failed_jobs", "0");
        fields.put("failed_job_ids", "");
        fields.put("allows_failures", allowsFailures ? "1" : "0");
        fields.put("then_callback", encode(callbacks.get(Kind.THEN)));
        fields.put("catch_callback", encode(callbacks.get(Kind.CATCH)));
        fields.put("finally_callback", encode(callbacks.get(Kind.FINALLY)));
        fields.put("created_at", String.valueOf(now.toEpochMilli()));
        fields.put("cancelled_at", "");
        fields.put("finished_at", "");

        commands().hset(key(id), fields);
        commands().zadd(index(), now.toEpochMilli(), id);

        return new Batch(id, name, totalJobs, totalJobs, 0, List.of(),
                allowsFailures, now, null, null);
    }

    private String encode(QueuedJob callback) {
        return callback == null ? "" : serializer.encode(callback);
    }

    // ================= 查询 =================

    @Override
    public Optional<Batch> find(String id) {
        return read(id);
    }

    @Override
    public List<Batch> recent(int limit) {
        if (limit <= 0) return List.of();
        List<String> ids = commands().zrevrange(index(), 0, limit - 1L);
        var out = new ArrayList<Batch>(ids.size());
        for (String id : ids) {
            read(id).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public Optional<QueuedJob> callback(String id, Kind kind) {
        String field = switch (kind) {
            case THEN -> "then_callback";
            case CATCH -> "catch_callback";
            case FINALLY -> "finally_callback";
        };
        String encoded = commands().hget(key(id), field);
        if (encoded == null || encoded.isBlank()) return Optional.empty();
        return Optional.of(serializer.decode(encoded));
    }

    // ================= 计数 =================

    @Override
    public Batch recordSuccess(String id) {
        commands().eval(SUCCESS, ScriptOutputType.INTEGER,
                new String[]{key(id)}, "1");
        return requireBatch(id);
    }

    @Override
    public Batch recordFailure(String id, String jobUuid) {
        commands().eval(FAILURE, ScriptOutputType.INTEGER,
                new String[]{key(id)}, jobUuid);
        return requireBatch(id);
    }

    private static final String SUCCESS = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            local pending = tonumber(redis.call('HGET', KEYS[1], 'pending_jobs') or '0')
            if pending > 0 then
              redis.call('HINCRBY', KEYS[1], 'pending_jobs', -1)
            end
            return 1
            """;

    private static final String FAILURE = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            local pending = tonumber(redis.call('HGET', KEYS[1], 'pending_jobs') or '0')
            if pending > 0 then
              redis.call('HINCRBY', KEYS[1], 'pending_jobs', -1)
            end
            redis.call('HINCRBY', KEYS[1], 'failed_jobs', 1)
            local ids = redis.call('HGET', KEYS[1], 'failed_job_ids') or ''
            if ids == '' then
              redis.call('HSET', KEYS[1], 'failed_job_ids', ARGV[1])
            else
              redis.call('HSET', KEYS[1], 'failed_job_ids', ids .. ',' .. ARGV[1])
            end
            return 1
            """;

    private Batch requireBatch(String id) {
        return find(id).orElseThrow(() -> new IllegalStateException("""
                批次 %s 在更新计数后查不到了 —— 它可能被手工删除或被 prune 清掉了。
                  prune 只应清理已完成的批次(finished_at 非空);
                  清掉在跑的批次会让它的 then / catch / finally 回调永不触发。"""
                .formatted(id)));
    }

    // ================= 状态 =================

    @Override
    public void cancel(String id) {
        commands().eval(CANCEL, ScriptOutputType.INTEGER,
                new String[]{key(id)}, String.valueOf(Clock.millis()));
    }

    private static final String CANCEL = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            local current = redis.call('HGET', KEYS[1], 'cancelled_at') or ''
            if current == '' then
              redis.call('HSET', KEYS[1], 'cancelled_at', ARGV[1])
            end
            return 1
            """;

    @Override
    public boolean markFinished(String id) {
        Long updated = (Long) commands().eval(FINISH, ScriptOutputType.INTEGER,
                new String[]{key(id)}, String.valueOf(Clock.millis()));
        return updated != null && updated == 1L;
    }

    private static final String FINISH = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              return 0
            end
            local current = redis.call('HGET', KEYS[1], 'finished_at') or ''
            if current ~= '' then
              return 0
            end
            redis.call('HSET', KEYS[1], 'finished_at', ARGV[1])
            return 1
            """;

    // ================= 清理 =================

    @Override
    public int prune(Duration olderThan) {
        long cutoff = Clock.now().minus(olderThan).toEpochMilli();
        List<String> ids = commands().zrangebyscore(index(), "-inf", String.valueOf(cutoff));

        int removed = 0;
        for (String id : ids) {
            Optional<Batch> batch = read(id);
            // 只清已完成的 —— 在跑的批次被清掉会让回调永不触发
            if (batch.isEmpty() || batch.get().finishedAt() == null) continue;
            if (batch.get().finishedAt().toEpochMilli() >= cutoff) continue;

            commands().del(key(id));
            commands().zrem(index(), id);
            removed++;
        }
        return removed;
    }

    // ================= 映射 =================

    private Optional<Batch> read(String id) {
        Map<String, String> fields = commands().hgetall(key(id));
        if (fields.isEmpty() || !fields.containsKey("id")) return Optional.empty();

        List<String> failedIds = new ArrayList<>();
        String ids = fields.getOrDefault("failed_job_ids", "");
        if (!ids.isBlank()) {
            for (String part : ids.split(",")) {
                if (!part.isBlank()) failedIds.add(part.trim());
            }
        }

        String name = fields.get("name");
        return Optional.of(new Batch(
                id,
                name == null || name.isEmpty() ? null : name,
                intOf(fields.get("total_jobs")),
                intOf(fields.get("pending_jobs")),
                intOf(fields.get("failed_jobs")),
                failedIds,
                "1".equals(fields.get("allows_failures")),
                instantOf(fields.get("created_at")),
                instantOf(fields.get("cancelled_at")),
                instantOf(fields.get("finished_at"))));
    }

    private static int intOf(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private static Instant instantOf(String value) {
        return value == null || value.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    @Override
    public void close() {
        if (ownsConnection) connection.close();
    }
}
