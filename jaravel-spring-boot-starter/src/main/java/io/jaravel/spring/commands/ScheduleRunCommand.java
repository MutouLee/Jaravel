package io.jaravel.spring.commands;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.scheduler.ScheduledTask;
import io.jaravel.scheduler.Scheduler;

/**
 * {@code jaravel schedule:run}(docs/13 §2.4)—— 执行此刻到期的任务。
 *
 * <p>这条命令的用途是**外部触发模式**:Kubernetes CronJob 每分钟调一次,
 * 而不是让应用自己带一个调度线程
 * ({@code schedule.in-process=false},理由见 JaravelSchedulerAutoConfiguration)。
 *
 * <p>所以它跑完就退出,一个到期的都没有也是正常结果 —— 不是错误。
 */
@Command(name = "schedule:run", description = "执行此刻到期的计划任务")
public final class ScheduleRunCommand extends ConsoleCommand {

    private final Supplier<Scheduler> scheduler;

    public ScheduleRunCommand(Supplier<Scheduler> scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public int handle() {
        List<ScheduledTask> ran = scheduler.get().runDue(ZonedDateTime.now());

        if (ran.isEmpty()) {
            // 「没有到期的」是正常结果:外部触发模式下这条命令每分钟跑一次,
            // 而绝大多数分钟里什么都不该发生
            comment("此刻没有到期的任务。");
            return 0;
        }
        ran.forEach(task -> success(task.label()));
        line("执行 " + ran.size() + " 个。");
        return 0;
    }
}
