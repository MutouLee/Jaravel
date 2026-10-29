package app.jobs;

import java.util.Map;

import app.models.Invoice;
import app.models.Invoice_;
import io.jaravel.queue.ShouldQueue;

/** 批量任务的一份:给一张发票生成对账单 */
public class GenerateStatement implements ShouldQueue {

    private final Long invoiceId;

    public GenerateStatement(Long invoiceId) {
        this.invoiceId = invoiceId;
    }

    @Override public String queue() { return "reports"; }

    @Override
    public String displayName() {
        return "GenerateStatement(invoice=" + invoiceId + ")";
    }

    public void handle() {
        int updated = Invoice.query().where(Invoice_.id, invoiceId)
                .update(Map.of(Invoice_.status, "stated"));

        // 发票不存在就失败 —— 这是批处理里最常见的一种失败,
        // 而它恰好是「任务只存 id」的代价:投递与执行之间记录可能被删
        if (updated == 0) {
            throw new IllegalStateException("发票 " + invoiceId + " 不存在了");
        }
    }
}
