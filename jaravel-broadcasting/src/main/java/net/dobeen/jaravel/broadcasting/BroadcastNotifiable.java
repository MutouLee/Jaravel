package net.dobeen.jaravel.broadcasting;

/**
 * 覆盖 broadcast 通知的收件频道(docs/19 §4.2)。
 *
 * <p>只在你想要一个**非默认**的频道时才实现它。默认是
 * {@code private-{notifiableType}.{notifiableId}}(如 {@code private-user.42})。
 *
 * <pre>{@code
 * public class User implements Notifiable, BroadcastNotifiable {
 *     @Override public String receivesBroadcastNotificationsOn() {
 *         return "private-admin." + getId();     // 管理员专用频道
 *     }
 * }
 * }</pre>
 */
public interface BroadcastNotifiable {

    String receivesBroadcastNotificationsOn();
}
