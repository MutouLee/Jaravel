package net.dobeen.jaravel.broadcasting;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import net.dobeen.jaravel.core.Application;

/**
 * 广播的管理器:持有一个 {@link BroadcastHub} 驱动,并支持 fake
 * (docs/19 §6.2 / §6.6)。
 *
 * <p>事件广播(经 {@code EventBusBroadcaster})、通知 broadcast 渠道、
 * 直接推送({@code Broadcast.emit})三条路径最终都汇到
 * {@link #publish} 这一个方法上 —— 驱动只有一个,fake 也只要拦一处。
 */
public final class BroadcastManager {

    /** 默认驱动:丢弃。**不是静默** —— 见 NullBroadcaster 的警告 */
    private volatile BroadcastHub hub = new NullBroadcaster();

    private volatile FakeRecorder fake;

    // ================= 装配 =================

    public BroadcastManager hub(BroadcastHub hub) {
        this.hub = hub == null ? new NullBroadcaster() : hub;
        return this;
    }

    public BroadcastHub hub() {
        return hub;
    }

    // ================= 发布 =================

    /**
     * 把一条消息推给频道。
     *
     * <p>fake 模式下只记录不推送 —— 与 {@code Event.fake()} 的分工:
     * 那个让广播**根本不发生**(事件没分发),这个让「事件真跑、
     * 广播被拦下」成为可能 —— 断言的对象不同。
     *
     * <p>驱动层的失败(连接断开、发送超时)**不抛出** —— 驱动契约是
     * 尽力而为(docs/19 §6.2)。序列化失败发生在进入驱动之前,
     * 由调用方抛出。
     */
    public void publish(String channel, String event, Map<String, Object> data) {
        FakeRecorder recorder = fake;
        if (recorder != null) {
            recorder.record(channel, event, data);
            return;
        }
        hub.publish(channel, event, data);
    }

    // ================= 测试 =================

    /** 拦截广播并记录。**在被测代码之前调** */
    public BroadcastManager fake() {
        this.fake = new FakeRecorder();
        return this;
    }

    public BroadcastManager stopFaking() {
        this.fake = null;
        return this;
    }

    public boolean isFaking() {
        return fake != null;
    }

    public FakeRecorder recorder() {
        FakeRecorder current = fake;
        if (current == null) {
            throw new IllegalStateException("""
                    当前不在 fake 模式,没有记录可查。
                      在被测代码**之前**调 Broadcast.fake()。""");
        }
        return current;
    }

    /** 不在 fake 模式下返回 null —— 测试「什么都没广播」时用 */
    public FakeRecorder recorderOrNull() {
        return fake;
    }

    // ================= 驱动 =================

    /**
     * 丢弃一切。
     *
     * <p>debug 模式下第一次丢弃时警告一次 —— 一个配了
     * {@code driver: null} 的应用里「广播全部消失」与「驱动没装」
     * 从外部看一模一样,警告让前者至少留一条线索。
     */
    public static final class NullBroadcaster implements BroadcastHub {

        private volatile boolean warned;

        @Override
        public void publish(String channel, String event, Map<String, Object> data) {
            if (!Application.isDebug() || warned) return;
            warned = true;
            System.err.printf("""

                    [jaravel] 提示:广播被丢弃(driver = null)。
                              Event.dispatch / 通知 via("broadcast") 的推送不会到达任何客户端。
                              要启用广播,在 config/broadcasting.yml 里写:
                                broadcast:
                                  driver: ws
                              (这条提示只打一次)
                    %n""");
        }
    }

    /**
     * 每次广播打一行 JSON 到 stdout —— 开发与单测用
     * (docs/19 §6.2)。输出的是「这条消息长什么样」,
     * 与驱动无关,所以换个驱动看到的行不变。
     */
    public static final class LogBroadcaster implements BroadcastHub {

        private final net.dobeen.jaravel.broadcasting.PayloadJson json =
                new net.dobeen.jaravel.broadcasting.PayloadJson();

        @Override
        public void publish(String channel, String event, Map<String, Object> data) {
            System.out.println(json.encode(Map.of(
                    "channel", channel,
                    "event", event,
                    "data", data == null ? Map.of() : data)));
        }
    }

    // ================= fake 记录 =================

    /** fake 模式下的记录 */
    public static final class FakeRecorder {

        public record Entry(String channel, String event, Map<String, Object> data) {
        }

        private final List<Entry> entries = new CopyOnWriteArrayList<>();

        void record(String channel, String event, Map<String, Object> data) {
            entries.add(new Entry(channel, event,
                    data == null ? Map.of() : Map.copyOf(data)));
        }

        public List<Entry> all() {
            return List.copyOf(entries);
        }

        public List<Entry> to(String channel) {
            return entries.stream().filter(e -> e.channel().equals(channel)).toList();
        }

        public List<Entry> named(String event) {
            return entries.stream().filter(e -> e.event().equals(event)).toList();
        }
    }
}
