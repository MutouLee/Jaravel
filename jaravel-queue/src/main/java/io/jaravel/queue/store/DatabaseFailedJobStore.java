package io.jaravel.queue.store;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import io.jaravel.database.Connection;
import io.jaravel.database.Row;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;

/**
 * {@code failed_jobs} 表。
 *
 * <pre>{@code
 * create table failed_jobs (
 *   id           bigint primary key auto_increment,
 *   uuid         varchar(36) not null unique,
 *   connection   varchar(64) not null,
 *   queue        varchar(64) not null,
 *   display_name varchar(255) not null,
 *   payload      text not null,
 *   exception    text not null,
 *   failed_at    timestamp not null
 * );
 * }</pre>
 *
 * <h2>uuid 唯一 —— 重复记录要覆盖而不是追加</h2>
 * <p>同一个任务的同一次失败不该出现两条。会发生重复的场景很具体:
 * worker 写完 failed_jobs 之后、删 jobs 行之前崩了 ——
 * 那条任务会被另一个 worker 捡起来、再失败一次。
 *
 * <p>唯一约束 + 覆盖写让结果收敛到「一个任务一条失败记录」,
 * 而追加会让 failed_jobs 里同一个 uuid 出现十几条,
 * 于是 {@code queue:retry} 不知道该重试哪一条。
 */
public class DatabaseFailedJobStore implements FailedJobStore {

    /** 堆栈最多存多长 —— 完整堆栈偶尔能到几百 KB */
    private static final int MAX_EXCEPTION_CHARS = 64_000;

    private final Connection connection;
    private final String table;

    public DatabaseFailedJobStore(Connection connection) {
        this(connection, "failed_jobs");
    }

    public DatabaseFailedJobStore(Connection connection, String table) {
        this.connection = connection;
        this.table = table;
    }

    @Override
    public long record(QueuedJob job, String connectionName, Throwable cause) {
        var row = new LinkedHashMap<String, Object>();
        row.put("uuid", job.uuid());
        row.put("connection", connectionName);
        row.put("queue", job.queue());
        row.put("display_name", job.displayName());
        row.put("payload", new JobSerializer().encode(job));
        row.put("exception", stackTraceOf(cause));
        row.put("failed_at", Instant.now());

        // 已有同 uuid 的记录 → 覆盖。见类注释里「为什么要覆盖」
        var existing = connection.table(table).where("uuid", job.uuid()).get().first();
        if (existing.isPresent()) {
            connection.table(table).where("uuid", job.uuid()).update(row);
            return ((Number) existing.get().get("id")).longValue();
        }

        return connection.table(table).insertGetId(row).orElse(-1L);
    }

    @Override
    public Optional<FailedJob> find(long id) {
        return connection.table(table).where("id", id).get().first()
                .map(DatabaseFailedJobStore::toFailedJob);
    }

    @Override
    public List<FailedJob> all() {
        return connection.table(table).orderByDesc("id").get().toList().stream()
                .map(DatabaseFailedJobStore::toFailedJob)
                .toList();
    }

    @Override
    public boolean forget(long id) {
        return connection.table(table).where("id", id).delete() > 0;
    }

    /**
     * 全删。
     *
     * <p>要显式 {@code allowUnconditional()}:框架默认拒绝不带 where 的
     * delete(那个保护挡住的是「忘了加 where」)。这里是真的要清空,
     * 所以显式说出来 —— 这正是那个逃生口存在的理由。
     */
    @Override
    public int flush() {
        return connection.table(table).allowUnconditional().delete();
    }

    @Override
    public int prune(Duration olderThan) {
        return connection.table(table)
                .where("failed_at", "<", Instant.now().minus(olderThan))
                .delete();
    }

    @Override
    public long count() {
        return connection.table(table).count();
    }

    // ================= 内部 =================

    private static FailedJob toFailedJob(Row row) {
        return new FailedJob(
                ((Number) row.get("id")).longValue(),
                String.valueOf(row.get("uuid")),
                String.valueOf(row.get("connection")),
                String.valueOf(row.get("queue")),
                String.valueOf(row.get("display_name")),
                String.valueOf(row.get("payload")),
                String.valueOf(row.get("exception")),
                io.jaravel.support.Cast.to(row.get("failed_at"), Instant.class));
    }

    /**
     * 完整堆栈,含 cause 链。
     *
     * <p>**必须含 cause 链**:队列里失败的真正原因几乎总在最内层
     * (一个 SQLException 被包了三层)。只存最外层的消息
     * 等于把排查所需的全部信息丢掉。
     *
     * <p>截断是必要的:一个深递归的堆栈能到几百 KB,而
     * failed_jobs 的行会被反复读(列表页)。截断点写明,
     * 免得让人以为堆栈本身就这么短。
     */
    private static String stackTraceOf(Throwable cause) {
        if (cause == null) return "(没有异常信息)";

        var writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        String text = writer.toString();

        if (text.length() <= MAX_EXCEPTION_CHARS) return text;
        return text.substring(0, MAX_EXCEPTION_CHARS)
             + System.lineSeparator()
             + "…(堆栈过长,已截断。原长度 " + text.length() + " 字符)";
    }

    public String table() {
        return table;
    }
}
