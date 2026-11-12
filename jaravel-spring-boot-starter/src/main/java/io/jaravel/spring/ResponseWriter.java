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
        // ★ ETag 必须在这里算 —— 这是唯一拿得到**最终字节**的位置。
        //
        // 理由写在 Response.etag() 上:响应体在那之前还是一个没序列化的对象,
        // 而一个算错的 ETag 比没有 ETag 糟得多(它会让客户端缓存住旧内容,
        // 而那种 bug 在开发期总是强刷的情况下完全看不见)。
        if (response.wantsEtag()) {
            return serializeConditional(response, servletResponse);
        }
        return write(response, servletResponse);
    }

    /**
     * 带 ETag 的那条路:先渲染出字节、算摘要、再决定是 200 还是 304。
     *
     * <h2>为什么 304 要先渲染一遍</h2>
     * <p>看起来浪费 —— 都要回 304 了还序列化干什么。但 ETag 的定义就是
     * **当前内容的摘要**:不渲染就不知道内容有没有变。
     *
     * <p>省掉的是**网络传输**,那通常才是瓶颈(一个 200KB 的 JSON 列表)。
     * 真要省掉渲染,得由调用方给一个便宜的 ETag
     * ({@code .etag(post.updatedAt().toString())})—— 那条路也留了。
     */
    private byte[] serializeConditional(Response response, HttpServletResponse servletResponse)
            throws IOException {

        String tag = response.explicitEtag();
        byte[] body = null;

        if (tag == null) {
            body = renderToBytes(response, servletResponse);
            tag = weakEtagOf(body);
        }

        var request = currentServletRequest();
        String ifNoneMatch = request == null ? null : request.getHeader("If-None-Match");
        if (ifNoneMatch != null && matches(ifNoneMatch, tag)) {
            // 304 不带响应体。头要保留 ETag 与缓存指令 ——
            // 客户端下一次还要用它们
            servletResponse.setStatus(304);
            servletResponse.setHeader("ETag", tag);
            response.headers().forEach((name, values) -> {
                if (name.equalsIgnoreCase("Cache-Control")
                        || name.equalsIgnoreCase("Last-Modified")
                        || name.equalsIgnoreCase("Vary")) {
                    values.forEach(v -> servletResponse.addHeader(name, v));
                }
            });
            return new byte[0];
        }

        byte[] written = write(response, servletResponse);
        servletResponse.setHeader("ETag", tag);
        return body != null ? body : written;
    }

    /**
     * 弱 ETag。
     *
     * <p>用 {@code W/} 前缀是诚实的:强 ETag 要求**字节级**相同,
     * 而我们这里的字节来自 JSON 序列化 —— 同一份数据在不同的
     * ObjectMapper 配置下字节不同。声明成弱的,语义就对了
     * (RFC 9110 §8.8.1:弱 ETag 表示「语义等价」)。
     */
    private static String weakEtagOf(byte[] body) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body);
            var hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return "W/\"" + hex + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("这个 JVM 没有 SHA-256", e);
        }
    }

    /**
     * {@code If-None-Match} 的匹配。
     *
     * <p>要处理三件事:多个值用逗号分隔、{@code *} 匹配一切、
     * 以及**比较时忽略 {@code W/} 前缀**(RFC 9110 §8.8.3.2 的弱比较)。
     * 只做字符串相等的话,客户端回传的 {@code W/"abc"} 与我们算出的
     * {@code W/"abc"} 能对上,但一旦经过某个会改写前缀的代理就对不上了 ——
     * 症状是「缓存永远不命中」,而那不会报错。
     */
    private static boolean matches(String ifNoneMatch, String tag) {
        String normalized = strip(tag);
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.equals("*")) return true;
            if (strip(trimmed).equals(normalized)) return true;
        }
        return false;
    }

    private static String strip(String etag) {
        String out = etag.startsWith("W/") ? etag.substring(2) : etag;
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        return out;
    }

    private byte[] renderToBytes(Response response, HttpServletResponse servletResponse)
            throws IOException {
        if (response.body() instanceof Response.Body.Bytes b) return b.content();
        String rendered = renderBody(response, servletResponse);
        return rendered == null || rendered.isEmpty()
                ? new byte[0]
                : rendered.getBytes(StandardCharsets.UTF_8);
    }

    /** 当前请求 —— 条件请求要读 If-None-Match */
    private static jakarta.servlet.http.HttpServletRequest currentServletRequest() {
        return io.jaravel.http.RequestScope.tryRequest()
                .flatMap(io.jaravel.http.Request::raw)
                .filter(raw -> raw instanceof jakarta.servlet.http.HttpServletRequest)
                .map(raw -> (jakarta.servlet.http.HttpServletRequest) raw)
                .orElse(null);
    }

    private byte[] write(Response response, HttpServletResponse servletResponse) throws IOException {
        servletResponse.setStatus(response.status());

        boolean binary = response.body() instanceof Response.Body.Bytes;

        if (response.contentType() != null) {
            if (binary) {
                // ★ 二进制要先**清掉字符编码**,再设 Content-Type。
                //
                // Spring Boot 默认装了 CharacterEncodingFilter 且
                // forceResponseEncoding=true,于是容器把当前编码拼到
                // Content-Type 上 —— 一张 PNG 变成
                // "image/png;charset=UTF-8"。
                //
                // 那对二进制类型没有意义(PNG 没有字符编码),
                // 而且部分客户端会据此认为响应是文本。
                //
                // ⚠ 改用 setHeader("Content-Type", ...) **不管用** ——
                // Tomcat 对 Content-Type 这个头做了特判,setHeader
                // 最终还是走回 setContentType。第一次就是那样写的,
                // 测试照样看到 charset。
                //
                // setCharacterEncoding(null) 在 Servlet 6.1 里才被明确
                // 允许,所以包一层:拼上 charset 只是不规范,
                // 不该让一次下载失败
                try {
                    servletResponse.setCharacterEncoding((String) null);
                } catch (RuntimeException ignored) {
                    // 见上
                }
                servletResponse.setContentType(response.contentType());
            } else {
                servletResponse.setContentType(response.contentType());
            }
        }
        response.headers().forEach((name, values) ->
                values.forEach(v -> servletResponse.addHeader(name, v)));

        response.cookies().forEach(c -> servletResponse.addHeader("Set-Cookie", cookieHeader(c)));

        // 二进制正文直接走,不经过 String ——
        // 一个「字节 → String → 字节」的往返会把所有不是合法 UTF-8 的
        // 字节替换成 U+FFFD,于是下载下来的 PNG / PDF 坏掉
        if (response.body() instanceof Response.Body.Bytes b) {
            return b.content();
        }

        String body = renderBody(response, servletResponse);
        return body == null || body.isEmpty()
                ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);
    }

    public void flush(byte[] body, HttpServletResponse servletResponse) throws IOException {
        servletResponse.setContentLength(body.length);
        if (body.length > 0) servletResponse.getOutputStream().write(body);
    }

    private String renderBody(Response response, HttpServletResponse servletResponse)
            throws IOException {
        return switch (response.body()) {
            case Response.Body.None n -> null;
            case Response.Body.Text t -> t.content();
            case Response.Body.ViewRef v -> views.render(v.name(), v.data());
            case Response.Body.Json j -> json.writeValueAsString(j.value());
            case Response.Body.Negotiated n -> renderNegotiated(n, servletResponse);

            // 到不了这里 —— serialize 在调 renderBody 之前就把它拦走了。
            // 写成抛异常而不是「转成 String」:后者正是这个分支要防的那个 bug
            case Response.Body.Bytes b -> throw new IllegalStateException(
                    "二进制正文不该走 renderBody —— 见 serialize 里的那个分支。");
        };
    }

    /**
     * 内容协商(docs/12 §6.2)。
     *
     * <p>{@code Accept} 从当前请求作用域读。没有可接受的格式时回 406,
     * 而不是「悄悄退回 JSON」—— 后者会让调用方以为自己拿到的是 XML。
     */
    private String renderNegotiated(Response.Body.Negotiated body,
                                    HttpServletResponse servletResponse) throws IOException {
        addVary(servletResponse, "Accept");

        var enabled = body.formats().isEmpty()
                ? java.util.Set.of(Response.Format.JSON)
                : body.formats();
        Response.Format format = ContentNegotiation.select(enabled, acceptHeader());

        if (format == null) {
            servletResponse.setStatus(406);
            servletResponse.setContentType("application/json;charset=UTF-8");
            var supported = enabled.stream()
                    .map(Response.Format::mediaType)
                    .sorted()
                    .toList();
            var error = new java.util.LinkedHashMap<String, Object>();
            error.put("message", "Not Acceptable");
            error.put("supported", supported);
            return json.writeValueAsString(error);
        }

        servletResponse.setContentType(format.mediaType() + ";charset=UTF-8");

        return switch (format) {
            case JSON -> json.writeValueAsString(body.value());
            case XML -> ContentNegotiation.xml(json, body.value());
            case CSV -> ContentNegotiation.csv(json, body.value(), body.csvHeaders());
        };
    }

    private static String acceptHeader() {
        return io.jaravel.http.RequestScope.tryRequest()
                .flatMap(request -> request.header("Accept"))
                .orElse(null);
    }

    /** {@code Vary: Accept} —— 同一 URL 的响应随 Accept 变化,缓存必须知道 */
    private static void addVary(HttpServletResponse response, String value) {
        String current = response.getHeader("Vary");
        if (current == null || current.isBlank()) {
            response.setHeader("Vary", value);
            return;
        }
        for (String existing : current.split(",")) {
            if (existing.trim().equalsIgnoreCase(value)) return;
        }
        response.setHeader("Vary", current + ", " + value);
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
