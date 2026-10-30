package io.jaravel.mail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件的「信封」:谁发给谁、主题、标签(docs/11 §1.1)。
 *
 * <pre>{@code
 * Envelope.of()
 *         .from("noreply@shop.com", "小店")
 *         .replyTo("support@shop.com")
 *         .subject("订单 #" + order.number() + " 已发货")
 *         .tags("order", "shipping")
 *         .metadata("order_id", order.id());
 * }</pre>
 *
 * <h2>收件人不在这里</h2>
 * <p>{@code to} / {@code cc} / {@code bcc} 由 {@code Mail.to(...)} 指定,
 * 不在 Mailable 的信封里。
 *
 * <p>这个分工与 Laravel 一致,理由是:**同一个 Mailable 要能发给不同的人**。
 * 把收件人写进 Mailable 意味着「给 100 个用户发同一封通知」要
 * new 100 个 Mailable,而那 100 个对象里 99 个字段是一样的。
 *
 * <p>代价:读 Mailable 的代码看不出它发给谁。所以 {@code Mail.to(...)}
 * 是必须的 —— 没有收件人时发送会报错,而不是静默什么都不做。
 *
 * <h2>tags 与 metadata 是给邮件服务商的</h2>
 * <p>SES / Postmark / Mailgun 都支持给每封邮件打标签与附带元数据,
 * 用来在它们的控制台里做分组统计与排查。
 *
 * <p>{@code log} 与 {@code smtp} 驱动**不支持**它们 —— SMTP 协议里
 * 没有对应的概念。那时它们会被写进自定义头部({@code X-Mail-Tag}),
 * 而不是静默丢掉:静默丢掉会让「我在 SES 控制台里看不到标签」
 * 变成一个找不到原因的问题。
 */
public final class Envelope {

    private Address from;
    private final List<Address> replyTo = new ArrayList<>();
    private String subject;
    private final List<String> tags = new ArrayList<>();
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private Envelope() {
    }

    public static Envelope of() {
        return new Envelope();
    }

    public static Envelope subject(String subject) {
        return of().withSubject(subject);
    }

    public Envelope from(String email) {
        this.from = Address.of(email);
        return this;
    }

    public Envelope from(String email, String name) {
        this.from = Address.of(email, name);
        return this;
    }

    public Envelope from(Address address) {
        this.from = address;
        return this;
    }

    public Envelope replyTo(String email) {
        replyTo.add(Address.of(email));
        return this;
    }

    public Envelope replyTo(String email, String name) {
        replyTo.add(Address.of(email, name));
        return this;
    }

    /** 与 {@link #subject(String)} 同名的实例方法 —— 链式写法用这个 */
    public Envelope withSubject(String text) {
        this.subject = text;
        return this;
    }

    public Envelope tags(String... values) {
        tags.addAll(List.of(values));
        return this;
    }

    public Envelope metadata(String key, Object value) {
        metadata.put(key, String.valueOf(value));
        return this;
    }

    // ================= 访问器 =================

    /** 可为 null —— 那时用 {@code mail.from.address} 的配置值 */
    public Address fromAddress() {
        return from;
    }

    public List<Address> replyToAddresses() {
        return List.copyOf(replyTo);
    }

    public String subjectText() {
        return subject;
    }

    public List<String> tagList() {
        return List.copyOf(tags);
    }

    public Map<String, String> metadataMap() {
        return Map.copyOf(metadata);
    }
}
