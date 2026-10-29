package io.jaravel.events;

/**
 * 「某个事件类型 → 某个监听器的某个 handle 方法」的一次绑定。
 *
 * <p>**由 APT 生成**,用户不写它。生成出来的样子:
 *
 * <pre>{@code
 * public final class NotifyWarehouse$$OrderPaid implements ListenerBinding {
 *     public Class<?> eventType()    { return OrderPaid.class; }
 *     public Class<?> listenerType() { return NotifyWarehouse.class; }
 *     public int priority()          { return 0; }
 *     public boolean queued()        { return false; }
 *
 *     public boolean invoke(Object listener, Object event) {
 *         ((NotifyWarehouse) listener).handle((OrderPaid) event);
 *         return true;
 *     }
 * }
 * }</pre>
 *
 * <h2>为什么是生成的类而不是反射 Method</h2>
 * <p>反射调用需要 {@code setAccessible} + 每次 {@code invoke} 的开销,
 * 而且**参数类型要在运行时读** —— 那意味着启动时要为每个监听器
 * 加载并解析一遍方法签名。
 *
 * <p>更重要的是:反射方案里「handle 的参数不是一个事件类型」
 * 这种错误只能在运行时第一次分发时发现。生成方案里它是**编译错误**。
 *
 * <h2>invoke 的返回值 = 要不要继续</h2>
 * <p>返回 false 表示**中止传播**,后续监听器不再执行(docs/10 §2.3)。
 *
 * <p>生成的代码按用户的 handle 签名决定返回什么:
 * <ul>
 *   <li>{@code void handle(E e)} → 生成 {@code return true}(永不中止);</li>
 *   <li>{@code boolean handle(E e)} → 直接返回用户的返回值。</li>
 * </ul>
 *
 * <p>这个分支由 APT 处理,所以用户写 {@code void} 时不会「忘了 return true
 * 导致后续监听器全被跳过」—— 那种错误在手写绑定的方案里非常容易犯。
 */
public interface ListenerBinding {

    /** 这个绑定关心哪个事件类型 */
    Class<?> eventType();

    /** 监听器的类。总线用它从容器取实例 */
    Class<?> listenerType();

    /** 大的先跑。见 {@link Listener#priority()} */
    default int priority() {
        return 0;
    }

    /**
     * 这个监听器是不是队列化的({@code implements ShouldQueue})。
     *
     * <p>由 APT 在编译期判定并写死 —— 运行时用 {@code instanceof} 判断
     * 需要先把监听器实例化,而那对一个本该异步的监听器意味着
     * 「为了知道它是异步的,先在请求线程里把它建出来」。
     */
    default boolean queued() {
        return false;
    }

    /**
     * 调用。
     *
     * @return 是否继续往后传播。false = 中止
     */
    boolean invoke(Object listener, Object event);

    /** 给错误消息用:哪个类的哪个方法 */
    default String describe() {
        return listenerType().getSimpleName() + ".handle("
                + eventType().getSimpleName() + ")";
    }
}
