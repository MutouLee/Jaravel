package io.jaravel.spring.broadcast;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.broadcasting.BroadcastHub;
import io.jaravel.broadcasting.PayloadJson;

/**
 * {@code ws} 驱动的本地枢纽(docs/19 §6.2 / §6.3)。
 *
 * <p>与 Spring 解耦:它只管理「连接 ↔ 频道」的登记关系,
 * WebSocket 协议与帧格式在 {@link JaravelWebSocketHandler} 里。
 * 这个划分让 {@code publish} 的逻辑可以在没有 Spring 的测试里跑,
 * 也让 v0.7 的 redis 驱动可以原样复用(它只需在 publish 之外
 * 加一步 pub/sub 扇出)。
 *
 * <h2>presence 成员表在枢纽里,不在连接里</h2>
 * <p>成员进出要广播给频道内**其他**成员 —— 这个动作天然需要
 * 「频道 → 连接集合」的视图,而那个视图就在这里。
 */
public final class LocalWsHub implements BroadcastHub {

    private final PayloadJson json = new PayloadJson();

    /** 连接 id → 连接 */
    private final ConcurrentHashMap<String, WsConnection> connections =
            new ConcurrentHashMap<>();

    /** 频道 → 订阅它的连接 id 集合 */
    private final ConcurrentHashMap<String, Set<String>> byChannel =
            new ConcurrentHashMap<>();

    /** presence 频道 → (连接 id → 成员) */
    private final ConcurrentHashMap<String, Map<String, PresenceMember>> presence =
            new ConcurrentHashMap<>();

    // ================= 连接生命周期 =================

    void connect(WsConnection connection) {
        connections.put(connection.id(), connection);
    }

    /** 断开(主动或被动):清掉它的全部订阅,presence 发 member_removed */
    void disconnect(WsConnection connection) {
        connections.remove(connection.id());
        for (String channel : connection.channels()) {
            unsubscribe(connection, channel);
        }
    }

    // ================= 订阅 =================

    /**
     * 订阅成功。
     *
     * @param member 非 null = presence 频道订阅(成员信息为 null 也**仍是**成员
     *               —— info 回调失败不该把「存在」变成「不存在」)
     */
    void subscribe(WsConnection connection, String channel, PresenceMember member) {
        connection.addChannel(channel);
        byChannel.computeIfAbsent(channel, c -> ConcurrentHashMap.newKeySet())
                .add(connection.id());

        if (member == null) return;

        var members = presence.computeIfAbsent(channel, c -> new ConcurrentHashMap<>());
        members.put(connection.id(), member);
    }

    void unsubscribe(WsConnection connection, String channel) {
        connection.removeChannel(channel);

        var ids = byChannel.get(channel);
        if (ids != null) {
            ids.remove(connection.id());
            if (ids.isEmpty()) byChannel.remove(channel);
        }

        var members = presence.get(channel);
        PresenceMember removed = members == null ? null : members.remove(connection.id());
        if (removed != null) {
            if (members.isEmpty()) presence.remove(channel);
            broadcast(channel, memberRemovedFrame(channel, removed.userId()), connection);
        }
    }

    // ================= 查询(协议层用) =================

    boolean isSubscribed(WsConnection connection, String channel) {
        return connection.hasChannel(channel);
    }

    /** presence 频道的成员表快照(按加入顺序) */
    Map<String, PresenceMember> membersOf(String channel) {
        Map<String, PresenceMember> members = presence.get(channel);
        return members == null ? Map.of() : Map.copyOf(members);
    }

    int connectionCount() {
        return connections.size();
    }

    // ================= 发布 =================

    /**
     * 把一条消息推给频道的所有订阅者。
     *
     * <p><b>序列化只做一次</b>,每个连接只发帧 —— 一个频道有 N 个订阅者时
     * payload 不会序列化 N 遍。连接内的发送失败(断线竞态)被吞掉:
     * 广播是尽力而为,断线的连接随后会走 disconnect 清理。
     */
    @Override
    public void publish(String channel, String event, Map<String, Object> data) {
        String payload = json.encode(data == null ? Map.of() : data);
        broadcast(channel, eventFrame(event, channel, payload), null);
    }

    /** 把一帧推给频道内所有订阅者(exclude 不收到 —— presence 用) */
    void broadcast(String channel, String frame, WsConnection exclude) {
        var ids = byChannel.get(channel);
        if (ids == null) return;
        for (String id : ids) {
            WsConnection connection = connections.get(id);
            if (connection == null || connection == exclude) continue;
            connection.sendFrame(frame);
        }
    }

    /**
     * presence 成员加入帧。
     *
     * <p>pusher 协议里 {@code data} 是 **JSON 字符串**(客户端库自行
     * JSON.parse),不是内嵌对象 —— 这一条对 member_added /
     * member_removed / 应用事件 / subscription_succeeded 全部适用。
     */
    String memberAddedFrame(String channel, PresenceMember member) {
        String data = json.encode(Map.of(
                "user_id", member.userId(), "user_info", member.userInfo()));
        return "{\"event\":\"pusher_internal:member_added\",\"channel\":\"" + channel
                + "\",\"data\":" + stringLiteral(data) + "}";
    }

    private static String memberRemovedFrame(String channel, String userId) {
        return "{\"event\":\"pusher_internal:member_removed\",\"channel\":\"" + channel
                + "\",\"data\":" + stringLiteral(
                        "{\"user_id\":\"" + escape(userId) + "\"}") + "}";
    }

    private static String eventFrame(String event, String channel, String payload) {
        return "{\"event\":\"" + escape(event) + "\",\"channel\":\"" + escape(channel)
                + "\",\"data\":" + stringLiteral(payload) + "}";
    }

    /** 把一段已是 JSON 的文本包成 JSON 字符串字面量 */
    private static String stringLiteral(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String escape(String value) {
        // 事件名/频道名已在入站侧校验过字符集(docs/19 §3.2),这里只是兜底
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** presence 频道的一个成员 */
    public record PresenceMember(String userId, Map<String, Object> userInfo) {
    }
}
