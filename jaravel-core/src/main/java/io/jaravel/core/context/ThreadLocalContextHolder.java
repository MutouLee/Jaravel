package io.jaravel.core.context;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Java 21 下的 {@link ContextHolder} 实现。
 *
 * <p>刻意**不暴露** {@code set()} —— 唯一的绑定入口是 {@link #runIn},
 * 其 finally 块保证解绑。这让「忘记清理导致跨请求串号」在 API 层面不可能发生。
 *
 * <p>支持嵌套绑定(内层退出后恢复外层值),用于「队列任务内再开子作用域」这类场景。
 *
 * <p>Java 25+ 应替换为 {@code ScopedValue} 实现:结构化绑定由语言保证,无需 finally。
 */
public final class ThreadLocalContextHolder<T> implements ContextHolder<T> {

    private final ThreadLocal<T> holder = new ThreadLocal<>();
    private final String name;

    public ThreadLocalContextHolder(String name) {
        this.name = name;
    }

    @Override
    public <R> R runIn(T value, Callable<R> body) throws Exception {
        T previous = holder.get();          // 支持嵌套
        holder.set(value);
        try {
            return body.call();
        } finally {
            if (previous == null) holder.remove();   // 彻底清理,避免线程池残留
            else holder.set(previous);
        }
    }

    @Override
    public Optional<T> current() {
        return Optional.ofNullable(holder.get());
    }

    @Override
    public String toString() {
        return "ContextHolder(" + name + ", bound=" + isBound() + ")";
    }
}
