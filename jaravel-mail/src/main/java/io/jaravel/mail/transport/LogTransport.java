package io.jaravel.mail.transport;

import java.util.function.Consumer;

import io.jaravel.mail.RenderedMessage;
import io.jaravel.mail.Transport;

/**
 * 把邮件写日志 —— 开发期默认驱动(docs/11 §1.3)。
 *
 * <h2>默认**不打印正文**</h2>
 * <p>只打印摘要(主题 + 收件人)。要完整内容显式开
 * {@code mail.mailers.log.body: true}。
 *
 * <p>理由:邮件正文常含个人信息(收货地址、订单明细、重置链接),
 * 而日志通常比数据库更容易被读到 —— 集中式日志、第三方 APM、
 * 客服工单的附件。开发机上这无所谓,但 log 驱动**也会被配到
 * 预发甚至生产**(「先别真发邮件」),而那时日志里就有了一堆
 * 真实用户的个人信息。
 *
 * <p>与 {@code AuthEvents.Failed} 不含密码是同一条考虑:
 * 把危险的东西从默认行为里排除,比写一条「请注意」的注释可靠。
 *
 * <p>⚠ 一个例外:**密码重置与邮箱验证的链接在开发期是必须看到的**,
 * 否则本地没法走完那个流程。所以开发模式下(`app.debug=true`)
 * 默认把正文打出来 —— 那个环境里本来就没有真实用户数据。
 */
public class LogTransport implements Transport {

    private final Consumer<String> sink;
    private final boolean includeBody;

    public LogTransport() {
        this(System.err::println, io.jaravel.core.Application.isDebug());
    }

    public LogTransport(Consumer<String> sink, boolean includeBody) {
        this.sink = sink;
        this.includeBody = includeBody;
    }

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void send(RenderedMessage message) {
        var out = new StringBuilder("\n[jaravel:mail] ")
                .append(message.summary())
                .append("\n  From: ").append(message.from().format());

        if (!message.cc().isEmpty()) {
            out.append("\n  Cc: ").append(formatAll(message.cc()));
        }
        if (!message.bcc().isEmpty()) {
            out.append("\n  Bcc: ").append(formatAll(message.bcc()));
        }
        if (!message.attachments().isEmpty()) {
            out.append("\n  附件: ").append(message.attachments());
        }

        if (includeBody) {
            // 优先打**纯文本** —— HTML 在终端里是一堵墙,
            // 而开发期真正要看的东西(验证链接、验证码)在文本版本里
            // 一样有,且一眼就能看到
            if (message.hasText()) {
                out.append("\n  ---- 正文(纯文本)----\n").append(message.text());
            } else if (message.hasHtml()) {
                out.append("\n  ---- 正文(HTML)----\n").append(message.html());
            }
        } else {
            out.append("\n  (正文未打印 —— 邮件正文常含个人信息。")
               .append("要看的话配 mail.mailers.log.body: true)");
        }

        sink.accept(out.append('\n').toString());
    }

    @Override
    public boolean actuallyDelivers() {
        return false;
    }

    private static String formatAll(java.util.List<io.jaravel.mail.Address> addresses) {
        return addresses.stream().map(io.jaravel.mail.Address::format)
                .reduce((a, b) -> a + ", " + b).orElse("");
    }
}
