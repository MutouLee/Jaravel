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

    /**
     * 路径存在、但这个方法不对 —— 405,而不是 404。
     *
     * <h2>★ 为什么这个区别值得一个专门的类型</h2>
     * <p>404 说的是「这个地址不存在」,405 说的是「这个地址存在,
     * 但你不能用 POST 访问它」。对调用方是两件完全不同的事:
     * 前者要去查 URL 拼错了没,后者要去查自己用的动词。
     *
     * <p>而 {@code Allow} 头直接给出答案(RFC 9110 要求 405 必须带它)——
     * 一个 {@code curl -X POST} 的人立刻能看到「这个地址只接受 GET」。
     *
     * <p>⚠ 这条路径此前**不存在**:未命中就 return null,于是 Spring
     * 回落到它的默认处理,最终是 404。而 {@code RouteCollection.allowedMethods}
     * 这个方法就是为 405 写的,一直只有单元测试在用
     * (它的注释里写着「用于 405 与 Allow 头」)。
     */
    public record MethodNotAllowed(String path, java.util.Set<String> allowed) {}

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
            // 路径存在但方法不对 → 405 + Allow,而不是落进 404
            var allowed = routes.allowedMethods(path);
            if (!allowed.isEmpty()) {
                return new HandlerExecutionChain(new MethodNotAllowed(path, allowed));
            }
            // 真的没有这个路径:返回 null,交给 Spring 的后续 HandlerMapping
            // (静态资源、actuator 等都在那后面)—— 这是回落路径
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
