package app.jobs;

import java.time.Duration;

import app.models.Invoice;
import app.models.Invoice_;
import app.support.InvoiceGateway;
import io.jaravel.queue.ShouldQueue;
import io.jaravel.queue.UniqueJob;

/**
 * 发一张发票。
 *
 * <p>这个任务同时演示了四件事:**只存主键**、重试与 backoff、
 * 唯一性约束、以及 handle 的依赖注入。
 */
public class SendInvoice implements ShouldQueue, UniqueJob {

    /**
     * 只存 id,不存整个模型。
     *
     * <p>放模型进来有两个后果:payload 膨胀,以及**数据过期** ——
     * 任务可能在几分钟后才执行,那时快照已不是库里的状态,
     * 而基于过期数据的写入会覆盖中间的修改。
     */
    private final Long invoiceId;

    public SendInvoice(Long invoiceId) {
        this.invoiceId = invoiceId;
    }

    @Override public String queue() { return "mail"; }

    @Override public int tries() { return 3; }

    @Override
    public Duration[] backoff() {
        // 递增。固定间隔在「下游服务挂了」时会变成一轮同步的重试风暴,
        // 而那正是下游最需要喘息的时候
        return new Duration[] {Duration.ofMillis(50), Duration.ofMillis(200)};
    }

    /**
     * 同一张发票不重复入队。
     *
     * <p>场景:用户在前端连点了三次「发送发票」。没有这个约束的话
     * 客户会收到三封一样的邮件。
     */
    @Override
    public String uniqueId() {
        return String.valueOf(invoiceId);
    }

    /**
     * 兜底 TTL。正常情况下 Worker 在任务结束时释放锁,
     * 这个值只在 worker 崩掉、没机会释放时起作用。
     */
    @Override
    public Duration uniqueFor() {
        return Duration.ofMinutes(10);
    }

    /** 带上关键参数 —— failed_jobs 里一排 "SendInvoice" 没法排查 */
    @Override
    public String displayName() {
        return "SendInvoice(invoice=" + invoiceId + ")";
    }

    /**
     * 执行体。参数**从容器注入** —— 与控制器一致。
     *
     * <p>它不在 ShouldQueue 接口上,因为参数因任务而异;
     * 接口化会迫使所有任务写无参签名然后在方法体里
     * {@code Application.make(...)},那就把依赖藏起来了。
     */
    public void handle(InvoiceGateway gateway) {
        Invoice invoice = Invoice.query().findOrFail(invoiceId);

        // 先记一次尝试再去调下游 —— 反过来的话下游抛异常时
        // 这次尝试不会被记下,于是「重试了几次」对不上
        Invoice.query().where(Invoice_.id, invoiceId)
                .increment(Invoice_.attempts, 1);

        gateway.send(invoice.orderNo, invoice.amount);

        invoice.status = "sent";
        invoice.save();

        // 顺手发一封通知邮件,**入队发送**。
        //
        // 注意这里是在一个队列任务里又入队了一封邮件 —— 完全正常:
        // 邮件的入队是「投递时渲染 + 把渲染结果入队」,它与当前任务
        // 没有耦合。而同步发的话这个任务的执行时间会被 SMTP 的往返决定,
        // 于是 retryAfter 要配得更长(否则任务会在还在发邮件时被判失联)。
        io.jaravel.mail.Mail.to("customer@example.com")
                .queue(new app.mail.InvoiceSent(invoice));

        notifyCustomer(invoice);
    }

    /**
     * 站内通知 —— v0.4 验收第 1 条的后半截。
     *
     * <p>⚠ **在邮件之后、而且不让它的失败影响这个任务。**
     *
     * <p>顺序的理由:发票邮件是这个任务的**主要产出**,
     * 站内通知是附带的。反过来的话一个「通知表写失败」会让
     * 整个任务重试,而重试会再发一封邮件 —— 客户因为
     * 一条站内通知的失败收到了两封发票。
     *
     * <p>所以这里把异常吞掉并写进日志。这是这个示例里**唯一**
     * 一处刻意吞异常的地方,因为它是唯一一处
     * 「失败了也不该重试整个任务」的副作用。
     *
     * <p>客户为 null 时直接跳过:很多测试只造一张发票来验证队列
     * 本身的行为,而为此强制每张发票都有客户会让那些测试
     * 变成在测通知。
     */
    private void notifyCustomer(Invoice invoice) {
        if (invoice.customerId == null) return;

        try {
            app.models.Customer.query().find(invoice.customerId)
                    .ifPresent(customer -> customer.notify(
                            new app.notifications.InvoiceReady(
                                    invoice.id, invoice.orderNo, invoice.amount)));
        } catch (RuntimeException e) {
            // 见上:不能让它触发重试
            System.err.printf(
                    "[示例] 发票 %s 的站内通知失败(邮件已发出,不重试):%s%n",
                    invoice.orderNo, e);
        }
    }

    /**
     * 重试耗尽后的补偿。
     *
     * <p>**不在这里记日志** —— 框架已经把失败写进 failed_jobs 并记了日志,
     * 再记一条只会让同一个失败出现两次。这里做的是业务上的收尾。
     */
    @Override
    public void failed(Throwable cause) {
        Invoice.query().where(Invoice_.id, invoiceId)
                .update(java.util.Map.of(Invoice_.status, "failed"));
    }
}
