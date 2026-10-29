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
            Response response;
            try {
                response = runPipeline(request, route);
            } catch (Throwable t) {
                response = exceptionHandler.render(request, t);
            }

            try {
                return writer.serialize(response, servletResponse);
            } catch (Throwable renderFailure) {
                // 渲染本身失败(模板错误 / 序列化失败)→ 交给 ExceptionHandler 重新渲染。
                // 此时不能再用视图,否则可能二次失败,因此走纯文本/JSON 错误页。
                var fallback = exceptionHandler.render(request, renderFailure);
                return writer.serialize(fallback, servletResponse);
            }
        });

        writer.flush(body, servletResponse);
        return null;   // 已自行写出,不交给 ViewResolver
    }

    private Response runPipeline(Request request, Route route) {
        List<Middleware> pipes = middleware.resolve(route);

        return Pipeline.<Request, Response>of(request)
                .through(pipes)
                .then(req -> invokeController(req, route));
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

        /** v0.1 的默认实现:只有全局中间件,别名中间件待 v0.3 的认证模块 */
        static MiddlewareResolver of(List<Middleware> global) {
            var snapshot = List.copyOf(global);
            return route -> {
                var out = new ArrayList<Middleware>(snapshot);
                // 路由级中间件的别名解析在 v0.3 随 Kernel 一起引入
                return out;
            };
        }
    }
}
