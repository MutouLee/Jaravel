package app.jobs;

import java.util.Map;

import app.models.Invoice;
import app.models.Invoice_;
import io.jaravel.queue.ShouldQueue;

/** 链上的第三环:归档 */
public class ArchiveInvoice implements ShouldQueue {

    private final Long invoiceId;

    public ArchiveInvoice(Long invoiceId) {
        this.invoiceId = invoiceId;
    }

    @Override public String queue() { return "mail"; }

    public void handle() {
        Invoice.query().where(Invoice_.id, invoiceId)
                .update(Map.of(Invoice_.status, "archived"));
    }
}
