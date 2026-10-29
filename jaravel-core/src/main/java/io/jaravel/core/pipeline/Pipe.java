package io.jaravel.core.pipeline;

import java.util.function.Function;

/**
 * 管道中的一环。
 *
 * <p>HTTP 中间件只是 {@code Pipe<Request, Response>} 的别名。
 *
 * <p>约定:前置逻辑写在 {@code next.apply(...)} 之前,后置逻辑写在之后;
 * 不调用 {@code next} 即为短路。
 */
@FunctionalInterface
public interface Pipe<T, R> {
    R handle(T passable, Function<T, R> next);
}
