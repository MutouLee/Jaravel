package io.jaravel.queue;

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
 */
public interface FailedJobStore {

    /**
     * 记一条失败。
     *
     * @return 失败记录的 id,用于 {@code queue:retry <id>}
     */
    long record(QueuedJob job, String connection, Throwable cause);

    Optional<FailedJob> find(long id);

    List<FailedJob> all();

    /** 删掉一条。{@code queue:forget}。 */
    boolean forget(long id);

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
     * @param id         自增 id
     * @param uuid       任务的 uuid —— 与日志里的那个相同,这是把
     *                   「失败记录」和「执行日志」串起来的唯一线索
     * @param connection 来自哪个队列连接。retry 时要放回同一个地方
     * @param payload    原始信封的 JSON。retry 靠它还原任务
     * @param exception  完整堆栈
     */
    record FailedJob(long id, String uuid, String connection, String queue,
                     String displayName, String payload, String exception,
                     Instant failedAt) {
    }
}
