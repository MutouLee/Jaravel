package io.jaravel.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.http.Cookie;
import io.jaravel.http.Response;
import io.jaravel.view.ViewEngine;

/**
 * {@link Response} → {@code HttpServletResponse}。
 *
 * <p>视图渲染与 JSON 序列化在这里发生 —— {@code jaravel-http} 只持有
 * 「要渲染哪个视图」「要序列化哪个对象」的意图,不碰具体实现。
 */
public class ResponseWriter {

    private final ViewEngine views;
    private final ObjectMapper json;
    private final boolean secureCookies;

    public ResponseWriter(ViewEngine views, ObjectMapper json, boolean secureCookies) {
        this.views = views;
        this.json = json;
        this.secureCookies = secureCookies;
    }

    /**
     * 把响应的状态、头、Cookie 写到 servlet 响应上,并把**正文渲染成字节**返回。
     *
     * <p>刻意拆成 serialize / flush 两步:渲染(模板编译、JSON 序列化)可能抛异常,
     * 必须发生在调用方的异常处理区域之内;而真正写出字节不会抛业务异常。
     */
    public byte[] serialize(Response response, HttpServletResponse servletResponse) throws IOException {
        servletResponse.setStatus(response.status());

        if (response.contentType() != null) {
            servletResponse.setContentType(response.contentType());
        }
        response.headers().forEach((name, values) ->
                values.forEach(v -> servletResponse.addHeader(name, v)));

        response.cookies().forEach(c -> servletResponse.addHeader("Set-Cookie", cookieHeader(c)));

        String body = renderBody(response);
        return body == null || body.isEmpty()
                ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);
    }

    public void flush(byte[] body, HttpServletResponse servletResponse) throws IOException {
        servletResponse.setContentLength(body.length);
        if (body.length > 0) servletResponse.getOutputStream().write(body);
    }

    private String renderBody(Response response) throws IOException {
        return switch (response.body()) {
            case Response.Body.None n -> null;
            case Response.Body.Text t -> t.content();
            case Response.Body.ViewRef v -> views.render(v.name(), v.data());
            case Response.Body.Json j -> json.writeValueAsString(j.value());
        };
    }

    /**
     * 手写 Set-Cookie 而不用 {@code servletResponse.addCookie} ——
     * Servlet 的 Cookie API 对 SameSite 支持不一致。
     */
    private String cookieHeader(Cookie c) {
        var sb = new StringBuilder();
        sb.append(c.name()).append('=').append(c.value() == null ? "" : c.value());
        if (c.path() != null)   sb.append("; Path=").append(c.path());
        if (c.domain() != null) sb.append("; Domain=").append(c.domain());
        if (c.maxAge() != null) sb.append("; Max-Age=").append(c.maxAge().toSeconds());
        if (c.httpOnly())       sb.append("; HttpOnly");
        // Secure 由环境决定:local 关闭,否则开启(默认安全,显式才放松)
        if (c.secure() || secureCookies) sb.append("; Secure");
        if (c.sameSite() != null) sb.append("; SameSite=").append(c.sameSite().headerValue());
        return sb.toString();
    }
}
