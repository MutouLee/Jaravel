package io.jaravel.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link Mailable} + 收件人 渲染成 {@link RenderedMessage}。
 *
 * <p>这是「投递时渲染」的实现点 —— 理由见 {@link Mailable} 的类注释。
 *
 * <h2>模板渲染走 SPI</h2>
 * <p>{@code jaravel-mail} 不依赖 {@code jaravel-view}。
 * {@code Content.view(...)} 的渲染通过 {@link ViewRenderer} 接口,
 * 由 starter 注入。
 *
 * <p>理由不只是解耦:它让这个模块的单元测试不需要一个模板编译器就能跑。
 * 邮件的测试要跑得多(地址处理、驱动回退、断言),
 * 为此拖进一个 jte 编译器不值得。
 *
 * <p>没装渲染器而用了 {@code Content.view(...)} 时**报错**,
 * 并说清是哪个 Mailable —— 静默发一封空白邮件是这里最坏的结果。
 */
public final class MailRenderer {

    private final ViewRenderer views;
    private final Address defaultFrom;

    public MailRenderer(ViewRenderer views, Address defaultFrom) {
        this.views = views;
        this.defaultFrom = defaultFrom;
    }

    /**
     * 渲染。
     *
     * @param recipients 收件人。**空的话报错** —— 见下
     */
    public RenderedMessage render(Mailable mailable, Recipients recipients, String locale) {
        Envelope envelope = mailable.envelope();
        Content content = mailable.content();

        requireRecipients(mailable, recipients);
        requireSubject(mailable, envelope);

        Address from = resolveFrom(mailable, envelope);
        String effectiveLocale = locale != null ? locale : mailable.locale();

        String html = renderHtml(mailable, content, effectiveLocale);
        String text = renderText(mailable, content, html, effectiveLocale);

        if (html == null && text == null) {
            throw new MailRenderException("""
                    %s 的 content() 没有任何正文。
                      给它一个:Content.view("…")、Content.html("…")
                      或 Content.plain("…")。
                      这里报错而不是发一封空白邮件 —— 后者会被收件人
                      当成垃圾邮件,而且发件方完全看不出有问题。"""
                    .formatted(mailable.getClass().getSimpleName()), null);
        }

        return new RenderedMessage(
                from,
                recipients.to(),
                recipients.cc(),
                recipients.bcc(),
                envelope.replyToAddresses(),
                envelope.subjectText(),
                html,
                text,
                renderAttachments(mailable),
                mailable.headers(),
                envelope.tagList(),
                envelope.metadataMap());
    }

    /**
     * 没有收件人时报错。
     *
     * <p>**不是静默什么都不做。** Laravel 在这种情况下静默跳过,
     * 而那会让「通知所有管理员」在管理员列表为空时完全无声 ——
     * 而「没人收到通知」是一个需要被知道的事实。
     *
     * <p>⚠ 一个真实的使用场景是「给筛选出来的用户发邮件」,
     * 而筛选结果可能合理地为空。那时**调用方应该自己判断** ——
     * {@code Mail.toAll(users)} 对空集合的处理在
     * {@link Mail#to(java.util.Collection)} 的注释里说明:
     * 它返回一个什么都不做的 PendingMail 而不是报错。
     * 这里的报错针对的是「写错了」的情形。
     */
    private void requireRecipients(Mailable mailable, Recipients recipients) {
        if (!recipients.to().isEmpty()) return;

        throw new MailRenderException("""
                %s 没有收件人。
                  用 Mail.to(...) 指定 —— 收件人不在 Mailable 的信封里,
                  因为同一个 Mailable 要能发给不同的人
                  (见 Envelope 的类注释)。
                  cc/bcc 不能代替 to:只有密送的邮件会被很多服务商
                  判成垃圾邮件。"""
                .formatted(mailable.getClass().getSimpleName()), null);
    }

    private void requireSubject(Mailable mailable, Envelope envelope) {
        if (envelope.subjectText() != null && !envelope.subjectText().isBlank()) return;

        throw new MailRenderException("""
                %s 的 envelope() 没有主题。
                  没有主题的邮件在大多数客户端里显示成「(无主题)」,
                  而且显著提高垃圾邮件评分。
                  Envelope.of().withSubject("…")"""
                .formatted(mailable.getClass().getSimpleName()), null);
    }

    /**
     * 发件人:Mailable 优先,其次配置。
     *
     * <p>两者都没有时**报错**,而不是发一封 From 为空的邮件 ——
     * 后者会被接收方的 SMTP 服务器拒掉,而拒信里的错误
     * ({@code 501 Bad address syntax})与「忘了配 mail.from.address」
     * 之间没有任何线索。
     */
    private Address resolveFrom(Mailable mailable, Envelope envelope) {
        if (envelope.fromAddress() != null) return envelope.fromAddress();
        if (defaultFrom != null) return defaultFrom;

        throw new MailRenderException("""
                %s 没有发件人,而 mail.from.address 也没配。
                  二选一:
                    · 在 config/mail.yml 里配
                        mail:
                          from:
                            address: noreply@example.com
                            name: 应用名
                    · 或者在这个 Mailable 的 envelope() 里 .from(...)
                  这里报错而不是发一封 From 为空的邮件 —— 后者会被
                  接收方拒掉,而拒信里的「501 Bad address syntax」
                  与真正的原因之间没有任何线索。"""
                .formatted(mailable.getClass().getSimpleName()), null);
    }

    private String renderHtml(Mailable mailable, Content content, String locale) {
        if (content.rawHtml() != null) return content.rawHtml();
        if (content.viewName() == null) return null;

        return requireViews(mailable, content.viewName())
                .render(content.viewName(), content.viewData(), locale);
    }

    /**
     * 纯文本版本。
     *
     * <p>优先级:显式给的文本 → 文本模板 → **从 HTML 退化生成**。
     *
     * <p>最后那一步是关键的默认行为:只发 HTML 的 multipart 邮件会
     * 加垃圾邮件评分,而且很多客户端用文本版本生成收件箱摘要 ——
     * 没有它时那行摘要会是「如果无法正常显示请点击这里」。
     * 理由完整写在 {@link Content} 的类注释里。
     */
    private String renderText(Mailable mailable, Content content, String html,
                              String locale) {
        if (content.rawText() != null) return content.rawText();

        if (content.textViewName() != null) {
            return requireViews(mailable, content.textViewName())
                    .render(content.textViewName(), content.viewData(), locale);
        }
        return Content.plainTextFallback(html);
    }

    private ViewRenderer requireViews(Mailable mailable, String viewName) {
        if (views != null) return views;

        throw new MailRenderException("""
                %s 用了模板 "%s",但邮件模块没有装模板渲染器。
                  正常情况下 starter 会装上它(它桥到 jaravel-view)。
                  单元测试里要么给 MailRenderer 一个 ViewRenderer,
                  要么改用 Content.html(...) / Mailable.simple()。
                  这里报错而不是发一封空白邮件。"""
                .formatted(mailable.getClass().getSimpleName(), viewName), null);
    }

    private List<RenderedMessage.RenderedAttachment> renderAttachments(Mailable mailable) {
        var out = new ArrayList<RenderedMessage.RenderedAttachment>();

        for (Attachment attachment : mailable.attachments()) {
            if (attachment.isPathBased()) {
                // 路径形态**不读盘** —— 只传路径,发送时才读。
                // 这让一个大文件附件不会进队列 payload
                out.add(new RenderedMessage.RenderedAttachment(
                        attachment.name(), attachment.mimeType(),
                        null, attachment.path().toString()));
            } else {
                out.add(new RenderedMessage.RenderedAttachment(
                        attachment.name(), attachment.mimeType(),
                        attachment.materialize(), null));
            }
        }
        return out;
    }

    /** 收件人三件套 */
    public record Recipients(List<Address> to, List<Address> cc, List<Address> bcc) {

        public Recipients {
            to = to == null ? List.of() : List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            bcc = bcc == null ? List.of() : List.copyOf(bcc);
        }

        public boolean isEmpty() {
            return to.isEmpty() && cc.isEmpty() && bcc.isEmpty();
        }
    }

    /** 模板渲染的 SPI。starter 把它桥到 jaravel-view */
    public interface ViewRenderer {

        /**
         * @param locale 渲染用的语言,可为 null(用应用默认)
         */
        String render(String viewName, java.util.Map<String, Object> data, String locale);
    }

    /** 渲染失败。**不可重试** —— 模板/配置不改,再试也一样 */
    public static class MailRenderException extends RuntimeException {
        public MailRenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
