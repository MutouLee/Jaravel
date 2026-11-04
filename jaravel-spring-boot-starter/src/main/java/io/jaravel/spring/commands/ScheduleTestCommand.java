package io.jaravel.spring.commands;

import java.util.function.Supplier;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.ConsoleException;
import io.jaravel.scheduler.Scheduler;

/**
 * {@code jaravel schedule:test <任务>}(docs/13 §2.4)——
 * 立即执行一个任务,**绕过 cron 与约束**。
 *
 * <p>它的用途是「我写的这个任务到底能不能跑」。那个问题不该靠等到
 * 下一个整点来回答,也不该靠临时把 cron 改成 everyMinute ——
 * 后者改完经常忘了改回去。
 */
@Command(name = "schedule:test", description = "立即执行一个计划任务(绕过 cron)")
public final class ScheduleTestCommand extends ConsoleCommand {

    @Argument(description = "任务名或描述")
    String task;

    private final Supplier<Scheduler> scheduler;

    public ScheduleTestCommand(Supplier<Scheduler> scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public int handle() {
        Scheduler active = scheduler.get();

        return active.runNow(task.trim())
                .map(ran -> {
                    success("已执行 " + ran.label());
                    return 0;
                })
                .orElseThrow(() -> new ConsoleException("""
                        没有叫 "%s" 的计划任务。

                          现有的:%s""".formatted(task,
                        active.schedule().tasks().stream()
                                .map(io.jaravel.scheduler.ScheduledTask::label).toList())));
    }
}
