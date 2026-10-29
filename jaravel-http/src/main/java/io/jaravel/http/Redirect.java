package io.jaravel.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重定向响应。
 *
 * <p>{@code Redirect.to("/posts").with("status", "created")}
 *
 * <h2>flash 数据存在哪</h2>
 * <p>{@code with()} 只是把值挂在**响应对象**上。真正写进会话 flash 区的
 * 动作由 {@code StartSession} 中间件完成 —— 本类在 {@code jaravel-http},
 * 那一层按 ADR-001 不该知道会话的存在(会话是可替换的,而重定向不是)。
 *
 * <p>实际后果:这些值只在**挂了会话中间件**的路由上生效。
 * {@code api} 组没有 StartSession,那里的 {@code with()} 会被静默丢弃 ——
 * 这是对的,无状态接口本来就没有「下一个请求」可言。
 */
public final class Redirect extends Response {

    private final String location;
    private final Map<String, Object> flash = new LinkedHashMap<>();
    private boolean withInput = false;

    private Redirect(String location, int status) {
        this.location = location;
        status(status);
        header("Location", location);
    }

    public static Redirect to(String location) {
        return new Redirect(location, 302);
    }

    public static Redirect to(String location, int status) {
        return new Redirect(location, status);
    }

    /** 永久重定向。SEO 与 API 迁移场景。 */
    public static Redirect permanent(String location) {
        return new Redirect(location, 301);
    }

    /** 回到来源页。无 Referer 时回落到 fallback。 */
    public static Redirect back(Request request, String fallback) {
        return new Redirect(request.header("Referer").orElse(fallback), 302);
    }

    /** 跳到外部地址。单独一个方法是为了让「离开本站」在代码里显式可见。 */
    public static Redirect away(String url) {
        return new Redirect(url, 302);
    }

    /** flash 数据:只在下一个请求可用 */
    public Redirect with(String key, Object value) {
        flash.put(key, value);
        return this;
    }

    public Redirect with(Map<String, ?> values) {
        flash.putAll(values);
        return this;
    }

    /** 把本次输入回填到 session,供表单重填 */
    public Redirect withInput() {
        this.withInput = true;
        return this;
    }

    public Redirect withErrors(Map<String, ?> errors) {
        flash.put("errors", errors);
        return this;
    }

    public String location()                  { return location; }

    /**
     * 待闪存的数据。
     *
     * <p>**允许 null 值** —— 所以这里不能用 {@code Map.copyOf}(它拒绝
     * null 并抛 NPE)。调用方常常直接把一个可能为空的结果交过来:
     * {@code .with("reset_token", maybeToken)}。为此崩掉整个请求,
     * 而且栈顶落在框架里,是最糟的失败方式。
     *
     * <p>null 的语义就是「这一项没有值」,读取端拿到
     * {@code Optional.empty()}。
     */
    public Map<String, Object> flash() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(flash));
    }
    public boolean shouldKeepInput()          { return withInput; }

    @Override public String toString() {
        return "Redirect(" + status() + " → " + location + ")";
    }
}
