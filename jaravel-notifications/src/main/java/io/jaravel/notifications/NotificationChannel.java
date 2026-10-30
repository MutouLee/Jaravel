package io.jaravel.notifications;

/**
 * 一个投递渠道(docs/11 §2.5)。
 *
 * <pre>{@code
 * public class VoiceChannel implements NotificationChannel {
 *     @Override public String name() { return "voice"; }
 *
 *     @Override public void send(Notifiable n, Notification notification) {
 *         var message = require(n, notification, HasVoice.class).toVoice(n);
 *         twilio.calls().create(n.routeNotificationFor("voice"), message.twiml());
 *     }
 * }
 * }</pre>
 *
 * <h2>{@link #require} 是这个接口最重要的部分</h2>
 * <p>它处理这组 API 最容易出的那个错:{@code via()} 声明了一个渠道,
 * 而通知没实现对应的接口。
 *
 * <p>没有它的话每个渠道都要自己写一遍 {@code instanceof} + 报错,
 * 而其中某一个的消息注定会写得敷衍 —— 而那个渠道就是出问题时
 * 最难查的那个。
 */
public interface NotificationChannel {

    /** 渠道名,与 {@code via()} 里的字符串对应 */
    String name();

    /**
     * 投递。
     *
     * <p>抛异常表示失败。{@code shouldQueue()} 时这个失败由队列的
     * 重试机制处理;同步时抛给调用方。
     */
    void send(Notifiable notifiable, Notification notification);

    /**
     * 这个渠道需要收件人有一个「地址」吗。
     *
     * <p>{@code database} 渠道返回 false —— 它写的是
     * {@code notifiable_id},不需要 {@code routeNotificationFor}。
     *
     * <p>其余渠道返回 true,于是地址为 null 时那个渠道被**跳过**
     * (并发一个 Skipped 事件)而不是报错 ——
     * 「有邮箱但没填手机号」是完全正常的。
     */
    default boolean requiresRoute() {
        return true;
    }

    /**
     * 取出通知上对应这个渠道的方法接口,没实现就报错。
     *
     * @param required 比如 {@code Notification.HasMail.class}
     */
    default <T> T require(Notifiable notifiable, Notification notification,
                          Class<T> required) {
        if (required.isInstance(notification)) {
            return required.cast(notification);
        }

        throw new MissingChannelMethodException("""
                通知 %s 的 via() 里有 "%s",但它没有实现 %s。
                  加上:
                    public class %s extends Notification implements %s {
                        @Override public ... %s(Notifiable n) { ... }
                    }

                  为什么是接口而不是靠反射找方法:
                    反射方案里把方法名拼错(toSMS 而不是 toSms)不会报错 ——
                    找不到方法就当成「这个渠道不发」,于是「短信没发出去」
                    没有任何征兆。接口会让编译器要求你写对。

                  如果这个渠道本来就不该走,把它从 via() 里去掉 ——
                  via() 是运行时决定的,它可以看收件人的偏好。"""
                .formatted(notification.displayName(), name(),
                           required.getSimpleName(),
                           notification.getClass().getSimpleName(),
                           required.getSimpleName(),
                           guessMethodName(required)));
    }

    /** 从接口名猜方法名({@code HasMail} → {@code toMail})—— 只为错误消息好读 */
    private static String guessMethodName(Class<?> required) {
        String simple = required.getSimpleName();
        return simple.startsWith("Has") ? "to" + simple.substring(3) : "to…";
    }

    /** via() 声明了渠道但通知没实现对应接口。**不可重试** */
    class MissingChannelMethodException extends RuntimeException {
        public MissingChannelMethodException(String message) {
            super(message);
        }
    }
}
