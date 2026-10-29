package app.jobs;

import java.util.Map;

import app.models.Invoice;
import app.models.Invoice_;
import io.jaravel.queue.ChainFailure;
import io.jaravel.queue.ShouldQueue;

/**
 * 链中断时的回调。
 *
 * <p>它是一个**任务**而不是 lambda —— 设计文档里写的
 * {@code .catch_(e -> ...)} 在 Java 里跨不过队列这道边界
 * (理由见 {@code PendingChain} 的类注释)。
 *
 * <p>回调是任务反而多拿到三样东西:能带参数、handle 能注入依赖、
 * 它自己也有重试与失败归档。
 */
public class NotifyChainBroken implements ShouldQueue {

    private final Long invoiceId;

    public NotifyChainBroken(Long invoiceId) {
        this.invoiceId = invoiceId;
    }

    @Override public String queue() { return "mail"; }

    /**
     * {@link ChainFailure} 由框架从**信封上下文**注入,不在容器里。
     *
     * <p>它是异常的可序列化摘要,不是原始 Throwable —— 后者没法穿过
     * 队列(这次执行可能在另一个进程里)。要完整堆栈的话
     * {@code failure.jobUuid()} 能在 failed_jobs 里定位到那一条。
     */
    public void handle(ChainFailure failure) {
        Invoice.query().where(Invoice_.id, invoiceId)
                .update(Map.of(
                        Invoice_.status, "broken",
                        // 把断点写进业务数据,这样测试不必依赖日志
                        Invoice_.orderNo, "broken-at:" + failure.jobName()));
    }
}
