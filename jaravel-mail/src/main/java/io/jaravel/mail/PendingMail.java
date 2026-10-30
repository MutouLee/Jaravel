package io.jaravel.mail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一次待发送的配置(docs/11 §1.2)。
 *
 * <pre>{@code
 * Mail.to(user).send(new OrderShipped(order));
 * Mail.to("a@b.com").cc(manager).bcc(audit).send(mailable);
 * Mail.to(user).locale("en").send(mailable);
 * Mail.mailer("ses").to(user).send(mailable);
 * Mail.to(users).queue(new OrderShipped(order));
 * Mail.to(user).later(Duration.ofHours(1), mailable);
 * }</pre>
 *
 * <h2>to / cc / bcc 内部去重,跨字段不去重</h2>
 * <p>{@code to(a).to(a)} → 一个 a。{@code to(a).cc(a)} → **两份**。
 *
 * <p>字段内去重是因为重复几乎总是 bug(同一个列表里有重复项),
 * 而去掉它没有任何副作用。
 *
 * <p>跨字段**不**去重是因为那个重复可能是刻意的:
 * 「发给客户,同时抄送给同一个人的工作邮箱」—— 而我们没法判断
 * 两个地址是不是同一个人。更要紧的是:去重会**改变邮件头**,
 * 于是收件人看到的 Cc 列表与代码里写的不一样,
 * 而那个差异在排查「为什么他没在抄送里」时非常费时间。
 */
public final class PendingMail {

    private final MailManager mail;

    // LinkedHashSet:去重 + 保持顺序。顺序会出现在邮件头里,
    // 而一个随机顺序的 Cc 列表看起来很不专业
    private final LinkedHashSet<Address> to = new LinkedHashSet<>();
    private final LinkedHashSet<Address> cc = new LinkedHashSet<>();
    private final LinkedHashSet<Address> bcc = new LinkedHashSet<>();

    private String mailerName;
    private String locale;

    /**
     * 空操作标记。
     *
     * <p>{@code Mail.to(emptyList())} 返回一个这样的实例:
     * 不报错(筛选结果为空是合理的),但也不进渲染 ——
     * 渲染会因为「没有收件人」而报错,而那条报错对这个场景是误导。
     *
     * <p>做成一个标记而不是一个子类:PendingMail 是 final 的,
     * 而把它改成可继承只为了一个空实现不值得 ——
     * 可继承意味着用户也能继承它,然后那些覆写会与链式方法
     * 的返回类型打架。
     */
    private final boolean noop;

    PendingMail(MailManager mail) {
        this(mail, false);
    }

    PendingMail(MailManager mail, boolean noop) {
        this.mail = mail;
        this.noop = noop;
    }

    // ================= 收件人 =================

    public PendingMail to(String email) {
        to.add(Address.of(email));
        return this;
    }

    public PendingMail to(String email, String name) {
        to.add(Address.of(email, name));
        return this;
    }

    public PendingMail to(Address address) {
        to.add(address);
        return this;
    }

    /**
     * 发给一个「可收邮件的东西」。
     *
     * <p>{@link Recipient} 是一个单方法接口,所以用户模型只要
     * 实现它(或提供一个 lambda)就能直接传进来 ——
     * 而不必在每个调用点写 {@code user.email()}。
     *
     * <p>**地址为空时报错**而不是静默跳过:一个
     * {@code Mail.to(user)} 里 user 的邮箱是 null,
     * 意味着那条数据有问题,而静默不发会让「他没收到邮件」
     * 变成一个查不到原因的工单。
     */
    public PendingMail to(Recipient recipient) {
        to.add(addressOf(recipient, "to"));
        return this;
    }

    public PendingMail cc(String email) {
        cc.add(Address.of(email));
        return this;
    }

    public PendingMail cc(Recipient recipient) {
        cc.add(addressOf(recipient, "cc"));
        return this;
    }

    public PendingMail bcc(String email) {
        bcc.add(Address.of(email));
        return this;
    }

    public PendingMail bcc(Recipient recipient) {
        bcc.add(addressOf(recipient, "bcc"));
        return this;
    }

    private static Address addressOf(Recipient recipient, String field) {
        String email = recipient.emailAddress();
        if (email == null || email.isBlank()) {
            throw new Address.InvalidAddressException("""
                    %s 的 emailAddress() 返回了空值,无法作为 %s 收件人。
                      静默跳过会让「他没收到邮件」变成一个查不到原因的工单 ——
                      所以这里报错。
                      如果「没有邮箱的用户」是正常情况,在调用前过滤掉他们。"""
                    .formatted(recipient.getClass().getSimpleName(), field));
        }
        return Address.of(email, recipient.recipientName());
    }

    // ================= 配置 =================

    /**
     * 用哪个 mailer。null = 默认。
     *
     * <p>**名字在这里就校验**,不等到发送时。
     *
     * <p>理由:拼错 mailer 名字是一个纯粹的笔误,而报错应该出现在
     * 写下那个名字的地方。等到发送时报的话,调用栈会在
     * {@code MailManager.deliver} 里,而且**渲染已经白做了一遍**。
     *
     * <p>这个顺序问题是写测试时发现的:一个 {@code Mail.mailer("aray")}
     * 的测试拿到的是「没有收件人」的报错 —— 因为渲染在 mailer 查找之前。
     */
    public PendingMail mailer(String name) {
        if (name != null) mail.mailer(name);        // 不存在就在这里抛
        this.mailerName = name;
        return this;
    }

    /**
     * 按收件人的语言渲染。
     *
     * <p>⚠ 这个 locale 作用于**整封邮件** —— 而一封邮件可能有多个收件人。
     * 一次 {@code Mail.to(users).locale(...)} 里所有人收到的是同一个语言。
     *
     * <p>要「每人按自己的语言」就得一人一封:
     * {@code users.forEach(u -> Mail.to(u).locale(u.locale()).send(mailable))}。
     * 这件事不能自动做 —— 自动做意味着一次调用变成 N 次 SMTP 往返,
     * 而调用方应该知道这件事。
     */
    public PendingMail locale(String tag) {
        this.locale = tag;
        return this;
    }

    // ================= 发送 =================

    /**
     * 立即发送。
     *
     * @return 真的发出去了吗。false = 被 {@code MessageSending} 监听器挡掉
     */
    public boolean send(Mailable mailable) {
        if (noop) return false;
        return mail.send(mailable, recipients(), mailerName, locale);
    }

    /** 入队发送(推荐)—— 在这里渲染,见 {@link Mailable} 的类注释 */
    public void queue(Mailable mailable) {
        if (noop) return;
        mail.queue(mailable, recipients(), mailerName, locale, null);
    }

    /** 延迟入队 */
    public void later(Duration delay, Mailable mailable) {
        if (noop) return;
        mail.queue(mailable, recipients(), mailerName, locale, delay);
    }

    /** 这是一个「没有收件人」的空操作吗 */
    public boolean isNoop() {
        return noop;
    }

    private MailRenderer.Recipients recipients() {
        return new MailRenderer.Recipients(
                new ArrayList<>(to), new ArrayList<>(cc), new ArrayList<>(bcc));
    }

    public List<Address> toAddresses() {
        return List.copyOf(to);
    }

    /** 能收邮件的东西 —— 用户模型实现它 */
    public interface Recipient {

        String emailAddress();

        /** 显示名。默认没有 */
        default String recipientName() {
            return null;
        }
    }
}
