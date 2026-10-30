package io.jaravel.mail;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * 邮件的静态门面(docs/11 §1.2)。
 *
 * <pre>{@code
 * Mail.to(user).send(new OrderShipped(order));
 * Mail.to(users).queue(new OrderShipped(order));
 * Mail.mailer("ses").to(user).send(mailable);
 * }</pre>
 */
public final class Mail {

    private static volatile MailManager manager;

    private Mail() {
    }

    public static void use(MailManager configured) {
        manager = configured;
    }

    public static MailManager manager() {
        MailManager current = manager;
        if (current == null) {
            throw new IllegalStateException("""
                    邮件尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调
                        Mail.use(new MailManager(new MailRenderer(null, Address.of("a@b.com")))
                                .register("array", new ArrayTransport())
                                .defaultMailer("array"));""");
        }
        return current;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        manager = null;
    }

    // ================= 入口 =================

    public static PendingMail to(String email) {
        return new PendingMail(manager()).to(email);
    }

    public static PendingMail to(String email, String name) {
        return new PendingMail(manager()).to(email, name);
    }

    public static PendingMail to(PendingMail.Recipient recipient) {
        return new PendingMail(manager()).to(recipient);
    }

    /**
     * 发给一组人 —— **一封邮件,多个收件人**。
     *
     * <p>⚠ 与 Laravel 的 {@code Mail::to($users)} 一样,这是**一封**邮件:
     * 所有人出现在同一个 To 头里,彼此看得到对方的地址。
     *
     * <p>那通常**不是**你想要的。给 100 个用户发通知时想要的是
     * 100 封独立的邮件 —— 否则:
     * <ul>
     *   <li>泄露了所有人的邮箱地址(GDPR 关心这件事);</li>
     *   <li>任何人点「全部回复」会发给所有人;</li>
     *   <li>个性化(称呼、locale)无从实现。</li>
     * </ul>
     *
     * <p>要一人一封就显式循环:
     * <pre>{@code
     * users.forEach(u -> Mail.to(u).queue(mailable));
     * }</pre>
     *
     * <p>不替用户自动拆成 N 封:那会让一次调用变成 N 次 SMTP 往返
     * (或 N 条队列记录),而调用方应该知道这件事。
     *
     * <p><b>空集合返回一个什么都不做的 PendingMail</b>,不报错 ——
     * 「给筛选出来的用户发邮件」而筛选结果为空是合理的。
     * 但 {@code Mail.to(someUser)} 里那个 user 的邮箱为 null 会报错,
     * 因为那是数据有问题。两者的区别见 {@link PendingMail}。
     */
    public static PendingMail to(Collection<? extends PendingMail.Recipient> recipients) {
        var pending = new PendingMail(manager());

        if (recipients == null || recipients.isEmpty()) {
            return new PendingMail(manager(), true);
        }
        recipients.forEach(pending::to);
        return pending;
    }

    public static PendingMail mailer(String name) {
        return new PendingMail(manager()).mailer(name);
    }

    // ================= 测试 =================

    /**
     * 记录发送但**不渲染、不发送**。
     *
     * <p>与 {@code array} 驱动的分工见 {@code ArrayTransport} 的类注释:
     * fake 在渲染之前拦下(快、断言写在业务对象上),
     * array 走完整渲染(真实、能断言 HTML 内容)。
     */
    public static void fake() {
        manager().fake();
    }

    public static void stopFaking() {
        manager().stopFaking();
    }

    // ================= 断言 =================

    public static void assertSent(Class<? extends Mailable> type) {
        if (countSent(type) == 0) {
            throw new AssertionError("""
                    期望发送过 %s,但它没有。
                      已发送的:%s
                      若邮件是在 Mail.fake() **之前**发的,这里看不到它。
                      若它是 .queue(...) 投的,用 assertQueued。"""
                    .formatted(type.getSimpleName(), sentTypeNames()));
        }
    }

    public static void assertSent(Class<? extends Mailable> type, int times) {
        long actual = countSent(type);
        if (actual != times) {
            throw new AssertionError("期望 %s 发送 %d 次,实际 %d 次。"
                    .formatted(type.getSimpleName(), times, actual));
        }
    }

    /**
     * 断言发过且满足条件。
     *
     * <pre>{@code
     * Mail.assertSent(OrderShipped.class, m -> m.orderId().equals(42L));
     * }</pre>
     *
     * <p>断言写在 **Mailable 对象**上而不是渲染出来的 HTML 里 ——
     * 后者会在改一个 CSS class 之后失败。
     */
    @SuppressWarnings("unchecked")
    public static <M extends Mailable> void assertSent(Class<M> type, Predicate<M> matching) {
        var matches = manager().recorder().of(type).stream()
                .filter(e -> !e.queued())
                .filter(e -> matching.test((M) e.mailable()))
                .toList();

        if (matches.isEmpty()) {
            if (countSent(type) == 0) assertSent(type);      // 更有信息量的那条消息

            throw new AssertionError("""
                    %s 发送了 %d 次,但没有一次满足给定条件。"""
                    .formatted(type.getSimpleName(), countSent(type)));
        }
    }

    /** 断言发给了某个地址 */
    public static void assertSentTo(Class<? extends Mailable> type, String email) {
        boolean found = manager().recorder().of(type).stream()
                .anyMatch(e -> e.recipients().to().stream()
                        .anyMatch(a -> a.email().equalsIgnoreCase(email)));

        if (!found) {
            throw new AssertionError("""
                    期望 %s 发给了 %s,但没有。
                      实际的收件人:%s"""
                    .formatted(type.getSimpleName(), email, recipientsOf(type)));
        }
    }

    public static void assertNotSent(Class<? extends Mailable> type) {
        long actual = countSent(type);
        if (actual > 0) {
            throw new AssertionError("期望没有发送 %s,但它发了 %d 次。"
                    .formatted(type.getSimpleName(), actual));
        }
    }

    /** 断言**入队**了(而不是同步发送) */
    public static void assertQueued(Class<? extends Mailable> type) {
        boolean found = manager().recorder().of(type).stream().anyMatch(
                MailManager.FakeRecorder.Entry::queued);

        if (!found) {
            long sentSync = countSent(type);
            throw new AssertionError("""
                    期望 %s 被入队,但它没有。%s"""
                    .formatted(type.getSimpleName(),
                               sentSync > 0
                                       ? "\n  它被**同步发送**了 %d 次 —— 用的是 .send(...) 而不是 .queue(...)。"
                                               .formatted(sentSync)
                                       : "\n  已记录的:" + sentTypeNames()));
        }
    }

    public static void assertNothingSent() {
        var types = manager().recorder().types();
        if (!types.isEmpty()) {
            throw new AssertionError("期望没有发送任何邮件,实际:" + sentTypeNames());
        }
    }

    private static long countSent(Class<? extends Mailable> type) {
        return manager().recorder().of(type).stream().filter(e -> !e.queued()).count();
    }

    private static List<String> sentTypeNames() {
        return manager().recorder().types().stream().map(Class::getSimpleName).toList();
    }

    private static List<String> recipientsOf(Class<? extends Mailable> type) {
        return manager().recorder().of(type).stream()
                .flatMap(e -> e.recipients().to().stream())
                .map(Address::email)
                .toList();
    }
}
