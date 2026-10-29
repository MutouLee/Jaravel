package app.jobs;

import app.support.BatchLedger;
import io.jaravel.queue.Batch;
import io.jaravel.queue.ShouldQueue;

/**
 * 批次完成后的汇总回调。
 *
 * <p>{@link Batch} 由框架从信封上下文注入 —— 它是**实时查出来的状态**,
 * 不是投递回调时的快照。快照会让 {@code batch.isFinished()} 返回 false
 * (投递回调的那一刻批次还没标完成),而那读起来像个 bug。
 */
public class StatementsDone implements ShouldQueue {

    private final String label;

    public StatementsDone(String label) {
        this.label = label;
    }

    @Override public String queue() { return "reports"; }

    public void handle(Batch batch, BatchLedger ledger) {
        ledger.record("%s:%d/%d:失败%d".formatted(
                label, batch.processedJobs(), batch.totalJobs(), batch.failedJobs()));
    }
}
