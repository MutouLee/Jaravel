package net.dobeen.jaravel.spring.broadcast;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.dobeen.jaravel.broadcasting.PayloadJson;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/**
 * 多实例广播的枢纽(docs/19 §9):publish 走 Redis pub/sub 扇出,
 * presence 成员表放进 Redis。
 *
 * <h2>为什么继承 {@link LocalWsHub}</h2>
 * <p>协议层、连接队列、频道登记全在本地枢纽里;**多实例只多两件事**:
 * publish 再发一份到 Redis、presence 的读写换成 Redis。继承让
 * 协议层完全不用知道是单机还是集群 —— 它仍调 {@code membersOf /
 * broadcast / subscribe},行为一致。
 *
 * <p>本地投递是**同步**的(先 {@code super.publish},再发 Redis),
 * 因为同一实例的订阅者不该等一次网络往返;远端实例从 pub/sub
 * 收到后再本地投递。消息里带 {@code source},本实例忽略自己发的那条,
 * 否则本地会收到两遍。
 *
 * <h2>presence:每个实例一个 hash + TTL</h2>
 * <p>键:{@code {prefix}presence:{channel}:{instanceId}},字段是
 * 连接 id,值是成员 JSON。连接的**任何活动**(ping / 订阅 / 事件)
 * 都会续期这个 hash({@link #touch});实例崩溃后,它的 presence
 * 会在 TTL 后自然消失。TTL 必须大于客户端的 activity timeout,
 * 默认 5 分钟。
 *
 * <p>成员进出还会发一条 pub/sub 消息,让其它实例广播
 * {@code pusher_internal:member_added / member_removed} ——
 * 本地那条由协议层自己发(排除新连接),两边不会重复。
 *
 * <p><b>尽力而为</b>:Redis 断开时本地投递照常,publish 只打一次警告。
 * 广播不是事务的一部分。
 */
public class RedisWsHub extends LocalWsHub implements DistributedHub, AutoCloseable {

    public static final String DEFAULT_PREFIX = "jaravel:broadcast:";

    private final String instanceId = UUID.randomUUID().toString();
    private final String prefix;
    private final Duration presenceTtl;
    private final String pubsubChannel;
    private final PayloadJson json = new PayloadJson();

    /** 连接 id → 它参与 presence 的频道(续期与退订用) */
    private final ConcurrentHashMap<String, Set<String>> presenceChannels =
            new ConcurrentHashMap<>();

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;
    private StatefulRedisPubSubConnection<String, String> pubsub;

    private volatile boolean warned;

    public RedisWsHub(String uri, String prefix, Duration presenceTtl) {
        this.prefix = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix;
        this.presenceTtl = presenceTtl == null || presenceTtl.isZero() || presenceTtl.isNegative()
                ? Duration.ofMinutes(5)
                : presenceTtl;
        this.pubsubChannel = this.prefix + "messages";

        try {
            client = RedisClient.create(uri);
            connection = client.connect(StringCodec.UTF8);
            commands = connection.sync();

            pubsub = client.connectPubSub(StringCodec.UTF8);
            pubsub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    onMessage(message);
                }
            });
            pubsub.sync().subscribe(pubsubChannel);
        } catch (RuntimeException e) {
            close();
            throw new IllegalStateException("""
                    连不上 Redis 广播(检查 broadcast.redis.uri)。
                      多实例广播需要所有实例连同一个 Redis;单机可以用
                      broadcast.driver: ws。
                      %s""".formatted(e.getMessage()), e);
        }
    }

    @Override
    public void publish(String channel, String event, Map<String, Object> data) {
        // 本实例先同步投递 —— 同一台机器上的订阅者不该等一次 Redis 往返
        super.publish(channel, event, data);

        publishEnvelope(Map.of(
                "source", instanceId,
                "kind", "event",
                "channel", channel,
                "event", event,
                "data", data == null ? Map.of() : data));
    }

    /** 收到别的实例发来的消息。本实例自己发的直接忽略(已经投递过) */
    @SuppressWarnings("unchecked")
    private void onMessage(String message) {
        try {
            Map<String, Object> envelope = json.mapper().readValue(message, Map.class);
            if (instanceId.equals(String.valueOf(envelope.get("source")))) return;

            String kind = String.valueOf(envelope.get("kind"));
            String channel = String.valueOf(envelope.get("channel"));

            switch (kind) {
                case "event" -> super.publish(channel,
                        String.valueOf(envelope.get("event")),
                        asMap(envelope.get("data")));
                case "presence_join" -> super.broadcast(channel,
                        memberAddedFrame(channel, member(asMap(envelope.get("member")))),
                        null);
                case "presence_leave" -> super.broadcast(channel,
                        LocalWsHub.memberRemovedFrame(channel,
                                String.valueOf(envelope.get("userId"))),
                        null);
                default -> {
                    // 未知帧:向前兼容,静默忽略
                }
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            redisFailure("处理 pub/sub 消息失败", e);
        }
    }

    private void publishEnvelope(Map<String, Object> envelope) {
        try {
            commands.publish(pubsubChannel, json.encode(envelope));
        } catch (RuntimeException e) {
            redisFailure("发布广播失败", e);
        }
    }

    // ================= 订阅 / presence =================

    @Override
    void subscribe(WsConnection connection, String channel, PresenceMember member) {
        super.subscribe(connection, channel, member);
        if (member == null) return;

        presenceChannels.computeIfAbsent(connection.id(),
                id -> ConcurrentHashMap.newKeySet()).add(channel);

        try {
            String key = presenceKey(channel);
            commands.hset(key, connection.id(), encodeMember(member));
            commands.pexpire(key, presenceTtl.toMillis());
        } catch (RuntimeException e) {
            redisFailure("写入 presence 失败", e);
        }

        publishEnvelope(Map.of(
                "source", instanceId,
                "kind", "presence_join",
                "channel", channel,
                "member", Map.of(
                        "userId", member.userId(),
                        "userInfo", member.userInfo() == null ? Map.of() : member.userInfo())));
    }

    @Override
    void unsubscribe(WsConnection connection, String channel) {
        // 成员信息要在 super 删掉本地表之前取
        PresenceMember member = super.membersOf(channel).get(connection.id());

        super.unsubscribe(connection, channel);

        Set<String> channels = presenceChannels.get(connection.id());
        if (channels != null) {
            channels.remove(channel);
            if (channels.isEmpty()) presenceChannels.remove(connection.id());
        }

        if (member == null) return;

        try {
            String key = presenceKey(channel);
            commands.hdel(key, connection.id());
            if (commands.hlen(key) == 0) commands.del(key);
        } catch (RuntimeException e) {
            redisFailure("删除 presence 失败", e);
        }

        publishEnvelope(Map.of(
                "source", instanceId,
                "kind", "presence_leave",
                "channel", channel,
                "userId", member.userId()));
    }

    @Override
    void touch(WsConnection connection) {
        Set<String> channels = presenceChannels.get(connection.id());
        if (channels == null) return;

        try {
            for (String channel : channels) {
                commands.pexpire(presenceKey(channel), presenceTtl.toMillis());
            }
        } catch (RuntimeException e) {
            redisFailure("presence 续期失败", e);
        }
    }

    /**
     * 本实例 + 其它实例的 presence 成员。
     *
     * <p>每个实例一个 hash,所以这里要 SCAN。频道内成员数通常很小;
     * 真到需要优化的规模,应该按频道分片,而不是让这个方法变复杂。
     */
    @Override
    Map<String, PresenceMember> membersOf(String channel) {
        try {
            var out = new LinkedHashMap<String, PresenceMember>();
            var args = ScanArgs.Builder.matches(presencePattern(channel)).limit(100);

            var cursor = commands.scan(args);
            collectMembers(cursor.getKeys(), out);
            while (!cursor.isFinished()) {
                cursor = commands.scan(cursor, args);
                collectMembers(cursor.getKeys(), out);
            }
            return out.isEmpty() ? Map.of() : Map.copyOf(out);
        } catch (RuntimeException e) {
            redisFailure("读取 presence 失败", e);
            return super.membersOf(channel);   // 退回本地,至少不空
        }
    }

    private void collectMembers(Iterable<String> keys, Map<String, PresenceMember> out) {
        for (String key : keys) {
            for (Map.Entry<String, String> entry : commands.hgetall(key).entrySet()) {
                try {
                    out.put(entry.getKey(), decodeMember(entry.getValue()));
                } catch (RuntimeException ignored) {
                    // 坏成员数据 = 跳过,不让一个人阻断整个列表
                }
            }
        }
    }

    private String presenceKey(String channel) {
        return prefix + "presence:" + channel + ":" + instanceId;
    }

    private String presencePattern(String channel) {
        return prefix + "presence:" + channel + ":*";
    }

    private String encodeMember(PresenceMember member) {
        return json.encode(Map.of(
                "userId", member.userId(),
                "userInfo", member.userInfo() == null ? Map.of() : member.userInfo()));
    }

    @SuppressWarnings("unchecked")
    private PresenceMember decodeMember(String jsonText) {
        try {
            Map<String, Object> map = json.mapper().readValue(jsonText, Map.class);
            return new PresenceMember(String.valueOf(map.get("userId")),
                    asMap(map.get("userInfo")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
    }

    private static PresenceMember member(Map<String, Object> map) {
        return new PresenceMember(String.valueOf(map.get("userId")),
                asMap(map.get("userInfo")));
    }

    private void redisFailure(String what, Exception e) {
        if (warned) return;
        warned = true;
        System.err.printf("""
                [jaravel] %s(只警告一次)。
                  %s
                  本地广播仍然工作;多实例扇出与 presence 在 Redis 恢复前不完整。%n""",
                what, e.getMessage());
    }

    // ================= 关闭 =================

    @Override
    public void close() {
        try {
            if (pubsub != null) {
                pubsub.sync().unsubscribe(pubsubChannel);
                pubsub.close();
            }
        } catch (RuntimeException ignored) {
            // 关闭路径不抛
        }
        try {
            if (connection != null) connection.close();
        } catch (RuntimeException ignored) {
            // 同上
        }
        try {
            if (client != null) client.shutdown();
        } catch (RuntimeException ignored) {
            // 同上
        }
        pubsub = null;
        connection = null;
        commands = null;
        client = null;
    }
}
