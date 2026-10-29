package io.jaravel.http;

import java.util.function.Function;

import io.jaravel.core.pipeline.Pipe;

/**
 * HTTP 中间件。只是 {@code Pipe<Request, Response>} 的别名 ——
 * 与队列任务中间件、CLI 命令中间件共用同一套 {@link io.jaravel.core.pipeline.Pipeline}。
 *
 * <p><b>为什么不用 Servlet Filter:</b>Filter 是全局的、按 URL pattern 匹配的,
 * 无法表达「同一 URL 的 GET 公开、POST 需要权限」,也拿不到已匹配的路由参数
 * (而 {@code can:update,post} 恰好需要路由参数)。因此中间件运行在路由匹配**之后**。
 *
 * <p>真正需要全局拦截的横切关注点(CORS、代理头、维护模式)由 starter 注册为
 * 一个 Servlet Filter 统一执行 —— 这是刻意的分层。
 */
@FunctionalInterface
public interface Middleware extends Pipe<Request, Response> {

    @Override
    Response handle(Request request, Function<Request, Response> next);

    /** 带参数的中间件({@code throttle:60,1})实现此接口接收参数 */
    interface Parameterized extends Middleware {
        void configure(String... params);
    }
}
