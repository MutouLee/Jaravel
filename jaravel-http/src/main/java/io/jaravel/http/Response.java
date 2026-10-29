package io.jaravel.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 响应。**不含任何 Servlet 类型** —— starter 负责写出到 {@code HttpServletResponse}。
 *
 * <p>是个可变的值对象:控制器构造它,中间件的回程可以继续加头/Cookie。
 */
public class Response {

    /** 响应体的三种形态 */
    public sealed interface Body {
        /** 已就绪的字符串(JSON / HTML / 文本) */
        record Text(String content) implements Body {}
        /** 待渲染的视图,由视图层处理 */
        record ViewRef(String name, Map<String, Object> data) implements Body {}
        /** 待序列化的对象,由 starter 的 JSON 层处理 */
        record Json(Object value) implements Body {}
        /** 空 */
        record None() implements Body {}
    }

    private int status = 200;
    private String contentType;
    private Body body = new Body.None();
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final List<Cookie> cookies = new ArrayList<>();

    protected Response() {}

    // ---------- 工厂 ----------

    public static Response json(Object value) {
        var r = new Response();
        r.body = new Body.Json(value);
        r.contentType = "application/json;charset=UTF-8";
        return r;
    }

    public static Response text(String content) {
        var r = new Response();
        r.body = new Body.Text(content == null ? "" : content);
        r.contentType = "text/plain;charset=UTF-8";
        return r;
    }

    public static Response html(String content) {
        var r = new Response();
        r.body = new Body.Text(content == null ? "" : content);
        r.contentType = "text/html;charset=UTF-8";
        return r;
    }

    public static Response noContent() {
        var r = new Response();
        r.status = 204;
        return r;
    }

    public static Response of(Body body, int status) {
        var r = new Response();
        r.body = body;
        r.status = status;
        return r;
    }

    /** 立即中止请求并返回指定状态码。抛异常,由 ExceptionHandler 渲染。 */
    public static void abort(int status) {
        throw new HttpException(status);
    }

    public static void abort(int status, String message) {
        throw new HttpException(status, message);
    }

    public static void abortIf(boolean condition, int status, String message) {
        if (condition) throw new HttpException(status, message);
    }

    public static void abortUnless(boolean condition, int status, String message) {
        if (!condition) throw new HttpException(status, message);
    }

    // ---------- 链式配置 ----------

    public Response status(int s) {
        this.status = s;
        return this;
    }

    public Response contentType(String ct) {
        this.contentType = ct;
        return this;
    }

    public Response header(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    public Response headers(Map<String, String> h) {
        h.forEach(this::header);
        return this;
    }

    public Response cookie(Cookie c) {
        cookies.add(c);
        return this;
    }

    public Response cookie(String name, String value) {
        return cookie(Cookie.make(name, value));
    }

    // ---------- 读取 ----------

    public int status()                        { return status; }
    public String contentType()                { return contentType; }
    public Body body()                         { return body; }
    public Map<String, List<String>> headers()  { return Map.copyOf(headers); }
    public List<Cookie> cookies()               { return List.copyOf(cookies); }

    public boolean isRedirect() {
        return status >= 300 && status < 400;
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    /** 已就绪的文本内容。视图与 JSON 需先经渲染/序列化,此处返回空。 */
    public String textContent() {
        return body instanceof Body.Text t ? t.content() : "";
    }

    /** 替换响应体。视图渲染与 JSON 序列化后由 starter 调用。 */
    public Response withRenderedBody(String content) {
        this.body = new Body.Text(content);
        return this;
    }

    @Override public String toString() {
        return "Response(" + status + ", " + body.getClass().getSimpleName() + ")";
    }
}
