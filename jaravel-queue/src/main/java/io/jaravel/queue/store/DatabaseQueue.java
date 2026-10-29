package io.jaravel.queue.store;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;

import io.jaravel.database.Connection;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueuedJob;

/**
 * 数据库驱动 —— 零额外依赖的默认驱动(docs/10 §1.5)。
 *
 * <pre>{@code
 * create table jobs (
 *   id          bigint primary key auto_increment,
 *   uuid        varchar(36) not null unique,
 *   queue       varchar(64) not null,
 *   payload     text        not null,
 *   attempts    int         not null default 0,
 *   reserved_at timestamp   null,
 *   available_at timestamp  not null,
 *   created_at  timestamp   not null
 * );
 * }</pre>
 *
 * <h2>pop 的原子性:乐观锁 UPDATE,不是 SELECT … FOR UPDATE SKIP LOCKED</h2>
 * <p>做法是「先挑一个候选 id,再用一条带条件的 UPDATE 去抢」:
 *
 * <pre>{@code
 * update jobs set reserved_at = now, attempts = attempts + 1
 *  where id = ? and reserved_at is null
 * }</pre>
 *
 * <p>受影响行数为 1 才算抢到;为 0 说明别人先抢走了,换下一个候选。
 *
 * <p>**为什么不用 SKIP LOCKED**:它只在 MySQL 8.0+ / PostgreSQL 9.5+ 上有,
 * 老版本与 SQLite 都没有,于是需要**版本探测**(docs/10 §待决也是这么写的)。
 * 而探测这件事本身有个很糟的失败模式:探测错了的话要么在 pop 时
 * 抛语法错误,要么**退化成不加锁的 SELECT**,而后者的症状是
 * 任务被执行两次 —— 对扣款这类任务就是生产事故,且只在并发下偶发。
 *
 * <p>乐观锁的代价是高并发下有重试开销(抢同一条的 worker 会多跑几次查询)。
 * 那个开销在「几十个 worker 抢一个队列」时才显著,而到那个量级
 * 本来就该换 Redis 驱动。<b>SKIP LOCKED 作为吞吐优化列入 v0.5</b>,
 * 它需要的版本探测届时与其它方言差异一起处理。
 *
 * <h2>保留超时:worker 崩了之后任务要能被捡起来</h2>
 * <p>{@code reserved_at} 早于 {@code now - retryAfter} 的任务被视为
 * 「持有它的 worker 失联了」,重新可见。
 *
 * <p>这意味着**长任务必须把 retryAfter 配得比它的执行时间长**,
 * 否则它会在还在跑的时候被另一个 worker 再取一遍。
 * 这是 database 驱动最容易踩的坑,所以 {@link #pop} 里有一条注释
 * 指向它,而 Worker 侧会用心跳续约来缓解(下一块)。
 */
public class DatabaseQueue implements QueueStore {

    /** 一次 pop 最多尝试抢几条候选 */
    private static final int MAX_CONTENTION_RETRIES = 5;

    private final Connection connection;
    private final JobSerializer serializer;
    private final String table;

    public DatabaseQueue(Connection connection, JobSerializer serializer) {
        this(connection, serializer, "jobs");
    }

    public DatabaseQueue(Connection connection, JobSerializer serializer, String table) {
        this.connection = connection;
        this.serializer = serializer;
        this.table = table;
    }

    @Override
    public String name() {
        return "database";
    }

    // ================= 入队 =================

    @Override
    public String push(QueuedJob job) {
        var row = new LinkedHashMap<String, Object>();
        row.put("uuid", job.uuid());
        row.put("queue", job.queue());
        row.put("payload", serializer.encode(job));
        row.put("attempts", job.attempts());
        row.put("reserved_at", null);
        row.put("available_at", job.availableAt());
        row.put("created_at", job.pushedAt());

        connection.table(table).insert(row);
        return job.uuid();
    }

    // ================= 取出 =================

    @Override
    public Optional<QueuedJob> pop(String queue, Duration retryAfter) {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(retryAfter);

        for (int attempt = 0; attempt < MAX_CONTENTION_RETRIES; attempt++) {
            var candidate = nextCandidate(queue, now, staleBefore);
            if (candidate.isEmpty()) return Optional.empty();

            long id = ((Number) candidate.get().get("id")).longValue();
            Object reservedAt = candidate.get().get("reserved_at");

            if (reserve(id, reservedAt, now)) {
                String payload = String.valueOf(candidate.get().get("payload"));
                // attempts 是**库里**那一份(刚 +1),不是 payload 里的 ——
                // payload 写入后就不再更新,它只是任务的不变部分
                int attempts = ((Number) candidate.get().get("attempts")).intValue() + 1;
                return Optional.of(withAttempts(serializer.decode(payload), attempts));
            }
            // 被别人抢走了,看下一条
        }
        return Optional.empty();
    }

    /**
     * 挑一个候选。
     *
     * <p>两类可取:没被保留过的、以及保留超时的。
     *
     * <p>⚠ 保留超时的那一类意味着:**retryAfter 必须大于任务的最长执行时间**,
     * 否则任务会在还在跑的时候被另一个 worker 再取一遍。
     */
    private Optional<io.jaravel.database.Row> nextCandidate(String queue, Instant now,
                                                            Instant staleBefore) {
        return connection.table(table)
                .where("queue", queue)
                .where(q -> q.whereNull("reserved_at")
                             .orWhere(inner -> inner.where("reserved_at", "<", staleBefore)))
                .where("available_at", "<=", now)
                // 按 id 排序 = 先进先出。按 available_at 排会让
                // 同一毫秒入队的任务顺序不确定,而「顺序不确定」
                // 在排查「为什么这个任务先跑了」时非常费时间
                .orderBy("id")
                .limit(1)
                .get()
                .first();
    }

    /**
     * 抢占。
     *
     * <p>条件里带上**读到的 reserved_at**,而不只是 {@code is null} ——
     * 保留超时的那一类任务的 reserved_at 不是 null,只用 is null
     * 会让它们永远抢不到,于是崩掉的 worker 持有的任务永久卡住。
     *
     * @return 抢到了吗
     */
    private boolean reserve(long id, Object previousReservedAt, Instant now) {
        var query = connection.table(table).where("id", id);

        if (previousReservedAt == null) {
            query.whereNull("reserved_at");
        } else {
            // 比对读到的那个值:期间被别人抢走的话它已经变了
            query.where("reserved_at", previousReservedAt);
        }

        var values = new LinkedHashMap<String, Object>();
        values.put("reserved_at", now);
        return query.update(values) == 1
                && bumpAttempts(id);
    }

    /**
     * attempts 自增。
     *
     * <p>单独一条语句而不是合进上面的 UPDATE:查询构造器的
     * {@code update} 接受的是值而不是表达式,而 {@code attempts + 1}
     * 是表达式。两条语句之间不会有别人介入 —— 这一行已经被我们保留了。
     */
    private boolean bumpAttempts(long id) {
        connection.update(
                "update " + table + " set attempts = attempts + 1 where id = ?",
                java.util.List.of(id));
        return true;
    }

    private static QueuedJob withAttempts(QueuedJob job, int attempts) {
        return new QueuedJob(job.uuid(), job.jobClass(), job.data(), job.queue(),
                attempts, job.pushedAt(), job.availableAt(), job.tries(),
                job.timeout(), job.retryUntil(), job.maxExceptions(), job.displayName());
    }

    // ================= 完成与放回 =================

    @Override
    public void delete(QueuedJob job) {
        connection.table(table).where("uuid", job.uuid()).delete();
    }

    @Override
    public void release(QueuedJob job, Duration delay) {
        var values = new LinkedHashMap<String, Object>();
        values.put("reserved_at", null);
        values.put("available_at", Instant.now().plus(
                delay == null ? Duration.ZERO : delay));

        int updated = connection.table(table).where("uuid", job.uuid()).update(values);

        if (updated == 0) {
            // 这条任务已经不在表里了 —— 它被 delete 过,或被 queue:forget 清了。
            // 不报错:release 的调用方(Worker 的重试路径)对此无能为力,
            // 而抛异常只会让一次重试变成一次 worker 崩溃
            connection.table(table).insert(reinsertRow(job, delay));
        }
    }

    /**
     * 任务不在表里了但要重试 —— 重新插进去。
     *
     * <p>这不是防御性编程:{@code queue:retry} 把失败任务放回队列走的
     * 就是这条路径(failed_jobs 里的任务不在 jobs 表里)。
     */
    private LinkedHashMap<String, Object> reinsertRow(QueuedJob job, Duration delay) {
        var row = new LinkedHashMap<String, Object>();
        row.put("uuid", job.uuid());
        row.put("queue", job.queue());
        row.put("payload", serializer.encode(job));
        row.put("attempts", job.attempts());
        row.put("reserved_at", null);
        row.put("available_at", Instant.now().plus(delay == null ? Duration.ZERO : delay));
        row.put("created_at", job.pushedAt());
        return row;
    }

    // ================= 其它 =================

    /**
     * 续约:把 reserved_at 推到现在。
     *
     * <p>条件里带上 {@code reserved_at is not null} —— 如果它已经被
     * 判定失联并被别人取走,那一行的 reserved_at 会是**别人**写的时间,
     * 我们更新它就等于把别人正在跑的任务抢回来。
     *
     * <p>所以这里按 uuid + 「仍处于保留中」更新,并用受影响行数判断。
     * 返回 false 时 Worker 必须放弃这条任务 —— 继续跑会产生重复副作用。
     */
    @Override
    public boolean touch(QueuedJob job, Duration retryAfter) {
        var values = new LinkedHashMap<String, Object>();
        values.put("reserved_at", Instant.now());

        return connection.table(table)
                .where("uuid", job.uuid())
                .whereNotNull("reserved_at")
                .update(values) == 1;
    }

    @Override
    public long size(String queue) {
        return connection.table(table).where("queue", queue).count();
    }

    @Override
    public void clear(String queue) {
        connection.table(table).where("queue", queue).delete();
    }

    public String table() {
        return table;
    }
}
