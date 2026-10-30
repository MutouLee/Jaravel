package io.jaravel.notifications.events;

/**
 * 通知事件(docs/10 §2.5 的「通知」那一行)。
 *
 * <h2>Skipped 是这组里最有价值的那个</h2>
 * <p>一条通知被跳过有两种原因:
 * <ul>
 *   <li>收件人在那个渠道上没有地址({@code routeNotificationFor}
 *       返回 null)—— 「有邮箱但没填手机号」;</li>
 *   <li>{@code shouldSend()} 返回了 false —— 「10 分钟后催付款,
 *       但用户已经付了」。</li>
 * </ul>
 *
 * <p>两者都是**正常**的,所以框架不报错。但它们与「发了但没收到」
 * 在外部看来一模一样 —— 而那两件事的处理方式完全不同
 * (一个是数据不全,一个是服务商故障)。
 *
 * <p>所以跳过一定带原因。没有这个事件的话,「用户说没收到短信」
 * 这个工单只能靠翻代码去猜。
 */
public final class NotificationEvents {

    private NotificationEvents() {
    }

    /**
     * 即将投递。**可中止** —— 返回 false 取消这一个渠道的投递。
     *
     * <p>用途:预发环境挡掉所有外发通知、或者一个全局的
     * 「免打扰时段」开关。
     *
     * @param notifiable 收件人的类型与 id(不是对象本身 —— 见下)
     */
    public record NotificationSending(String notification, String channel,
                                      String notifiableType, String notifiableId) {
    }

    public record NotificationSent(String notification, String channel,
                                   String notifiableType, String notifiableId,
                                   java.time.Duration duration) {
    }

    /**
     * 某一个渠道失败了。
     *
     * <p>⚠ **其它渠道仍然会被尝试**(docs/11 §2.6)。所以一条通知
     * 可能同时产生一个 Failed 与两个 Sent。
     */
    public record NotificationFailed(String notification, String channel,
                                     String notifiableType, String notifiableId,
                                     Throwable cause) {
    }

    /** 被跳过,带原因 */
    public record NotificationSkipped(String notification, String channel,
                                      String notifiableType, String notifiableId,
                                      String reason) {
    }
}
