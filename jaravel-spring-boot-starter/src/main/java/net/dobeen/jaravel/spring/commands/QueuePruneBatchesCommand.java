package net.dobeen.jaravel.spring.commands;

import java.time.Duration;
import java.util.function.Supplier;

import net.dobeen.jaravel.console.Command;
import net.dobeen.jaravel.console.Option;
import net.dobeen.jaravel.queue.BatchRepository;

/**
 * {@code jaravel queue:prune-batches --hours=48}(docs/10 §2)。
 *
 * <p>批次记录只增不减:每个 {@code Bus.batch(...)} 留一行,带着
 * 成功/失败计数与回调的 payload。一个每分钟发一个批次的应用,一个月
 * 就是四万多行 —— 而它们的用途(「这个批次跑完了吗」)在批次结束后
 * 几小时就没有了。
 *
 * <p>所以这条命令该接到调度器上:
 *
 * <pre>{@code
 * schedule.command("queue:prune-batches --hours=48").daily();
 * }</pre>
 *
 * <h2>★ 只删**已结束**的批次</h2>
 * <p>判据是 {@code BatchRepository.prune}:按「结束时刻」而不是「创建时刻」。
 * 按创建时刻删会把一个跑了三天的长批次删掉 —— 于是它的回调永远不会执行,
 * 而症状是「批次卡在 99%」,看起来像队列丢了任务。
 *
 * <p>没结束的批次不管多老都留着。要清一个卡住的批次,先
 * {@code Bus.findBatch(id).cancel()}(取消也是一种结束)。
 */
@Command(name = "queue:prune-batches", description = "清掉已结束的旧批次记录")
public final class QueuePruneBatchesCommand extends RuntimeCommand {

    @Option(names = "--hours", description = "清掉这么多小时之前结束的批次(默认 24)")
    int hours;

    private final Supplier<BatchRepository> batches;

    public QueuePruneBatchesCommand(Supplier<BatchRepository> batches) {
        this.batches = batches;
    }

    @Override
    public int handle() {
        int window = hours > 0 ? hours : 24;

        if (!confirmInProduction("清掉 " + window + " 小时之前结束的批次记录")) return 1;

        int pruned = batches.get().prune(Duration.ofHours(window));
        if (pruned == 0) {
            comment("没有可清的批次(" + window + " 小时之前结束的)。");
            return 0;
        }

        success("清掉 " + pruned + " 个批次。");
        line("  未结束的批次不会被清 —— 删掉它们会让回调永远不执行。");
        return 0;
    }
}
