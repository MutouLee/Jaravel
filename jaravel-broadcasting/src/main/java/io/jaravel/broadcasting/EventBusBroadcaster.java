package io.jaravel.broadcasting;

import java.util.Map;

import io.jaravel.events.EventBus;
import io.jaravel.queue.Queue;
import io.jaravel.queue.ShouldQueue;

/**
 * 事件总线 → 广播的桥(docs/19 §2.2)。由 starter 装配进
 * {@link EventBus#broadcaster(EventBus.Broadcaster)}。
 *
 * <p>事件模块不知道「广播」这个概念 —— 它只承诺在监听器全部跑完后
 * 把事件交给这个钩子。这里做三件事:
 * <ol>
 *   <li>{@code instanceof ShouldBroadcast} —— 不是就放行(绝大多数事件不是);</li>
 *   <li>{@code broadcastWhen()} —— 这次分发要不要推;</li>
 *   <li>payload 归一化({@code broadcastWith()} 接管或全字段提取)后,
 *       逐频道发布。</li>
 * </ol>
 */
public final class EventBusBroadcaster implements EventBus.Broadcaster {

    private final BroadcastManager manager;
    private final PayloadJson json;

    public EventBusBroadcaster(BroadcastManager manager) {
        this(manager, new PayloadJson());
    }

    public EventBusBroadcaster(BroadcastManager manager, PayloadJson json) {
        this.manager = manager;
        this.json = json;
    }

    @Override
    public void broadcast(Object event) {
        if (!(event instanceof ShouldBroadcast broadcast)) return;
        if (!broadcast.broadcastWhen()) return;

        Map<String, Object> payload = payloadOf(broadcast, event);
        var channels = broadcast.broadcastOn();

        // 事件同时实现 ShouldQueue → 广播入队,由 worker 发布。
        // 监听器仍然同步跑(那是事件总线的语义,不是广播的),
        // 这里只把「推给前端」这一步异步化 —— 一个慢的 WS 驱动
        // 不该拖慢发起事件的那个请求。
        if (event instanceof ShouldQueue queued) {
            Queue.dispatch(new BroadcastJob(channels, broadcast.broadcastAs(),
                    payload, queued.queue()));
            return;
        }

        for (String channel : channels) {
            manager.publish(channel, broadcast.broadcastAs(), payload);
        }
    }

    /**
     * {@code broadcastWith()} 返回 {@code null} = 用事件的全部字段;
     * 返回一个 Map(哪怕是空 Map)就完全接管 —— 见
     * {@link ShouldBroadcast#broadcastWith} 里为什么不用「空 Map 也算空」。
     */
    private Map<String, Object> payloadOf(ShouldBroadcast broadcast, Object event) {
        Map<String, Object> explicit = broadcast.broadcastWith();
        if (explicit != null) return explicit;
        return json.toMap(event);
    }
}
