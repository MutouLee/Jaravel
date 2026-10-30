package io.jaravel.mail;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.mail.events.MailEvents;
import io.jaravel.support.Arr;

/**
 * 按名字管理 mailer,并执行发送(docs/11 §1.3)。
 *
 * <pre>{@code
 * var mail = new MailManager(renderer)
 *         .register("log", m -> new LogTransport())
 *         .register("smtp", m -> new SmtpTransport(...))
 *         .defaultMailer("log");
 * }</pre>
 *
 * <h2>发送的完整顺序</h2>
 * <ol>
 *   <li>渲染({@link MailRenderer})—— 地址校验、模板、附件;</li>
 *   <li>发 {@code MessageSending} 事件。**被中止就到此为止**;</li>
 *   <li>驱动发送;</li>
 *   <li>发 {@code MessageSent} 或 {@code MessageFailed}。</li>
 * </ol>
 *
 * <p>渲染在事件**之前**:那样监听器能看到最终的收件人与主题,
 * 而「挡掉发往非白名单域名的邮件」正需要它。
 *
 * <p>代价:一封被挡掉的邮件仍然付了渲染成本。那个成本是微秒级的,
 * 而换来的是一个能真正起作用的拦截点。
 */
public final class MailManager {

    private final Map<String, Function<MailManager, Transport>> factories =
            new LinkedHashMap<>();
    private final Map<String, Transport> resolved = new LinkedHashMap<>();

    private final MailRenderer renderer;

    private String defaultMailer;

    /** 入队发送的通道。没装时 queue() 报错 */
    private QueueDispatcher queue;

    private FakeRecorder fake;

    public MailManager(MailRenderer renderer) {
        this.renderer = renderer;
    }

    // ================= 注册 =================

    public MailManager register(String name, Function<MailManager, Transport> factory) {
        factories.put(name, factory);
        resolved.remove(name);
        return this;
    }

    public MailManager register(String name, Transport transport) {
        return register(name, m -> transport);
    }

    public MailManager defaultMailer(String name) {
        requireKnown(name);
        this.defaultMailer = name;
        return this;
    }

    public MailManager queueDispatcher(QueueDispatcher dispatcher) {
        this.queue = dispatcher;
        return this;
    }

    public Transport mailer() {
        if (defaultMailer == null) {
            throw new IllegalStateException("""
                    没有设默认 mailer。
                      在 config/mail.yml 里配 mail.default,
                      或者调 MailManager.defaultMailer("log")。""");
        }
        return mailer(defaultMailer);
    }

    public Transport mailer(String name) {
        requireKnown(name);
        return resolved.computeIfAbsent(name, n -> factories.get(n).apply(this));
    }

    private void requireKnown(String name) {
        if (factories.containsKey(name)) return;

        throw new IllegalStateException("""
                没有名为 "%s" 的 mailer。%s
                  已注册:%s
                  在 config/mail.yml 的 mail.mailers 下登记它。"""
                .formatted(name,
                           Arr.closest(name, factories.keySet().stream().toList())
                              .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                           factories.keySet()));
    }

    public java.util.Set<String> registeredMailers() {
        return java.util.Set.copyOf(factories.keySet());
    }

    public String defaultMailerName() {
        return defaultMailer;
    }

    // ================= 发送 =================

    /**
     * 立即发送。
     *
     * @return 真的发出去了吗。false = 被 {@code MessageSending} 的监听器挡掉
     */
    public boolean send(Mailable mailable, MailRenderer.Recipients recipients,
                        String mailerName, String locale) {

        String target = mailerName != null ? mailerName : defaultMailer;

        if (fake != null) {
            // fake 在**渲染之前**拦下 —— 它的用途是「测试里别真的发」,
            // 而渲染要模板引擎。见 ArrayTransport 的类注释里
            // 关于 fake 与 array 驱动的分工
            fake.record(mailable, recipients, target);
            return true;
        }

        RenderedMessage message = renderer.render(mailable, recipients, locale);
        return deliver(message, mailable.getClass().getSimpleName(), target);
    }

    /**
     * 发一封**已经渲染好**的邮件 —— worker 从队列里取出来后走这条路径。
     */
    public boolean deliver(RenderedMessage message, String mailableType,
                           String mailerName) {
        String target = mailerName != null ? mailerName : defaultMailer;
        Transport transport = mailer(target);

        boolean proceed = io.jaravel.events.Event.listening(
                MailEvents.MessageSending.class)
                ? io.jaravel.events.Event.bus().dispatch(
                        new MailEvents.MessageSending(target, mailableType, message))
                : true;

        if (!proceed) {
            io.jaravel.events.Event.tryDispatchIfListening(
                    MailEvents.MessageBlocked.class,
                    () -> new MailEvents.MessageBlocked(target, mailableType, message));
            return false;
        }

        long start = System.nanoTime();
        try {
            transport.send(message);
        } catch (RuntimeException e) {
            io.jaravel.events.Event.tryDispatchIfListening(
                    MailEvents.MessageFailed.class,
                    () -> new MailEvents.MessageFailed(target, mailableType, message, e));
            throw e;
        }

        var elapsed = Duration.ofNanos(System.nanoTime() - start);
        io.jaravel.events.Event.tryDispatchIfListening(
                MailEvents.MessageSent.class,
                () -> new MailEvents.MessageSent(target, mailableType, message, elapsed));
        return true;
    }

    /**
     * 入队发送 —— **在这里渲染**,把渲染结果放进队列。
     *
     * <p>这个设计的完整理由见 {@link Mailable} 的类注释。
     *
     * @param delay 延迟多久,null = 立即可取
     */
    public void queue(Mailable mailable, MailRenderer.Recipients recipients,
                      String mailerName, String locale, Duration delay) {

        String target = mailerName != null ? mailerName : defaultMailer;

        if (fake != null) {
            fake.recordQueued(mailable, recipients, target);
            return;
        }

        if (queue == null) {
            throw new IllegalStateException("""
                    Mail.to(...).queue(...) 需要队列,但邮件模块没有队列通道。
                      原因通常是 jaravel-queue 不在 classpath 上。
                      修复:引入 jaravel-queue,或者改用 .send(...) 同步发送。
                      这里报错而不是退回同步发送 —— 一个本该异步的邮件
                      在请求线程里发,会让接口的 P99 被 SMTP 的往返时间决定,
                      而那是入队要解决的那个问题。""");
        }

        // ★ 渲染在**这里**(投递时),不在 worker 里。
        // 于是 Mailable 不需要可序列化,也不会有「模型被删了」的失败
        RenderedMessage message = renderer.render(mailable, recipients, locale);

        queue.dispatch(message, mailable.getClass().getSimpleName(), target, delay);
    }

    // ================= 测试 =================

    /** 记录发送但**不渲染、不发送** */
    public MailManager fake() {
        this.fake = new FakeRecorder();
        return this;
    }

    public MailManager stopFaking() {
        this.fake = null;
        return this;
    }

    public boolean isFaking() {
        return fake != null;
    }

    public FakeRecorder recorder() {
        if (fake == null) {
            throw new IllegalStateException("""
                    当前不在 fake 模式,没有记录可查。
                      在被测代码**之前**调 Mail.fake() ——
                      之后调的话那封邮件已经真的发出去了。""");
        }
        return fake;
    }

    /** 把渲染好的邮件投进队列。starter 注入 */
    public interface QueueDispatcher {
        void dispatch(RenderedMessage message, String mailableType,
                      String mailerName, Duration delay);
    }

    /**
     * fake 模式下的记录。
     *
     * <p>存的是 **Mailable 实例** 而不是渲染结果 —— 于是
     * {@code assertSent(OrderShipped.class, m -> m.order().id() == 42)}
     * 能直接读那个 Mailable 的字段。
     *
     * <p>这是 fake 相对 array 驱动的主要好处:断言写在**业务对象**上,
     * 而不是在渲染出来的 HTML 字符串里找子串。
     * 后者会在改一个 CSS class 之后失败。
     */
    public static final class FakeRecorder {

        public record Entry(Mailable mailable, MailRenderer.Recipients recipients,
                            String mailer, boolean queued) {
        }

        private final List<Entry> entries = new java.util.concurrent.CopyOnWriteArrayList<>();

        void record(Mailable mailable, MailRenderer.Recipients recipients, String mailer) {
            entries.add(new Entry(mailable, recipients, mailer, false));
        }

        void recordQueued(Mailable mailable, MailRenderer.Recipients recipients,
                          String mailer) {
            entries.add(new Entry(mailable, recipients, mailer, true));
        }

        public List<Entry> all() {
            return List.copyOf(entries);
        }

        public List<Entry> of(Class<? extends Mailable> type) {
            return entries.stream().filter(e -> type.isInstance(e.mailable())).toList();
        }

        public List<Class<?>> types() {
            var out = new java.util.ArrayList<Class<?>>();
            for (Entry entry : entries) {
                Class<?> type = entry.mailable().getClass();
                if (!out.contains(type)) out.add(type);
            }
            return List.copyOf(out);
        }
    }
}
