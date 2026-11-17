package io.jaravel.broadcasting;

import java.util.List;
import java.util.Map;

/**
 * 标记「这个事件要广播到前端」(docs/19 §2.1)。
 *
 * <pre>{@code
 * public record OrderPaid(Long orderId, Long userId) implements ShouldBroadcast {
 *
 *     @Override public List<String> broadcastOn() {
 *         return List.of("orders", "private-users." + userId);
 *     }
 * }
 *
 * Event.dispatch(new OrderPaid(1L, 42L));   // 监听器照常跑,跑完后广播
 * }</pre>
 *
 * <h2>广播发生在事件总线的末尾</h2>
 * <p>全部监听器跑完(且没有监听器中止传播)之后,事件总线把事件交给
 * {@code Broadcaster} 钩子 —— 广播是同步分发的一部分(docs/19 §2.2)。
 * 于是三个语义自动成立:
 * <ul>
 *   <li>被中止传播的事件不广播;</li>
 *   <li>{@code dispatchAfterCommit} 的事件提交后才广播;</li>
 *   <li>{@code Event.fake()} 时什么都不发生。</li>
 * </ul>
 *
 * <p>事件**同时实现 {@link io.jaravel.queue.ShouldQueue}** 时,广播改为
 * 入队一个 {@link BroadcastJob},由 worker 发布 —— 监听器仍然同步跑,
 * 只有「推给前端」这一步异步化(v0.7)。
 *
 * <h2>payload 里只放 JSON 可表示的东西</h2>
 * <p>广播的 payload 要穿过 WebSocket 到达 JS 端。字段是 Eloquent 模型时
 * 序列化会失败(报错发生在分发那一刻,与队列的 JobSerializer 同一哲学)——
 * 正确姿势是只存主键:
 *
 * <pre>{@code
 * public record OrderPaid(Order order) implements ShouldBroadcast {
 *     @Override public Map<String, Object> broadcastWith() {
 *         return Map.of("orderId", order.getId());     // 不要直接放 order
 *     }
 * }
 * }</pre>
 */
public interface ShouldBroadcast {

    /**
     * 推到哪些频道。可以推多个(公开、私有、存在频道都行)。
     *
     * <p>频道命名见 docs/19 §1.1:{@code orders} / {@code private-orders.42} /
     * {@code presence-chat.1}。私有/存在频道的鉴权与订阅无关 ——
     * 服务端推消息不需要授权,授权只发生在订阅侧。
     */
    List<String> broadcastOn();

    /**
     * payload 内容。
     *
     * <p><b>返回 {@code null}(默认)表示「用事件的全部字段」</b> ——
     * 事件是 record,字段按组件提取后序列化成 JSON。
     *
     * <p>返回一个 Map(哪怕是空 Map)就完全接管 payload。
     * 「想发空 payload」与「忘了写 broadcastWith」在默认实现里
     * 被显式区分开,而不是靠一个「空 Map 也算空」的约定 ——
     * 后者会让 {@code Map.of()} 这个最自然的占位写法
     * 悄悄变成「什么都没发出去」。
     */
    default Map<String, Object> broadcastWith() {
        return null;
    }

    /**
     * JS 端的事件名,即 {@code channel.bind(name, handler)} 里的 name。
     *
     * <p>默认取事件类的简单名 —— {@code OrderPaid} 在 JS 端就是
     * {@code bind('OrderPaid')},两端同名,不需要一个映射表。
     */
    default String broadcastAs() {
        return getClass().getSimpleName();
    }

    /**
     * 这次分发要不要广播。
     *
     * <p>与 {@code Event.dispatchIf} 的分工:那个是「连监听器都不跑」,
     * 这个是「监听器照跑,只是不推给前端」。
     */
    default boolean broadcastWhen() {
        return true;
    }
}
