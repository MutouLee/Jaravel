package io.jaravel.scheduler.events;

import java.time.Duration;

/**
 * 调度事件(docs/10 §2.5)。
 *
 * <pre>{@code
 * @Listener
 * public class ScheduleMonitor {
 *     public void handle(ScheduleEvents.TaskSkipped e) {
 *         // 「任务没跑」是最难发现的故障 —— 这个事件让它可观测
 *         metrics.increment("schedule.skipped", "task", e.task(), "why", e.reason());
 *     }
 * }
 * }</pre>
 *
 * <h2>TaskSkipped 为什么重要</h2>
 * <p>这组事件里最有价值的是 {@code TaskSkipped}。
 *
 * <p>一个失败的任务会留下异常、日志、告警。而一个**被跳过**的任务
 * 什么都不留 —— 它看起来和「还没到时间」一模一样。
 * 于是「库存同步从上周起就没跑过」这类问题往往是业务发现的,
 * 不是监控发现的。
 *
 * <p>所以跳过**一定带原因**:是约束挡住了、还是锁没抢到、
 * 还是上一轮还在跑。这三种的处理方式完全不同。
 *
 * <h2>事件里带的是名字而不是 ScheduledTask</h2>
 * <p>{@code ScheduledTask} 带着一堆 lambda(约束、钩子),
 * 把它放进事件意味着一个监听器能调那些 lambda ——
 * 而那会在监听器里触发任务的副作用。
 *
 * <p>名字 + cron + 耗时足够做监控与告警。真要操作任务就从
 * {@code Scheduler.schedule()} 里按名字找。
 */
public final class ScheduleEvents {

    private ScheduleEvents() {
    }

    /** 开始执行 */
    public record TaskStarting(String task, String cron) {
    }

    /**
     * 执行结束。
     *
     * @param failure 失败的描述;成功时为 null
     */
    public record TaskFinished(String task, Duration duration,
                               boolean succeeded, String failure) {
    }

    /**
     * 被跳过。
     *
     * @param reason **为什么**跳过 —— 约束、锁、还是上一轮没跑完。
     *               没有原因的「跳过」通知是没用的
     */
    public record TaskSkipped(String task, String reason) {
    }
}
