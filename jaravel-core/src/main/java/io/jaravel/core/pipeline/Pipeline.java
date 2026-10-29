package io.jaravel.core.pipeline;

import java.util.List;
import java.util.function.Function;

/**
 * 纯函数式管道。与 HTTP **完全解耦** —— HTTP 中间件、队列任务中间件、
 * CLI 命令中间件共用这一个实现。
 *
 * <p>与 Laravel 的 {@code array_reduce} 同构:从后往前把 pipe 折叠成嵌套闭包。
 *
 * <p>栈深度:每个 pipe 增加约 3 层栈帧。虚拟线程的栈是可增长的堆上结构,
 * 20 个中间件无压力。
 */
public final class Pipeline<T, R> {

    private final T passable;
    private List<? extends Pipe<T, R>> pipes = List.of();

    private Pipeline(T passable) {
        this.passable = passable;
    }

    public static <T, R> Pipeline<T, R> of(T passable) {
        return new Pipeline<>(passable);
    }

    public Pipeline<T, R> through(List<? extends Pipe<T, R>> pipes) {
        this.pipes = pipes;
        return this;
    }

    @SafeVarargs
    public final Pipeline<T, R> through(Pipe<T, R>... pipes) {
        this.pipes = List.of(pipes);
        return this;
    }

    public R then(Function<T, R> destination) {
        Function<T, R> chain = destination;
        for (int i = pipes.size() - 1; i >= 0; i--) {
            final Pipe<T, R> pipe = pipes.get(i);
            final Function<T, R> next = chain;
            chain = p -> pipe.handle(p, next);
        }
        return chain.apply(passable);
    }
}
