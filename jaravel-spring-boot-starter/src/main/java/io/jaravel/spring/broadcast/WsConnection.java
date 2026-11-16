package io.jaravel.spring.broadcast;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import io.jaravel.core.context.ExecutionScope;
import io.jaravel.session.SessionData;

/**
 * 一条 WS 连接:每连接一个虚拟线程 + 一个执行作用域(docs/19 §6.3)。
 *
 * <h2>为什么是「每连接一个线程」而不是每消息一个</h2>
 * <p>连接存活期间「我是谁」只解析一次(会话里的用户 id),订阅集合、
 * presence 成员数据都是连接级状态;消息级作用域会让「鉴权回调里
 * 查了一次用户,下一个消息又查一次」变成常态。这是 HTTP
 * 每请求一个作用域的 WS 对应物。
 *
 * <h2>顺序与并发</h2>
 * <p>入站消息由 Tomcat 的线程收到后**投进队列**,连接线程按序取出处理;
 * 出站也走同一线程 —— {@code WebSocketSession} 不保证并发写安全,
 * 单写者让并发问题从结构上不存在。广播路径({@link LocalWsHub#publish})
 * 从任意线程进入,但只做一件事:把帧塞进队列。
 */
final class WsConnection {

    private static final AtomicLong COUNTER = new AtomicLong();

    /** pusher 连接 id 形态:进程内自增.纳秒(docs/19 §5.2) */
    static String nextId() {
        return COUNTER.incrementAndGet() + "." + System.nanoTime();
    }

    private final String id;
    private final WebSocketSession session;
    private final LocalWsHub hub;
    private final long activityTimeoutMillis;
    private final SessionData sessionData;   // 可能为 null(guest 或会话已过期)
    private final Object user;               // 可能为 null(guest)

    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    private volatile boolean open = true;
    private volatile long lastSeen = System.currentTimeMillis();

    WsConnection(String id, WebSocketSession session, LocalWsHub hub,
                 long activityTimeoutMillis, SessionData sessionData, Object user) {
        this.id = id;
        this.session = session;
        this.hub = hub;
        this.activityTimeoutMillis = activityTimeoutMillis;
        this.sessionData = sessionData;
        this.user = user;
    }

    String id() {
        return id;
    }

    Object user() {
        return user;
    }

    boolean hasChannel(String channel) {
        return channels.contains(channel);
    }

    void addChannel(String channel) {
        channels.add(channel);
    }

    void removeChannel(String channel) {
        channels.remove(channel);
    }

    Set<String> channels() {
        return Set.copyOf(channels);
    }

    /** 由协议层在连接线程上调用 —— 订阅/退订/客户端事件的处理 */
    void runOnConnectionThread(Runnable task) {
        if (!open) return;
        queue.offer(task);
    }

    /** 广播路径:把一帧塞进出站队列 */
    void sendFrame(String frame) {
        if (!open) return;
        queue.offer(() -> {
            try {
                session.sendMessage(new TextMessage(frame));
            } catch (Exception ignored) {
                // 发送失败 = 连接已死或正在死 —— disconnect 会来清理。
                // 广播是尽力而为(docs/19 §6.2)
            }
        });
    }

    // ================= 连接线程 =================

    void start() {
        Thread.ofVirtual().name("jaravel-ws-" + id).start(() -> {
            try {
                ExecutionScope.runIn("ws", () -> {
                    bindSession();
                    loop();
                    return null;
                });
            } catch (Exception e) {
                // 作用域收尾清理抛的异常 —— 连接已经死了,记一笔即可
                if (open) {
                    System.err.println("[jaravel] WS 连接 " + id + " 异常退出:" + e.getMessage());
                }
            } finally {
                hub.disconnect(this);
            }
        });
    }

    /**
     * 把会话绑进连接作用域 —— 频道鉴权回调里 {@code Auth.user()}
     * 能通过 guard 的既有路径解析出用户(docs/19 §6.4)。
     */
    private void bindSession() {
        if (sessionData == null) return;
        ExecutionScope.current().scoped(SessionData.class, () -> sessionData);
    }

    private void loop() {
        long idleTimeout = Math.max(activityTimeoutMillis / 2, 1_000);

        while (open && session.isOpen()) {
            Runnable task;
            try {
                task = queue.poll(idleTimeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) {
                // 空闲醒来:检查活动超时。pusher-js 空闲时按 activity_timeout
                // 发 ping —— 超过 1.5 倍还没任何消息,判定客户端已死
                if (System.currentTimeMillis() - lastSeen > activityTimeoutMillis * 3 / 2) {
                    close();
                }
                continue;
            }
            lastSeen = System.currentTimeMillis();
            try {
                task.run();
            } catch (RuntimeException e) {
                // 一条坏消息不该杀死连接 —— 协议层已经各自处理了能处理的;
                // 这里的兜底防止一个未预期的异常让连接线程退出
                System.err.println("[jaravel] WS 连接 " + id + " 处理消息失败:" + e.getMessage());
            }
        }
        hub.disconnect(this);
    }

    void close() {
        open = false;
        try {
            session.close();
        } catch (Exception ignored) {
            // 关闭失败 = 已经断了
        }
    }
}
