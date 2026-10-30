package app.mail;

import app.models.Invoice;
import io.jaravel.mail.Content;
import io.jaravel.mail.Envelope;
import io.jaravel.mail.Mailable;

/**
 * 发票已发送的通知邮件。
 *
 * <p>**它持有一个完整的 Invoice 模型,而这是允许的** ——
 * 与队列任务的「只存主键」规矩不同。
 *
 * <p>理由:入队发送是「投递时渲染」(见 {@code Mailable} 的类注释),
 * 所以 Mailable 永远不进队列 payload,也就没有序列化约束,
 * 更不会有「模型快照过期」的问题。
 *
 * <p>这个区别值得记住:
 * <ul>
 *   <li>{@code ShouldQueue} 的字段 → **只存主键**;</li>
 *   <li>{@code Mailable} 的字段 → 随便放。</li>
 * </ul>
 */
public class InvoiceSent extends Mailable {

    private final Invoice invoice;

    public InvoiceSent(Invoice invoice) {
        this.invoice = invoice;
    }

    public Invoice invoice() {
        return invoice;
    }

    @Override
    public Envelope envelope() {
        return Envelope.of()
                .withSubject("发票 " + invoice.orderNo + " 已发送")
                .tags("invoice", "transactional")
                .metadata("invoice_id", invoice.id);
    }

    @Override
    public Content content() {
        // 用 Content.html 而不是模板:这个示例没有视图目录,
        // 而「不写模板也能发一封邮件」正是 Content.html 与
        // Mailable.simple() 存在的理由
        return Content.html("""
                <p>你的发票 <strong>%s</strong> 已发送。</p>
                <p>金额:%d 元</p>
                """.formatted(invoice.orderNo, invoice.amount));
    }
}
