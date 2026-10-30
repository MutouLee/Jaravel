package io.jaravel.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * 不需要模板的简单邮件 —— {@code Mailable.simple()} 的产物。
 *
 * <pre>{@code
 * Mailable.simple()
 *         .subject("发票已支付")
 *         .line("你的发票 #42 已支付。")
 *         .action("查看发票", "https://example.com/invoices/42")
 *         .line("谢谢使用我们的服务!");
 * }</pre>
 *
 * <h2>内置布局:为什么是内联 CSS 的手写 HTML</h2>
 * <p>邮件客户端不支持外部样式表,大部分也不支持 {@code <style>} 块
 * (Gmail 会剥掉它)。唯一可靠的办法是**每个元素上写 style 属性**。
 *
 * <p>所以这个布局是一段手写的、带内联样式的 HTML 字符串。它刻意很保守:
 * <ul>
 *   <li>用 {@code <table>} 做布局 —— Outlook 的渲染引擎是 Word,
 *       它对 div + CSS 的支持极不可靠;</li>
 *   <li>不用 flex / grid / 媒体查询之外的现代 CSS;</li>
 *   <li>按钮是一个有 padding 的 {@code <a>},不是 {@code <button>} ——
 *       后者在邮件里不可点。</li>
 * </ul>
 *
 * <p>这不是 docs/11 §1.4 的那套 {@code <x-mail::message>} 组件 ——
 * 那需要 jblade 的组件支持(还没有),且支持主题定制。
 * 这里是一个够用的最小版本,而「够用」的标准是:
 * 一封通知邮件在 Gmail / Outlook / Apple Mail 里都能正常显示。
 *
 * <p>**要完全控制外观就写自己的模板**({@code Content.view(...)}) ——
 * 这个内置布局的用途是让「发一封通知」不必先建一个模板文件。
 */
public final class SimpleMailable extends Mailable {

    private String subject;
    private String greeting;
    private String salutation;
    private final List<Block> blocks = new ArrayList<>();
    private Address from;
    private String locale;

    SimpleMailable() {
    }

    public SimpleMailable subject(String text) {
        this.subject = text;
        return this;
    }

    /** 开头的称呼。默认「你好!」—— 不想要就传空串 */
    public SimpleMailable greeting(String text) {
        this.greeting = text;
        return this;
    }

    /** 结尾。默认「谢谢」 */
    public SimpleMailable salutation(String text) {
        this.salutation = text;
        return this;
    }

    public SimpleMailable line(String text) {
        blocks.add(new Block(Block.Kind.LINE, text, null));
        return this;
    }

    /**
     * 一个按钮。
     *
     * <p>**只能有一个**:两个同等显眼的按钮会让「我该点哪个」
     * 变成一个问题,而邮件里没有地方解释。第二次调用会覆盖第一个。
     *
     * <p>这个限制与 Laravel 一致,理由也相同。
     */
    public SimpleMailable action(String text, String url) {
        blocks.removeIf(b -> b.kind() == Block.Kind.ACTION);
        blocks.add(new Block(Block.Kind.ACTION, text, url));
        return this;
    }

    public SimpleMailable from(String email, String name) {
        this.from = Address.of(email, name);
        return this;
    }

    public SimpleMailable inLocale(String tag) {
        this.locale = tag;
        return this;
    }

    @Override
    public String locale() {
        return locale;
    }

    @Override
    public Envelope envelope() {
        var envelope = Envelope.of().withSubject(subject);
        if (from != null) envelope.from(from);
        return envelope;
    }

    @Override
    public Content content() {
        return Content.html(renderHtml()).withText(renderText());
    }

    // ================= 渲染 =================

    private String renderHtml() {
        var body = new StringBuilder();

        String hello = greeting == null ? "你好!" : greeting;
        if (!hello.isBlank()) {
            body.append("""
                    <p style="margin:0 0 16px;font-size:16px;line-height:1.6;color:#1f2937;">%s</p>
                    """.formatted(escape(hello)));
        }

        for (Block block : blocks) {
            if (block.kind() == Block.Kind.LINE) {
                body.append("""
                        <p style="margin:0 0 16px;font-size:15px;line-height:1.7;color:#374151;">%s</p>
                        """.formatted(escape(block.text())));
            } else {
                // 按钮用 table 包一层:Outlook 对 a 标签的 padding 支持不可靠
                body.append("""
                        <table role="presentation" cellpadding="0" cellspacing="0" border="0"
                               style="margin:24px 0;">
                          <tr><td style="border-radius:6px;background:#2563eb;">
                            <a href="%s" style="display:inline-block;padding:12px 24px;
                               font-size:15px;font-weight:600;color:#ffffff;
                               text-decoration:none;border-radius:6px;">%s</a>
                          </td></tr>
                        </table>
                        """.formatted(escapeAttribute(block.url()), escape(block.text())));
            }
        }

        String bye = salutation == null ? "谢谢" : salutation;
        if (!bye.isBlank()) {
            body.append("""
                    <p style="margin:24px 0 0;font-size:15px;line-height:1.6;color:#374151;">%s</p>
                    """.formatted(escape(bye)));
        }

        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title></head>
                <body style="margin:0;padding:0;background:#f3f4f6;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"
                       style="background:#f3f4f6;padding:32px 16px;">
                  <tr><td align="center">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"
                           style="max-width:560px;background:#ffffff;border-radius:8px;
                                  padding:32px;font-family:-apple-system,BlinkMacSystemFont,
                                  'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;">
                      <tr><td>
                %s
                      </td></tr>
                    </table>
                  </td></tr>
                </table>
                </body></html>
                """.formatted(escape(subject == null ? "" : subject), body);
    }

    /**
     * 纯文本版本 —— **手写的**,不是从 HTML 退化出来的。
     *
     * <p>这里知道结构(几行字 + 一个按钮),所以能生成一个比
     * {@code Content.plainTextFallback} 好得多的版本:
     * 按钮变成「文字:URL」而不是只剩文字 ——
     * 退化版本会把链接整个丢掉,而那正是收件人要点的东西。
     */
    private String renderText() {
        var out = new StringBuilder();

        String hello = greeting == null ? "你好!" : greeting;
        if (!hello.isBlank()) out.append(hello).append("\n\n");

        for (Block block : blocks) {
            if (block.kind() == Block.Kind.LINE) {
                out.append(block.text()).append("\n\n");
            } else {
                out.append(block.text()).append(":").append(block.url()).append("\n\n");
            }
        }

        String bye = salutation == null ? "谢谢" : salutation;
        if (!bye.isBlank()) out.append(bye).append("\n");

        return out.toString().trim();
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    /**
     * 属性值的转义。
     *
     * <p>与正文转义分开:属性在引号里,所以单引号也要转
     * (有些邮件客户端的 HTML 清洗器会把双引号改成单引号)。
     */
    private static String escapeAttribute(String value) {
        return escape(value).replace("'", "&#39;");
    }

    private record Block(Kind kind, String text, String url) {
        enum Kind { LINE, ACTION }
    }
}
