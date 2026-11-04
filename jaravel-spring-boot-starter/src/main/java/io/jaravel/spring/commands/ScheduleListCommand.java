package io.jaravel.spring.commands;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.scheduler.Scheduler;

/**
 * {@code jaravel schedule:list}(docs/13 §2.4)。
 *
 * <p>Scheduler 的 {@code list()} 早就有了,连「下次运行时间对有约束的任务
 * 是乐观估计」这件事都处理了 —— 这里只是把它画成表,并**把那个乐观标记
 * 显示出来**。不显示的话一个 {@code weekdays()} 的任务会在列表里给出
 * 一个周六的时间,而那会让人以为约束没生效。
 */
@Command(name = "schedule:list", description = "列出计划任务与下次运行时间")
public final class ScheduleListCommand extends ConsoleCommand {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final Supplier<Scheduler> scheduler;

    public ScheduleListCommand(Supplier<Scheduler> scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public int handle() {
        List<Scheduler.Entry> entries = scheduler.get().list(ZonedDateTime.now());
        if (entries.isEmpty()) {
            comment("没有计划任务(在 ScheduleDefinition 里用 s.call(...) 登记)。");
            return 0;
        }

        var rows = new ArrayList<List<Object>>();
        boolean anyOptimistic = false;

        for (Scheduler.Entry entry : entries) {
            anyOptimistic |= entry.nextRunIsOptimistic();
            rows.add(List.of(
                    entry.label(),
                    entry.cron(),
                    entry.nextRun() == null ? "(算不出)" : entry.nextRun().format(WHEN)
                            + (entry.nextRunIsOptimistic() ? " *" : ""),
                    String.join(" ", entry.constraints())));
        }

        table(List.of("任务", "cron", "下次", "约束"), rows);

        if (anyOptimistic) {
            newLine();
            line("* 带星号的下次时间是**乐观估计** —— 约束要到那一刻才判,");
            line("  所以真正跑的时间可能更晚。");
        }
        entries.stream()
                .filter(e -> e.dayLogicWarning() != null && !e.dayLogicWarning().isBlank())
                .forEach(e -> warn(e.label() + ":" + e.dayLogicWarning()));
        return 0;
    }
}
