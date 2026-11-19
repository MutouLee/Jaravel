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
    private String fragment;

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

    /**
     * 只渲染模板里的一个 {@code @fragment}(docs/04 §1.8)。
     *
     * <pre>{@code
     * // htmx 的局部刷新:只回那一行
     * return View.of("notes.page").with("notes", notes).fragment("row-" + id);
     * }</pre>
     *
     * <p>数据仍然要**齐全** —— 片段的渲染路径与整页完全一样
     * (整页照样渲染一遍,再把那一段取出来),所以片段上方用到的
     * 变量一个都不能少。理由见 {@code JbladeEngine.renderFragment}。
     */
    public View fragment(String name) {
        this.fragment = name;
        return this;
    }

    public String name()                 { return name; }
    public Map<String, Object> data()    { return Map.copyOf(data); }
    public int status()                   { return status; }

    /** 要渲染的片段名;null = 整页 */
    public String fragment()             { return fragment; }

    /** 转成 Response,由 starter 在返回值归一化时调用 */
    public Response toResponse() {
        return Response.of(new Response.Body.ViewRef(name, data(), fragment), status)
                       .contentType("text/html;charset=UTF-8");
    }

    @Override public String toString() {
        return "View(" + name + ", data=" + data.keySet() + ")";
    }
}
