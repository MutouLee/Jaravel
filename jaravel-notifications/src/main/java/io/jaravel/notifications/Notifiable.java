package io.jaravel.notifications;

/**
 * 能收通知的东西 —— 通常是 User 模型(docs/11 §2.3)。
 *
 * <pre>{@code
 * @Model(table = "users")
 * public class User extends UserBase implements Notifiable {
 *
 *     @Override public Object notifiableId()   { return id; }
 *     @Override public String notifiableType() { return "users"; }
 *
 *     @Override public String routeNotificationFor(String channel) {
 *         return switch (channel) {
 *             case "mail"  -> email;
 *             case "sms"   -> phone;
 *             case "slack" -> slackWebhook;
 *             default -> null;
 *         };
 *     }
 * }
 * }</pre>
 *
 * <h2>★ 关于 {@code notify(...)} 这个方法名</h2>
 * <p>docs/11 §2.2 写的是 {@code user.notify(new InvoicePaid(invoice))}。
 *
 * <p>Java 里这个名字有一个陷阱:{@code Object.notify()} 是
 * {@code final} 的(线程唤醒)。我们这个是**重载**而不是覆写,
 * 所以它合法、也能正常工作 —— {@code user.notify(notification)}
 * 会选到我们的那个。
 *
 * <p>但:{@code user.notify()}(不带参数)调的是
 * {@code Object.notify()},而那会抛 {@code IllegalMonitorStateException}
 * (没持有监视器锁)。
 *
 * <p>这个风险是可接受的:它只在**手误少写参数**时出现,
 * 而那时报错是立刻且响亮的。相比之下,换一个
 * {@code sendNotification(...)} 这样的名字会让这个框架的
 * 「Laravel 式体验」在最常用的一个 API 上破功。
 *
 * <p>仍然把它写在这里,因为那个异常消息
 * ({@code current thread is not owner})与「我少写了一个参数」
 * 之间没有任何线索。
 *
 * <h2>为什么不用 APT 生成 notify()</h2>
 * <p>docs/11 §2.3 说「APT 生成 notify() / notifications() /
 * unreadNotifications()」。
 *
 * <p>没那么做:这三个方法**不需要**生成 —— 它们能写成接口的
 * 默认方法。生成代码的理由应该是「没有它就没法做」
 * (比如 {@code @Model} 的类型化查询),而不是「少写几行」。
 *
 * <p>一个额外的 APT 处理器意味着:多一个 pom 里要配的处理器路径、
 * 多一处可能忘配的地方(忘了的症状是「方法不存在」的编译错误,
 * 还算响亮)、以及多一份要维护的生成逻辑。
 * 这里不值得。
 */
public interface Notifiable {

    /**
     * 这个收件人在某个渠道上的「地址」。
     *
     * <p>mail → 邮箱;sms → 手机号;slack → webhook URL。
     *
     * <p>**返回 null 表示「这个渠道不可用」**,那个渠道会被跳过 ——
     * 而不是报错。理由:「有邮箱但没填手机号」是完全正常的,
     * 而为此要求 {@code via()} 去判断每个渠道的地址是否存在
     * 等于把框架的工作推给用户。
     *
     * <p>但跳过时会发一个 {@code NotificationSkipped} 事件 ——
     * 「短信没发出去」需要可观测,否则它和「发了但没收到」分不开。
     */
    String routeNotificationFor(String channel);

    /**
     * 主键 —— {@code database} 渠道用它。
     *
     * <p>只用 database 渠道时才需要它,所以给了一个默认实现:
     * 抛错并说清。
     */
    default Object notifiableId() {
        throw new UnsupportedOperationException("""
                %s 要用 database 渠道,但没有实现 notifiableId()。
                  站内通知表要知道「这条通知属于谁」,所以需要主键与类型:
                    @Override public Object notifiableId()   { return id; }
                    @Override public String notifiableType() { return "users"; }"""
                .formatted(getClass().getSimpleName()));
    }

    /**
     * 类型标识 —— 与 {@link #notifiableId()} 一起定位收件人。
     *
     * <p>**用表名或一个稳定的短字符串,不要用类的全限定名。**
     *
     * <p>默认实现返回简单类名。用全限定名的话,一次包重构
     * ({@code com.app.User} → {@code com.app.models.User})
     * 会让所有历史通知**查不到归属** —— 而那些数据还在表里,
     * 只是没人能读到了。
     */
    default String notifiableType() {
        return getClass().getSimpleName();
    }

    /**
     * 发一条通知给自己。
     *
     * <p>⚠ 方法名与 {@code Object.notify()} 的关系见类注释。
     */
    default void notify(Notification notification) {
        Notifications.send(this, notification);
    }

    /**
     * 渲染通知时用的语言。null = 应用默认。
     *
     * <p>这是「每人按自己的语言」的正确位置 —— 比在调用点写
     * {@code Notifications.locale(...)} 可靠,因为那个调用点
     * 可能一次发给很多人。
     *
     * <p>⚠ 它目前只影响 mail 渠道,而 mail 的 locale 本身
     * 还没生效(需要本地化模块)—— 见
     * {@code JaravelMailAutoConfiguration} 里的说明。
     */
    default String preferredLocale() {
        return null;
    }
}
