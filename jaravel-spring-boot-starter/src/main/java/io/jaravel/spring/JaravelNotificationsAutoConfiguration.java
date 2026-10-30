package io.jaravel.spring;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.database.Connection;
import io.jaravel.notifications.NotificationChannel;
import io.jaravel.notifications.NotificationManager;
import io.jaravel.notifications.Notifiable;
import io.jaravel.notifications.Notification;
import io.jaravel.notifications.Notifications;
import io.jaravel.notifications.channel.DatabaseChannel;
import io.jaravel.notifications.channel.HttpChannels;
import io.jaravel.notifications.channel.MailChannel;
import io.jaravel.notifications.channel.SmsChannel;

/**
 * 通知的自动装配(docs/11 §2)。
 *
 * <pre>{@code
 * notifications:
 *   database:
 *     table: notifications
 * }</pre>
 *
 * <h2>渠道的注册是**按可用性**的</h2>
 * <p>mail / slack / webhook 总是注册(它们的依赖都在);
 * database 要有 {@link Connection};sms 要应用提供一个
 * {@link SmsChannel.SmsSender} 的 bean。
 *
 * <p>没注册的渠道在 {@code via()} 里出现时**报错并给出相近候选** ——
 * 而不是静默跳过。一条「声明了 sms 但没发」的通知与
 * 「发了但没收到」在外部看来一模一样。
 */
@AutoConfiguration(after = {JaravelAutoConfiguration.class,
        JaravelQueueAutoConfiguration.class, JaravelEventsAutoConfiguration.class,
        JaravelMailAutoConfiguration.class})
public class JaravelNotificationsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NotificationManager jaravelNotificationManager(
            ConfigRepository config,
            ObjectProvider<Connection> connection,
            ObjectProvider<SmsChannel.SmsSender> smsSenders,
            ObjectProvider<NotificationChannel> customChannels,
            JaravelAutoConfiguration.JaravelStaticWiring wiring) {

        var manager = new NotificationManager()
                .register(new MailChannel())
                .register(new HttpChannels.Slack())
                .register(new HttpChannels.Webhook());

        Connection db = connection.getIfAvailable();
        if (db != null) {
            manager.register(new DatabaseChannel(db,
                    config.string("notifications.database.table", "notifications")));
        }

        // sms 只在应用提供了发送器时注册。
        //
        // 不提供时**不注册** —— 于是 via() 里的 "sms" 会撞上
        // 「没有这个渠道」的错误(带候选),那比
        // SmsChannel 自己的「没装发送器」更早、也更明确
        SmsChannel.SmsSender sender = smsSenders.getIfAvailable();
        if (sender != null) {
            manager.register(new SmsChannel(sender));
        }

        // 应用自定义的渠道(docs/11 §2.5)—— 一个 @Component 就够,
        // 不必再调 Notifications.extend(...)
        customChannels.orderedStream().forEach(manager::register);

        wireQueue(manager);

        Notifications.use(manager);
        return manager;
    }

    /**
     * 入队投递的通道 —— **每个渠道一条任务**(docs/11 §2.6)。
     *
     * <p>⚠ 这里与邮件的做法**不同**,值得说清。
     *
     * <p>邮件能在投递时把一切渲染完,所以 payload 里是一个
     * 扁平的 {@code RenderedMessage},Mailable 从不进队列。
     *
     * <p>通知做不到:渠道的 {@code toMail} / {@code toSms} /
     * {@code toDatabase} 产出的是**不同类型**的东西,
     * 没有一个统一的「渲染结果」。
     *
     * <p>所以这里不得不把**通知对象与收件人**序列化进 payload,
     * 于是「只存主键」那条规矩在通知这里仍然适用:
     * 一个持有整个 Order 的通知会在入队时报序列化错误。
     *
     * <p>这个差异写在这里而不只在文档里 —— 它会让从邮件那边
     * 带着「字段随便放」的印象过来的人踩一次。
     */
    private void wireQueue(NotificationManager manager) {
        if (!classPresent("io.jaravel.queue.Queue")) return;

        manager.queueDispatcher((notifiable, notification, channel, queueName, delay) -> {
            var job = new SendNotificationOnChannel(notifiable, notification,
                    channel, queueName);
            if (delay == null) {
                io.jaravel.queue.Queue.dispatch(job);
            } else {
                io.jaravel.queue.Queue.job(job).delay(delay).dispatch();
            }
        });
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false,
                    JaravelNotificationsAutoConfiguration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 在一个渠道上投递一条通知 —— 入队投递的 worker 侧。
     *
     * <p>字段是 public 非 final 的,因为要被 Jackson 反序列化。
     *
     * <p>⚠ {@code notifiable} 与 {@code notification} 都是**用户的对象**,
     * 它们必须能 JSON 往返 —— 见 {@link #wireQueue} 的注释。
     */
    public static class SendNotificationOnChannel
            implements io.jaravel.queue.ShouldQueue {

        public Notifiable notifiable;
        public Notification notification;
        public String channel;
        public String queueName;

        /** Jackson 需要 */
        public SendNotificationOnChannel() {
        }

        public SendNotificationOnChannel(Notifiable notifiable, Notification notification,
                                         String channel, String queueName) {
            this.notifiable = notifiable;
            this.notification = notification;
            this.channel = channel;
            this.queueName = queueName;
        }

        @Override
        public String queue() {
            return queueName;
        }

        @Override
        public int tries() {
            // 通知值得重试:渠道的失败大多是瞬时的(限流、连接重置)
            return 3;
        }

        @Override
        public String displayName() {
            return "Notify(%s → %s)".formatted(
                    notification == null ? "?" : notification.displayName(), channel);
        }

        public void handle(NotificationManager notifications) {
            notifications.deliverOne(notifiable, notification, channel);
        }
    }
}
