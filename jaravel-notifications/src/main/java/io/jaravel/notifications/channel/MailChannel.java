package io.jaravel.notifications.channel;

import io.jaravel.mail.Mail;
import io.jaravel.mail.Mailable;
import io.jaravel.notifications.Notifiable;
import io.jaravel.notifications.Notification;
import io.jaravel.notifications.HasMail;
import io.jaravel.notifications.NotificationChannel;

/**
 * mail 渠道 —— 复用 {@link Mailable}(docs/11 §2.5)。
 *
 * <h2>★ 这里**不再入队**</h2>
 * <p>这是一处容易做错的地方。
 *
 * <p>{@code Notification.shouldQueue()} 为 true 时,通知管理器
 * 已经为**每个渠道**投了一条队列任务(docs/11 §2.6)。
 * 所以这个渠道的 {@code send} 是在 worker 里跑的。
 *
 * <p>如果这里再调 {@code Mail.to(...).queue(...)},就变成了
 * **两层队列**:worker 取出通知任务 → 又投一条邮件任务 →
 * 另一个 worker 取出来才真的发。
 *
 * <p>后果不只是多一跳:
 * <ul>
 *   <li>通知任务会**立刻成功**(它只是投了封邮件),于是
 *       「通知发失败了」这件事在通知的重试里永远不会发生 ——
 *       失败全部记在那条邮件任务上;</li>
 *   <li>{@code NotificationSent} 事件的含义从「发出去了」
 *       退化成「入队了」。</li>
 * </ul>
 *
 * <p>所以这里一律 {@code send(...)} 同步发。
 * 异步性由通知那一层的 {@code shouldQueue()} 提供,
 * 而那一层已经有重试与 failed_jobs。
 */
public class MailChannel implements NotificationChannel {

    @Override
    public String name() {
        return "mail";
    }

    @Override
    public void send(Notifiable notifiable, Notification notification) {
        Mailable mailable = require(notifiable, notification,
                HasMail.class).toMail(notifiable);

        String address = notifiable.routeNotificationFor("mail");

        var pending = Mail.to(address);

        String locale = notifiable.preferredLocale();
        if (locale != null) pending.locale(locale);

        // 同步发 —— 理由见类注释
        pending.send(mailable);
    }
}
