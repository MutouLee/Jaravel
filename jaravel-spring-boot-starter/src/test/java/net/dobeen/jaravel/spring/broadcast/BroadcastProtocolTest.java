package net.dobeen.jaravel.spring.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;

import net.dobeen.jaravel.broadcasting.ChannelRegistry;
import net.dobeen.jaravel.session.SessionData;

/**
 * Pusher 协议子集的行为(docs/19 §5.2)。
 *
 * <p>用 {@link FakeWebSocketSession} 驱动真实 handler + hub,
 * 验证的是协议应答与状态流转 —— 不验证 Spring 的端点注册
 * (那部分是声明式的,由 e2e 测试覆盖)。
 */
class BroadcastProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record User(Long id, String name) {
    }

    private LocalWsHub hub;
    private ChannelRegistry registry;
    private JaravelWebSocketHandler handler;

    private FakeWebSocketSession connect(String sessionId) {
        return connect(sessionId, new User(42L, "张三"));
    }

    private FakeWebSocketSession connect(String sessionId, User user) {
        var session = new FakeWebSocketSession(sessionId);
        session.getAttributes().put(JaravelWebSocketHandler.USER_ATTRIBUTE, user);
        session.getAttributes().put(JaravelWebSocketHandler.SESSION_ATTRIBUTE,
                new SessionData("sess-" + sessionId, Map.of("login_web", user.id())));
        handler.afterConnectionEstablished(session);
        return session;
    }

    private FakeWebSocketSession connectGuest(String sessionId) {
        var session = new FakeWebSocketSession(sessionId);
        handler.afterConnectionEstablished(session);
        return session;
    }

    private void send(FakeWebSocketSession session, String json) {
        handler.handleTextMessage(session, new TextMessage(json));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> frame(FakeWebSocketSession session, String event) {
        waitUntil(5000, () -> assertThat(
                session.sentFrames().stream()
                        .map(f -> {
                            try {
                                return (Map<String, Object>) MAPPER.readValue(f, Map.class);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .anyMatch(f -> event.equals(f.get("event")))).isTrue());
        return session.sentFrames().stream()
                .map(f -> {
                    try {
                        return (Map<String, Object>) MAPPER.readValue(f, Map.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(f -> event.equals(f.get("event")))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private void subscribe(FakeWebSocketSession session, String channel) {
        send(session, "{\"event\":\"pusher:subscribe\",\"data\":{\"channel\":\"" + channel + "\"}}");
    }

    /**
     * 轮询直到条件成立(连接线程异步处理消息,断言前必须等)。
     *
     * <p>不用 awaitility:为测试引入一个新依赖不值得,20ms 轮询
     * 对这类「等一帧到达」的场景完全够用。
     */
    private static void waitUntil(long timeoutMillis, Runnable condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                condition.run();
                return;
            } catch (AssertionError e) {
                last = e;
                sleepMillis(20);
            }
        }
        throw last != null ? last : new AssertionError("等待超时(" + timeoutMillis + "ms)");
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @BeforeEach
    void setUp() {
        hub = new LocalWsHub();
        registry = new ChannelRegistry();
        handler = new JaravelWebSocketHandler(hub, registry, 120);
    }

    @Nested
    @DisplayName("连接")
    class Connection {

        @Test
        @DisplayName("连接建立后先收到 connection_established")
        void connectionEstablished() {
            var session = connect("c1");

            var frame = frame(session, "pusher:connection_established");

            assertThat((String) frame.get("data")).contains("\"socket_id\"")
                    .contains("\"activity_timeout\":120");
        }

        @Test
        @DisplayName("ping → pong")
        void pingPong() {
            var session = connect("c1");

            send(session, "{\"event\":\"pusher:ping\",\"data\":{}}");

            frame(session, "pusher:pong");
        }

        @Test
        @DisplayName("坏帧 → 关闭连接")
        void badFrameCloses() {
            var session = connect("c1");

            send(session, "not json at all");

            waitUntil(5000, () ->
                    assertThat(session.isOpen()).isFalse());
        }
    }

    @Nested
    @DisplayName("订阅")
    class Subscribe {

        @Test
        @DisplayName("公开频道:任何人(含 guest)直接成功")
        void publicChannelSucceeds() {
            var session = connectGuest("g1");

            subscribe(session, "orders");

            var frame = frame(session, "pusher_internal:subscription_succeeded");
            assertThat(frame.get("channel")).isEqualTo("orders");
            assertThat(frame.get("data")).isEqualTo("{}");
        }

        @Test
        @DisplayName("私有频道:未注册 → subscription_error")
        void unregisteredPrivateDenied() {
            var session = connect("c1");

            subscribe(session, "private-orders.42");

            var frame = frame(session, "pusher_internal:subscription_error");
            assertThat(frame.get("channel")).isEqualTo("private-orders.42");
        }

        @Test
        @DisplayName("私有频道:注册且通过 → 成功;拒绝 → AuthError")
        void privateChannelAuthorized() {
            registry.channel("private-orders.{orderId}",
                    (User u, Map<String, String> p) -> u.id().equals(Long.parseLong(p.get("orderId"))));

            var allowed = connect("c1");
            subscribe(allowed, "private-orders.42");
            assertThat(frame(allowed, "pusher_internal:subscription_succeeded").get("channel"))
                    .isEqualTo("private-orders.42");

            var denied = connect("c2", new User(7L, "李四"));
            subscribe(denied, "private-orders.42");
            var error = frame(denied, "pusher_internal:subscription_error");
            assertThat((String) error.get("data")).contains("AuthError");
        }

        @Test
        @DisplayName("私有频道:guest 直接拒绝,回调不跑")
        void guestDeniedForPrivate() {
            registry.channel("private-orders.{orderId}",
                    (User u, Map<String, String> p) -> {
                        throw new AssertionError("guest 不该走到回调");
                    });

            var guest = connectGuest("g1");
            subscribe(guest, "private-orders.42");

            frame(guest, "pusher_internal:subscription_error");
        }

        @Test
        @DisplayName("重复订阅幂等:再发一次 succeeded")
        void duplicateSubscribeIdempotent() {
            var session = connect("c1");

            subscribe(session, "orders");
            subscribe(session, "orders");

            waitUntil(5000, () -> assertThat(
                    session.sentFrames().stream()
                            .filter(f -> f.contains("subscription_succeeded"))
                            .count()).isEqualTo(2));
        }

        @Test
        @DisplayName("退订后收不到广播")
        void unsubscribeStopsDelivery() {
            var session = connect("c1");
            subscribe(session, "orders");
            frame(session, "pusher_internal:subscription_succeeded");

            send(session, "{\"event\":\"pusher:unsubscribe\",\"data\":{\"channel\":\"orders\"}}");
            waitUntil(5000, () ->
                    assertThat(hub.isSubscribed(connectionOf(session), "orders")).isFalse());

            int before = session.sentFrames().size();
            hub.publish("orders", "OrderPaid", Map.of("orderId", 1));
            // 没有新帧(给连接线程一点时间处理,然后数帧数)
            sleepMillis(200);
            waitUntil(2000, () ->
                    assertThat(session.sentFrames().size()).isEqualTo(before));
        }

        private WsConnection connectionOf(FakeWebSocketSession session) {
            return (WsConnection) session.getAttributes().get("jaravel.ws.connection");
        }
    }

    @Nested
    @DisplayName("广播")
    class Publish {

        @Test
        @DisplayName("订阅者收到应用事件帧:event / channel / data(JSON 字符串)")
        void subscribersReceiveEvent() {
            var session = connect("c1");
            subscribe(session, "orders");
            frame(session, "pusher_internal:subscription_succeeded");

            hub.publish("orders", "OrderPaid", Map.of("orderId", 7));

            var frame = frame(session, "OrderPaid");
            assertThat(frame.get("channel")).isEqualTo("orders");
            assertThat((String) frame.get("data")).contains("\"orderId\":7");
        }

        @Test
        @DisplayName("没订阅的频道收不到")
        void otherChannelNotDelivered() {
            var session = connect("c1");
            subscribe(session, "orders");
            frame(session, "pusher_internal:subscription_succeeded");

            int before = session.sentFrames().size();
            hub.publish("chat", "Message", Map.of("text", "hi"));

            sleepMillis(200);
            waitUntil(2000, () ->
                    assertThat(session.sentFrames().size()).isEqualTo(before));
        }

        @Test
        @DisplayName("鉴权回调的作用域里会话可用(连接作用域绑定)")
        void callbacksRunInConnectionScope() {
            registry.channel("private-orders.{orderId}", (User u, Map<String, String> p) ->
                    net.dobeen.jaravel.session.Session.tryCurrent()
                            .map(s -> s.get("login_web").orElse(null))
                            .map(id -> id.equals(42L))
                            .orElse(false));

            var session = connect("c1");
            subscribe(session, "private-orders.42");

            assertThat(frame(session, "pusher_internal:subscription_succeeded").get("channel"))
                    .isEqualTo("private-orders.42");
        }
    }

    @Nested
    @DisplayName("presence")
    class Presence {

        @Test
        @DisplayName("第二个成员加入:先到者收到 member_added,后来者拿到 count=2")
        void memberJoinedNotifiesExisting() {
            registry.presence("presence-chat.{room}",
                    (User u, Map<String, String> p) -> true,
                    (User u) -> Map.of("name", u.name()));

            var first = connect("c1");
            subscribe(first, "presence-chat.lobby");
            var firstSucceeded = frame(first, "pusher_internal:subscription_succeeded");
            assertThat((String) firstSucceeded.get("data")).contains("\"count\":1");

            var second = connect("c2", new User(43L, "李四"));
            subscribe(second, "presence-chat.lobby");
            var secondSucceeded = frame(second, "pusher_internal:subscription_succeeded");
            assertThat((String) secondSucceeded.get("data")).contains("\"count\":2")
                    .contains("张三").contains("李四");

            var added = frame(first, "pusher_internal:member_added");
            assertThat((String) added.get("data")).contains("user_id")
                    .contains("李四");
        }

        @Test
        @DisplayName("断开:其他成员收到 member_removed")
        void disconnectNotifiesMembers() {
            registry.presence("presence-chat.{room}",
                    (User u, Map<String, String> p) -> true,
                    (User u) -> Map.of("name", u.name()));

            var first = connect("c1");
            subscribe(first, "presence-chat.lobby");
            frame(first, "pusher_internal:subscription_succeeded");

            var second = connect("c2");
            subscribe(second, "presence-chat.lobby");
            frame(first, "pusher_internal:member_added");

            second.close();
            handler.afterConnectionClosed(second, null);

            var removed = frame(first, "pusher_internal:member_removed");
            assertThat((String) removed.get("data")).contains("user_id");
        }
    }

    @Nested
    @DisplayName("客户端事件")
    class ClientEvents {

        @Test
        @DisplayName("whisper 频道:转发给其他成员,不回声给发送者")
        void whisperRelayedToOthers() {
            registry.channel("private-chat.{room}", (User u, Map<String, String> p) -> true)
                    .whisper("private-chat.{room}");

            var a = connect("c1");
            subscribe(a, "private-chat.1");
            frame(a, "pusher_internal:subscription_succeeded");

            var b = connect("c2");
            subscribe(b, "private-chat.1");
            frame(b, "pusher_internal:subscription_succeeded");

            send(a, "{\"event\":\"client-typing\",\"channel\":\"private-chat.1\",\"data\":{\"user\":\"a\"}}");

            waitUntil(5000, () -> assertThat(
                    b.sentFrames().stream().anyMatch(f -> f.contains("client-typing"))).isTrue());
            assertThat(a.sentFrames().stream().anyMatch(f -> f.contains("client-typing"))).isFalse();
        }

        @Test
        @DisplayName("未开 whisper 的频道:客户端事件被丢弃")
        void clientEventDroppedWithoutWhisper() {
            registry.channel("private-chat.{room}", (User u, Map<String, String> p) -> true);

            var a = connect("c1");
            subscribe(a, "private-chat.1");
            frame(a, "pusher_internal:subscription_succeeded");

            var b = connect("c2");
            subscribe(b, "private-chat.1");
            frame(b, "pusher_internal:subscription_succeeded");

            int before = b.sentFrames().size();
            send(a, "{\"event\":\"client-typing\",\"channel\":\"private-chat.1\",\"data\":{}}");

            sleepMillis(200);
            waitUntil(2000, () ->
                    assertThat(b.sentFrames().size()).isEqualTo(before));
        }

        @Test
        @DisplayName("没订阅的频道不能借 whisper 广播")
        void clientEventRequiresSubscription() {
            registry.channel("private-chat.{room}", (User u, Map<String, String> p) -> true)
                    .whisper("private-chat.{room}");

            var a = connect("c1");
            subscribe(a, "private-chat.1");
            frame(a, "pusher_internal:subscription_succeeded");

            var b = connect("c2");
            subscribe(b, "private-chat.1");
            frame(b, "pusher_internal:subscription_succeeded");

            // a 退订后再发 client 事件 → 不会被转发
            send(a, "{\"event\":\"pusher:unsubscribe\",\"data\":{\"channel\":\"private-chat.1\"}}");
            waitUntil(5000, () ->
                    assertThat(hub.isSubscribed(connectionOf(a), "private-chat.1")).isFalse());

            int before = b.sentFrames().size();
            send(a, "{\"event\":\"client-typing\",\"channel\":\"private-chat.1\",\"data\":{}}");

            sleepMillis(200);
            waitUntil(2000, () ->
                    assertThat(b.sentFrames().size()).isEqualTo(before));
        }

        private WsConnection connectionOf(FakeWebSocketSession session) {
            return (WsConnection) session.getAttributes().get("jaravel.ws.connection");
        }
    }
}
