package io.jaravel.spring;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求 ID 与链路追踪(docs/12 §6.4)。
 *
 * <p>每个请求分配一个 {@code X-Request-Id},**或者沿用上游传进来的**,
 * 写进响应头、日志上下文(MDC)与错误响应体。
 *
 * <h2>★ 沿用上游的那一个,而不是每一跳都换</h2>
 * <p>一个请求经过网关 → 这个服务 → 下游服务。每一跳都生成新 id 的话,
 * 那个 id 就只能定位**一跳**;而排查的问题恰恰是「用户那次下单到底
 * 在哪一环挂了」。所以上游带了就用上游的 —— 这是请求 ID 的全部意义。
 *
 * <p>⚠ 但要**校验格式**:这个值会进日志、进响应头。不校验的话,
 * 一个带换行的 {@code X-Request-Id} 可以往响应里注入额外的头
 * (HTTP 响应拆分),或者往日志里注入伪造的行。
 * 所以只接受「长度合理的可见 ASCII」,其余一律重新生成。
 *
 * <h2>为什么也是 Servlet Filter</h2>
 * <p>与 CORS 同一条分层理由({@code Middleware} 的类注释):
 * 请求 ID 必须覆盖**每一个**响应,包括没匹配上路由的 404、
 * CORS 预检、以及静态资源 —— 那些都不走中间件管道。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** 请求属性名 —— 异常处理器要把它放进错误响应体 */
    public static final String ATTRIBUTE = "jaravel.request-id";

    /** MDC 的键。用 {@code requestId} 而不是带横线的头名:日志格式里好引用 */
    public static final String MDC_KEY = "requestId";

    private static final int MAX_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String id = sanitize(request.getHeader(HEADER));
        if (id == null) id = generate();

        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);

        org.slf4j.MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // ★ 必须清:线程是复用的(线程池 / 虚拟线程载体也一样)。
            // 不清的话下一个请求的日志会带上**上一个请求的 id** ——
            // 而那种串台比没有 id 更糟:它会把排查引向完全无关的另一次请求。
            org.slf4j.MDC.remove(MDC_KEY);
        }
    }

    /**
     * 上游传来的值可信吗。
     *
     * @return 清洗后的值;不可接受时返回 null(调用方会重新生成)
     */
    static String sanitize(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_LENGTH) return null;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // 只要可见 ASCII。挡掉的重点是 \r \n(响应头注入、日志注入)
            if (c < 0x21 || c > 0x7E) return null;
        }
        return raw;
    }

    /**
     * 生成一个 id。
     *
     * <p>用 UUID 而不是文档里提的 ULID:ULID 要自己实现(JDK 没有),
     * 而它的好处是**按时间可排序** —— 那对日志检索有用,但不值得
     * 为它引一个依赖或手写一份。这笔账记在这里,将来要换是一行的事。
     */
    private static String generate() {
        return java.util.UUID.randomUUID().toString();
    }

    /** 当前请求的 id —— 没有(不在请求里)时是 null */
    public static String current() {
        return io.jaravel.http.RequestScope.tryRequest()
                .flatMap(io.jaravel.http.Request::raw)
                .filter(raw -> raw instanceof HttpServletRequest)
                .map(raw -> (String) ((HttpServletRequest) raw).getAttribute(ATTRIBUTE))
                .orElse(null);
    }
}
