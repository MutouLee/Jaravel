package io.jaravel.mail;

import java.util.List;

/**
 * 一封邮件的定义(docs/11 §1.1)。
 *
 * <pre>{@code
 * public class OrderShipped extends Mailable {
 *
 *     private final Order order;
 *
 *     public OrderShipped(Order order) { this.order = order; }
 *
 *     @Override public Envelope envelope() {
 *         return Envelope.of()
 *                 .from("noreply@shop.com", "小店")
 *                 .withSubject("订单 #" + order.number() + " 已发货");
 *     }
 *
 *     @Override public Content content() {
 *         return Content.view("mail.orders.shipped").with("order", order);
 *     }
 * }
 * }</pre>
 *
 * <h2>★ 入队发送是「投递时渲染」,不是序列化 Mailable</h2>
 *
 * <p>这是与 Laravel 最大的一处不同,值得完整解释。
 *
 * <p>Laravel 的 {@code Mail::queue($mailable)} 把 Mailable 本身序列化进
 * 队列 payload(靠 {@code SerializesModels} 把模型换成主键,
 * worker 那边再查回来)。
 *
 * <p>Jaravel 的做法是:**在投递时把邮件渲染完**,把渲染结果
 * ({@link RenderedMessage})放进 payload。
 *
 * <h3>为什么</h3>
 * <ol>
 *   <li><b>Mailable 不再有任何序列化约束。</b> 它可以持有模型、
 *       接口、Lambda —— 什么都行。Laravel 的方案里
 *       {@code private Order order} 是个陷阱(模型快照过期),
 *       而这个项目在队列那边已经为此立了「只存主键」的规矩 ——
 *       邮件这里不必再让用户记一遍;</li>
 *   <li><b>没有「模型被删了导致邮件发不出」这类失败。</b>
 *       Laravel 的方案里 worker 查不到那个订单,邮件就永远发不出去,
 *       而失败原因指向「订单不存在」—— 与「发货通知」毫无关系;</li>
 *   <li><b>队列里慢的是 SMTP 往返,不是渲染。</b> 渲染是本地的、
 *       微秒级、确定性的;SMTP 是网络的、数百毫秒、会失败的。
 *       入队的目的是把后者挪走,前者留在请求里不影响这个目的;</li>
 *   <li><b>语义上也更对。</b> 邮件描述的是**已经发生的事**,
 *       它应该反映投递那一刻的状态,而不是 worker 执行那一刻的。</li>
 * </ol>
 *
 * <h3>代价(必须说清)</h3>
 * <ul>
 *   <li>渲染发生在请求线程里。模板很慢(比如一个循环渲染 1000 行的
 *       对账单)时这部分时间没被挪走;</li>
 *   <li>{@code Attachment.fromData} 的字节会进 payload。
 *       见 {@link Attachment} 的类注释 —— 大附件建议用
 *       {@code fromPath} 只传路径。</li>
 * </ul>
 *
 * <h2>Mailable 可以直接作为控制器返回值</h2>
 * <p>{@code r.get("/dev/mail/shipped", () -> new OrderShipped(order))}
 * 会渲染成 HTML 预览。这是 Laravel 的好设计,照搬 ——
 * 邮件模板的调试没有它会非常痛苦(改一行发一封)。
 *
 * <p>**但它只在 debug 模式下生效**:生产环境里一个暴露邮件预览的路由
 * 等于泄露任意用户的邮件内容。这个限制由 starter 的响应转换器实现。
 */
public abstract class Mailable {

    /** 谁发、主题、标签 */
    public abstract Envelope envelope();

    /** 正文 */
    public abstract Content content();

    /** 附件。默认没有 */
    public List<Attachment> attachments() {
        return List.of();
    }

    /** 自定义头部。默认没有 */
    public List<Header> headers() {
        return List.of();
    }

    /**
     * 渲染这封邮件用哪个语言。
     *
     * <p>null = 用应用默认。{@code Mail.to(user).locale("en")} 会覆盖它。
     *
     * <p>放在 Mailable 上而不是只在 {@code Mail.to(...)} 上:
     * 有些邮件天生是单语言的(给运维的告警),把语言写进 Mailable
     * 比要求每个调用点都记得指定可靠。
     */
    public String locale() {
        return null;
    }

    /**
     * 一个不需要模板的简单邮件。
     *
     * <pre>{@code
     * Mailable.simple()
     *         .subject("发票已支付")
     *         .line("你的发票 #42 已支付。")
     *         .action("查看发票", "https://...")
     *         .line("谢谢使用我们的服务!");
     * }</pre>
     *
     * <p>存在的理由:通知(docs/11 §2)的 {@code toMail} 几乎总是
     * 「几行字 + 一个按钮」,为它写一个模板文件是多余的。
     *
     * <p>它用一个**内置的、带内联 CSS 的布局**渲染 ——
     * 邮件客户端不支持外部样式表,而内联 CSS 是唯一可靠的办法。
     * 这不是 docs/11 §1.4 的那套 {@code <x-mail::*>} 组件
     * (那需要 jblade 的组件支持,还没有),而是一个够用的最小版本。
     */
    public static SimpleMailable simple() {
        return new SimpleMailable();
    }

    /** 一个自定义头部 */
    public record Header(String name, String value) {

        public Header {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("邮件头部的名字不能为空。");
            }
            // 与地址同样的理由:头部的值里带换行会注入新头部
            if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("""
                        邮件头部 %s 的值里不能有换行 —— 那会注入新的头部。
                          见 Address 的类注释里关于邮件头注入的说明。"""
                        .formatted(name));
            }
        }

        public static Header of(String name, String value) {
            return new Header(name, value);
        }
    }
}
