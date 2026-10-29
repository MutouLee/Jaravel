package io.jaravel.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个事件监听器(docs/10 §2.2)。
 *
 * <pre>{@code
 * @Listener
 * public class NotifyWarehouse {
 *     public void handle(OrderPaid e) { warehouse.reserve(e.orderId()); }
 * }
 *
 * // 一个类监听多个事件:多写几个 handle 重载
 * @Listener
 * public class AuditLogger {
 *     public void handle(OrderPaid e)     { audit("paid", e.orderId()); }
 *     public void handle(OrderRefunded e) { audit("refunded", e.orderId()); }
 * }
 *
 * // 异步:实现 ShouldQueue,自动入队
 * @Listener
 * public class SendReceipt implements ShouldQueue {
 *     @Override public String queue() { return "mail"; }
 *     public void handle(OrderPaid e) { ... }
 * }
 * }</pre>
 *
 * <h2>APT 生成绑定,运行时零反射</h2>
 * <p>注解处理器为每个 {@code handle} 方法生成一个 {@code ListenerBinding}
 * 实现,里面是一次**直接的强类型调用**:
 *
 * <pre>{@code
 * ((NotifyWarehouse) listener).handle((OrderPaid) event);
 * }</pre>
 *
 * <p>以及一份 {@code META-INF/jaravel/listeners.idx},于是启动时既不扫包
 * 也不反射。这件事在 Java 上是**相对 Laravel 的真实优势**:
 * Laravel 的事件发现要么扫目录、要么靠反射读参数类型,
 * 两者都在启动时付代价。
 *
 * <h2>与 ShouldQueue 组合时的一个真实约束</h2>
 * <p>队列化监听器的**事件要能 JSON 往返** —— 它会被写进 payload。
 * 而 {@code ShouldQueue} 的类注释要求「只存主键,不存模型」,
 * 两者在这里会打架:{@code record OrderPaid(Order order)} 作为同步事件
 * 完全正常,一旦有一个队列化监听器就不行了。
 *
 * <p>Jaravel 的处理是**在投递时报错**而不是静默序列化半个模型 ——
 * 错误消息会指出是哪个监听器让这个事件需要序列化。
 * 这是一个取舍:它把问题暴露在加监听器的那一刻,而不是
 * 「几周后有人发现队列里的事件少了一半字段」。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Listener {

    /**
     * 执行顺序,**大的先跑**。
     *
     * <p>默认 0。用途:风控、鉴权这类「可能要中止后续监听器」的
     * 必须排在前面 —— 见 {@link EventBus} 关于中止传播的说明。
     *
     * <p>同优先级之间的顺序**不保证**。刻意不保证:让它等于
     * 「类名字母序」或「APT 处理顺序」会让人依赖一个
     * 会随重构悄悄改变的顺序。真的有先后依赖就写优先级。
     */
    int priority() default 0;
}
