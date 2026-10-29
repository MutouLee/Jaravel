package io.jaravel.spring;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.ModelAndView;

import io.jaravel.core.container.Container;
import io.jaravel.core.pipeline.Pipeline;
import io.jaravel.http.BasicRequest;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.RequestScope;
import io.jaravel.http.Response;
import io.jaravel.routing.Route;

/**
 * Jaravel 的请求执行器。整个请求生命周期(docs/01 §3)在这里编排:
 *
 * <pre>
 * 1. HttpServletRequest → Jaravel Request
 * 2. 开启 RequestScope(ADR-007,退出即解绑)
 * 3. 中间件管道(全局 → 分组 → 路由级)
 * 4. 参数绑定(路径参数按类型转换 / Request 注入 / 容器注入)
 * 5. 调用控制器(控制器实例从容器解析,因此支持构造注入)
 * 6. 返回值归一化(View / Response / Redirect / POJO / void)
 * 7. 写出到 HttpServletResponse
 * </pre>
 */
public class JaravelHandlerAdapter implements HandlerAdapter {

    private final Container container;
    private final MiddlewareResolver middleware;
    private final ResponseWriter writer;
    private final JaravelExceptionHandler exceptionHandler;

    public JaravelHandlerAdapter(Container container, MiddlewareResolver middleware,
                                 ResponseWriter writer, JaravelExceptionHandler exceptionHandler) {
        this.container = container;
        this.middleware = middleware;
        this.writer = writer;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public boolean supports(Object handler) {
        return handler instanceof JaravelHandlerMapping.Handler;
    }

    /** 最终 {@link Response} 的请求属性名 */
    public static final String RESPONSE_ATTRIBUTE = "jaravel.response";

    /**
     * 本请求要跳过哪些中间件({@code Set<String>},类的简单名)。
     *
     * <p>**必须是按请求的,不能改 Route。** {@link MiddlewareResolver#of}
     * 按路由缓存解析结果且永不失效 —— 改 {@code route.withoutMiddleware(...)}
     * 在这条路由被命中过之后就不再生效,而那是个只在「测试单独跑绿、
     * 整体跑红」时才会暴露的坑。
     *
     * <p>用途:{@code jaravel-testing} 的 {@code withoutMiddleware(...)};
     * 以及健康检查之类想绕过会话/认证的入口。
     */
    public static final String EXCLUDED_MIDDLEWARE_ATTRIBUTE = "jaravel.excluded-middleware";

    @Override
    public ModelAndView handle(HttpServletRequest servletRequest,
                               HttpServletResponse servletResponse,
                               Object handler) throws Exception {
        var match = ((JaravelHandlerMapping.Handler) handler).match();
        Route route = match.route();

        BasicRequest request = ServletRequestAdapter.adapt(servletRequest);
        match.params().forEach(request::routeParam);
        route.routeName().ifPresent(request::routeName);

        // 渲染必须在**异常处理区域之内**:模板错误、JSON 序列化错误都发生在渲染阶段,
        // 若把 write 放到 runIn 之外,这些错误会绕过 ExceptionHandler 直接泄漏给 Spring
        // (实测:模板错误曾导致 500 且响应体为空,用户看不到任何有用信息)。
        byte[] body = RequestScope.runIn(request, () -> {
            // 让中间件也能拿到路由对象。SubstituteBindings 需要它来知道
            // 「这个路由参数对应的控制器参数是什么类型」——
            // Request 刻意不持有 Route(那会让 jaravel-http 依赖 routing)
            io.jaravel.core.context.ExecutionScope.current()
                    .put(SubstituteBindings.ROUTE_KEY, route);

            Response response;
            try {
                response = runPipeline(request, route);
            } catch (Throwable t) {
                response = exceptionHandler.render(request, t);
            }

            // 会话收尾。正常路径上 StartSession 自己做完了,这里是
            // **异常路径**的补救:302 / 401 / 419 / 校验失败都是用异常
            // 表达的,那时 StartSession 的收尾代码被跳过,
            // url.intended 与校验错误的闪存会一起丢掉。
            // 见 SessionLifecycle 的类注释
            //
            // 它自己也可能失败(往会话里放了个序列化不了的对象、
            // 磁盘满)。那时要走 Jaravel 的错误页,而不是让异常冒到
            // 容器那里换成一个没有任何信息的裸 500
            try {
                response = io.jaravel.session.SessionLifecycle
                        .finalizeResponse(response, request);
            } catch (Throwable sessionFailure) {
                response = exceptionHandler.render(request, sessionFailure);
            }

            // 把本请求内排队的 cookie 附加上去(remember me 等)。
            // 放在这里而不是某个中间件里:异常路径也要附加 ——
            // 「登录成功但渲染时抛了异常」仍然应该下发 remember cookie
            response = io.jaravel.http.PendingCookies.applyTo(response);

            // 把最终响应挂在请求属性上。
            //
            // 用途:jaravel-testing 要断言「返回的是哪个视图、视图数据是什么」,
            // 而那个信息在序列化成字节之后就没了。请求属性是 Servlet 的
            // 标准机制(日志、指标也用它),不是为测试新开的通道。
            servletRequest.setAttribute(RESPONSE_ATTRIBUTE, response);

            try {
                return writer.serialize(response, servletResponse);
            } catch (Throwable renderFailure) {
                // 渲染本身失败(模板错误 / 序列化失败)→ 交给 ExceptionHandler 重新渲染。
                // 此时不能再用视图,否则可能二次失败,因此走纯文本/JSON 错误页。
                var fallback = io.jaravel.http.PendingCookies.applyTo(
                        io.jaravel.session.SessionLifecycle.finalizeResponse(
                                exceptionHandler.render(request, renderFailure), request));
                return writer.serialize(fallback, servletResponse);
            }
        });

        writer.flush(body, servletResponse);
        return null;   // 已自行写出,不交给 ViewResolver
    }

    private Response runPipeline(Request request, Route route) {
        List<Middleware> pipes = applyExclusions(request, middleware.resolve(route));

        return Pipeline.<Request, Response>of(request)
                .through(pipes)
                .then(req -> invokeController(req, route));
    }

    /**
     * 滤掉本请求显式排除的中间件。
     *
     * <p>在**解析之后**过滤而不是在解析时排除:解析结果是按路由缓存的
     * (见 {@link #EXCLUDED_MIDDLEWARE_ATTRIBUTE}),按请求变化的东西
     * 只能在缓存之外处理。
     */
    @SuppressWarnings("unchecked")
    private List<Middleware> applyExclusions(Request request, List<Middleware> pipes) {
        var raw = request.raw().orElse(null);
        if (!(raw instanceof HttpServletRequest servletRequest)) return pipes;

        var excluded = servletRequest.getAttribute(EXCLUDED_MIDDLEWARE_ATTRIBUTE);
        if (!(excluded instanceof java.util.Set<?> names) || names.isEmpty()) return pipes;

        return pipes.stream()
                .filter(m -> !names.contains(m.getClass().getSimpleName())
                          && !names.contains(m.getClass().getName()))
                .toList();
    }

    private Response invokeController(Request request, Route route) {
        Object result;

        if (route.isDirect()) {
            // r.view(...) / r.redirect(...) —— 无控制器
            result = route.directHandler().apply(request);
        } else {
            Object controller = container.make(route.controller());
            Object[] args = ArgumentBinder.bind(request, route, container);
            result = route.invoker().invoke(controller, args);
        }

        return ReturnValueNormalizer.normalize(result, container);
    }

    @Override
    @Deprecated
    public long getLastModified(HttpServletRequest request, Object handler) {
        return -1;
    }

    /** 解析路由应经过的中间件链 */
    public interface MiddlewareResolver {
        List<Middleware> resolve(Route route);

        /**
         * 只有全局中间件,不解析路由级声明。
         *
         * <p>保留它只为「没有 Kernel 也能跑」与测试;真实应用走
         * {@link #of(io.jaravel.http.MiddlewareStack)}。
         */
        static MiddlewareResolver ofGlobalOnly(List<Middleware> global) {
            var snapshot = List.copyOf(global);
            return route -> snapshot;
        }

        /**
         * 走 {@link io.jaravel.http.MiddlewareStack}:全局 + 分组 + 别名 + 参数化 + 优先级。
         *
         * <p><b>按路由缓存</b>。中间件链在路由注册完成后不再变化,每个请求重新
         * 解析一遍是纯浪费 —— 而解析里含分组展开、去重、优先级重排,不便宜。
         */
        static MiddlewareResolver of(io.jaravel.http.MiddlewareStack stack) {
            var cache = new java.util.concurrent.ConcurrentHashMap<Route, List<Middleware>>();
            return route -> cache.computeIfAbsent(route,
                    r -> stack.resolve(r.middlewareNames(), r.excludedMiddleware()));
        }
    }
}
