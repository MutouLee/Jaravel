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

                if (contentType.contains("multipart/form-data")) {
                    readFileParts(servletRequest, request);
                }
            }
        // JacksonException 继承自 IOException,必须先捕获,否则会被后者吞掉
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new io.jaravel.http.HttpException(400,
                    "请求体不是合法的 JSON:" + e.getOriginalMessage());
        } catch (IOException e) {
            throw new io.jaravel.http.HttpException(400, "无法读取请求体:" + e.getMessage());
        }
    }

    /**
     * 把 multipart 里的**文件** part 读成 {@link io.jaravel.http.UploadedFile}。
     *
     * <h2>怎么区分「文件 part」与「普通表单字段 part」</h2>
     * <p>看 {@code getSubmittedFileName() != null}。
     *
     * <p>不看 part 有没有 {@code Content-Type}:一个普通的文本字段
     * 也可能带 {@code Content-Type: text/plain}(某些客户端会加),
     * 于是那个字段会同时出现在 {@code input("name")} 与
     * {@code file("name")} 里 —— 后者是错的。
     *
     * <p>Servlet 规范里 {@code submittedFileName} 来自
     * {@code Content-Disposition} 的 {@code filename} 参数,
     * 而那个参数只有文件 part 才有。
     *
     * <h2>大小限制不在这里</h2>
     * <p>这里直接 {@code readAllBytes()},**没有**大小检查。
     *
     * <p>理由:检查在这里做已经太晚 —— 容器为了让
     * {@code getParts()} 可用,已经把整个请求体落到临时文件/内存里了。
     * 一个在这一层的检查只能在「内存已经吃完之后」报错。
     *
     * <p>真正的限制由 {@code MultipartConfigElement} 施加
     * (见 {@code JaravelUploadsAutoConfiguration}),它由容器在
     * **读取流的过程中**执行,超限时直接中断连接。
     * 那个异常会被映射成 413。
     *
     * <p>所以这个方法里的 {@code readAllBytes} 是安全的:
     * 能走到这里说明请求已经过了那道闸。
     */
    private static void readFileParts(HttpServletRequest servletRequest,
                                      BasicRequest request) {
        java.util.Collection<jakarta.servlet.http.Part> parts;
        try {
            parts = servletRequest.getParts();
        } catch (jakarta.servlet.ServletException e) {
            // 容器拒绝解析 —— 最常见的原因是 multipart 支持没开
            // (spring.servlet.multipart.enabled=false),
            // 其次是畸形的 boundary
            throw new io.jaravel.http.HttpException(400, """
                    无法解析 multipart 请求体:%s
                      如果是「没开 multipart 支持」,检查
                      spring.servlet.multipart.enabled 是不是被关掉了。"""
                    .formatted(e.getMessage()));
        } catch (IOException e) {
            throw new io.jaravel.http.HttpException(400,
                    "读取 multipart 请求体失败:" + e.getMessage());
        }

        for (var part : parts) {
            String fileName = part.getSubmittedFileName();
            if (fileName == null) continue;   // 普通表单字段,上面已经收了

            byte[] content;
            try (var in = part.getInputStream()) {
                content = in.readAllBytes();
            } catch (IOException e) {
                throw new io.jaravel.http.HttpException(400,
                        "读取上传的文件 \"%s\" 失败:%s"
                                .formatted(part.getName(), e.getMessage()));
            }

            request.file(new io.jaravel.http.UploadedFile(
                    part.getName(), fileName, part.getContentType(), content));

            // 临时文件立刻删掉 —— 内容已经在内存里了。
            //
            // 不删的后果:容器在请求结束时才清理,而一个高并发的
            // 上传接口会在那之前堆满 /tmp。
            // delete() 的异常被忽略:内容已经读到了,
            // 一个「临时文件删不掉」不该让一次成功的上传失败
            try {
                part.delete();
            } catch (IOException ignored) {
                // 见上
            }
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
