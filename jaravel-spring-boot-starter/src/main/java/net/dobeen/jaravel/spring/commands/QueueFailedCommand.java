package net.dobeen.jaravel.spring.commands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.dobeen.jaravel.console.Command;
import net.dobeen.jaravel.console.ConsoleCommand;
import net.dobeen.jaravel.console.Option;
import net.dobeen.jaravel.queue.FailedJobStore;

/**
 * {@code jaravel queue:failed}(docs/13 §2.4)—— 失败任务一览。
 *
 * <p>列的是 **uuid 的前 8 位**而不是完整的 uuid:它要与日志里的那个对得上
 * (那是把「失败记录」和「执行日志」串起来的唯一线索),而完整的 uuid
 * 会把这张表挤到换行。要完整的用 {@code --uuid}。
 */
@Command(name = "queue:failed", description = "列出失败的任务")
public final class QueueFailedCommand extends ConsoleCommand {

    @Option(names = "--uuid", description = "显示完整 uuid")
    boolean fullUuid;

    @Option(names = "--queue", description = "只看这个队列")
    String queue;

    private final Supplier<FailedJobStore> failedJobs;

    public QueueFailedCommand(Supplier<FailedJobStore> failedJobs) {
        this.failedJobs = failedJobs;
    }

    @Override
    public int handle() {
        List<FailedJobStore.FailedJob> all = failedJobs.get().all();
        var matched = new ArrayList<net.dobeen.jaravel.queue.FailedJobStore.FailedJob>();
        for (var job : all) {
            if (queue != null && !queue.isBlank() && !job.queue().equals(queue.trim())) {
                continue;
            }
            matched.add(job);
        }

        if (matched.isEmpty()) {
            comment(all.isEmpty() ? "没有失败的任务。" : "没有匹配的失败任务(共 " + all.size() + " 条)。");
            return 0;
        }

        // ★ KV 型的归档存储(dynamodb)里 id **就是** uuid。
        // 照原样排两列的话,同一个 36 字符的字符串在一行里出现两次,
        // 把表挤到换行 —— 而那两列里有一列是纯噪音
        boolean idIsUuid = matched.stream().allMatch(job -> job.id().equals(job.uuid()));

        var rows = new ArrayList<List<Object>>();
        for (var job : matched) {
            String shortUuid = fullUuid
                    ? job.uuid()
                    : job.uuid().substring(0, Math.min(8, job.uuid().length()));

            rows.add(idIsUuid
                    ? List.of(job.id(), job.queue(), job.displayName(),
                              reasonOf(job.exception()))
                    : List.of(job.id(), shortUuid, job.queue(), job.displayName(),
                              reasonOf(job.exception())));
        }

        table(idIsUuid
                        ? List.of("id(= uuid)", "队列", "任务", "原因")
                        : List.of("id", "uuid", "队列", "任务", "原因"),
                rows);
        newLine();
        line("重试:queue:retry <id> 或 queue:retry all");
        return 0;
    }

    /**
     * 一行「为什么」。
     *
     * <h2>★ 取的是**根因**,不是第一行</h2>
     * <p>一张表里放完整堆栈没有可读性,所以只能留一行。
     * 而那一行要回答的是「这些失败是不是同一个原因」—— 看这张表时的
     * 第一个问题。
     *
     * <p>第一行不行:worker 会把任务的异常包一层
     * ({@code JobFailedException: 已尝试 1 次…}),于是每一行都长得一样,
     * 而真正区分它们的那句话(「连接超时」/「字段太长」)在
     * 最后一个 {@code Caused by} 上。
     */
    private String reasonOf(String exception) {
        if (exception == null || exception.isBlank()) return "";

        String line = exception.lines()
                .filter(l -> l.stripLeading().startsWith("Caused by:"))
                .reduce((first, last) -> last)          // 最后一个 = 最深的根因
                .map(l -> l.stripLeading().substring("Caused by:".length()).trim())
                .orElseGet(() -> exception.lines().findFirst().orElse(""));

        return line.length() > 60 ? line.substring(0, 57) + "…" : line;
    }
}
