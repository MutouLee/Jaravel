package io.jaravel.broadcasting;

import java.util.List;
import java.util.Map;

/**
 * 广播的静态门面(docs/19 §2.3 / §6.6)。
 *
 * <pre>{@code
 * Broadcast.emit("orders", "refresh", Map.of("at", Instant.now()));
 *
 * Broadcast.fake();
 * service.pay(order);
 * Broadcast.assertBroadcastedTo("orders");
 * }</pre>
 */
public final class Broadcast {

    private static volatile BroadcastManager manager;

    private Broadcast() {
    }

    public static void use(BroadcastManager configured) {
        manager = configured;
    }

    public static BroadcastManager manager() {
        BroadcastManager current = manager;
        if (current == null) {
            throw new IllegalStateException("""
                    广播管理器尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调 Broadcast.use(new BroadcastManager())。""");
        }
        return current;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        manager = null;
    }

    // ================= 发布 =================

    /**
     * 不经事件总线,直接把一条消息推到频道。
     *
     * <p>用途:「这条消息不配拥有一个事件类」的场景,以及想要绕过
     * 事件层直接操作连接的逃生口(docs/19 §2.3)。
     */
    public static void emit(String channel, String event, Map<String, Object> data) {
        manager().publish(channel, event, data);
    }

    /** 同上,无 payload 的便捷写法 */
    public static void emit(String channel, String event) {
        emit(channel, event, Map.of());
    }

    // ================= 测试 =================

    /** 拦截广播并记录。**在被测代码之前调** */
    public static void fake() {
        manager().fake();
    }

    public static void stopFaking() {
        manager().stopFaking();
    }

    public static boolean isFaking() {
        return manager().isFaking();
    }

    // ================= 断言 =================

    /**
     * 断言某条消息被广播到了某频道。
     *
     * <p>不写 event 名时只断言频道 —— 断言太精确会脆,
     * 太粗会放过「频道对了但事件名错了」;两个都提供。
     */
    public static void assertBroadcastedTo(String channel) {
        List<BroadcastManager.FakeRecorder.Entry> hits = manager().recorder().to(channel);
        if (hits.isEmpty()) {
            throw new AssertionError("""
                    期望有消息广播到频道 "%s",但没有。
                      已广播:%s
                      若广播发生在 Broadcast.fake() **之前**,这里看不到它。"""
                    .formatted(channel, describe(manager().recorder().all())));
        }
    }

    public static void assertBroadcastedTo(String channel, String event) {
        List<BroadcastManager.FakeRecorder.Entry> hits = manager().recorder().to(channel)
                .stream().filter(e -> e.event().equals(event)).toList();
        if (hits.isEmpty()) {
            throw new AssertionError("""
                    期望事件 "%s" 广播到频道 "%s",但没有。
                      该频道收到的:%s"""
                    .formatted(event, channel,
                               describe(manager().recorder().to(channel))));
        }
    }

    /** 断言没有任何广播发生 */
    public static void assertNothingBroadcasted() {
        List<BroadcastManager.FakeRecorder.Entry> all = manager().recorder().all();
        if (!all.isEmpty()) {
            throw new AssertionError(
                    "期望没有任何广播,实际:" + describe(all));
        }
    }

    private static String describe(List<BroadcastManager.FakeRecorder.Entry> entries) {
        if (entries.isEmpty()) return "(空)";
        return entries.stream()
                .map(e -> e.channel() + " ← " + e.event())
                .toList().toString();
    }
}
