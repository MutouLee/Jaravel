package io.jaravel.events;

import java.util.Map;

/**
 * 把一个「队列化监听器 + 一个事件」包成可入队的任务。
 *
 * <p>它是 {@code ShouldQueue} 的实现,但**这个类不能直接 implements 它** ——
 * {@code jaravel-queue} 对 {@code jaravel-events} 是 optional 依赖,
 * 硬依赖会让一个不用异步监听器的应用也被迫引入队列。
 *
 * <p>所以这里只是一个数据载体,由 starter 里的适配器包成真正的
 * {@code ShouldQueue}。这个分层的代价是多一层间接,
 * 换来的是「事件不强依赖队列」。
 *
 * <h2>payload 里存什么</h2>
 * <p>监听器的**类名** + 事件的类名与字段。监听器实例不序列化 ——
 * 它是容器建的 bean,worker 那边照样能建一个。
 *
 * <p>事件必须能 JSON 往返。这与 {@code ShouldQueue} 的
 * 「只存主键、不存模型」在这里会打架:
 * {@code record OrderPaid(Order order)} 作为同步事件完全正常,
 * 一旦有一个队列化监听器就不行了 —— 那个 Order 快照会在几分钟后
 * 被当成当前状态使用。
 *
 * <p>Jaravel 的处理是让序列化**在投递时报错**,并在消息里指出
 * 是哪个监听器让这个事件需要序列化。把问题暴露在加监听器的那一刻,
 * 而不是「几周后有人发现队列里的事件少了一半字段」。
 *
 * @param listenerClass 监听器类名(全限定)
 * @param eventClass    事件类名(全限定)
 * @param eventData     事件字段
 */
public record QueuedListenerJob(
        String listenerClass,
        String eventClass,
        Map<String, Object> eventData
) {

    public QueuedListenerJob {
        eventData = eventData == null ? Map.of() : Map.copyOf(eventData);
    }

    /** failed_jobs 里好认一点 */
    public String displayName() {
        return "%s←%s".formatted(simple(listenerClass), simple(eventClass));
    }

    private static String simple(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }
}
