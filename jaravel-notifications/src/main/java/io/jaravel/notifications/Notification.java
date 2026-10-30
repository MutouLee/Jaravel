package io.jaravel.notifications;

import java.util.List;

/**
 * 一条通知:**同一条消息,多渠道投递**(docs/11 §2.1)。
 *
 * <pre>{@code
 * public class InvoicePaid extends Notification implements HasMail, HasDatabase {
 *
 *     private final Invoice invoice;
 *
 *     public InvoicePaid(Invoice invoice) { this.invoice = invoice; }
 *
 *     @Override public List<String> via(Notifiable n) {
 *         return List.of("mail", "database");
 *     }
 *
 *     @Override public Mailable toMail(Notifiable n) {
 *         return Mailable.simple()
 *                 .subject("发票已支付")
 *                 .line("你的发票 #" + invoice.number() + " 已支付。")
 *                 .action("查看发票", url);
 *     }
 *
 *     @Override public Map<String, Object> toDatabase(Notifiable n) {
 *         return Map.of("invoice_id", invoice.id(), "amount", invoice.amount());
 *     }
 * }
 * }</pre>
 *
 * <h2>★ 渠道方法由**接口**声明,不靠反射找</h2>
 * <p>docs/11 §2.1 里的 {@code toMail} / {@code toDatabase} 是裸方法。
 * Jaravel 要求实现对应的接口({@link HasMail}、{@link HasDatabase} 等)。
 *
 * <p>理由是这组 API 最容易出的那个错:
 *
 * <pre>{@code
 * @Override public List<String> via(Notifiable n) {
 *     return List.of("mail", "sms");      // 声明了 sms
 * }
 * // ……但忘了写 toSms
 * }</pre>
 *
 * <p>反射方案里这个错误要等到**运行时第一次发短信**才暴露,
 * 而那时的报错是「找不到方法 toSms」。更糟的变体是把方法名拼错
 * ({@code toSMS}),那时反射找不到它,而「短信没发出去」
 * 没有任何征兆。
 *
 * <p>接口方案里 {@code implements HasSms} 会让编译器要求你写
 * {@code toSms} —— 拼错的话编译不过。
 *
 * <p>代价:{@code via()} 返回的字符串与接口之间仍然没有编译期关联
 * (via 是运行时决定的,它要看收件人的偏好)。所以渠道在找不到
 * 对应接口时**报错并说清是哪个通知缺哪个接口** —— 见
 * {@code NotificationChannel} 的实现。
 *
 * <h2>shouldQueue 时每个渠道是**独立**的队列任务</h2>
 * <p>docs/11 §2.6:单个渠道失败不影响其他渠道。
 *
 * <p>所以 {@code via()} 返回三个渠道时会投三条队列任务,各自重试。
 * Slack 挂掉不会让邮件发不出去 —— 而把三个渠道放一个任务里的话,
 * 第一个渠道的失败会让后两个永远不执行(重试时又从第一个开始,
 * 于是邮件被发了三遍而短信一次都没发)。
 */
public abstract class Notification {

    /**
     * 走哪些渠道。
     *
     * <p>**运行时决定** —— 它能看收件人的偏好:
     *
     * <pre>{@code
     * @Override public List<String> via(Notifiable n) {
     *     var channels = new ArrayList<>(List.of("database"));
     *     if (n.prefersEmail()) channels.add("mail");
     *     return channels;
     * }
     * }</pre>
     *
     * <p>返回空列表表示「这个收件人不需要被通知」—— 那是合法的,
     * 不报错。但在 debug 模式下会提示一次:一个永远返回空的
     * {@code via()} 是个什么都不做的通知,而那通常是写错了。
     */
    public abstract List<String> via(Notifiable notifiable);

    /**
     * 异步投递吗。
     *
     * <p>默认 **false**(同步)。
     *
     * <p>这个默认值与队列的 {@code tries()=1} 同理:异步意味着
     * 「调用返回时通知还没发出去」,而那个语义变化应该是显式选的。
     *
     * <p>但实践中**几乎总该返回 true** —— 一条通知可能要发三个渠道,
     * 每个都是一次网络往返。同步做的话一次 {@code user.notify(...)}
     * 会给请求加上数百毫秒。文档里会这么建议。
     */
    public boolean shouldQueue() {
        return false;
    }

    /** 进哪个队列。null = 默认 */
    public String queue() {
        return null;
    }

    /**
     * 延迟多久投递。
     *
     * <p>用途:「下单 10 分钟后如果还没支付就提醒一次」——
     * 配合 {@link #shouldSend} 做条件取消。
     */
    public java.time.Duration delay() {
        return null;
    }

    /**
     * 投递前的最后一道判断,**在 worker 里执行**。
     *
     * <p>这是 {@code via()} 做不到的事:{@code via()} 在投递时跑,
     * 而这个在真正要发的那一刻跑。
     *
     * <p>用途:一条延迟 10 分钟的「催付款」通知,在这 10 分钟里
     * 用户可能已经付了 —— 那时它不该再发出去。
     * 用 {@code via()} 判断是无效的:它在延迟**开始**时就跑完了。
     */
    public boolean shouldSend(Notifiable notifiable, String channel) {
        return true;
    }

    /**
     * 唯一性标识。
     *
     * <p>非 null 时同一个 id 的通知在短时间内只投递一次 ——
     * 用途是防止「同一个事件触发了多次通知」。
     *
     * <p>⚠ **v0.4 未实现去重**。这个方法存在是为了让签名稳定,
     * 但 {@code NotificationManager} 目前忽略它。
     *
     * <p>不假装实现它:去重需要一个共享的锁存储(与队列的
     * {@code UniqueJob} 同一套机制),而把那套东西接过来
     * 需要把缓存变成通知模块的依赖。列入后续版本。
     *
     * <p>这一行注释是刻意的 —— 一个「声明了却不生效」的
     * {@code uniqueId()} 正是这个项目反复在修的那类问题,
     * 所以它必须在 javadoc 里说清,而不是看起来像已经工作了。
     */
    public String uniqueId() {
        return null;
    }

    /** 日志与事件里的名字 */
    public String displayName() {
        return getClass().getSimpleName();
    }

    // ================= 渠道接口 =================
    //
    // 它们是**包级的顶层接口**(HasMail / HasDatabase / HasSlack /
    // HasSms / HasWebhook),不是这个类的嵌套类型。
    //
    // 理由是一个真实的易用性问题:嵌套类型在同一个声明的
    // `implements` 子句里**不在作用域内**,所以
    //
    //     public class InvoicePaid extends Notification implements HasMail
    //
    // 会报「找不到符号 HasMail」—— 而那正是用户会照着文档写的第一行。
    // 必须写成 `implements Notification.HasMail` 的话,这个 API
    // 在最常用的位置上就变难用了。
    //
    // 这个坑是写这个模块的测试时当场撞上的。
}
