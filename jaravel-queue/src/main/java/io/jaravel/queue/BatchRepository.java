package io.jaravel.queue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 批次状态的存储(docs/10 §1.5 的 {@code job_batches} 表)。
 *
 * <h2>为什么计数必须由存储来做,而不是读出来改回去</h2>
 * <p>一个批次的任务由**多个 worker 并发**执行。
 * {@code batch.pendingJobs - 1} 然后写回去会丢更新:两个 worker 同时
 * 读到 10,各自写回 9,于是两次完成只减了一次。
 *
 * <p>结果是批次**永远完不了** —— pendingJobs 停在一个大于 0 的数上,
 * {@code then} 回调永不触发。而这个症状在小批次、低并发下看不出来,
 * 只在生产的大批次上出现。
 *
 * <p>所以 {@link #recordSuccess} / {@link #recordFailure} 是**原子的
 * 读改写**,由实现用一条 SQL(或 Redis 的 HINCRBY)完成,
 * 并返回变更后的状态 —— 调用方需要靠它判断「我是不是最后一个」。
 */
public interface BatchRepository {

    /** 建一个批次。回调以**信封**形式存着 —— 理由见 {@link PendingChain} 的类注释 */
    Batch create(String id, String name, int totalJobs, boolean allowsFailures,
                 QueuedJob thenCallback, QueuedJob catchCallback, QueuedJob finallyCallback);

    Optional<Batch> find(String id);

    /**
     * 记一次成功。
     *
     * @return 变更后的状态。调用方用 {@code pendingJobs() == 0} 判断
     *         「这次完成是不是最后一次」—— 这个判断必须基于**返回值**,
     *         再查一次库的话两个 worker 可能都看到 0,于是回调跑两遍
     */
    Batch recordSuccess(String id);

    /** 记一次最终失败(重试已耗尽) */
    Batch recordFailure(String id, String jobUuid);

    /**
     * 取消。
     *
     * <p>幂等 —— 重复取消不改变 cancelledAt,否则「取消时间」会随
     * 每次误调而后移。
     */
    void cancel(String id);

    /**
     * 标记完成。
     *
     * @return 这一次调用是否**真的**把它从未完成改成了完成。
     *         false 表示别人已经标过了 —— 调用方据此决定要不要跑回调,
     *         这是防止 {@code then} 回调被投两次的那道闸
     */
    boolean markFinished(String id);

    /** 取回调信封。null 表示没配这个回调 */
    Optional<QueuedJob> callback(String id, Kind kind);

    /** 清理已完成的旧批次(对应 {@code jaravel queue:prune-batches}) */
    int prune(Duration olderThan);

    List<Batch> recent(int limit);

    enum Kind { THEN, CATCH, FINALLY }
}
