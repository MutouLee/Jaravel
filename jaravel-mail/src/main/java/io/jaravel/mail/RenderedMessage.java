package io.jaravel.mail;

import java.util.List;
import java.util.Map;

/**
 * 一封**已经渲染好**的邮件 —— 驱动真正要发的东西。
 *
 * <h2>为什么这个类型存在</h2>
 * <p>它是 {@link Mailable}(定义)与 {@code Transport}(投递)之间的边界。
 *
 * <p>驱动只认这个类型:它拿到的是最终的 HTML、最终的收件人列表、
 * 已经读出来的附件字节。驱动**不知道** Mailable、模板、语言这些概念 ——
 * 于是写一个新驱动(比如 Resend 的 HTTP API)只需要处理一个
 * 扁平的数据结构。
 *
 * <p>更要紧的是它让**入队发送**成立:入队时放进 payload 的就是这个对象
 * (见 {@link Mailable} 类注释里关于「投递时渲染」的完整理由)。
 *
 * <h2>字段都是 public 且可 JSON 往返的</h2>
 * <p>因为它要进队列 payload。所以这里**不能**有 Supplier、
 * 不能有模型、不能有接口 —— 附件在这一层已经是
 * {@code byte[]} 或一个路径字符串。
 *
 * @param from        发件人。渲染时已经把「Mailable 没给就用配置」解决掉了
 * @param to          收件人。**非空** —— 空列表在渲染之前就被拦下
 * @param attachments 附件。{@code bytes} 非空表示内联,否则读 {@code path}
 */
public record RenderedMessage(
        Address from,
        List<Address> to,
        List<Address> cc,
        List<Address> bcc,
        List<Address> replyTo,
        String subject,
        String html,
        String text,
        List<RenderedAttachment> attachments,
        List<Mailable.Header> headers,
        List<String> tags,
        Map<String, String> metadata
) {

    public RenderedMessage {
        to = List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        bcc = bcc == null ? List.of() : List.copyOf(bcc);
        replyTo = replyTo == null ? List.of() : List.copyOf(replyTo);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        headers = headers == null ? List.of() : List.copyOf(headers);
        tags = tags == null ? List.of() : List.copyOf(tags);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 所有实际收件人(to + cc + bcc)—— 日志与断言用 */
    public List<Address> allRecipients() {
        var all = new java.util.ArrayList<Address>(to);
        all.addAll(cc);
        all.addAll(bcc);
        return List.copyOf(all);
    }

    public boolean hasHtml() {
        return html != null && !html.isBlank();
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    /**
     * 一行摘要,给 {@code log} 驱动与失败告警用。
     *
     * <p>**不含正文** —— 邮件正文常含个人信息(地址、订单明细),
     * 而日志通常比数据库更容易被读到。{@code log} 驱动想要完整内容时
     * 显式去取 {@link #html()}。
     */
    public String summary() {
        return "「%s」→ %s".formatted(
                subject,
                to.stream().map(Address::email).toList());
    }

    /**
     * 一个已渲染的附件。
     *
     * @param bytes 内联的字节;null 表示用 {@code path} 在发送时读
     * @param path  路径形态的附件。入队时只传这个,文件由 worker 读
     */
    public record RenderedAttachment(String name, String mimeType,
                                     byte[] bytes, String path) {

        public boolean isInline() {
            return bytes != null;
        }

        /** 真正要发的字节 —— 路径形态的在这里读盘 */
        public byte[] resolve() {
            if (bytes != null) return bytes;

            try {
                return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path));
            } catch (java.io.IOException e) {
                throw new Attachment.AttachmentException("""
                        发送时读不到附件 %s(路径 %s):%s
                          路径形态的附件在发送时才读盘。入队发送的话那是在
                          worker 里 —— 文件必须在那时还在
                          (同一台机器或共享存储)。"""
                        .formatted(name, path, e.getMessage()), e);
            }
        }

        /**
         * equals / hashCode 要自己写 —— record 对 {@code byte[]} 用的是
         * 引用比较,于是两个内容相同的附件不相等。
         *
         * <p>这件事在测试里会咬人:{@code assertSent} 比较
         * RenderedMessage 时会因为附件数组的引用不同而失败,
         * 而那个失败的信息是「两个看起来一样的对象不相等」。
         */
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RenderedAttachment o)) return false;
            return java.util.Objects.equals(name, o.name)
                    && java.util.Objects.equals(mimeType, o.mimeType)
                    && java.util.Objects.equals(path, o.path)
                    && java.util.Arrays.equals(bytes, o.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, mimeType, path)
                    * 31 + java.util.Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "%s (%s, %s)".formatted(name, mimeType,
                    isInline() ? bytes.length + " bytes" : path);
        }
    }
}
