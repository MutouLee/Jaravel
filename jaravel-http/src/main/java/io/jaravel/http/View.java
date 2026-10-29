package io.jaravel.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待渲染的视图引用。控制器返回它,由 starter 交给视图引擎渲染。
 *
 * <p>{@code return View.of("posts.index").with("posts", posts);}
 *
 * <p>v0.1 是弱类型形态(Map 载荷)。v0.2 会由 APT 从模板的 {@code @params}
 * 生成类型安全的 {@code Views.postsIndex(posts)} 门面,两者并存。
 */
public final class View {

    private final String name;
    private final Map<String, Object> data = new LinkedHashMap<>();
    private int status = 200;

    private View(String name) {
        this.name = name;
    }

    public static View of(String name) {
        return new View(name);
    }

    public static View of(String name, Map<String, ?> data) {
        var v = new View(name);
        v.data.putAll(data);
        return v;
    }

    public View with(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public View with(Map<String, ?> values) {
        data.putAll(values);
        return this;
    }

    public View status(int s) {
        this.status = s;
        return this;
    }

    public String name()                 { return name; }
    public Map<String, Object> data()    { return Map.copyOf(data); }
    public int status()                   { return status; }

    /** 转成 Response,由 starter 在返回值归一化时调用 */
    public Response toResponse() {
        return Response.of(new Response.Body.ViewRef(name, data()), status)
                       .contentType("text/html;charset=UTF-8");
    }

    @Override public String toString() {
        return "View(" + name + ", data=" + data.keySet() + ")";
    }
}
