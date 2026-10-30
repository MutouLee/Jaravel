package io.jaravel.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.events.Event;
import io.jaravel.events.EventBus;
import io.jaravel.mail.Address;
import io.jaravel.mail.Mail;
import io.jaravel.mail.MailManager;
import io.jaravel.mail.MailRenderer;
import io.jaravel.mail.Mailable;
import io.jaravel.mail.transport.ArrayTransport;
import io.jaravel.notifications.channel.DatabaseChannel;
import io.jaravel.notifications.channel.MailChannel;
import io.jaravel.notifications.channel.SmsChannel;
import io.jaravel.notifications.events.NotificationEvents;

/** 多渠道投递、渠道独立性、跳过的可观测性、fake 断言 */
class NotificationTest {

    private java.sql.Connection jdbc;
    private io.jaravel.database.Connection db;
    private ArrayTransport mailbox;
    private NotificationManager notifications;
    private EventBus bus;

    private final List<String> smsLog = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        smsLog.clear();

        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
            st.execute("""
                    create table notifications (
                        id text primary key,
                        type text not null,
                        notifiable_type text not null,
                        notifiable_id text not null,
                        data text not null,
                        read_at text null,
                        created_at text not null)
                    """);
        }
        db = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());

        mailbox = new ArrayTransport();
        Mail.use(new MailManager(new MailRenderer(null, Address.of("noreply@example.com")))
                .register("array", mailbox)
                .defaultMailer("array"));

        notifications = new NotificationManager()
                .register(new MailChannel())
                .register(new DatabaseChannel(db))
                .register(new SmsChannel((phone, message) ->
                        smsLog.add(phone + ":" + message.content())));
        Notifications.use(notifications);

        bus = EventBus.standalone();
        Event.use(bus);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Notifications.reset();
        Mail.reset();
        Event.reset();
        if (jdbc != null) jdbc.close();
    }

    // ================= 多渠道 =================

    @Nested
    @DisplayName("多渠道投递")
    class MultiChannel {

        @Test
        @DisplayName("★ 一条通知同时走邮件与站内")
        void twoChannels() {
            var user = new User(1L, "u@example.com", "13800138000");

            assertThat(Notifications.send(user, new InvoicePaid(42L, "mail", "database")))
                    .isEqualTo(2);

            assertThat(mailbox.sent()).singleElement().satisfies(m -> {
                assertThat(m.subject()).isEqualTo("发票已支付");
                assertThat(m.to()).extracting(Address::email)
                        .containsExactly("u@example.com");
            });
            assertThat(db.table("notifications").count()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 一个渠道失败不影响其它渠道")
        void oneChannelFailureIsIsolated() {
            notifications.extend("broken", new NotificationChannel() {
                @Override public String name() { return "broken"; }

                @Override public boolean requiresRoute() { return false; }

                @Override public void send(Notifiable n, Notification notif) {
                    throw new IllegalStateException("这个渠道挂了");
                }
            });

            var user = new User(1L, "u@example.com", null);

            int sent = Notifications.send(user,
                    new InvoicePaid(42L, "broken", "mail", "database"));

            assertThat(sent)
                    .as("docs/11 §2.6:单个渠道失败不影响其他渠道")
                    .isEqualTo(2);
            assertThat(mailbox.count()).isEqualTo(1);
            assertThat(db.table("notifications").count()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 全部渠道都失败时才抛给调用方")
        void allFailuresPropagate() {
            notifications.extend("broken", new NotificationChannel() {
                @Override public String name() { return "broken"; }

                @Override public boolean requiresRoute() { return false; }

                @Override public void send(Notifiable n, Notification notif) {
                    throw new IllegalStateException("挂了");
                }
            });

            var user = new User(1L, "u@example.com", null);

            assertThatThrownBy(() -> Notifications.send(user,
                    new InvoicePaid(42L, "broken")))
                    .hasMessageContaining("挂了");
        }

        @Test
        @DisplayName("部分成功不抛 —— 调用方分不清「全挂了」与「Slack 挂了」")
        void partialSuccessDoesNotThrow() {
            notifications.extend("broken", new NotificationChannel() {
                @Override public String name() { return "broken"; }

                @Override public boolean requiresRoute() { return false; }

                @Override public void send(Notifiable n, Notification notif) {
                    throw new IllegalStateException("挂了");
                }
            });

            var user = new User(1L, "u@example.com", null);

            assertThat(Notifications.send(user, new InvoicePaid(42L, "broken", "mail")))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("via() 是运行时决定的 —— 它能看收件人的偏好")
        void viaIsDynamic() {
            var withPhone = new User(1L, "a@example.com", "13800138000");
            var withoutPhone = new User(2L, "b@example.com", null);

            Notifications.send(withPhone, new PreferenceAware());
            assertThat(smsLog).hasSize(1);

            smsLog.clear();
            Notifications.send(withoutPhone, new PreferenceAware());
            assertThat(smsLog)
                    .as("没手机号的用户 via() 里就没有 sms")
                    .isEmpty();
        }
    }

    // ================= 缺接口 =================

    @Nested
    @DisplayName("via 声明了渠道但没实现对应接口")
    class MissingInterface {

        @Test
        @DisplayName("★ 报错并说清缺哪个接口、以及为什么不用反射")
        void explainsClearly() {
            var user = new User(1L, "u@example.com", "13800138000");

            assertThatThrownBy(() -> Notifications.send(user, new ForgotToImplement()))
                    .isInstanceOf(NotificationChannel.MissingChannelMethodException.class)
                    .hasMessageContaining("没有实现 HasMail")
                    .hasMessageContaining("toMail")
                    .hasMessageContaining("拼错");
        }

        @Test
        @DisplayName("渠道名不存在时给出相近的候选")
        void unknownChannelSuggests() {
            var user = new User(1L, "u@example.com", null);

            assertThatThrownBy(() -> Notifications.send(user,
                    new InvoicePaid(42L, "mial")))
                    .hasMessageContaining("是否想写 \"mail\"");
        }
    }

    // ================= 跳过 =================

    @Nested
    @DisplayName("跳过的可观测性")
    class Skipping {

        @Test
        @DisplayName("★ 没有地址时跳过那个渠道,并发 Skipped 事件")
        void missingRouteIsSkippedAndReported() {
            var skips = new ArrayList<NotificationEvents.NotificationSkipped>();
            bus.listen(NotificationEvents.NotificationSkipped.class, skips::add);

            var user = new User(1L, "u@example.com", null);   // 没有手机号

            assertThat(Notifications.send(user, new InvoicePaid(42L, "mail", "sms")))
                    .isEqualTo(1);
            assertThat(smsLog).isEmpty();

            assertThat(skips).singleElement().satisfies(e -> {
                assertThat(e.channel()).isEqualTo("sms");
                assertThat(e.reason())
                        .as("「有邮箱但没填手机号」是正常的,所以不报错 —— "
                                + "但它与「发了但没收到」在外部看来一模一样")
                        .contains("没有地址");
            });
        }

        @Test
        @DisplayName("★ shouldSend 返回 false 时跳过,理由不同")
        void shouldSendIsHonoured() {
            var skips = new ArrayList<NotificationEvents.NotificationSkipped>();
            bus.listen(NotificationEvents.NotificationSkipped.class, skips::add);

            var user = new User(1L, "u@example.com", null);
            Notifications.send(user, new AlreadyPaid());

            assertThat(mailbox.count()).isZero();
            assertThat(skips).singleElement()
                    .satisfies(e -> assertThat(e.reason())
                            .as("两种跳过的处理方式完全不同,所以理由要分开")
                            .contains("shouldSend"));
        }

        @Test
        @DisplayName("★ database 渠道不需要地址")
        void databaseNeedsNoRoute() {
            var user = new User(1L, null, null);   // 什么地址都没有

            assertThat(Notifications.send(user, new InvoicePaid(42L, "database")))
                    .as("不覆盖 requiresRoute 的话,一个没实现"
                            + " routeNotificationFor(\"database\") 的模型"
                            + "会让站内通知静默不出现")
                    .isEqualTo(1);
            assertThat(db.table("notifications").count()).isEqualTo(1);
        }

        @Test
        @DisplayName("via 返回空列表时什么都不做,不报错")
        void emptyViaIsLegal() {
            var user = new User(1L, "u@example.com", null);

            assertThat(Notifications.send(user, new InvoicePaid(42L))).isZero();
            assertThat(mailbox.count()).isZero();
        }
    }

    // ================= database 渠道 =================

    @Nested
    @DisplayName("站内通知")
    class DatabaseNotifications {

        @Test
        @DisplayName("写进表里的字段")
        void rowShape() {
            var user = new User(7L, "u@example.com", null);
            Notifications.send(user, new InvoicePaid(42L, "database"));

            var row = db.table("notifications").first().orElseThrow();

            assertThat(row.string("type")).isEqualTo("InvoicePaid");
            assertThat(row.string("notifiable_type"))
                    .as("用表名或稳定短字符串,不用全限定名 —— "
                            + "一次包重构会让所有历史通知查不到归属")
                    .isEqualTo("users");
            assertThat(row.string("notifiable_id")).isEqualTo("7");
            assertThat(row.string("data")).contains("\"invoice_id\":42");
            assertThat(row.get("read_at")).isNull();
        }

        @Test
        @DisplayName("★ data 放了不可序列化的东西时报错,不存一个空 {}")
        void unserializableDataFails() {
            var user = new User(1L, null, null);

            assertThatThrownBy(() -> Notifications.send(user, new BadDatabaseData()))
                    .hasMessageContaining("无法序列化成 JSON")
                    .hasMessageContaining("长期保留");
        }

        @Test
        @DisplayName("★ 没实现 notifiableId 时报错并说清要什么")
        void missingIdExplains() {
            Notifiable minimal = channel -> "x@y.com";

            assertThatThrownBy(() -> Notifications.send(minimal,
                    new InvoicePaid(42L, "database")))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("notifiableId()");
        }
    }

    // ================= 按需路由 =================

    @Nested
    @DisplayName("按需路由")
    class OnDemand {

        @Test
        @DisplayName("发给一个不是模型的收件人")
        void routeToPlainAddress() {
            int sent = Notifications.route("mail", "ops@example.com")
                    .deliver(new InvoicePaid(42L, "mail"));

            assertThat(sent).isEqualTo(1);
            assertThat(mailbox.sent().get(0).to()).extracting(Address::email)
                    .containsExactly("ops@example.com");
        }

        @Test
        @DisplayName("★ 按需路由不能用 database 渠道 —— 它没有身份")
        void databaseNeedsIdentity() {
            assertThatThrownBy(() -> Notifications.route("mail", "ops@example.com")
                    .deliver(new InvoicePaid(42L, "database")))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("没有身份");
        }

        @Test
        @DisplayName("多个渠道各自一个地址")
        void multipleRoutes() {
            var target = Notifications.route("mail", "ops@example.com")
                    .route("sms", "13800138000");

            assertThat(target.channels()).containsExactly("mail", "sms");
            assertThat(target.deliver(new InvoicePaid(42L, "mail", "sms"))).isEqualTo(2);
            assertThat(smsLog).hasSize(1);
        }
    }

    // ================= slack / webhook =================

    /**
     * slack 与 webhook 渠道。
     *
     * <p>这两个渠道在换用 {@code Http} 门面**之前没有测试** ——
     * 它们自己 new 了一个 HttpClient,要测只能起一个真 HTTP 服务器,
     * 于是实际上就没测。
     *
     * <p>这是「把 HTTP 收拢到一个门面」顺带得到的东西:
     * {@code Http.fake()} 让它们变得可测。
     */
    @Nested
    @DisplayName("slack / webhook 渠道")
    class HttpChannelsTests {

        @BeforeEach
        void registerChannels() {
            notifications.register(new io.jaravel.notifications.channel
                    .HttpChannels.Slack());
            notifications.register(new io.jaravel.notifications.channel
                    .HttpChannels.Webhook());
        }

        @org.junit.jupiter.api.AfterEach
        void stopFakingHttp() {
            io.jaravel.http.client.Http.reset();
        }

        @Test
        @DisplayName("★ slack 渠道 POST 了正确的 payload")
        void slackPostsPayload() {
            io.jaravel.http.client.Http.fake(Map.of(
                    "https://hooks.slack.com/*",
                    io.jaravel.http.client.Http.response(Map.of("ok", true))));

            var target = Notifications.route("slack",
                    "https://hooks.slack.com/services/XXX");
            assertThat(target.deliver(new SlackAlert())).isEqualTo(1);

            io.jaravel.http.client.Http.assertSent(r ->
                    r.method().equals("POST")
                            && r.bodyContains("发票已支付")
                            && r.bodyContains("good"));
        }

        @Test
        @DisplayName("★ slack 返回 4xx 时失败,并把响应体放进错误")
        void slackFailureIncludesBody() {
            // body 是 String 时原样发,所以这是一个 404 + 纯文本响应体
            io.jaravel.http.client.Http.fake(Map.of("*",
                    io.jaravel.http.client.Http.response("channel_not_found", 404)));

            assertThatThrownBy(() -> Notifications
                    .route("slack", "https://hooks.slack.com/x")
                    .deliver(new SlackAlert()))
                    .hasMessageContaining("返回了 404")
                    .hasMessageContaining("channel_not_found");
        }

        @Test
        @DisplayName("★ 5xx 会重试(门面带来的,第一版完全没有)")
        void serverErrorsAreRetried() {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            io.jaravel.http.client.Http.use((request, connectTimeout) -> {
                int n = calls.incrementAndGet();
                return n < 3
                        ? io.jaravel.http.client.Http.status(503)
                        : io.jaravel.http.client.Http.response(Map.of("ok", true));
            });

            Notifications.route("slack", "https://hooks.slack.com/x")
                    .deliver(new SlackAlert());

            assertThat(calls.get())
                    .as("Slack 的限流与瞬时 5xx 很常见,"
                            + "而第一版手写的 HttpClient 完全没有重试")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("连接失败时报错并提示常见原因")
        void connectionFailureExplains() {
            io.jaravel.http.client.Http.fake(Map.of("*",
                    io.jaravel.http.client.Http.failedConnection()));

            assertThatThrownBy(() -> Notifications
                    .route("slack", "https://hooks.slack.com/x")
                    .deliver(new SlackAlert()))
                    .hasMessageContaining("出口网络被封");
        }

        @Test
        @DisplayName("★ webhook 渠道带上自定义头部(签名走这里)")
        void webhookSendsHeaders() {
            io.jaravel.http.client.Http.fake(Map.of("*",
                    io.jaravel.http.client.Http.response(Map.of())));

            Notifications.route("webhook", "https://hooks.example.com/x")
                    .deliver(new WebhookAlert());

            io.jaravel.http.client.Http.assertSent(r ->
                    r.hasHeader("X-Signature", "abc123")
                            && r.bodyContains("invoice.paid"));
        }

        @Test
        @DisplayName("webhook 支持 PUT")
        void webhookSupportsPut() {
            io.jaravel.http.client.Http.fake(Map.of("*",
                    io.jaravel.http.client.Http.response(Map.of())));

            Notifications.route("webhook", "https://hooks.example.com/x")
                    .deliver(new PuttingWebhook());

            io.jaravel.http.client.Http.assertSent(r -> r.method().equals("PUT"));
        }
    }

    static class SlackAlert extends Notification implements io.jaravel.notifications.HasSlack {
        @Override public List<String> via(Notifiable n) { return List.of("slack"); }

        @Override
        public SlackMessage toSlack(Notifiable n) {
            return SlackMessage.of("发票已支付")
                    .attachment(a -> a.title("#42").color("good"));
        }
    }

    static class WebhookAlert extends Notification
            implements io.jaravel.notifications.HasWebhook {
        @Override public List<String> via(Notifiable n) { return List.of("webhook"); }

        @Override
        public WebhookMessage toWebhook(Notifiable n) {
            return WebhookMessage.post(Map.of("event", "invoice.paid", "id", 42))
                    .header("X-Signature", "abc123");
        }
    }

    static class PuttingWebhook extends Notification
            implements io.jaravel.notifications.HasWebhook {
        @Override public List<String> via(Notifiable n) { return List.of("webhook"); }

        @Override
        public WebhookMessage toWebhook(Notifiable n) {
            return WebhookMessage.put(Map.of("x", 1));
        }
    }

    // ================= 入队 =================

    @Nested
    @DisplayName("入队投递")
    class Queueing {

        @Test
        @DisplayName("★ 每个渠道一条队列任务 —— docs/11 §2.6")
        void onePerChannel() {
            var dispatched = new ArrayList<String>();
            notifications.queueDispatcher((n, notif, channel, queue, delay) ->
                    dispatched.add(channel));

            var user = new User(1L, "u@example.com", "13800138000");
            Notifications.send(user, new QueuedNotification());

            assertThat(dispatched)
                    .as("放一个任务里的话,第一个渠道的失败会让后两个永远不执行,"
                            + "而重试时又从第一个开始 —— 于是邮件发三遍而短信一次都没发")
                    .containsExactly("mail", "database", "sms");
            assertThat(mailbox.count()).as("入队了就不该同步发").isZero();
        }

        @Test
        @DisplayName("queue 名字与 delay 一路传下去")
        void queueAndDelayArePassed() {
            var queues = new ArrayList<String>();
            var delays = new ArrayList<Duration>();
            notifications.queueDispatcher((n, notif, channel, queue, delay) -> {
                queues.add(queue);
                delays.add(delay);
            });

            Notifications.send(new User(1L, "u@example.com", null),
                    new QueuedNotification());

            assertThat(queues).containsOnly("notifications");
            assertThat(delays).containsOnly(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("sendNow 跳过队列")
        void sendNowBypassesQueue() {
            var dispatched = new ArrayList<String>();
            notifications.queueDispatcher((n, notif, channel, queue, delay) ->
                    dispatched.add(channel));

            Notifications.sendNow(new User(1L, "u@example.com", null),
                    new QueuedNotification());

            assertThat(dispatched).isEmpty();
            assertThat(mailbox.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 没有队列通道时退化为同步并警告")
        void noQueueFallsBackToSync() {
            Notifications.send(new User(1L, "u@example.com", null),
                    new QueuedNotification());

            assertThat(mailbox.count())
                    .as("退化而不是报错 —— 通知发不出去比「慢了一点」严重;"
                            + "但警告必须响")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("worker 侧 deliverOne 只发一个渠道")
        void workerPathDeliversOneChannel() {
            var user = new User(1L, "u@example.com", null);

            assertThat(notifications.deliverOne(user, new QueuedNotification(), "mail"))
                    .isTrue();
            assertThat(mailbox.count()).isEqualTo(1);
            assertThat(db.table("notifications").count())
                    .as("只发 mail —— database 那条是另一个任务")
                    .isZero();
        }
    }

    // ================= 事件 =================

    @Nested
    @DisplayName("通知事件")
    class Events {

        @Test
        @DisplayName("Sending / Sent 真的发出来")
        void lifecycle() {
            var received = new ArrayList<Object>();
            bus.listen(NotificationEvents.NotificationSending.class, received::add);
            bus.listen(NotificationEvents.NotificationSent.class, received::add);

            Notifications.send(new User(1L, "u@example.com", null),
                    new InvoicePaid(42L, "mail"));

            assertThat(received).hasSize(2);
        }

        @Test
        @DisplayName("★ Sending 返回 false 挡掉这一个渠道")
        void sendingCanBlockOneChannel() {
            // 「预发环境只准发站内,不准外发」
            bus.listenAbortable(NotificationEvents.NotificationSending.class, 0,
                    e -> e.channel().equals("database"));

            var user = new User(1L, "u@example.com", null);
            assertThat(Notifications.send(user, new InvoicePaid(42L, "mail", "database")))
                    .isEqualTo(1);

            assertThat(mailbox.count()).isZero();
            assertThat(db.table("notifications").count()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 某个渠道失败时发 Failed,其它渠道的 Sent 照发")
        void failedAndSentCoexist() {
            var failed = new ArrayList<NotificationEvents.NotificationFailed>();
            var sent = new ArrayList<NotificationEvents.NotificationSent>();
            bus.listen(NotificationEvents.NotificationFailed.class, failed::add);
            bus.listen(NotificationEvents.NotificationSent.class, sent::add);

            notifications.extend("broken", new NotificationChannel() {
                @Override public String name() { return "broken"; }

                @Override public boolean requiresRoute() { return false; }

                @Override public void send(Notifiable n, Notification notif) {
                    throw new IllegalStateException("挂了");
                }
            });

            Notifications.send(new User(1L, "u@example.com", null),
                    new InvoicePaid(42L, "broken", "mail"));

            assertThat(failed).hasSize(1);
            assertThat(sent)
                    .as("一条通知可能同时产生一个 Failed 与一个 Sent")
                    .hasSize(1);
        }
    }

    // ================= fake =================

    @Nested
    @DisplayName("Notifications.fake()")
    class Faking {

        @BeforeEach
        void startFaking() {
            Notifications.fake();
        }

        @Test
        @DisplayName("★ fake 后不投递,但断言看得到")
        void recordsWithoutSending() {
            var user = new User(1L, "u@example.com", null);
            Notifications.send(user, new InvoicePaid(42L, "mail"));

            assertThat(mailbox.count()).isZero();
            Notifications.assertSentTo(user, InvoicePaid.class);
        }

        @Test
        @DisplayName("★ 断言「走了哪个渠道」—— via 是运行时决定的,它值得被测")
        void assertChannel() {
            var withPhone = new User(1L, "a@example.com", "13800138000");
            Notifications.send(withPhone, new PreferenceAware());

            Notifications.assertSentOnChannel(withPhone, PreferenceAware.class, "sms");

            var withoutPhone = new User(2L, "b@example.com", null);
            Notifications.send(withoutPhone, new PreferenceAware());

            assertThatThrownBy(() -> Notifications.assertSentOnChannel(
                    withoutPhone, PreferenceAware.class, "sms"))
                    .hasMessageContaining("实际的渠道是");
        }

        @Test
        @DisplayName("断言带条件")
        void assertWithPredicate() {
            var user = new User(1L, "u@example.com", null);
            Notifications.send(user, new InvoicePaid(99L, "mail"));

            Notifications.assertSentTo(user, InvoicePaid.class,
                    (InvoicePaid n) -> n.invoiceId() == 99L);

            assertThatThrownBy(() -> Notifications.assertSentTo(user,
                    InvoicePaid.class, (InvoicePaid n) -> n.invoiceId() == 1L))
                    .hasMessageContaining("没有一条满足给定条件");
        }

        @Test
        @DisplayName("★ 按身份匹配收件人,不用 equals")
        void matchesByIdentity() {
            Notifications.send(new User(1L, "a@example.com", null),
                    new InvoicePaid(42L, "mail"));

            // 同一个 id,但是另一个实例、而且邮箱改过了
            Notifications.assertSentTo(new User(1L, "changed@example.com", null),
                    InvoicePaid.class);
        }

        @Test
        @DisplayName("assertNotSentTo / assertCount / assertNothingSent")
        void otherAssertions() {
            var a = new User(1L, "a@example.com", null);
            var b = new User(2L, "b@example.com", null);

            Notifications.assertNothingSent();

            Notifications.send(a, new InvoicePaid(42L, "mail"));

            Notifications.assertCount(InvoicePaid.class, 1);
            Notifications.assertNotSentTo(b, InvoicePaid.class);

            assertThatThrownBy(Notifications::assertNothingSent)
                    .isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> Notifications.assertNotSentTo(a, InvoicePaid.class))
                    .hasMessageContaining("但它收到了");
        }

        @Test
        @DisplayName("失败消息提醒「可能 fake 调晚了」")
        void messageHintsAboutLateFake() {
            assertThatThrownBy(() -> Notifications.assertSentTo(
                    new User(1L, "a@example.com", null), InvoicePaid.class))
                    .hasMessageContaining("之前**发的");
        }
    }

    @Test
    @DisplayName("不在 fake 模式时查记录会报错")
    void assertingWithoutFakeFails() {
        assertThatThrownBy(() -> Notifications.assertNothingSent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在 fake 模式");
    }

    @Test
    @DisplayName("★ sms 没装发送器时报错 —— 验证码收不到必须在开发期暴露")
    void smsWithoutSenderFails() {
        var bare = new NotificationManager().register(new SmsChannel(null));
        Notifications.use(bare);

        assertThatThrownBy(() -> Notifications.send(
                new User(1L, null, "13800138000"), new InvoicePaid(42L, "sms")))
                .hasMessageContaining("不内置任何短信服务商")
                .hasMessageContaining("放弃注册");
    }

    // ================= 测试用类型 =================

    record User(Long id, String email, String phone) implements Notifiable {

        @Override
        public String routeNotificationFor(String channel) {
            return switch (channel) {
                case "mail" -> email;
                case "sms" -> phone;
                default -> null;
            };
        }

        @Override public Object notifiableId()   { return id; }

        @Override public String notifiableType() { return "users"; }
    }

    /** 渠道由构造参数决定 —— 方便每个测试指定 */
    static class InvoicePaid extends Notification
            implements HasMail, HasDatabase, HasSms {

        private final long invoiceId;
        private final List<String> channels;

        InvoicePaid(long invoiceId, String... channels) {
            this.invoiceId = invoiceId;
            this.channels = List.of(channels);
        }

        long invoiceId() {
            return invoiceId;
        }

        @Override public List<String> via(Notifiable n) { return channels; }

        @Override
        public Mailable toMail(Notifiable n) {
            return Mailable.simple().subject("发票已支付")
                    .line("你的发票 #" + invoiceId + " 已支付。");
        }

        @Override
        public Map<String, Object> toDatabase(Notifiable n) {
            return Map.of("invoice_id", invoiceId);
        }

        @Override
        public SmsMessage toSms(Notifiable n) {
            return SmsMessage.of("发票 #" + invoiceId + " 已支付");
        }
    }

    /** via() 看收件人有没有手机号 */
    static class PreferenceAware extends Notification implements HasMail, HasSms {

        @Override
        public List<String> via(Notifiable n) {
            var out = new ArrayList<>(List.of("mail"));
            if (n.routeNotificationFor("sms") != null) out.add("sms");
            return out;
        }

        @Override
        public Mailable toMail(Notifiable n) {
            return Mailable.simple().subject("s").line("x");
        }

        @Override
        public SmsMessage toSms(Notifiable n) {
            return SmsMessage.of("x");
        }
    }

    /** 声明了 mail 但忘了 implements HasMail */
    static class ForgotToImplement extends Notification {
        @Override public List<String> via(Notifiable n) { return List.of("mail"); }
    }

    /** shouldSend 返回 false */
    static class AlreadyPaid extends Notification implements HasMail {
        @Override public List<String> via(Notifiable n) { return List.of("mail"); }

        @Override
        public boolean shouldSend(Notifiable n, String channel) {
            return false;
        }

        @Override
        public Mailable toMail(Notifiable n) {
            return Mailable.simple().subject("s").line("x");
        }
    }

    /** toDatabase 返回一个不可序列化的东西 */
    static class BadDatabaseData extends Notification implements HasDatabase {
        @Override public List<String> via(Notifiable n) { return List.of("database"); }

        @Override
        public Map<String, Object> toDatabase(Notifiable n) {
            // 一个没有任何属性、也没有序列化器的对象
            return Map.of("broken", new Object());
        }
    }

    static class QueuedNotification extends Notification
            implements HasMail, HasDatabase, HasSms {

        @Override public boolean shouldQueue() { return true; }

        @Override public String queue() { return "notifications"; }

        @Override public Duration delay() { return Duration.ofMinutes(5); }

        @Override
        public List<String> via(Notifiable n) {
            return List.of("mail", "database", "sms");
        }

        @Override
        public Mailable toMail(Notifiable n) {
            return Mailable.simple().subject("s").line("x");
        }

        @Override
        public Map<String, Object> toDatabase(Notifiable n) {
            return Map.of("x", 1);
        }

        @Override
        public SmsMessage toSms(Notifiable n) {
            return SmsMessage.of("x");
        }
    }

    // ================= 工具 =================

    private static DataSource nonClosing(java.sql.Connection shared) {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                java.sql.Connection.class.getClassLoader(),
                                new Class<?>[] {java.sql.Connection.class},
                                (p2, m2, a2) -> m2.getName().equals("close")
                                        ? null
                                        : m2.invoke(shared, a2));
                    }
                    if (method.getName().equals("unwrap")) return shared;
                    return null;
                });
    }
}
