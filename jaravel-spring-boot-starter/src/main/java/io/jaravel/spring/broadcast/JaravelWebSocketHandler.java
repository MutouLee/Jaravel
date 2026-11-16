package io.jaravel.spring.broadcast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import io.jaravel.broadcasting.ChannelPattern;
import io.jaravel.broadcasting.ChannelRegistry;

/**
 * Pusher 协议子集的服务端(docs/19 §5.2)。
 *
 * <p>只处理协议消息的**解析与应答**;连接状态在 {@link WsConnection},
 * 频道/成员登记在 {@link LocalWsHub},鉴权在 {@link ChannelRegistry}。
 *
 * <h2>消息都在连接线程上处理</h2>
 * <p>{@code handleTextMessage} 只做「解析 + 投递队列」,真正的处理在
 * 连接线程 —— 于是订阅/退订/presence 变更天然串行,不需要锁。
 *
 * <h2>私有/存在频道鉴权凭会话,不凭 auth 字段</h2>
 * <p>pusher-js 会在 subscribe 消息里带 {@code auth} / {@code channel_data}
 * (它必须给点什么才肯发订阅)。这里**忽略**它们,用握手时解析的
 * 会话用户重新鉴权 —— ADR-015:WS 嵌在应用里,会话就是凭据。
 */
public class JaravelWebSocketHandler extends TextWebSocketHandler {

    /** 握手属性名:用户(可能为 null)。由 UserResolvingInterceptor 写入 */
    public static final String USER_ATTRIBUTE = "jaravel.ws.user";

    /** 握手属性名:会话数据(可能为 null) */
    public static final String SESSION_ATTRIBUTE = "jaravel.ws.session";

    /** 连接建立后存回 session 属性,供 handleTextMessage 取回 */
    private static final String CONNECTION_ATTRIBUTE = "jaravel.ws.connection";

    private final LocalWsHub hub;
    private final ChannelRegistry registry;
    private final long activityTimeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();

    public JaravelWebSocketHandler(LocalWsHub hub, ChannelRegistry registry,
                                   long activityTimeoutSeconds) {
        this.hub = hub;
        this.registry = registry;
        this.activityTimeoutSeconds = activityTimeoutSeconds;
    }

    // ================= 生命周期 =================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var attributes = session.getAttributes();
        Object user = attributes.get(USER_ATTRIBUTE);
        var sessionData = (io.jaravel.session.SessionData) attributes.get(SESSION_ATTRIBUTE);

        var connection = new WsConnection(WsConnection.nextId(), session, hub,
                activityTimeoutSeconds * 1000, sessionData, user);
        session.getAttributes().put(CONNECTION_ATTRIBUTE, connection);
        hub.connect(connection);

        // 先发 connection_established 再启动处理线程 —— 客户端在等这帧
        connection.sendFrame("{\"event\":\"pusher:connection_established\","
                + "\"data\":\"{\\\"socket_id\\\":\\\"" + connection.id()
                + "\\\",\\\"activity_timeout\\\":" + activityTimeoutSeconds + "}\"}");

        connection.start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WsConnection connection = connectionOf(session);
        if (connection == null) return;

        var frame = parse(message.getPayload());
        if (frame == null) {
            // 坏帧:客户端 bug 不该留着一个僵尸连接。
            // 4002 是「坏消息」的语义(4000-4099 为私有错误码段)
            connection.close();
            return;
        }

        connection.runOnConnectionThread(() -> handle(connection, frame));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WsConnection connection = connectionOf(session);
        if (connection != null) {
            connection.close();
            hub.disconnect(connection);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        WsConnection connection = connectionOf(session);
        if (connection != null) {
            hub.disconnect(connection);
        }
    }

    // ================= 协议处理(连接线程) =================

    private void handle(WsConnection connection, Frame frame) {
        switch (frame.event()) {
            case "pusher:ping" -> connection.sendFrame(
                    "{\"event\":\"pusher:pong\",\"data\":{}}");

            case "pusher:subscribe" -> subscribe(connection, frame);

            case "pusher:unsubscribe" -> {
                String channel = frame.channelFromData();
                if (channel != null) hub.unsubscribe(connection, channel);
            }

            default -> {
                if (frame.event().startsWith("client-")) {
                    relayClientEvent(connection, frame);
                }
                // 其余消息(未知 pusher:* 等)忽略 —— 协议是子集,
                // 客户端库可能发来我们没有实现的特性
            }
        }
    }

    private void subscribe(WsConnection connection, Frame frame) {
        String channel = frame.channelFromData();

        try {
            ChannelPattern.validate(channel);
        } catch (IllegalArgumentException e) {
            sendError(connection, channel, "InvalidChannel: " + e.getMessage());
            return;
        }

        if (hub.isSubscribed(connection, channel)) {
            // 重复订阅:pusher-js 对已订阅频道会直接发 succeeded。
            // 幂等应答比报错更接近客户端的行为预期
            sendSubscribed(connection, channel, hub.membersOf(channel));
            return;
        }

        if (channel.startsWith("private-") || channel.startsWith("presence-")) {
            var authorization = registry.authorize(channel, connection.user());
            if (!authorization.allowed()) {
                System.err.println("[jaravel] WS 频道订阅被拒:" + channel
                        + " —— " + authorization.reason());
                sendError(connection, channel, "AuthError");
                return;
            }

            if (channel.startsWith("presence-")) {
                var member = new LocalWsHub.PresenceMember(
                        userIdOf(connection.user()),
                        authorization.presenceInfo() == null
                                ? Map.of() : authorization.presenceInfo());
                hub.subscribe(connection, channel, member);

                sendSubscribed(connection, channel, hub.membersOf(channel));
                // 通知老成员(广播排除新成员自己 —— 它已在 succeeded 里拿到全量)
                hub.broadcast(channel, hub.memberAddedFrame(channel, member), connection);
                return;
            }

            hub.subscribe(connection, channel, null);
            sendSubscribed(connection, channel, Map.of());
            return;
        }

        // 公开频道:免注册(docs/19 §3.1)
        hub.subscribe(connection, channel, null);
        sendSubscribed(connection, channel, Map.of());
    }

    /** 客户端事件(whisper):转发给频道内其他成员,不回声给发送者 */
    private void relayClientEvent(WsConnection connection, Frame frame) {
        String channel = frame.channel();
        if (channel == null
                || !hub.isSubscribed(connection, channel)
                || !registry.whispersAllowed(channel)) {
            // 静默丢弃未允许的客户端事件 —— 但**必须**订阅了该频道才能发
            // (否则任意连接都能借 whisper 频道广播任意内容)
            return;
        }
        hub.broadcast(channel, frame.raw(), connection);
    }

    // ================= 应答帧 =================

    private void sendSubscribed(WsConnection connection, String channel,
                                Map<String, LocalWsHub.PresenceMember> members) {
        String data;
        if (members == null || channel == null || !channel.startsWith("presence-")) {
            data = "{}";
        } else {
            List<String> ids = new ArrayList<>(members.size());
            Map<String, Object> hash = new LinkedHashMap<>();
            members.values().forEach(m -> {
                ids.add(m.userId());
                hash.put(m.userId(), m.userInfo());
            });
            data = "{\"presence\":{\"ids\":" + stringify(ids)
                    + ",\"hash\":" + stringify(hash) + ",\"count\":" + members.size() + "}}";
        }
        connection.sendFrame("{\"event\":\"pusher_internal:subscription_succeeded\","
                + "\"channel\":\"" + escape(channel) + "\",\"data\":" + stringify(data) + "}");
    }

    private void sendError(WsConnection connection, String channel, String error) {
        connection.sendFrame("{\"event\":\"pusher_internal:subscription_error\","
                + "\"channel\":\"" + escape(channel)
                + "\",\"data\":" + stringify("{\"type\":\"" + error + "\"}") + "}");
    }

    private String stringify(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 成员的 user_id:连接用户的 id 字符串化(docs/19 §9 的待决项) */
    private static String userIdOf(Object user) {
        if (user instanceof io.jaravel.auth.Authenticatable auth) {
            return String.valueOf(auth.getAuthIdentifier());
        }
        return String.valueOf(user);
    }

    // ================= 帧解析 =================

    private WsConnection connectionOf(WebSocketSession session) {
        return (WsConnection) session.getAttributes().get(CONNECTION_ATTRIBUTE);
    }

    private Frame parse(String payload) {
        try {
            Map<?, ?> raw = mapper.readValue(payload, Map.class);
            String event = (String) raw.get("event");
            Object data = raw.get("data");
            Object channel = raw.get("channel");

            String dataChannel = null;
            if (data instanceof Map<?, ?> map) {
                dataChannel = (String) map.get("channel");
            }

            return new Frame(event, dataChannel, (String) channel, payload);
        } catch (Exception e) {
            return null;
        }
    }

    /** 一条入站帧的最小表示 */
    private record Frame(String event, String dataChannel, String channel, String raw) {

        String channelFromData() {
            return dataChannel != null ? dataChannel : channel;
        }
    }
}
