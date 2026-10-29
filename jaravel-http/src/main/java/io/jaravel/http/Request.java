package io.jaravel.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.support.Coll;

/**
 * HTTP 请求。**不含任何 Servlet 类型**(ADR-001)——
 * starter 负责把 {@code HttpServletRequest} 适配成这个接口。
 *
 * <p>输入访问遵循 Laravel 的约定:{@link #input} 合并查询串与请求体,
 * {@link #query} 只看查询串。
 */
public interface Request {

    // ---------- 基本信息 ----------

    String method();

    /** 不含查询串的路径,如 {@code /posts/42} */
    String path();

    /** 完整 URL,含 scheme 与 host */
    String url();

    /** 完整 URL + 查询串 */
    String fullUrl();

    String ip();

    default boolean isMethod(String m) {
        return method().equalsIgnoreCase(m);
    }

    default boolean isGet()  { return isMethod("GET"); }
    default boolean isPost() { return isMethod("POST"); }

    // ---------- 输入 ----------

    /** 查询串 + 请求体合并后的全部输入 */
    Map<String, Object> all();

    Optional<String> input(String key);

    default String input(String key, String def) {
        return input(key).orElse(def);
    }

    <T> Optional<T> input(String key, Class<T> type);

    default <T> T input(String key, Class<T> type, T def) {
        return input(key, type).orElse(def);
    }

    Optional<String> query(String key);

    default String query(String key, String def) {
        return query(key).orElse(def);
    }

    /** 同名多值,如 {@code ?tag=a&tag=b} */
    List<String> inputs(String key);

    default Map<String, Object> only(String... keys) {
        return io.jaravel.support.Arr.only(all(), keys);
    }

    default Map<String, Object> except(String... keys) {
        return io.jaravel.support.Arr.except(all(), keys);
    }

    /** 全部 key 都存在 */
    default boolean has(String... keys) {
        var all = all();
        for (String k : keys) if (!all.containsKey(k)) return false;
        return true;
    }

    /** 存在且非空(空串与空集合视为空,与 Laravel 一致) */
    default boolean filled(String key) {
        return input(key).filter(s -> !s.isBlank()).isPresent();
    }

    default boolean missing(String key) {
        return !has(key);
    }

    /** 原始请求体 */
    String rawBody();

    // ---------- 路由 ----------

    /** 路由参数,由路由匹配阶段填入 */
    Optional<String> routeParam(String name);

    <T> Optional<T> routeParam(String name, Class<T> type);

    Map<String, String> routeParams();

    /** 匹配到的路由名,未命名则为空 */
    Optional<String> routeName();

    // ---------- 头与内容协商 ----------

    Optional<String> header(String name);

    Map<String, List<String>> headers();

    default Optional<String> bearerToken() {
        return header("Authorization")
                .filter(h -> h.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(h -> h.substring(7).trim());
    }

    /** 期望 JSON 响应:Accept 含 json,或 Content-Type 是 json,或是 XHR */
    default boolean expectsJson() {
        if (isAjax() && !accepts("text/html")) return true;
        return wantsJson();
    }

    default boolean wantsJson() {
        return header("Accept").map(a -> a.contains("/json") || a.contains("+json")).orElse(false);
    }

    default boolean isJson() {
        return header("Content-Type").map(c -> c.contains("/json") || c.contains("+json")).orElse(false);
    }

    default boolean isAjax() {
        return header("X-Requested-With").map("XMLHttpRequest"::equalsIgnoreCase).orElse(false);
    }

    default boolean accepts(String contentType) {
        return header("Accept").map(a -> a.contains(contentType) || a.contains("*/*")).orElse(false);
    }

    // ---------- Cookie ----------

    Optional<String> cookie(String name);

    // ---------- 逃生口 ----------

    /**
     * 底层原生请求对象({@code HttpServletRequest})。
     * 需要 Servlet 原语时用这个 —— 逃生口是设计的一部分,不是用户自己撬。
     */
    Optional<Object> raw();

    /** 便于调试与日志 */
    default String describe() {
        return method() + " " + path();
    }

    default Coll<String> inputKeys() {
        return Coll.from(all().keySet());
    }
}
