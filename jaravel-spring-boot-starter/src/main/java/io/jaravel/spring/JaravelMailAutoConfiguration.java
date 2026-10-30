package io.jaravel.spring;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.mail.Address;
import io.jaravel.mail.Mail;
import io.jaravel.mail.MailManager;
import io.jaravel.mail.MailRenderer;
import io.jaravel.mail.RenderedMessage;
import io.jaravel.mail.Transport;
import io.jaravel.mail.transport.ArrayTransport;
import io.jaravel.mail.transport.FailoverTransport;
import io.jaravel.mail.transport.LogTransport;
import io.jaravel.mail.transport.RoundRobinTransport;
import io.jaravel.mail.transport.SmtpTransport;
import io.jaravel.view.ViewEngine;

/**
 * 邮件的自动装配(docs/11 §1.3 的 config/mail.yml)。
 *
 * <pre>{@code
 * mail:
 *   default: ${MAIL_MAILER:log}
 *   from:
 *     address: ${MAIL_FROM:noreply@example.com}
 *     name: ${MAIL_FROM_NAME:Jaravel}
 *   mailers:
 *     smtp:
 *       transport: smtp
 *       host: ${MAIL_HOST}
 *       port: ${MAIL_PORT:587}
 *       encryption: tls
 *     failover:
 *       transport: failover
 *       mailers: [ ses, smtp ]
 * }</pre>
 *
 * <h2>默认驱动是 log</h2>
 * <p>一个没配 {@code MAIL_MAILER} 的应用**不会真的发邮件**,
 * 而是把它写日志。
 *
 * <p>这是对的默认值:开发期不该往真实地址发邮件
 * (给自己发几封是无害的,但用一份生产数据的副本跑测试时
 * 就会给真实用户发邮件 —— 那是一类真实事故)。
 *
 * <p>代价是生产环境忘配 {@code MAIL_MAILER} 时邮件静默不发,
 * 症状是「用户说没收到」而日志里一切正常。所以
 * {@link #warnIfNotDelivering} 在生产环境检测到一个不真发的驱动时
 * **大声警告**。
 */
@AutoConfiguration(after = {JaravelAutoConfiguration.class,
        JaravelQueueAutoConfiguration.class, JaravelEventsAutoConfiguration.class})
public class JaravelMailAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MailRenderer jaravelMailRenderer(ConfigRepository config,
                                            ObjectProvider<ViewEngine> views) {
        Address from = defaultFrom(config);

        ViewEngine engine = views.getIfAvailable();
        MailRenderer.ViewRenderer renderer = engine == null ? null
                : bridgeToViews(engine);

        return new MailRenderer(renderer, from);
    }

    /**
     * 把邮件的渲染 SPI 桥到 jaravel-view。
     *
     * <p>⚠ **locale 目前被忽略**。
     *
     * <p>docs/11 §1.2 里有 {@code Mail.to(user).locale("en")},
     * 而要让它真的生效,视图层需要一个「按语言找模板」的机制
     * ({@code mail/orders/shipped.en.jblade} 或一套翻译文件)——
     * 那属于本地化,而 Jaravel 还没有这个模块。
     *
     * <p>所以这里把 locale 一路传到这个桥上然后**丢掉**,
     * 并在用了它的时候警告一次 —— 静默忽略会让
     * 「我设了 locale 但邮件还是中文」变成一个找不到原因的问题。
     */
    private MailRenderer.ViewRenderer bridgeToViews(ViewEngine engine) {
        return (viewName, data, locale) -> {
            if (locale != null) {
                warnLocaleUnsupported(locale, viewName);
            }
            return engine.render(viewName, data);
        };
    }

    /** 只警告一次 —— 每封邮件都警告的话日志会被淹掉 */
    private static volatile boolean localeWarned;

    private static void warnLocaleUnsupported(String locale, String viewName) {
        if (localeWarned) return;
        localeWarned = true;

        System.err.printf("""

                [jaravel] 警告:邮件指定了 locale="%s"(模板 %s),但**本地化还没实现**。
                          模板会按默认语言渲染。
                          要按语言渲染,现在的办法是自己在 Mailable 里按语言
                          选不同的模板名:
                            Content.view("mail.shipped." + locale)
                          本地化模块列入后续版本。
                          (这条警告只打一次)
                %n""", locale, viewName);
    }

    private Address defaultFrom(ConfigRepository config) {
        String address = config.string("mail.from.address", null);
        if (address == null || address.isBlank()) return null;

        String name = config.string("mail.from.name", null);
        return name == null || name.isBlank()
                ? Address.of(address)
                : Address.of(address, name);
    }

    @Bean
    @ConditionalOnMissingBean
    public MailManager jaravelMailManager(
            ConfigRepository config, MailRenderer renderer,
            JaravelAutoConfiguration.JaravelStaticWiring wiring) {

        var manager = new MailManager(renderer);

        var mailers = config.section("mail.mailers");
        if (mailers.isEmpty()) {
            // 没配就给一套能用的 —— 「不写 config/mail.yml 也能跑」
            manager.register("log", m -> new LogTransport());
            manager.register("array", m -> new ArrayTransport());
        } else {
            mailers.keySet().forEach(name -> registerMailer(manager, name, config));

            // log 没在配置里出现时也注册它 —— 它是开发期的退路,
            // 而「把 MAIL_MAILER 改成 log 看看」是一个常用的排查手段
            if (!manager.registeredMailers().contains("log")) {
                manager.register("log", m -> new LogTransport());
            }
        }

        String defaultMailer = config.string("mail.default", "log");
        if (!manager.registeredMailers().contains(defaultMailer)) {
            throw new IllegalStateException("""
                    mail.default 是 "%s",但没有这个 mailer。
                      已注册:%s
                      在 config/mail.yml 的 mail.mailers 下登记它。"""
                    .formatted(defaultMailer, manager.registeredMailers()));
        }
        manager.defaultMailer(defaultMailer);

        wireQueue(manager);
        warnIfNotDelivering(manager, config);

        Mail.use(manager);
        return manager;
    }

    private void registerMailer(MailManager manager, String name, ConfigRepository config) {
        String path = "mail.mailers." + name;
        String kind = config.string(path + ".transport", "log");

        switch (kind) {
            case "log" -> manager.register(name, m -> new LogTransport(
                    System.err::println,
                    config.bool(path + ".body", io.jaravel.core.Application.isDebug())));

            case "array" -> manager.register(name, m -> new ArrayTransport());

            // 懒注册:只有真正用到时才要求配置完整 ——
            // 否则一个「配了但没用」的 smtp 会让启动失败
            case "smtp" -> manager.register(name, m -> new SmtpTransport(
                    config.string(path + ".host", null),
                    config.integer(path + ".port", 587),
                    config.string(path + ".encryption", "tls"),
                    config.string(path + ".username", null),
                    config.string(path + ".password", null),
                    config.duration(path + ".timeout", Duration.ofSeconds(10))));

            case "failover" -> manager.register(name, m -> new FailoverTransport(
                    config.stringList(path + ".mailers"), m::mailer));

            case "roundrobin" -> manager.register(name, m -> new RoundRobinTransport(
                    config.stringList(path + ".mailers"), m::mailer));

            default -> throw new IllegalStateException("""
                    不认识的邮件 transport:"%s"(mailer "%s")
                      v0.4 已实现:log、array、smtp、failover、roundrobin。
                      docs/11 §1.3 里的 ses / sendgrid / mailgun / postmark / resend
                      尚未实现 —— 它们各需要一个 HTTP 客户端与各自的 API 适配,
                      列入后续版本。
                      现在可以用 smtp:这几家都提供 SMTP 中继。"""
                    .formatted(kind, name));
        }
    }

    /**
     * 入队发送的通道。
     *
     * <p>用反射判断 {@code jaravel-queue} 在不在 classpath 上 ——
     * starter 对它是可选依赖。没有时不装,于是
     * {@code Mail.to(...).queue(...)} 报一条说清后果的错。
     */
    private void wireQueue(MailManager manager) {
        if (!classPresent("io.jaravel.queue.Queue")) return;

        manager.queueDispatcher((message, mailableType, mailerName, delay) -> {
            var job = new SendRenderedMail(message, mailableType, mailerName);
            if (delay == null) {
                io.jaravel.queue.Queue.dispatch(job);
            } else {
                io.jaravel.queue.Queue.job(job).delay(delay).dispatch();
            }
        });
    }

    /**
     * 生产环境用了不真发的驱动时警告。
     *
     * <p>这是 {@code mail.default} 默认 log 的代价:忘配
     * {@code MAIL_MAILER} 时邮件静默不发,而症状是
     * 「用户说没收到」而发件方的日志里一切正常。
     *
     * <p>警告而不是报错:**预发环境刻意用 log 驱动是常见做法**
     * (「先别真发邮件」),而那时 {@code app.env} 往往已经是
     * production。报错会让那个合理的用法不可行。
     */
    private void warnIfNotDelivering(MailManager manager, ConfigRepository config) {
        if (io.jaravel.core.Application.isDebug()) return;

        Transport transport;
        try {
            transport = manager.mailer();
        } catch (RuntimeException e) {
            return;             // 懒注册的驱动配置不全 —— 留给真正用到时报错
        }
        if (transport.actuallyDelivers()) return;

        System.err.printf("""

                [jaravel] ⚠ 生产环境的默认 mailer 是 "%s"(%s),它**不会真的发邮件**。
                          邮件会被写日志/存内存,而用户不会收到任何东西 ——
                          症状是「用户说没收到」而这边的日志里一切正常。
                          配 MAIL_MAILER 环境变量指向一个真实的驱动。
                          (如果这是刻意的 —— 比如预发环境「先别真发」—— 忽略这条。)
                %n""", manager.defaultMailerName(), transport.name());
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false,
                    JaravelMailAutoConfiguration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 发一封**已经渲染好**的邮件 —— 入队发送的 worker 侧。
     *
     * <p>字段是 public 非 final 的,因为要被 Jackson 反序列化。
     *
     * <p>⚠ 这个任务的 payload 里是完整的 HTML(以及内联附件的字节)。
     * 大附件的问题见 {@code Attachment} 的类注释。
     */
    public static class SendRenderedMail implements io.jaravel.queue.ShouldQueue {

        public RenderedMessage message;
        public String mailableType;
        public String mailerName;

        /** Jackson 需要 */
        public SendRenderedMail() {
        }

        public SendRenderedMail(RenderedMessage message, String mailableType,
                                String mailerName) {
            this.message = message;
            this.mailableType = mailableType;
            this.mailerName = mailerName;
        }

        @Override
        public String queue() {
            // 邮件单独一个队列:它比大多数任务慢(SMTP 往返),
            // 混在 default 里会让一批邮件把别的任务挤在后面
            return "mail";
        }

        @Override
        public int tries() {
            // 邮件值得重试:SMTP 的失败大多是瞬时的(连接重置、限流)。
            // 3 次 + 递增 backoff 是个常规选择
            return 3;
        }

        @Override
        public String displayName() {
            return "SendMail(%s → %s)".formatted(
                    mailableType,
                    message == null ? "?" : message.to().stream()
                            .map(Address::email).toList());
        }

        public void handle(MailManager mail) {
            mail.deliver(message, mailableType, mailerName);
        }
    }
}
