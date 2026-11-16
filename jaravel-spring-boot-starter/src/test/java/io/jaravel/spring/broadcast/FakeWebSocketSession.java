package io.jaravel.spring.broadcast;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocketSession 的测试替身。
 *
 * <p>只实现协议层用到的部分({@code getAttributes / isOpen / sendMessage /
 * close});收到的出站帧按序放进一个线程安全的列表,测试端轮询断言。
 *
 * <p>Spring 没有官方的 WS session 假件(它有 servlet 的,那是给握手用的),
 * 手写是必要的 —— 但要小心:接口的默认方法可能在未来版本改变行为,
 * 这个替身只服务于广播的协议测试,不是通用假件。
 */
final class FakeWebSocketSession implements WebSocketSession {

    private final String id;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<String> sentFrames = new CopyOnWriteArrayList<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    FakeWebSocketSession(String id) {
        this.id = id;
    }

    List<String> sentFrames() {
        return sentFrames;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public URI getUri() {
        return URI.create("ws://test/app/jaravel");
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        return new HttpHeaders();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        sentFrames.add((String) message.getPayload());
    }

    @Override
    public void close() {
        open.set(false);
    }

    @Override
    public void close(CloseStatus status) {
        open.set(false);
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public java.net.InetSocketAddress getRemoteAddress() {
        return null;
    }

    @Override
    public java.net.InetSocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public String getAcceptedProtocol() {
        return null;
    }

    @Override
    public void setTextMessageSizeLimit(int limit) {
    }

    @Override
    public int getTextMessageSizeLimit() {
        return 8192;
    }

    @Override
    public void setBinaryMessageSizeLimit(int limit) {
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        return 8192;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return List.of();
    }
}
