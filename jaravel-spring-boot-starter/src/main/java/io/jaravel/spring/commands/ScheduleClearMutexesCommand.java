package io.jaravel.spring.commands;

import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.scheduler.Scheduler;

/**
 * {@code jaravel schedule:clear-mutexes}(docs/13 §2.4)。
 *
 * <p>{@code withoutOverlapping()} 的锁有兜底 TTL,但进程被 kill -9 时
 * 那把锁会一直留到 TTL 到期 —— 而 TTL 常常设得很长(一个跑一小时的任务
 * 要配一小时以上的 TTL)。这条命令是那种情况下的出路。
 */
@Command(name = "schedule:clear-mutexes", description = "清掉计划任务的防重叠锁")
public final class ScheduleClearMutexesCommand extends ConsoleCommand {

    private final Supplier<Scheduler> scheduler;

    public ScheduleClearMutexesCommand(Supplier<Scheduler> scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public int handle() {
        scheduler.get().clearMutexes();
        success("已清掉防重叠锁。");
        return 0;
    }
}
