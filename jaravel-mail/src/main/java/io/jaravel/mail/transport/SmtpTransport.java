package io.jaravel.mail.transport;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import io.jaravel.mail.Address;
import io.jaravel.mail.RenderedMessage;
import io.jaravel.mail.Transport;

/**
 * SMTP 驱动,基于 Jakarta Mail(docs/11 §1.3)。
 *
 * <pre>{@code
 * mail:
 *   mailers:
 *     smtp:
 *       transport: smtp
 *       host: smtp.example.com
 *       port: 587
 *       encryption: tls
 *       username: ${MAIL_USERNAME}
 *       password: ${MAIL_PASSWORD}
 *       timeout: 10s
 * }</pre>
 *
 * <h2>超时必须配,而且默认值不是「无限」</h2>
 * <p>Jakarta Mail 的默认超时是**无限**。那意味着一个连不上的 SMTP
 * 服务器会让发送线程永久挂住 —— 在请求线程里就是一个永不返回的请求,
 * 在 worker 里就是一个永久占着并发额度的任务。
 *
 * <p>所以这里默认 10 秒,并且同时设 connect / read / write 三个 ——
 * 只设 connect 的话「连上了但不响应」仍然会挂住,
 * 而那恰好是服务商过载时的表现。
 *
 * <h2>encryption 的三个值</h2>
 * <ul>
 *   <li>{@code tls}(推荐)—— 587 端口,明文连接后用 STARTTLS 升级;</li>
 *   <li>{@code ssl} —— 465 端口,一上来就是 TLS;</li>
 *   <li>{@code none} —— **不加密**。只在本机或可信内网的中继上用。</li>
 * </ul>
 *
 * <p>{@code none} + 非本机 host 时**警告**:那意味着用户名密码
 * 在网络上以明文传输。不报错是因为「内网里的无认证中继」是真实存在的
 * 部署方式,但它值得一行提醒。
 */
public class SmtpTransport implements Transport {

    private final Properties properties;
    private final String username;
    private final String password;
    private final String host;

    public SmtpTransport(String host, int port, String encryption,
                         String username, String password, Duration timeout) {
        requireJakartaMail();

        this.host = host;
        this.username = username;
        this.password = password;

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("""
                    smtp 驱动没有 host。
                      在 config/mail.yml 里配:
                        mail:
                          mailers:
                            smtp:
                              host: ${MAIL_HOST}
                              port: 587""");
        }

        long millis = (timeout == null ? Duration.ofSeconds(10) : timeout).toMillis();

        var props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));

        // ★ 三个超时都要设。
        // Jakarta Mail 的默认值是**无限**,而只设 connect 的话
        // 「连上了但不响应」仍然会永久挂住 —— 那恰好是服务商过载时的表现
        props.put("mail.smtp.connectiontimeout", String.valueOf(millis));
        props.put("mail.smtp.timeout", String.valueOf(millis));
        props.put("mail.smtp.writetimeout", String.valueOf(millis));

        String mode = encryption == null ? "tls" : encryption.toLowerCase();
        switch (mode) {
            case "tls", "starttls" -> {
                props.put("mail.smtp.starttls.enable", "true");
                // required 而不是 enable:只 enable 的话服务器不支持
                // STARTTLS 时会**静默退回明文**,而那时用户以为自己是加密的
                props.put("mail.smtp.starttls.required", "true");
            }
            case "ssl", "smtps" -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.transport.protocol", "smtps");
            }
            case "none", "plain" -> warnAboutPlaintext(host);
            default -> throw new IllegalArgumentException("""
                    不认识的 smtp encryption:"%s"
                      可选:tls(推荐,587 端口)、ssl(465 端口)、none(不加密)。"""
                    .formatted(encryption));
        }

        if (username != null && !username.isBlank()) {
            props.put("mail.smtp.auth", "true");
        }
        this.properties = props;
    }

    private static void warnAboutPlaintext(String host) {
        boolean local = host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("::1");
        if (local) return;

        System.err.printf("""

                [jaravel] 警告:smtp 驱动配了 encryption: none,而 host 是 %s(不是本机)。
                          用户名与密码会在网络上**以明文传输**。
                          除非这是一个可信内网里的无认证中继,请改成 tls。
                %n""", host);
    }

    private static void requireJakartaMail() {
        try {
            Class.forName("jakarta.mail.Session");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("""
                    smtp 驱动需要 Jakarta Mail,但它不在 classpath 上。
                      jaravel-mail 对它是 optional 依赖 —— 用 ses/sendgrid/log
                      的应用不该被迫引入它。
                      在 pom 里加:
                        <dependency>
                          <groupId>org.eclipse.angus</groupId>
                          <artifactId>angus-mail</artifactId>
                          <version>2.0.3</version>
                        </dependency>""", e);
        }
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public void send(RenderedMessage message) {
        try {
            sendViaJakarta(message);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("smtp", """
                    通过 %s 发送失败:%s
                      常见原因:
                        · 认证失败 —— 检查 username/password
                          (Gmail 之类要用应用专用密码,不是账号密码);
                        · 连不上 —— 很多云服务商默认封禁 25/587 出口端口;
                        · 发件人被拒 —— SPF/DKIM 没配,或 from 的域名
                          与认证的账号不匹配。"""
                    .formatted(host, e.getMessage()), e);
        }
    }

    /**
     * 真正的发送。
     *
     * <p>单独一个方法并且**用全限定名引用 Jakarta Mail** ——
     * 不 import 的话这个类在 Jakarta Mail 缺失时也能加载,
     * 于是 {@link #requireJakartaMail()} 的那条错误消息能被看到。
     *
     * <p>import 了的话类加载就会先失败,报一个
     * {@code NoClassDefFoundError: jakarta/mail/Session} —— 那条消息
     * 里没有「加这个依赖」的指引。
     */
    private void sendViaJakarta(RenderedMessage message) throws Exception {
        jakarta.mail.Session session;
        if (properties.containsKey("mail.smtp.auth")) {
            session = jakarta.mail.Session.getInstance(properties,
                    new jakarta.mail.Authenticator() {
                        @Override
                        protected jakarta.mail.PasswordAuthentication
                                getPasswordAuthentication() {
                            return new jakarta.mail.PasswordAuthentication(username, password);
                        }
                    });
        } else {
            session = jakarta.mail.Session.getInstance(properties);
        }

        var mime = new jakarta.mail.internet.MimeMessage(session);
        mime.setFrom(toInternet(message.from()));
        mime.setRecipients(jakarta.mail.Message.RecipientType.TO, toInternet(message.to()));
        if (!message.cc().isEmpty()) {
            mime.setRecipients(jakarta.mail.Message.RecipientType.CC,
                    toInternet(message.cc()));
        }
        if (!message.bcc().isEmpty()) {
            mime.setRecipients(jakarta.mail.Message.RecipientType.BCC,
                    toInternet(message.bcc()));
        }
        if (!message.replyTo().isEmpty()) {
            mime.setReplyTo(toInternet(message.replyTo()));
        }
        mime.setSubject(message.subject(), "UTF-8");
        mime.setSentDate(new java.util.Date());

        for (var header : message.headers()) {
            mime.setHeader(header.name(), header.value());
        }
        // tags / metadata 在 SMTP 里没有对应概念 —— 写成自定义头部
        // 而不是静默丢掉。见 Envelope 的类注释
        for (String tag : message.tags()) {
            mime.addHeader("X-Mail-Tag", tag);
        }
        message.metadata().forEach((key, value) ->
                safeAddHeader(mime, "X-Mail-Metadata-" + key, value));

        mime.setContent(buildBody(message));

        jakarta.mail.Transport.send(mime);
    }

    private static void safeAddHeader(jakarta.mail.internet.MimeMessage mime,
                                      String name, String value) {
        try {
            mime.addHeader(name, value);
        } catch (jakarta.mail.MessagingException e) {
            // 一个 metadata 的 key 不合法不该让整封邮件发不出去
            System.err.printf("[jaravel] 邮件头部 %s 加不上(已跳过):%s%n",
                    name, e.getMessage());
        }
    }

    /**
     * 正文的 MIME 结构。
     *
     * <p>HTML + 文本都有时用 {@code multipart/alternative},而且
     * **文本在前、HTML 在后** —— RFC 2046 规定 alternative 的各部分
     * 按「偏好递增」排列,客户端取它能显示的最后一个。
     * 顺序反了的话支持 HTML 的客户端会显示纯文本版本。
     */
    private jakarta.mail.Multipart buildBody(RenderedMessage message) throws Exception {
        var alternative = new jakarta.mail.internet.MimeMultipart("alternative");

        if (message.hasText()) {
            var part = new jakarta.mail.internet.MimeBodyPart();
            part.setText(message.text(), "UTF-8");
            alternative.addBodyPart(part);
        }
        if (message.hasHtml()) {
            var part = new jakarta.mail.internet.MimeBodyPart();
            part.setContent(message.html(), "text/html; charset=UTF-8");
            alternative.addBodyPart(part);
        }

        if (message.attachments().isEmpty()) {
            return alternative;
        }

        // 有附件时外层是 mixed,正文那部分是上面的 alternative
        var mixed = new jakarta.mail.internet.MimeMultipart("mixed");

        var bodyPart = new jakarta.mail.internet.MimeBodyPart();
        bodyPart.setContent(alternative);
        mixed.addBodyPart(bodyPart);

        for (var attachment : message.attachments()) {
            var part = new jakarta.mail.internet.MimeBodyPart();
            part.setDataHandler(new jakarta.activation.DataHandler(
                    new jakarta.mail.util.ByteArrayDataSource(
                            attachment.resolve(), attachment.mimeType())));
            part.setFileName(attachment.name());
            mixed.addBodyPart(part);
        }
        return mixed;
    }

    private static jakarta.mail.internet.InternetAddress toInternet(Address address)
            throws Exception {
        return address.hasName()
                ? new jakarta.mail.internet.InternetAddress(address.email(),
                        address.name(), "UTF-8")
                : new jakarta.mail.internet.InternetAddress(address.email());
    }

    private static jakarta.mail.internet.InternetAddress[] toInternet(List<Address> addresses)
            throws Exception {
        var out = new jakarta.mail.internet.InternetAddress[addresses.size()];
        for (int i = 0; i < addresses.size(); i++) {
            out[i] = toInternet(addresses.get(i));
        }
        return out;
    }
}
