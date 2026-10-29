package io.jaravel.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import io.jaravel.http.BasicRequest;

/**
 * {@code HttpServletRequest} → Jaravel {@link io.jaravel.http.Request}。
 *
 * <p>这是 ADR-001 的边界所在:Servlet 类型只出现在这一侧,
 * Jaravel 的其余部分完全看不到它。
 */
final class ServletRequestAdapter {

    private ServletRequestAdapter() {}

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static BasicRequest adapt(HttpServletRequest servletRequest) {
        var request = BasicRequest.of(servletRequest.getMethod(), servletRequest.getRequestURI())
                .scheme(servletRequest.getScheme())
                .host(servletRequest.getServerName())
                .port(servletRequest.getServerPort())
                .ip(clientIp(servletRequest))
                .raw(servletRequest);

        // 头
        var headerNames = servletRequest.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            var values = servletRequest.getHeaders(name);
            while (values.hasMoreElements()) request.header(name, values.nextElement());
        }

        // 查询串
        request.parseQueryString(servletRequest.getQueryString());

        // Cookie
        if (servletRequest.getCookies() != null) {
            for (var c : servletRequest.getCookies()) request.cookie(c.getName(), c.getValue());
        }

        // 请求体
        readBody(servletRequest, request);

        return request;
    }

    private static void readBody(HttpServletRequest servletRequest, BasicRequest request) {
        String contentType = servletRequest.getContentType();
        if (contentType == null) return;

        try {
            if (contentType.contains("/json") || contentType.contains("+json")) {
                String body = new String(servletRequest.getInputStream().readAllBytes(),
                                         StandardCharsets.UTF_8);
                request.rawBody(body);
                if (!body.isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = JSON.readValue(body, Map.class);
                    request.bodyParams(parsed);
                }
            } else if (contentType.contains("application/x-www-form-urlencoded")
                    || contentType.contains("multipart/form-data")) {
                // Servlet 容器已解析表单参数
                servletRequest.getParameterMap().forEach((k, v) -> {
                    if (v.length == 1) request.bodyParam(k, v[0]);
                    else request.bodyParam(k, java.util.List.of(v));
                });
            }
        // JacksonException 继承自 IOException,必须先捕获,否则会被后者吞掉
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new io.jaravel.http.HttpException(400,
                    "请求体不是合法的 JSON:" + e.getOriginalMessage());
        } catch (IOException e) {
            throw new io.jaravel.http.HttpException(400, "无法读取请求体:" + e.getMessage());
        }
    }

    /** 尊重反向代理头,但只在 TrustProxies 允许时(v0.1 简化:直接取第一跳) */
    private static String clientIp(HttpServletRequest r) {
        String forwarded = r.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return r.getRemoteAddr();
    }
}
