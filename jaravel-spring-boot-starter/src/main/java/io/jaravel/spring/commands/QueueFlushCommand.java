package io.jaravel.spring.commands;

import java.time.Duration;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.queue.FailedJobStore;

/**
 * {@code jaravel queue:flush}(docs/13 §2.4)—— 清掉失败记录。
 *
 * <p>{@code --older-than=7d} 对应 docs 的 {@code queue:prune-failed}:
 * 失败记录只增不减,一个有问题的任务一天能产生几万条
 * (那句话写在 {@code FailedJobStore.prune} 上)。
 */
@Command(name = "queue:flush", description = "清掉失败任务的记录")
public final class QueueFlushCommand extends RuntimeCommand {

    @Option(names = "--older-than", description = "只清这么多天之前的")
    int olderThanDays;

    private final Supplier<FailedJobStore> failedJobs;

    public QueueFlushCommand(Supplier<FailedJobStore> failedJobs) {
        this.failedJobs = failedJobs;
    }

    @Override
    public int handle() {
        FailedJobStore store = failedJobs.get();

        if (olderThanDays > 0) {
            if (!confirmInProduction("删掉 " + olderThanDays + " 天前的失败记录")) return 1;
            int pruned = store.prune(Duration.ofDays(olderThanDays));
            success("清掉 " + pruned + " 条。");
            return 0;
        }

        long count = store.count();
        if (count == 0) {
            comment("没有失败记录。");
            return 0;
        }
        // ⚠ 全删会丢掉「哪些任务失败过」这件事,而那是重试的唯一依据 ——
        // 所以即便不在生产环境也先确认(不可交互时默认答不)
        if (!confirm("要删掉全部 " + count + " 条失败记录吗?(删了就不能重试了)", false)) {
            error("已取消。只想清旧的用 --older-than=7。");
            return 1;
        }

        success("清掉 " + store.flush() + " 条。");
        return 0;
    }
}
