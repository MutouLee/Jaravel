package io.jaravel.spring;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

import io.jaravel.routing.RouteCollection;

/**
 * 把请求匹配到 Jaravel 路由表。
 *
 * <p>优先级高于 Spring 的 {@code RequestMappingHandlerMapping} ——
 * Jaravel 路由优先,**未命中时返回 null 自动回落给 Spring**。
 * 这正是 ADR-001 承诺的「照常能写 @RestController」的实现方式,
 * 也是最重要的逃生口。
 */
public class JaravelHandlerMapping implements HandlerMapping, Ordered {

    /** Jaravel 的匹配结果,交给 {@link JaravelHandlerAdapter} 执行 */
    public record Handler(RouteCollection.Match match) {}

    private final RouteCollection routes;

    public JaravelHandlerMapping(RouteCollection routes) {
        this.routes = routes;
    }

    @Override
    public HandlerExecutionChain getHandler(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        var match = routes.match(request.getMethod(), path);
        if (match.isEmpty()) {
            // 未命中则返回 null,交给 Spring 的后续 HandlerMapping —— 这是回落路径
            return null;
        }
        return new HandlerExecutionChain(new Handler(match.get()));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    public RouteCollection routes() {
        return routes;
    }
}
