package net.dobeen.jaravel.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 失败任务的归档(docs/10 §1.7)。
 *
 * <h2>为什么失败任务要单独存一张表,而不是留在 jobs 里打个标记</h2>
 * <p>两件事的访问模式完全不同:{@code jobs} 是一个高频读写的队列
 * (每次 pop 都在扫它),而失败记录是**低频、只增、要长期保留**的。
 * 混在一起的后果是队列表里堆满永远不会被执行的行,而 pop 的
 * {@code where} 条件要多一个判断 —— 在队列最热的那条查询上。
 *
 * <p>更实际的一点:失败记录要存 {@code exception} 的完整堆栈,
 * 那是个大字段。把它放进队列表会让每次 pop 都可能读到它
 * (取决于数据库的行存储),而 pop 根本不需要它。
 *
 * <h2>★ id 是 {@code String},形状由存储决定</h2>
 * <p>{@code database} / {@code redis} 返回自增编号的字符串({@code "3"}),
 * 于是 {@code queue:retry 3} 照常能用;KV 型的存储(DynamoDB,或任何
 * 以 uuid 为主键的实现)返回**任务的 uuid**,{@code queue:retry 9f8c…}
 * 同样能用。
 *
 * <p>**为什么不是 {@code long}**:第一版是 long,而那让任何以 uuid 为
 * 主键的存储都进不来 —— 要么在 KV 上额外维护一个自增序列(每条失败记录
 * 多一次往返,而 uuid 本来就在记录上、天生唯一),要么就不支持。
 * 为了迁就一个 Java 的 {@code long} 去给 KV 造序列,方向是反的。
 * Laravel 这里本来也是松的({@code queue:retry} 既吃 id 也吃 uuid,
 * 取决于驱动)。
 *
 * <p>⚠ 所以**调用方不要解析它**。它是一个句柄,不是一个数;
 * 排序要用 {@link FailedJob#failedAt()},不是 id。
 */
public interface FailedJobStore {

    /**
     * 记一条失败。
     *
     * @return 失败记录的 id,用于 {@code queue:retry <id>}
     */
    String record(QueuedJob job, String connection, Throwable cause);

    Optional<FailedJob> find(String id);

    List<FailedJob> all();

    /** 删掉一条。{@code queue:forget}。 */
    boolean forget(String id);

    /** 全删。{@code queue:flush}。 */
    int flush();

    /**
     * 清理超过指定时长的记录。{@code queue:prune-failed}。
     *
     * <p>必须有:失败记录只增不减,一个有问题的任务一天能产生几万条。
     */
    int prune(Duration olderThan);

    long count();

    /**
     * 一条失败记录。
     *
     * @param id         这条记录的句柄,**形状由存储决定**(见接口注释)
     * @param uuid       任务的 uuid —— 与日志里的那个相同,这是把
     *                   「失败记录」和「执行日志」串起来的唯一线索
     * @param connection 来自哪个队列连接。retry 时要放回同一个地方
     * @param payload    原始信封的 JSON。retry 靠它还原任务
     * @param exception  完整堆栈
     */
    record FailedJob(String id, String uuid, String connection, String queue,
                     String displayName, String payload, String exception,
                     Instant failedAt) {

        /**
         * 一行「为什么失败」—— 取**根因**,不是第一行。
         *
         * <p>worker 会把任务的异常包一层(「已尝试 N 次…」),
         * 第一行每一条都一样;真正区分它们的是最后一个
         * {@code Caused by}。控制台表格与 dashboard 都需要这个。
         */
        public String reason() {
            if (exception == null || exception.isBlank()) return "";

            String line = exception.lines()
                    .filter(l -> l.stripLeading().startsWith("Caused by:"))
                    .reduce((first, last) -> last)
                    .map(l -> l.stripLeading().substring("Caused by:".length()).trim())
                    .orElseGet(() -> exception.lines().findFirst().orElse(""));

            return line.length() > 80 ? line.substring(0, 77) + "…" : line;
        }
    }
}
