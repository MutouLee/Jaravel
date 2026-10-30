package app.notifications;

import java.util.List;
import java.util.Map;

import io.jaravel.notifications.HasDatabase;
import io.jaravel.notifications.Notifiable;
import io.jaravel.notifications.Notification;

/**
 * 发票已发出 —— **站内通知**。
 *
 * <p>这是 v0.4 验收第 1 条里「站内通知」那一半:
 * 「下单 → 异步发票 → 邮件 + 站内通知」。
 *
 * <h2>★ 为什么只有 database 渠道,没有 mail</h2>
 * <p>这条流程里的邮件由 {@code app.mail.InvoiceSent} 这个
 * {@code Mailable} 发出 —— 那是一封**带模板的发票邮件**(有金额、
 * 单号、纯文本版本)。
 *
 * <p>如果这里也实现 {@code HasMail},客户会收到**两封**邮件:
 * 一封发票、一封「您的发票已发出」。那不是「两个渠道」,
 * 那是一个 bug。
 *
 * <p>所以分工是:
 * <ul>
 *   <li>邮件 → {@code Mailable}。它是一份**文档**,有自己的模板;</li>
 *   <li>站内 → {@code Notification} + database 渠道。
 *       它是一个**指针**:一句话 + 一个跳转目标。</li>
 * </ul>
 *
 * <p>一个通知同时发 mail + database 的写法在
 * {@code examples/auth} 的 {@code WelcomeAboard} 里 ——
 * 那种场景(「欢迎加入」)两个渠道的内容本来就一样。
 *
 * <h2>字段只存主键</h2>
 * <p>与队列任务一样。理由是 {@code shouldQueue()} 时**通知对象本身
 * 要进 payload**(邮件能在投递时渲染完,通知不能 —— 各渠道的产出
 * 是不同类型的东西,没有统一的「渲染结果」)。
 *
 * <p>这里没有 {@code shouldQueue()},因为它已经在一个队列任务
 * ({@code SendInvoice})里被调用了 —— 再入队一层只是多一次往返。
 */
public class InvoiceReady extends Notification implements HasDatabase {

    private final Long invoiceId;
    private final String orderNo;
    private final Integer amount;

    public InvoiceReady(Long invoiceId, String orderNo, Integer amount) {
        this.invoiceId = invoiceId;
        this.orderNo = orderNo;
        this.amount = amount;
    }

    @Override
    public List<String> via(Notifiable notifiable) {
        return List.of("database");
    }

    /**
     * 站内通知的内容。
     *
     * <p>⚠ 存的是**当时的**单号与金额,不是一个指向发票的引用。
     *
     * <p>这看起来与「只存主键」矛盾,其实不是:站内通知会被长期保留
     * (用户可能一个月后才点开),而那时按 id 去查发票可能
     * 查不到(归档了)或者查到的是改过的数据。
     * 一条「您的发票 #A-1001(¥200)已发出」的通知应该永远显示
     * 它被创建时的事实。
     *
     * <p>{@code invoiceId} 仍然存着 —— 它是**跳转目标**,
     * 而「点进去发现发票不见了」是一个可接受的结果。
     */
    @Override
    public Map<String, Object> toDatabase(Notifiable notifiable) {
        return Map.of(
                "message", "您的发票 %s(¥%d)已发出".formatted(orderNo, amount),
                "invoice_id", invoiceId,
                "url", "/api/invoices/" + invoiceId);
    }
}
