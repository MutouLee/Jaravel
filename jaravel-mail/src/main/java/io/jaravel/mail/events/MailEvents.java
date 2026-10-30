package io.jaravel.mail.events;

import java.time.Duration;

import io.jaravel.mail.RenderedMessage;

/**
 * 邮件事件(docs/10 §2.5 的「邮件」那一行)。
 *
 * <pre>{@code
 * @Listener
 * public class MailMetrics {
 *     public void handle(MailEvents.MessageSent e) {
 *         metrics.timing("mail.sent", e.duration(), "mailer", e.mailer());
 *     }
 * }
 * }</pre>
 *
 * <h2>Sending 可中止 —— 这是「全局邮件开关」的实现方式</h2>
 * <p>{@code MessageSending} 的监听器写成 {@code boolean handle} 并返回
 * false,就能**取消**这封邮件。
 *
 * <p>真实用途:
 * <ul>
 *   <li>预发环境里挡掉所有发往非白名单域名的邮件 ——
 *       「用生产数据做的压测给真实用户发了邮件」是一类真实事故;</li>
 *   <li>一个全局的「维护模式,暂停所有外发」开关。</li>
 * </ul>
 *
 * <p>比在每个发送点加 if 可靠:发送点会越来越多,而漏掉的那一个
 * 就是出事的那一个。
 *
 * <h2>事件里带的是 RenderedMessage,不是 Mailable</h2>
 * <p>所以监听器能看到最终的收件人与主题 —— 那正是「挡掉非白名单域名」
 * 需要的东西。Mailable 那一层还不知道收件人是谁
 * (见 {@code Envelope} 的类注释)。
 *
 * <p>代价:监听器拿不到「这是哪个 Mailable 类」。为此事件里单独带了
 * {@link MessageSending#mailableType()}。
 */
public final class MailEvents {

    private MailEvents() {
    }

    /**
     * 即将发送。**可中止**。
     *
     * @param mailableType 原始 Mailable 的类名 —— RenderedMessage 里没有它
     */
    public record MessageSending(String mailer, String mailableType,
                                 RenderedMessage message) {
    }

    /** 发送成功 */
    public record MessageSent(String mailer, String mailableType,
                              RenderedMessage message, Duration duration) {
    }

    /**
     * 发送失败。
     *
     * <p>docs/10 §2.5 的表里没有这一条(它只列了 Sending/Sent),
     * 但没有它的话「邮件发失败了」就只能靠日志发现 ——
     * 而这正是最需要告警的一类事件。加上它。
     */
    public record MessageFailed(String mailer, String mailableType,
                                RenderedMessage message, Throwable cause) {
    }

    /** 被 MessageSending 的监听器挡掉了 */
    public record MessageBlocked(String mailer, String mailableType,
                                 RenderedMessage message) {
    }
}
