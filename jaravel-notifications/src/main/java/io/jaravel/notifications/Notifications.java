package io.jaravel.notifications;

import java.util.List;
import java.util.function.Predicate;

/**
 * 通知的静态门面(docs/11 §2.2)。
 *
 * <pre>{@code
 * user.notify(new InvoicePaid(invoice));
 * Notifications.send(users, new InvoicePaid(invoice));
 * Notifications.sendNow(user, notification);
 *
 * // 按需路由(收件人不是模型)
 * Notifications.route("mail", "a@b.com")
 *              .route("slack", webhookUrl)
 *              .deliver(new InvoicePaid(invoice));
 * }</pre>
 *
 * <h2>为什么门面叫 Notifications 而不是 Notification</h2>
 * <p>docs/11 §2.2 写的是 {@code Notification.send(...)},但
 * {@code Notification} 已经是**通知的基类**了
 * (用户写 {@code extends Notification})。
 *
 * <p>同一个名字既是基类又是门面在 PHP 里可行(两个不同的命名空间),
 * 在 Java 里不行。复数形式是最小的改动,而且它读起来仍然对:
 * {@code Notifications.send(users, notification)}。
 */
public final class Notifications {

    private static volatile NotificationManager manager;

    private Notifications() {
    }

    public static void use(NotificationManager configured) {
        manager = configured;
    }

    public static NotificationManager manager() {
        NotificationManager current = manager;
        if (current == null) {
            throw new IllegalStateException("""
                    通知尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调
                        Notifications.use(new NotificationManager()
                                .register(new MailChannel()));""");
        }
        return current;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        manager = null;
    }

    // ================= 发送 =================

    public static int send(Notifiable notifiable, Notification notification) {
        return manager().send(notifiable, notification);
    }

    public static int send(Iterable<? extends Notifiable> notifiables,
                           Notification notification) {
        return manager().send(notifiables, notification);
    }

    /** 跳过队列,立即投递 */
    public static int sendNow(Notifiable notifiable, Notification notification) {
        return manager().sendNow(notifiable, notification);
    }

    /** 注册一个自定义渠道(docs/11 §2.5) */
    public static void extend(String name, NotificationChannel channel) {
        manager().extend(name, channel);
    }

    // ================= 按需路由 =================

    /**
     * 给一个不是模型的收件人发通知(docs/11 §2.2 的 {@code Notification.route})。
     *
     * <pre>{@code
     * Notifications.route("mail", "ops@example.com")
     *              .route("slack", webhookUrl)
     *              .notify(new SystemAlert(...));
     * }</pre>
     *
     * <p>用途:发给一个运维邮箱、一个 Slack 频道 —— 它们不是用户。
     *
     * <p>⚠ 按需路由的收件人**不能用 database 渠道** ——
     * 站内通知要知道「这条通知属于谁」,而一个裸地址没有身份。
     * 那时会报错说清。
     */
    public static AnonymousNotifiable route(String channel, String route) {
        return new AnonymousNotifiable().route(channel, route);
    }

    // ================= 测试 =================

    public static void fake() {
        manager().fake();
    }

    public static void stopFaking() {
        manager().stopFaking();
    }

    // ================= 断言 =================

    public static void assertSentTo(Notifiable notifiable,
                                    Class<? extends Notification> type) {
        boolean found = manager().recorder().of(type).stream()
                .anyMatch(e -> sameRecipient(e.notifiable(), notifiable));

        if (!found) {
            throw new AssertionError("""
                    期望 %s 收到了 %s,但没有。
                      已记录的通知:%s
                      若通知是在 Notifications.fake() **之前**发的,这里看不到它。"""
                    .formatted(describe(notifiable), type.getSimpleName(),
                               manager().recorder().typeNames()));
        }
    }

    /**
     * 断言某个渠道被用了。
     *
     * <p>这个断言值得单独有:{@code via()} 是运行时决定的
     * (它看收件人的偏好),所以「这条通知走了哪些渠道」
     * 是一个真的需要被测的行为。
     */
    public static void assertSentOnChannel(Notifiable notifiable,
                                           Class<? extends Notification> type,
                                           String channel) {
        var matching = manager().recorder().of(type).stream()
                .filter(e -> sameRecipient(e.notifiable(), notifiable))
                .toList();

        if (matching.isEmpty()) {
            assertSentTo(notifiable, type);       // 更有信息量的那条消息
        }

        boolean found = matching.stream().anyMatch(e -> e.channels().contains(channel));
        if (!found) {
            throw new AssertionError("""
                    期望 %s 走 "%s" 渠道发给 %s,但实际的渠道是 %s。
                      via() 是运行时决定的 —— 检查它在这个收件人上
                      返回了什么。"""
                    .formatted(type.getSimpleName(), channel, describe(notifiable),
                               matching.stream().map(
                                       NotificationManager.FakeRecorder.Entry::channels)
                                       .toList()));
        }
    }

    @SuppressWarnings("unchecked")
    public static <N extends Notification> void assertSentTo(
            Notifiable notifiable, Class<N> type, Predicate<N> matching) {

        boolean found = manager().recorder().of(type).stream()
                .filter(e -> sameRecipient(e.notifiable(), notifiable))
                .anyMatch(e -> matching.test((N) e.notification()));

        if (!found) {
            throw new AssertionError(
                    "%s 发给了 %s,但没有一条满足给定条件。"
                            .formatted(type.getSimpleName(), describe(notifiable)));
        }
    }

    public static void assertNotSentTo(Notifiable notifiable,
                                       Class<? extends Notification> type) {
        boolean found = manager().recorder().of(type).stream()
                .anyMatch(e -> sameRecipient(e.notifiable(), notifiable));

        if (found) {
            throw new AssertionError("期望 %s 没有收到 %s,但它收到了。"
                    .formatted(describe(notifiable), type.getSimpleName()));
        }
    }

    public static void assertCount(Class<? extends Notification> type, int times) {
        int actual = manager().recorder().of(type).size();
        if (actual != times) {
            throw new AssertionError("期望 %s 发了 %d 条,实际 %d 条。"
                    .formatted(type.getSimpleName(), times, actual));
        }
    }

    public static void assertNothingSent() {
        var names = manager().recorder().typeNames();
        if (!names.isEmpty()) {
            throw new AssertionError("期望没有发送任何通知,实际:" + names);
        }
    }

    /**
     * 两个 Notifiable 是同一个收件人吗。
     *
     * <p>先试 {@code notifiableType + notifiableId} —— 那是身份;
     * 它们不可用时(只用 mail 渠道的模型没实现 id)退回引用比较。
     *
     * <p>**不用 equals**:用户模型的 equals 可能按全部字段比,
     * 而一条通知发出去之后模型可能被改过 —— 那时断言会失败,
     * 而失败原因与被测的行为无关。
     */
    private static boolean sameRecipient(Notifiable a, Notifiable b) {
        if (a == b) return true;

        try {
            return a.notifiableType().equals(b.notifiableType())
                    && String.valueOf(a.notifiableId())
                            .equals(String.valueOf(b.notifiableId()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String describe(Notifiable notifiable) {
        try {
            return "%s#%s".formatted(notifiable.notifiableType(),
                    notifiable.notifiableId());
        } catch (RuntimeException e) {
            return notifiable.getClass().getSimpleName();
        }
    }

    /**
     * 按需路由的收件人 —— 一组「渠道 → 地址」。
     *
     * <p>它不是模型,所以没有身份。见
     * {@link #notifiableId()} 的说明。
     */
    public static final class AnonymousNotifiable implements Notifiable {

        private final java.util.Map<String, String> routes =
                new java.util.LinkedHashMap<>();

        public AnonymousNotifiable route(String channel, String destination) {
            routes.put(channel, destination);
            return this;
        }

        @Override
        public String routeNotificationFor(String channel) {
            return routes.get(channel);
        }

        @Override
        public String notifiableType() {
            return "anonymous";
        }

        @Override
        public Object notifiableId() {
            throw new UnsupportedOperationException("""
                    按需路由的收件人不能用 database 渠道。
                      站内通知要记录「这条通知属于谁」,而一个裸地址
                      (Notifications.route("mail", "ops@example.com"))
                      没有身份 —— 它不对应任何一行数据。
                      把 "database" 从这条通知的 via() 里去掉,
                      或者改成发给一个真实的 Notifiable 模型。""");
        }

        /**
         * 发出去。
         *
         * <p>**不叫 notify** —— {@code Notifiable.notify} 的返回类型是
         * void,而这里想返回「成功了几个渠道」。改签名就不是覆写了,
         * 编译器会拒。
         *
         * <p>这是 {@code Notifiable.notify} 为了与 Laravel 对齐
         * 而返回 void 的一个小代价。按需路由的场景里知道
         * 「几个渠道成功了」有用(它常用来发运维告警),
         * 所以这里给一个单独的名字。
         */
        public int deliver(Notification notification) {
            return Notifications.send(this, notification);
        }

        public List<String> channels() {
            return List.copyOf(routes.keySet());
        }
    }
}
