package io.jaravel.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重定向响应。
 *
 * <p>{@code Redirect.to("/posts").with("status", "created")}
 *
 * <p>v0.1 的 {@code with()} 把 flash 数据暂存在响应上,由 starter 写入 session;
 * v0.3 引入 session 模块后会接到真正的 flash 机制上。
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
    public Map<String, Object> flash()        { return Map.copyOf(flash); }
    public boolean shouldKeepInput()          { return withInput; }

    @Override public String toString() {
        return "Redirect(" + status() + " → " + location + ")";
    }
}
