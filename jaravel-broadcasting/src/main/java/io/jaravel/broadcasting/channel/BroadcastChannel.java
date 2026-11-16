package io.jaravel.broadcasting.channel;

import io.jaravel.broadcasting.BroadcastManager;
import io.jaravel.broadcasting.BroadcastNotifiable;
import io.jaravel.broadcasting.HasBroadcast;
import io.jaravel.notifications.Notification;
import io.jaravel.notifications.NotificationChannel;
import io.jaravel.notifications.Notifiable;

/**
 * 通知的 {@code broadcast} 渠道(docs/19 §4)。
 *
 * <p>只是**又一个 NotificationChannel**:{@code shouldSend} /
 * {@code shouldQueue} / 每渠道一条队列任务 / {@code NotificationSent} /
 * {@code NotificationFailed} 事件全部由 {@code NotificationManager}
 * 照常处理 —— 这个类只做「把 payload 推到收件人的私有频道」。
 *
 * <h2>{@link #requiresRoute()} 返回 false</h2>
 * <p>频道由渠道自己推导(收件人覆盖或 {@code private-{type}.{id}} 默认),
 * 不需要走 {@code routeNotificationFor("broadcast")} 的通用路径 ——
 * 一个用户同时是 User 与 Notifiable 时,那个方法会被两个渠道
 * 问同一个问题,而答案不该由 route 表决定。
 */
public final class BroadcastChannel implements NotificationChannel {

    private final BroadcastManager manager;

    public BroadcastChannel(BroadcastManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "broadcast";
    }

    @Override
    public boolean requiresRoute() {
        return false;
    }

    @Override
    public void send(Notifiable notifiable, Notification notification) {
        var message = require(notifiable, notification, HasBroadcast.class)
                .toBroadcast(notifiable);

        // 走 manager.publish 而不是直接调驱动 —— 三条路径
        // (事件广播 / 通知渠道 / emit)都汇到这一个方法,
        // Broadcast.fake() 才能拦下全部(docs/19 §6.6)
        manager.publish(channelOf(notifiable), notification.displayName(), message.data());
    }

    /**
     * 收件频道:覆盖优先,默认 {@code private-{notifiableType}.{notifiableId}}。
     *
     * <p>没有实现 {@code notifiableId()} 的收件人(只用 mail 渠道的那种)
     * 走 broadcast 时**报错** —— 这与 {@code NotificationManager.safeId}
     * 的容错不同:那里是「事件里带个说明」,这里是「频道名都拼不出来,
     * 消息无处可去」,不能静默。
     */
    public static String channelOf(Notifiable notifiable) {
        if (notifiable instanceof BroadcastNotifiable override) {
            String channel = override.receivesBroadcastNotificationsOn();
            if (channel != null && !channel.isBlank()) {
                return channel;
            }
        }

        Object id;
        try {
            id = notifiable.notifiableId();
        } catch (RuntimeException e) {
            throw new IllegalStateException("""
                    通知走 broadcast 渠道需要收件人实现 notifiableId()(%s 没有)。
                      它用于推导收件频道 private-{type}.{id}:
                        @Override public Object notifiableId()   { return id; }
                        @Override public String notifiableType() { return "users"; }
                      或者实现 BroadcastNotifiable 显式指定频道。"""
                    .formatted(notifiable.getClass().getSimpleName()), e);
        }
        return "private-" + notifiable.notifiableType() + "." + id;
    }
}
