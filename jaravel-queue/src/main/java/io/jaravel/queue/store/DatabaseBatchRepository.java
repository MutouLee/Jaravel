package io.jaravel.queue.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import io.jaravel.database.Connection;
import io.jaravel.database.Row;
import io.jaravel.queue.Batch;
import io.jaravel.queue.BatchRepository;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;

/**
 * 批次状态存在数据库里(docs/10 §1.5)。
 *
 * <pre>{@code
 * create table job_batches (
 *   id              varchar(36) primary key,
 *   name            varchar(255) null,
 *   total_jobs      int        not null,
 *   pending_jobs    int        not null,
 *   failed_jobs     int        not null default 0,
 *   failed_job_ids  text       not null,
 *   allows_failures tinyint(1) not null default 0,
 *   then_callback   text       null,
 *   catch_callback  text       null,
 *   finally_callback text      null,
 *   created_at      timestamp  not null,
 *   cancelled_at    timestamp  null,
 *   finished_at     timestamp  null
 * );
 * }</pre>
 *
 * <h2>计数用一条 SQL,不是读出来改回去</h2>
 * <p>见 {@link BatchRepository} 的类注释。这里的实现是:
 *
 * <pre>{@code
 * update job_batches set pending_jobs = pending_jobs - 1 where id = ? and pending_jobs > 0
 * }</pre>
 *
 * <p>然后**再查一次**拿变更后的值。这里有一个必须承认的窗口:
 * 两个 worker 几乎同时完成最后两个任务时,两条 UPDATE 顺序执行
 * (由行锁保证),但随后的两次 SELECT 可能都读到 {@code pending_jobs = 0}。
 *
 * <p>那会让 {@code then} 回调被投两次 —— 所以「是不是该跑回调」
 * **不由这个读数决定**,而由 {@link #markFinished} 的条件 UPDATE 决定:
 * 只有把 finished_at 从 null 改成非 null 的那一次返回 true。
 * 读数只用于展示进度。
 *
 * <p>这个分工是刻意的:计数可以近似,**回调不能重复**。
 * 一个「给所有人发汇总邮件」的回调跑两遍是用户可见的事故。
 *
 * <h2>pending_jobs > 0 这个条件</h2>
 * <p>没有它的话,一次重复的 recordSuccess(例如任务被判失联后由两个
 * worker 都报了成功)会把计数减成负数,于是
 * {@code processedJobs() = total - (-1)} 比 total 还大,进度条超过 100%。
 */
public class DatabaseBatchRepository implements BatchRepository {

    private final Connection connection;
    private final JobSerializer serializer;
    private final String table;

    public DatabaseBatchRepository(Connection connection, JobSerializer serializer) {
        this(connection, serializer, "job_batches");
    }

    public DatabaseBatchRepository(Connection connection, JobSerializer serializer,
                                   String table) {
        this.connection = connection;
        this.serializer = serializer;
        this.table = table;
    }

    // ================= 建立 =================

    @Override
    public Batch create(String id, String name, int totalJobs, boolean allowsFailures,
                        QueuedJob thenCallback, QueuedJob catchCallback,
                        QueuedJob finallyCallback) {
        Instant now = Instant.now();

        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("name", name);
        row.put("total_jobs", totalJobs);
        row.put("pending_jobs", totalJobs);
        row.put("failed_jobs", 0);
        row.put("failed_job_ids", "");
        row.put("allows_failures", allowsFailures ? 1 : 0);
        row.put("then_callback", encode(thenCallback));
        row.put("catch_callback", encode(catchCallback));
        row.put("finally_callback", encode(finallyCallback));
        row.put("created_at", now);
        row.put("cancelled_at", null);
        row.put("finished_at", null);

        connection.table(table).insert(row);

        return new Batch(id, name, totalJobs, totalJobs, 0, List.of(),
                allowsFailures, now, null, null);
    }

    private String encode(QueuedJob callback) {
        return callback == null ? null : serializer.encode(callback);
    }

    // ================= 查询 =================

    @Override
    public Optional<Batch> find(String id) {
        return connection.table(table).where("id", id).first().map(this::toBatch);
    }

    @Override
    public List<Batch> recent(int limit) {
        return connection.table(table)
                .orderByDesc("created_at")
                .limit(limit)
                .get()
                .stream()
                .map(this::toBatch)
                .toList();
    }

    @Override
    public Optional<QueuedJob> callback(String id, Kind kind) {
        String column = switch (kind) {
            case THEN -> "then_callback";
            case CATCH -> "catch_callback";
            case FINALLY -> "finally_callback";
        };

        return connection.table(table).where("id", id).first()
                .map(row -> row.get(column))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(value -> serializer.decode(String.valueOf(value)));
    }

    // ================= 计数 =================

    @Override
    public Batch recordSuccess(String id) {
        connection.update(
                "update %s set pending_jobs = pending_jobs - 1 where id = ? and pending_jobs > 0"
                        .formatted(wrap(table)),
                List.of(id));

        return requireBatch(id);
    }

    @Override
    public Batch recordFailure(String id, String jobUuid) {
        // failed_job_ids 是一个逗号分隔的列表 —— 用 **SQL 字符串拼接**
        // 而不是读出来再写回去,理由与计数相同:并发下读改写会丢条目。
        //
        // concat 的方言差异在这里是真实的:MySQL 用 concat(),
        // SQLite / PostgreSQL 用 || 。所以按 grammar 分支
        String append = appendExpression();

        // ★ 绑定值要传 **两次** jobUuid。
        //
        // appendExpression() 里的 case 表达式有两个 ? (then 分支与 else 分支
        // 各一个),加上 where 一共三个占位符。只传两个的话 SQLite 会把
        // id 当成第二个 uuid、把 where 的 ? 留空 —— 于是那条 UPDATE
        // 匹配不到任何行,**静默**什么都没改。
        //
        // 症状:批次的 failed_jobs 永远是 0,于是 catch 回调永不触发、
        // allowFailures=false 也不会取消剩余任务。这是写这块时真实踩到的
        // 一个 bug,而它在「只测 BatchRepository 本身」的测试里也会暴露,
        // 但在「mock 掉存储」的测试里不会
        connection.update("""
                update %s
                   set pending_jobs = case when pending_jobs > 0 then pending_jobs - 1 else 0 end,
                       failed_jobs = failed_jobs + 1,
                       failed_job_ids = %s
                 where id = ?"""
                .formatted(wrap(table), append),
                List.of(jobUuid, jobUuid, id));

        return requireBatch(id);
    }

    /**
     * 往 failed_job_ids 后面追加一个 uuid 的 SQL 片段。
     *
     * <p>第一个条目前不加逗号 —— 否则列表变成 {@code ",uuid1,uuid2"},
     * 切分出来头上多一个空字符串,而那个空字符串会被当成一个任务 uuid
     * 去查 failed_jobs。
     */
    private String appendExpression() {
        boolean mysql = connection.grammar().getClass().getSimpleName()
                .toLowerCase().contains("mysql");

        return mysql
                ? "case when failed_job_ids = '' then ? else concat(failed_job_ids, ',', ?) end"
                : "case when failed_job_ids = '' then ? else failed_job_ids || ',' || ? end";
    }

    /**
     * 取批次,不存在就报错。
     *
     * <p>计数之后它必须存在。不存在说明有人在批次跑着的时候删了那一行
     * (手工清理、或 prune 的条件写错),而那时继续往下走会让
     * 回调判定、进度展示全部基于一个编造出来的状态。
     */
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
        // `and cancelled_at is null` 让它幂等 —— 否则重复取消会把
        // 「取消时间」一次次后移,而那是排查时会看的一个时间点
        connection.update(
                "update %s set cancelled_at = ? where id = ? and cancelled_at is null"
                        .formatted(wrap(table)),
                List.of(Instant.now(), id));
    }

    @Override
    public boolean markFinished(String id) {
        // ★ 这条 UPDATE 是「回调只跑一次」的唯一保证。
        // 条件 `finished_at is null` 把并发的多次调用收敛成一次成功
        int affected = connection.update(
                "update %s set finished_at = ? where id = ? and finished_at is null"
                        .formatted(wrap(table)),
                List.of(Instant.now(), id));

        return affected == 1;
    }

    // ================= 清理 =================

    @Override
    public int prune(Duration olderThan) {
        Instant cutoff = Instant.now().minus(olderThan);

        // 只清**已完成**的。在跑的批次被清掉会让它的回调永不触发,
        // 而那个症状(「月度报表跑完了但没收到汇总邮件」)
        // 极难联想到一次清理任务
        return connection.table(table)
                .whereNotNull("finished_at")
                .where("finished_at", "<", cutoff)
                .delete();
    }

    // ================= 映射 =================

    private Batch toBatch(Row row) {
        String ids = row.string("failed_job_ids");
        List<String> failedIds = new ArrayList<>();
        if (ids != null && !ids.isBlank()) {
            for (String part : ids.split(",")) {
                if (!part.isBlank()) failedIds.add(part.trim());
            }
        }

        return new Batch(
                row.string("id"),
                row.string("name"),
                intOf(row.get("total_jobs")),
                intOf(row.get("pending_jobs")),
                intOf(row.get("failed_jobs")),
                failedIds,
                intOf(row.get("allows_failures")) != 0,
                row.instant("created_at"),
                row.instant("cancelled_at"),
                row.instant("finished_at"));
    }

    private static int intOf(Object value) {
        if (value == null) return 0;
        if (value instanceof Boolean b) return b ? 1 : 0;
        return ((Number) value).intValue();
    }

    private String wrap(String identifier) {
        return connection.grammar().wrap(identifier);
    }
}
