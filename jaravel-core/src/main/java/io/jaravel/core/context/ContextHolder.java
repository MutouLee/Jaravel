package io.jaravel.core.context;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 请求/任务级上下文的载体抽象(ADR-007)。
 *
 * <p><b>为什么要这层抽象:</b>基线是 Java 21,而 {@code ScopedValue} 在 21 中仍是预览特性,
 * 到 Java 25 才正式化。这个接口只有三个方法,成本极低,却让核心代码不绑定具体机制 ——
 * Java 25+ 可以无痛切到 {@code ScopedValue}(结构化绑定,出块即解绑,不可能泄漏)。
 *
 * <p><b>为什么不直接用 ThreadLocal:</b>线程池复用下忘记清理会**串号**(用户 A 看到用户 B 的
 * 数据,安全事故)。因此 Java 21 的实现({@link ThreadLocalContextHolder})强制
 * try/finally 清理,且只提供 {@link #runIn} 这一个绑定入口 —— 不暴露裸 set()。
 */
public interface ContextHolder<T> {

    /**
     * 在绑定了 {@code value} 的作用域内执行 {@code body}。
     * 实现**必须**保证退出时解绑,无论正常返回还是抛异常。
     */
    <R> R runIn(T value, Callable<R> body) throws Exception;

    /** 同上,无返回值版本 */
    default void runIn(T value, ThrowingRunnable body) throws Exception {
        runIn(value, () -> { body.run(); return null; });
    }

    /** 当前绑定值;未绑定时为空。 */
    Optional<T> current();

    /** 是否处于绑定作用域内 */
    default boolean isBound() {
        return current().isPresent();
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
