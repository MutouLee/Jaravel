package app.http.controllers;

import java.util.List;
import java.util.Map;

import app.jobs.ArchiveInvoice;
import app.jobs.GenerateStatement;
import app.jobs.MarkInvoiceReady;
import app.jobs.NotifyChainBroken;
import app.jobs.SendInvoice;
import app.jobs.StatementsDone;
import app.models.Invoice;
import app.models.Invoice_;
import io.jaravel.http.Request;
import io.jaravel.queue.Batch;
import io.jaravel.queue.Queue;
import org.springframework.stereotype.Component;

/**
 * 发票接口。每个端点对应 docs/10 §1.2 里的一种投递形式。
 *
 * <p>控制器里**只做投递**,不做任何实际工作 —— 这是队列的全部意义:
 * 接口立即返回,重活交给 worker。一个端点里既投任务又顺手干一点活
 * 会让「这件事到底在哪做」变得不确定。
 */
@Component
public class InvoiceController {

    /** 建一张发票并投递发送任务 */
    public Map<String, Object> store(Request request) {
        Invoice invoice = new Invoice();
        invoice.fill(Map.of(
                "order_no", request.input("order_no", "ORD-" + System.nanoTime()),
                "amount", Integer.parseInt(request.input("amount", "100")),
                "status", "pending"));
        invoice.save();

        // 返回 uuid 给调用方。**null 表示被唯一性约束去重了** ——
        // 这个区别要暴露出来,否则「连点三次只发了一封」在接口层
        // 完全看不出来
        String uuid = Queue.dispatch(new SendInvoice(invoice.id));

        return Map.of(
                "id", invoice.id,
                "job", uuid == null ? "deduplicated" : uuid);
    }

    /** 延迟投递 */
    public Map<String, Object> remind(Invoice invoice, Request request) {
        long seconds = Long.parseLong(request.input("after", "60"));

        String uuid = Queue.job(new SendInvoice(invoice.id))
                .delay(java.time.Duration.ofSeconds(seconds))
                .dispatch();

        return Map.of("job", uuid == null ? "deduplicated" : uuid);
    }

    /**
     * 同步执行 —— 不入队。
     *
     * <p>用途是开发期调试。异常会**原样抛出**,于是接口返回 500 ——
     * 同步执行的调用方就在当前调用栈上,它有权知道失败了。
     */
    public Map<String, Object> sendNow(Invoice invoice) {
        Queue.dispatchSync(new SendInvoice(invoice.id));
        return Map.of("status", "sent");
    }

    /**
     * 链式:标记 → 发送 → 归档,前一个成功才继续。
     *
     * <p>catch 回调是一个**任务**({@link NotifyChainBroken}),
     * 不是 lambda —— 理由见那个类的注释。
     */
    public Map<String, Object> pipeline(Invoice invoice) {
        String head = Queue.chain(
                        new MarkInvoiceReady(invoice.id),
                        new SendInvoice(invoice.id),
                        new ArchiveInvoice(invoice.id))
                .catchWith(new NotifyChainBroken(invoice.id))
                .dispatch();

        return Map.of("chain", head);
    }

    /**
     * 批量:给每张发票生成对账单,全部完成后投汇总回调。
     *
     * <p>这里显式 {@code allowFailures()}:每张发票互相独立,
     * 一张失败不该让其余的都不做。默认行为是相反的 ——
     * 见 {@code PendingBatch} 的类注释。
     */
    public Map<String, Object> statements() {
        List<io.jaravel.queue.ShouldQueue> jobs =
                Invoice.query().pluck(Invoice_.id).stream()
                        .map(id -> (io.jaravel.queue.ShouldQueue)
                                new GenerateStatement(id))
                        .toList();

        Batch batch = Queue.batch(jobs)
                .name("月度对账单")
                .allowFailures()
                .then(new StatementsDone("then"))
                .finallyRun(new StatementsDone("finally"))
                .dispatch();

        return Map.of("batch", batch.id(), "total", batch.totalJobs());
    }

    /** 查批次进度 */
    public Map<String, Object> batchStatus(Request request) {
        String id = request.routeParam("batch").orElseThrow();

        return Queue.findBatch(id)
                .map(b -> Map.<String, Object>of(
                        "id", b.id(),
                        "name", b.name() == null ? "" : b.name(),
                        "progress", b.progress(),
                        "processed", b.processedJobs(),
                        "failed", b.failedJobs(),
                        "finished", b.isFinished(),
                        "cancelled", b.isCancelled()))
                .orElseThrow(() -> new IllegalArgumentException("批次不存在:" + id));
    }

    /** 取消批次 —— 未出队的任务会在出队时被跳过 */
    public Map<String, Object> cancelBatch(Request request) {
        Queue.batches().cancel(request.routeParam("batch").orElseThrow());
        return Map.of("status", "cancelled");
    }

    public Invoice show(Invoice invoice) {
        return invoice;
    }
}
