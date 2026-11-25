package io.jaravel.spring;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import io.jaravel.http.SecurityHeaders;

/**
 * 把 {@link SecurityHeaders} 的策略贴到每一个响应上(SECURITY-AUDIT §6)。
 *
 * <h2>★ 为什么是 Filter</h2>
 * <p>最需要这些头的恰恰是**错误响应**:401 / 419 / 404 / 500 的页面同样会被
 * 浏览器解析。而它们是用异常表达的,响应在中间件链 unwind **之后**才由
 * ExceptionHandler 造出来 —— 一个中间件永远碰不到它们
 * (这是第一版写成全局中间件时被一条验收测试当场抓住的)。
 * 静态资源与没匹配上路由的请求更是连中间件都不经过。
 *
 * <p>与 {@link CorsFilter} / {@link RequestIdFilter} 同一层,理由同一条:
 * {@code Middleware} 的类注释里写的「真正需要全局拦截的横切关注点由 starter
 * 注册为 Servlet Filter」。
 *
 * <h2>在链的最前面就设</h2>
 * <p>不是等 {@code doFilter} 返回之后再设:响应一旦**已提交**
 * (body 开始写出)就不能再加头了,而静态资源与异步写出都可能提前提交。
 * 提前设的代价是「控制器想覆盖它」时要让位 —— 由
 * {@code response.containsHeader} 判断,见下。
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private final SecurityHeaders policy;

    public SecurityHeadersFilter(SecurityHeaders policy) {
        this.policy = policy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        policy.headersFor(request.isSecure()).forEach((name, value) -> {
            // 已经有同名头就让位 —— 可能是容器配的,也可能是上游 filter 设的。
            // 控制器的覆盖走的是另一条路:它设的是 Jaravel 的 Response,
            // ResponseWriter 用 setHeader 写出,会替换掉这里的值
            if (!response.containsHeader(name)) response.setHeader(name, value);
        });

        chain.doFilter(request, response);
    }
}
