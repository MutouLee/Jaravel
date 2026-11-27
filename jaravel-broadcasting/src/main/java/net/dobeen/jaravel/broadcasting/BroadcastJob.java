package net.dobeen.jaravel.broadcasting;

import java.util.List;
import java.util.Map;

import net.dobeen.jaravel.queue.ShouldQueue;

/**
 * 队列化的广播任务(docs/19 §8)。
 *
 * <p>事件同时实现 {@link ShouldBroadcast} 与 {@link ShouldQueue} 时,
 * {@link EventBusBroadcaster} 不再同步推送,而是把「频道 / 事件名 /
 * payload」快照成这个任务入队。worker 执行 {@link #handle()} 时再发布。
 *
 * <h2>为什么存 payload 而不是事件对象</h2>
 * <p>事件是应用的 record,把它整个塞进队列 payload 会让「改一个字段」
 * 与积压的旧任务不兼容;而且广播需要的只是归一化后的 JSON
 * (见 {@code ShouldBroadcast.broadcastWith})。存快照让队列里的
 * 任务与事件类解耦。
 *
 * <p>{@code queue} 取自事件的 {@link ShouldQueue#queue()} —— 事件想
 * 进哪个队列,广播任务就跟进哪个;null = 连接的默认队列。
 */
public record BroadcastJob(List<String> channels, String event,
                           Map<String, Object> data, String queue)
        implements ShouldQueue {

    public BroadcastJob {
        channels = channels == null ? List.of() : List.copyOf(channels);
        // 不能用 Map.copyOf:payload 里可能有 null(record 的某个字段为 null),
        // 而 Map.copyOf 对 null 值直接 NPE —— 事件里一个可选字段就会让
        // 广播任务在入队那一刻失败。
        data = data == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }

    @Override
    public String queue() {
        return queue;
    }

    /** worker 里执行:逐频道发布。驱动失败是尽力而为,不抛 */
    public void handle() {
        for (String channel : channels) {
            Broadcast.manager().publish(channel, event, data);
        }
    }
}
