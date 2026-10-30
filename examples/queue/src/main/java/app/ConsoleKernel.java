package app;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.jobs.SendInvoice;
import app.models.Invoice;
import app.models.Invoice_;
import io.jaravel.scheduler.Schedule;
import org.springframework.stereotype.Component;

/**
 * 计划任务的登记处(docs/10 §3.1)。
 *
 * <p>注意**没有外部 cron** —— 应用常驻,调度器就在进程里。
 * 这是相对 Laravel 的一个真实差异:Laravel 必须在 crontab 里加
 * {@code * * * * * php artisan schedule:run}。
 *
 * <p>它带来一个 Laravel 没有的问题:多实例部署时每个实例都有一个
 * 调度器。所以 {@code onOneServer()} 在这里不是可选项 ——
 * 而它依赖共享的锁存储,这个组合由启动校验把住
 * (见 {@code SchedulerConfigCheck})。
 */
@Component
public class ConsoleKernel implements Schedule.ScheduleDefinition {

    /** 任务跑过的记录 —— 测试靠它断言调度真的发生了,而不是只看日志 */
    private static final List<String> RUNS = new CopyOnWriteArrayList<>();

    @Override
    public void schedule(Schedule s) {
        // 时区放在最前面 —— 它只作用于**之后登记的**任务。
        // 不设的话默认是 JVM 时区,而那会随部署环境变
        s.timezone("Asia/Shanghai");

        // s.call:在调度线程(或后台虚拟线程)里**同步执行**,
        // 所以 onSuccess 看到的是「真的跑完了」
        s.call("标记过期发票", () -> {
            int updated = Invoice.query()
                    .where(Invoice_.status, "pending")
                    .update(java.util.Map.of(Invoice_.status, "stale"));
            RUNS.add("mark-stale:" + updated);
        })
         .everyFiveMinutes()
         .between("08:00", "22:00")
         .weekdays()
         .withoutOverlapping(Duration.ofMinutes(10))
         .description("标记过期发票");

        // s.job:**只保证入队**,不保证在这一分钟内跑完。
        // 这个区别在 schedule:list 里能看出来 —— 名字带 (queued)
        s.job(NightlyReminder.class)
         .dailyAt("03:00")
         .description("每日提醒");

        // 一个永远不会到点的任务 —— 测试用 runNow 直接触发它,
        // 以验证「绕过 cron 与约束」这条路径
        s.call("手动触发", () -> RUNS.add("manual"))
         .yearly()
         .when(() -> false);
    }

    public static List<String> runs() {
        return List.copyOf(RUNS);
    }

    public static void reset() {
        RUNS.clear();
    }

    /**
     * 一个被定时投递的队列任务。
     *
     * <p>它是 {@code ShouldQueue} —— {@code s.job(...)} 要求这一点,
     * 因为那条路径走的是队列。
     */
    @Component
    public static class NightlyReminder implements io.jaravel.queue.ShouldQueue {

        @Override public String queue() { return "mail"; }

        public void handle() {
            RUNS.add("nightly-reminder");
        }
    }
}
