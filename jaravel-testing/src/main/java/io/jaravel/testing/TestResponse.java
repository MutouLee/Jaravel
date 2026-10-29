package io.jaravel.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.session.SessionData;

/**
 * 一次测试请求的响应,带断言。
 *
 * <pre>{@code
 * get("/posts")
 *     .assertOk()
 *     .assertSee("第一篇")
 *     .assertJsonPath("data.0.title", "第一篇");
 * }</pre>
 *
 * <h2>所有断言都返回 this</h2>
 * <p>一条链读起来就是一句话。这不只是语法糖:它让「一个功能测试 ≤ 5 行」
 * 这个目标(docs/14 设计目标第 1 条)成立,而行数直接决定人愿不愿意写测试。
 *
 * <h2>断言失败时打印整个响应</h2>
 * <p>HTTP 测试失败时最常问的是「那它到底返回了什么」。每条断言都在
 * 失败消息里带上状态码与响应体前若干字符 —— 否则排查第一步总是
 * 「加一行 System.out.println(response)」。
 */
public final class TestResponse {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    /** 失败消息里最多带多少响应体 —— 全量会把控制台冲掉 */
    private static final int BODY_PREVIEW = 2000;

    private final MockHttpServletResponse raw;
    private final String content;
    private final SessionData session;
    private final io.jaravel.http.Response jaravelResponse;

    TestResponse(MockHttpServletResponse raw, String content, SessionData session,
                 io.jaravel.http.Response jaravelResponse) {
        this.raw = raw;
        this.content = content == null ? "" : content;
        this.session = session;
        this.jaravelResponse = jaravelResponse;
    }

    // ================= 原始数据 =================

    public int status() {
        return raw.getStatus();
    }

    public String content() {
        return content;
    }

    public MockHttpServletResponse raw() {
        return raw;
    }

    /** 本次请求结束时的会话。没有会话中间件时为空。 */
    public Optional<SessionData> session() {
        return Optional.ofNullable(session);
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(raw.getHeader(name));
    }

    // ================= 状态 =================

    public TestResponse assertStatus(int expected) {
        if (status() != expected) {
            fail("期望状态 %d,实际 %d".formatted(expected, status()));
        }
        return this;
    }

    public TestResponse assertOk()           { return assertStatus(200); }
    public TestResponse assertCreated()      { return assertStatus(201); }
    public TestResponse assertNoContent()    { return assertStatus(204); }
    public TestResponse assertNotFound()     { return assertStatus(404); }
    public TestResponse assertForbidden()    { return assertStatus(403); }
    public TestResponse assertUnauthorized() { return assertStatus(401); }
    public TestResponse assertUnprocessable() { return assertStatus(422); }

    public TestResponse assertSuccessful() {
        if (status() < 200 || status() >= 300) {
            fail("期望 2xx,实际 %d".formatted(status()));
        }
        return this;
    }

    /**
     * 断言重定向到某个地址。
     *
     * <p>只比对**路径**:框架给出的 Location 可能是绝对 URL
     * (`Redirect.to(intendedUrl())` 存的就是 fullUrl),而测试关心的
     * 几乎总是「跳到哪个页面」。要比对完整 URL 用
     * {@link #assertHeader}。
     */
    public TestResponse assertRedirect(String expectedPath) {
        if (status() < 300 || status() >= 400) {
            fail("期望 3xx 重定向,实际 %d".formatted(status()));
        }
        String location = raw.getHeader("Location");
        if (location == null) {
            fail("3xx 响应没有 Location 头");
        }
        if (!pathOf(location).equals(expectedPath)) {
            fail("期望重定向到 %s,实际 %s".formatted(expectedPath, location));
        }
        return this;
    }

    private static String pathOf(String location) {
        int schemeEnd = location.indexOf("://");
        if (schemeEnd < 0) {
            int query = location.indexOf('?');
            return query < 0 ? location : location.substring(0, query);
        }
        int pathStart = location.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) return "/";
        String path = location.substring(pathStart);
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    // ================= 内容 =================

    public TestResponse assertSee(String text) {
        if (!content.contains(text)) {
            fail("响应体里找不到 \"%s\"".formatted(text));
        }
        return this;
    }

    public TestResponse assertDontSee(String text) {
        if (content.contains(text)) {
            fail("响应体里不该出现 \"%s\",但出现了".formatted(text));
        }
        return this;
    }

    /** 按顺序出现。用于断言列表的排序。 */
    public TestResponse assertSeeInOrder(List<String> texts) {
        int cursor = 0;
        for (String text : texts) {
            int at = content.indexOf(text, cursor);
            if (at < 0) {
                fail("按顺序找 %s 时,在位置 %d 之后找不到 \"%s\""
                        .formatted(texts, cursor, text));
            }
            cursor = at + text.length();
        }
        return this;
    }

    /**
     * 剥掉 HTML 标签后比对。
     *
     * <p>必要性:模板渲染出来的文本常常被标签切断
     * ({@code <b>第一</b>篇}),而用户看到的是「第一篇」。
     * 直接 contains 会失败,然后让人以为是数据不对。
     */
    public TestResponse assertSeeText(String text) {
        String stripped = content.replaceAll("<[^>]*>", "");
        if (!stripped.contains(text)) {
            fail("剥离标签后找不到 \"%s\"".formatted(text));
        }
        return this;
    }

    public TestResponse assertHeader(String name, String expected) {
        String actual = raw.getHeader(name);
        if (!expected.equals(actual)) {
            fail("头 %s 期望 \"%s\",实际 \"%s\"".formatted(name, expected, actual));
        }
        return this;
    }

    public TestResponse assertHeaderMissing(String name) {
        if (raw.getHeader(name) != null) {
            fail("不该有头 %s,实际 \"%s\"".formatted(name, raw.getHeader(name)));
        }
        return this;
    }

    // ================= Cookie =================

    public TestResponse assertCookie(String name) {
        if (cookieHeader(name).isEmpty()) {
            fail("没有下发 cookie \"%s\"。实际下发:%s".formatted(name, cookieNames()));
        }
        return this;
    }

    public TestResponse assertCookie(String name, String expectedValue) {
        String value = cookieValue(name).orElseGet(() -> {
            fail("没有下发 cookie \"%s\"。实际下发:%s".formatted(name, cookieNames()));
            return null;
        });
        if (!expectedValue.equals(value)) {
            fail("cookie %s 期望 \"%s\",实际 \"%s\""
                    .formatted(name, expectedValue, value));
        }
        return this;
    }

    public TestResponse assertCookieMissing(String name) {
        if (cookieHeader(name).isPresent()) {
            fail("不该下发 cookie \"%s\"".formatted(name));
        }
        return this;
    }

    /**
     * 断言 cookie 被删除({@code Max-Age=0})。
     *
     * <p>「删 cookie」在 HTTP 上就是下发一个同名、空值、Max-Age=0 的
     * cookie —— 所以 {@link #assertCookieMissing} 在登出场景里会误判通过。
     */
    public TestResponse assertCookieExpired(String name) {
        String header = cookieHeader(name).orElseGet(() -> {
            fail("没有下发 cookie \"%s\",无法判断它是否被删除".formatted(name));
            return null;
        });
        if (!header.contains("Max-Age=0") && !header.contains("Max-Age=-1")) {
            fail("cookie %s 不是过期的:%s".formatted(name, header));
        }
        return this;
    }

    /** cookie 的完整 Set-Cookie 串 —— 用于断言 HttpOnly / SameSite 等属性 */
    public Optional<String> cookieHeader(String name) {
        return setCookies().stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst();
    }

    public Optional<String> cookieValue(String name) {
        return cookieHeader(name).map(header -> {
            String pair = header.split(";", 2)[0];
            return pair.substring(pair.indexOf('=') + 1);
        });
    }

    private List<String> setCookies() {
        var out = new ArrayList<String>(raw.getHeaders("Set-Cookie"));
        return out;
    }

    private List<String> cookieNames() {
        return setCookies().stream()
                .map(c -> c.substring(0, Math.max(0, c.indexOf('='))))
                .toList();
    }

    // ================= JSON =================

    public JsonNode json() {
        try {
            return JSON.readTree(content);
        } catch (Exception e) {
            fail("响应体不是合法 JSON:" + e.getMessage());
            return null;
        }
    }

    /**
     * 按点号路径断言。
     *
     * <pre>{@code
     * assertJsonPath("data.author.name", "张三")
     * assertJsonPath("data.0.id", 1)          // 数组下标也用点号
     * }</pre>
     */
    public TestResponse assertJsonPath(String path, Object expected) {
        JsonNode node = at(path);
        if (node == null || node.isMissingNode()) {
            fail("JSON 路径 %s 不存在".formatted(path));
        }
        String actual = node.isValueNode() ? node.asText() : node.toString();
        String want = String.valueOf(expected);
        if (!actual.equals(want)) {
            fail("JSON 路径 %s 期望 \"%s\",实际 \"%s\"".formatted(path, want, actual));
        }
        return this;
    }

    public TestResponse assertJsonMissing(String path) {
        JsonNode node = at(path);
        if (node != null && !node.isMissingNode()) {
            fail("JSON 路径 %s 不该存在,实际值 %s".formatted(path, node));
        }
        return this;
    }

    /** 数组长度 */
    public TestResponse assertJsonCount(int expected, String path) {
        JsonNode node = path == null || path.isEmpty() ? json() : at(path);
        if (node == null || !node.isArray()) {
            fail("JSON 路径 %s 不是数组:%s".formatted(path, node));
        }
        if (node.size() != expected) {
            fail("JSON 路径 %s 期望 %d 个元素,实际 %d"
                    .formatted(path, expected, node.size()));
        }
        return this;
    }

    /** 顶层对象至少包含这些键值对 */
    public TestResponse assertJson(Map<String, ?> expected) {
        expected.forEach((key, value) -> assertJsonPath(key, value));
        return this;
    }

    public TestResponse assertJsonStructure(String path, List<String> keys) {
        JsonNode node = path == null || path.isEmpty() ? json() : at(path);
        if (node == null || node.isMissingNode()) {
            fail("JSON 路径 %s 不存在".formatted(path));
        }
        var missing = keys.stream().filter(k -> !node.has(k)).toList();
        if (!missing.isEmpty()) {
            fail("JSON 路径 %s 缺少键 %s。实际键:%s"
                    .formatted(path, missing, fieldNames(node)));
        }
        return this;
    }

    /**
     * 校验错误断言。
     *
     * <p>匹配 {@code ValidationException.toJson()} 的结构:
     * {@code {"message": ..., "errors": {"field": [...]}}}。
     */
    public TestResponse assertJsonValidationErrors(String... fields) {
        assertStatus(422);
        JsonNode errors = at("errors");
        if (errors == null || errors.isMissingNode()) {
            fail("422 响应里没有 errors 对象。响应体:" + preview());
        }
        var missing = java.util.Arrays.stream(fields)
                .filter(f -> !errors.has(f)).toList();
        if (!missing.isEmpty()) {
            fail("期望字段 %s 有校验错误,实际有错误的字段:%s"
                    .formatted(List.of(missing), fieldNames(errors)));
        }
        return this;
    }

    public TestResponse assertJsonMissingValidationErrors(String... fields) {
        JsonNode errors = at("errors");
        if (errors == null || errors.isMissingNode()) return this;

        var present = java.util.Arrays.stream(fields).filter(errors::has).toList();
        if (!present.isEmpty()) {
            fail("字段 %s 不该有校验错误".formatted(present));
        }
        return this;
    }

    private JsonNode at(String path) {
        JsonNode node = json();
        for (String segment : path.split("\\.")) {
            if (node == null) return null;
            node = segment.matches("\\d+") ? node.path(Integer.parseInt(segment))
                                           : node.path(segment);
        }
        return node;
    }

    private static List<String> fieldNames(JsonNode node) {
        var out = new ArrayList<String>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ================= 视图 =================

    public TestResponse assertViewIs(String expected) {
        String actual = viewName().orElseGet(() -> {
            fail("响应不是一个视图(实际是 %s)".formatted(bodyKind()));
            return null;
        });
        if (!expected.equals(actual)) {
            fail("期望视图 %s,实际 %s".formatted(expected, actual));
        }
        return this;
    }

    public TestResponse assertViewHas(String key) {
        if (!viewData().containsKey(key)) {
            fail("视图数据里没有 \"%s\"。实际键:%s".formatted(key, viewData().keySet()));
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> TestResponse assertViewHas(String key, java.util.function.Predicate<T> check) {
        assertViewHas(key);
        if (!check.test((T) viewData().get(key))) {
            fail("视图数据 \"%s\" 不满足断言。实际值:%s"
                    .formatted(key, viewData().get(key)));
        }
        return this;
    }

    public TestResponse assertViewMissing(String key) {
        if (viewData().containsKey(key)) {
            fail("视图数据里不该有 \"%s\"".formatted(key));
        }
        return this;
    }

    public Optional<String> viewName() {
        return jaravelResponse != null
                && jaravelResponse.body() instanceof io.jaravel.http.Response.Body.ViewRef v
                ? Optional.of(v.name())
                : Optional.empty();
    }

    public Map<String, Object> viewData() {
        return jaravelResponse != null
                && jaravelResponse.body() instanceof io.jaravel.http.Response.Body.ViewRef v
                ? v.data()
                : Map.of();
    }

    private String bodyKind() {
        if (jaravelResponse == null) return "未知";
        return switch (jaravelResponse.body()) {
            case io.jaravel.http.Response.Body.None n -> "空响应";
            case io.jaravel.http.Response.Body.Text t -> "文本/HTML";
            case io.jaravel.http.Response.Body.Json j -> "JSON";
            case io.jaravel.http.Response.Body.ViewRef v -> "视图 " + v.name();
        };
    }

    // ================= 会话 =================

    public TestResponse assertSessionHas(String key) {
        var data = requireSession();
        if (!data.has(key)) {
            fail("会话里没有 \"%s\"。实际键:%s".formatted(key, data.all().keySet()));
        }
        return this;
    }

    public TestResponse assertSessionHas(String key, Object expected) {
        assertSessionHas(key);
        Object actual = requireSession().get(key).orElse(null);
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            fail("会话 \"%s\" 期望 \"%s\",实际 \"%s\""
                    .formatted(key, expected, actual));
        }
        return this;
    }

    public TestResponse assertSessionMissing(String key) {
        if (requireSession().has(key)) {
            fail("会话里不该有 \"%s\"".formatted(key));
        }
        return this;
    }

    /**
     * 断言这些字段有校验错误。
     *
     * <p>读的是闪存的 {@code errors} —— 表单校验失败时框架 302 回跳并
     * 把错误闪存进会话(模板里用 {@code @error} 读)。
     */
    @SuppressWarnings("unchecked")
    public TestResponse assertSessionHasErrors(String... fields) {
        var errors = requireSession().get("errors")
                .filter(Map.class::isInstance)
                .map(v -> (Map<String, Object>) v)
                .orElseGet(() -> {
                    fail("会话里没有闪存的 errors。实际键:%s"
                            .formatted(requireSession().all().keySet()));
                    return Map.of();
                });

        var missing = java.util.Arrays.stream(fields)
                .filter(f -> !errors.containsKey(f)).toList();
        if (!missing.isEmpty()) {
            fail("期望字段 %s 有校验错误,实际:%s".formatted(missing, errors.keySet()));
        }
        return this;
    }

    public TestResponse assertSessionHasNoErrors() {
        if (requireSession().has("errors")) {
            fail("会话里不该有校验错误,实际:%s".formatted(
                    requireSession().get("errors").orElse(null)));
        }
        return this;
    }

    private SessionData requireSession() {
        if (session == null) {
            fail("""
                    本次请求没有会话。
                      会话由 StartSession 中间件建立 —— 路由上要有 web 组
                      (api 组刻意不含它:无状态接口不该下发会话 cookie)。""");
        }
        return session;
    }

    // ================= 失败消息 =================

    /**
     * 统一的失败出口。
     *
     * <p>每条消息都带上状态码与响应体 —— HTTP 测试失败时第一个问题
     * 永远是「那它到底返回了什么」,而排查的第一步不该是加一行 println。
     */
    private void fail(String message) {
        Assertions.fail("""
                %s

                  ── 响应 ──
                  状态:%d
                  类型:%s
                  正文:%s""".formatted(message, status(),
                        raw.getContentType() == null ? "(无)" : raw.getContentType(),
                        preview()));
    }

    private String preview() {
        if (content.isEmpty()) return "(空)";
        return content.length() <= BODY_PREVIEW
                ? content
                : content.substring(0, BODY_PREVIEW) + "…(共 " + content.length() + " 字符)";
    }

    @Override
    public String toString() {
        return "TestResponse[" + status() + " " + preview() + "]";
    }
}
