package io.jaravel.queue.events;

import java.time.Duration;

import io.jaravel.queue.QueuedJob;

/**
 * 队列事件(docs/10 §2.5)。
 *
 * <pre>{@code
 * @Listener
 * public class QueueMetrics {
 *     public void handle(QueueEvents.JobProcessed e) {
 *         metrics.timing("queue." + e.job().queue(), e.duration());
 *     }
 *     public void handle(QueueEvents.JobFailed e) {
 *         alerts.page("任务最终失败:" + e.job().displayName());
 *     }
 * }
 * }</pre>
 *
 * <h2>与 {@code QueueWorker.Listener} 的分工</h2>
 * <p>Worker 已经有一个 {@code Listener} 接口。两者**不是重复**:
 *
 * <table>
 *   <caption>各自的用途</caption>
 *   <tr><th></th><th>QueueWorker.Listener</th><th>事件</th></tr>
 *   <tr><td>注册方式</td><td>给某个 worker 实例 addListener</td>
 *       <td>全局,@Listener 自动装</td></tr>
 *   <tr><td>看得到</td><td>只有那一个 worker</td>
 *       <td>所有 worker + 投递方</td></tr>
 *   <tr><td>适合</td><td>worker 自己的日志/统计</td>
 *       <td>跨进程的业务关注(告警、审计)</td></tr>
 * </table>
 *
 * <p>{@code JobQueued} 只有事件版 —— 它发生在**投递方**,
 * 而投递方手上没有 worker 实例。
 *
 * <h2>事件里带的是信封,不是任务实例</h2>
 * <p>任务实例要反序列化才有,而 {@code JobQueued} 发生在投递时
 * (那时有实例)、{@code JobFailed} 可能发生在反序列化失败时
 * (那时没有)。统一带信封让这组事件的形状一致 ——
 * 否则 {@code JobFailed.job()} 有时是 null,而那种 API
 * 每个监听器都得判一次。
 *
 * <p>信封里有 uuid / displayName / queue / attempts,
 * 足够做告警与统计。真要任务字段就读 {@code envelope.data()}。
 */
public final class QueueEvents {

    private QueueEvents() {
    }

    /**
     * 任务入队了。发生在**投递方**。
     *
     * <p>⚠ {@code afterCommit}(默认开)下这个事件在**事务提交后**才发 ——
     * 因为那时才真的入队。一个「统计入队量」的监听器因此不会
     * 把回滚掉的投递算进去。
     */
    public record JobQueued(String connection, QueuedJob job) {
    }

    /** 开始执行 */
    public record JobProcessing(String connection, QueuedJob job) {
    }

    /** 执行成功 */
    public record JobProcessed(String connection, QueuedJob job, Duration duration) {
    }

    /**
     * 最终失败 —— 重试已耗尽,已写进 failed_jobs。
     *
     * <p>**中途的重试不发这个事件**,发 {@link JobRetryRequested}。
     * 混在一起的话一个「失败告警」监听器会在每次重试都响,
     * 于是一个最终成功的任务也会触发两条告警。
     */
    public record JobFailed(String connection, QueuedJob job, Throwable cause) {
    }

    /** 失败但还会重试 */
    public record JobRetryRequested(String connection, QueuedJob job,
                                    Throwable cause, Duration delay) {
    }
}
