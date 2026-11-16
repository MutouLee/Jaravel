package io.jaravel.spring.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;

/**
 * 多实例广播(docs/19 §9)的真实 Redis 测试。
 *
 * <p>两个 {@link RedisWsHub} 共用一个 Redis 前缀,各自带一条假 WS 连接:
 * A 的 publish 必须同时到 A(同步本地)和 B(经 pub/sub);presence
 * 的成员表要合并两边,join/leave 要让对面广播 member_added / removed。
 *
 * <p>给了 {@code -Djaravel.test.redis.uri=redis://127.0.0.1:6379/15}
 * (或环境变量 {@code JARAVEL_TEST_REDIS_URI})才跑;结束时清掉前缀。
 */
class RedisWsHubTest {

    private static String uri;
    private static String prefix;
    private static RedisWsHub hubA;
    private static RedisWsHub hubB;

    @BeforeAll
    static void setUp() {
        uri = firstNonBlank(
                System.getProperty("jaravel.test.redis.uri"),
                System.getenv("JARAVEL_TEST_REDIS_URI"));
        Assumptions.assumeTrue(uri != null,
                "设置 -Djaravel.test.redis.uri 才跑 Redis 广播测试");

        prefix = "jaravel-ws-test:" + UUID.randomUUID() + ":";
        hubA = new RedisWsHub(uri, prefix, Duration.ofSeconds(30));
        hubB = new RedisWsHub(uri, prefix, Duration.ofSeconds(30));
    }

    @AfterAll
    static void tearDown() {
        if (hubA != null) hubA.close();
        if (hubB != null) hubB.close();
        if (uri == null || prefix == null) return;

        RedisClient client = RedisClient.create(uri);
        try (var conn = client.connect(StringCodec.UTF8)) {
            RedisCommands<String, String> commands = conn.sync();
            var keys = commands.keys(prefix + "*");
            if (!keys.isEmpty()) commands.del(keys.toArray(String[]::new));
        } finally {
            client.shutdown();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private record Client(WsConnection connection, FakeWebSocketSession session) {
    }

    private static Client connect(RedisWsHub hub, String id) {
        var session = new FakeWebSocketSession(id);
        var connection = new WsConnection(id, session, hub, 60_000, null, null);
        hub.connect(connection);
        connection.start();
        return new Client(connection, session);
    }

    private static void awaitFrame(FakeWebSocketSession session, String contains) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (session.sentFrames().stream().anyMatch(f -> f.contains(contains))) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("没有收到包含 \"" + contains + "\" 的帧,已收到:" + session.sentFrames());
    }

    private static void awaitMembers(RedisWsHub hub, String channel, int size) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Map<String, LocalWsHub.PresenceMember> members = Map.of();
        while (System.nanoTime() < deadline) {
            members = hub.membersOf(channel);
            if (members.size() == size) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("presence 成员数应为 " + size + ",实际 " + members.size());
    }

    @Test
    @DisplayName("★ A 的 publish 扇出到 A(本地)与 B(pub/sub)")
    void pubSubFanOut() {
        var a = connect(hubA, "a-event");
        var b = connect(hubB, "b-event");
        try {
            hubA.subscribe(a.connection(), "orders", null);
            hubB.subscribe(b.connection(), "orders", null);

            hubA.publish("orders", "OrderPaid", Map.of("id", 42));

            awaitFrame(a.session(), "OrderPaid");
            awaitFrame(b.session(), "OrderPaid");
            assertThat(b.session().sentFrames().toString()).contains("42");
        } finally {
            a.connection().close();
            b.connection().close();
        }
    }

    @Test
    @DisplayName("★ 跨实例 presence:合并成员表,join / leave 广播到对面")
    void presenceAcrossInstances() {
        var a = connect(hubA, "a-presence");
        var b = connect(hubB, "b-presence");
        try {
            hubA.subscribe(a.connection(), "presence-chat",
                    new LocalWsHub.PresenceMember("u1", Map.of("name", "A")));
            awaitMembers(hubA, "presence-chat", 1);

            hubB.subscribe(b.connection(), "presence-chat",
                    new LocalWsHub.PresenceMember("u2", Map.of("name", "B")));

            awaitMembers(hubA, "presence-chat", 2);
            awaitFrame(a.session(), "pusher_internal:member_added");
            awaitFrame(a.session(), "u2");

            hubB.unsubscribe(b.connection(), "presence-chat");

            awaitMembers(hubA, "presence-chat", 1);
            awaitFrame(a.session(), "pusher_internal:member_removed");
        } finally {
            a.connection().close();
            b.connection().close();
        }
    }
}
