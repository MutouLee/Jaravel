package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import net.dobeen.jaravel.broadcasting.Broadcast;
import net.dobeen.jaravel.broadcasting.ShouldBroadcast;
import net.dobeen.jaravel.events.Event;

/**
 * ★ 广播的端到端验收:真 Tomcat + 真 WebSocket 端点 + JDK 的 WS 客户端。
 *
 * <p>单测证明了协议层与握手解析各自正确;这里要证明的是**接线**——
 * {@code config/broadcasting.yml} → 自动装配 → {@code /app/{key}} 端点 →
 * 枢纽 → 客户端收到的帧。v0.3/v0.4 的教训:类与类之间的接线
 * 只能在真实运行时里验证。
 *
 * <p>客户端用 JDK 自带的 {@code java.net.http.WebSocket} ——
 * 不引入新依赖,协议本来就是为了「任何 WS 客户端都能写」。
 */
@SpringBootTest(
        classes = app.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:sqlite:file:broadcaste2e?mode=memory&cache=shared",
                "app.env=local"
        })
@TestPropertySource(properties = "spring.threads.virtual.enabled=true")
class BroadcastE2eTest {

    @LocalServerPort
    int port;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record OrderPaid(Long orderId) implements ShouldBroadcast {
        @Override public List<String> broadcastOn() {
            return List.of("orders");
        }
    }

    /** 一个记录所有收到的帧、可等待指定事件的 WS 客户端 */
    static final class ProbeClient {

        final List<String> frames = new CopyOnWriteArrayList<>();
        final WebSocket socket;

        ProbeClient(int port, String appKey) throws Exception {
            var listener = new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket,
                                                 CharSequence data, boolean last) {
                    webSocket.request(1);
                    frames.add(data.toString());
                    return null;
                }
            };

            socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/app/" + appKey),
                            listener)
                    .get(5, TimeUnit.SECONDS);
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        void subscribe(String channel) {
            send("{\"event\":\"pusher:subscribe\",\"data\":{\"channel\":\""
                    + channel + "\"}}");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> awaitFrame(String event) throws Exception {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                for (String frame : frames) {
                    Map<String, Object> parsed = MAPPER.readValue(frame, Map.class);
                    if (event.equals(parsed.get("event"))) {
                        return parsed;
                    }
                }
                Thread.sleep(20);
            }
            throw new AssertionError("5 秒内没有收到事件 " + event + ",收到的帧:" + frames);
        }

        void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Nested
    @DisplayName("公开频道")
    class PublicChannel {

        @Test
        @DisplayName("连接 → 订阅 → Broadcast.emit → 客户端收到帧")
        void emitReachesClient() throws Exception {
            var client = new ProbeClient(port, "jaravel");
            try {
                var established = client.awaitFrame("pusher:connection_established");
                assertThat((String) established.get("data"))
                        .contains("socket_id").contains("activity_timeout");

                client.subscribe("orders");
                var subscribed = client.awaitFrame("pusher_internal:subscription_succeeded");
                assertThat(subscribed.get("channel")).isEqualTo("orders");

                Broadcast.emit("orders", "refresh", Map.of("at", "now"));

                var frame = client.awaitFrame("refresh");
                assertThat(frame.get("channel")).isEqualTo("orders");
                assertThat((String) frame.get("data")).contains("\"at\":\"now\"");
            } finally {
                client.close();
            }
        }

        @Test
        @DisplayName("ShouldBroadcast 事件经 Event.dispatch 到达客户端")
        void eventBroadcastReachesClient() throws Exception {
            var client = new ProbeClient(port, "jaravel");
            try {
                client.subscribe("orders");
                client.awaitFrame("pusher_internal:subscription_succeeded");

                Event.dispatch(new OrderPaid(7L));

                var frame = client.awaitFrame("OrderPaid");
                assertThat(frame.get("channel")).isEqualTo("orders");
                assertThat((String) frame.get("data")).contains("\"orderId\":7");
            } finally {
                client.close();
            }
        }
    }

    @Nested
    @DisplayName("私有频道")
    class PrivateChannel {

        @Test
        @DisplayName("guest 订阅私有频道 → subscription_error")
        void guestDeniedForPrivate() throws Exception {
            var client = new ProbeClient(port, "jaravel");
            try {
                client.subscribe("private-customers.1");

                var error = client.awaitFrame("pusher_internal:subscription_error");
                assertThat(error.get("channel")).isEqualTo("private-customers.1");
            } finally {
                client.close();
            }
        }

        @Test
        @DisplayName("未注册的私有频道 → subscription_error")
        void unregisteredPrivateDenied() throws Exception {
            var client = new ProbeClient(port, "jaravel");
            try {
                client.subscribe("private-nobody.1");

                client.awaitFrame("pusher_internal:subscription_error");
            } finally {
                client.close();
            }
        }
    }

    @Nested
    @DisplayName("端点")
    class Endpoint {

        @Test
        @DisplayName("appKey 不匹配 → 握手被拒")
        void wrongAppKeyRejected() throws Exception {
            assertThat(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    new ProbeClient(port, "wrong-key");
                    return false;
                } catch (Exception e) {
                    return true;   // 握手失败 = 期望行为
                }
            }).get(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
