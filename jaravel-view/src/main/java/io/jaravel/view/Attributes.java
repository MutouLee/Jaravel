package io.jaravel.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.jte.Content;
import gg.jte.TemplateOutput;

/**
 * 组件的**属性袋**(docs/04 §1.7 的 {@code {{ attributes }}})。
 *
 * <p>调用方传了、而组件没在 {@code @props} 里声明的属性,会被收进这里 ——
 * 于是组件可以把它们原样透传到自己的根标签上:
 *
 * <pre>{@code
 * {{-- components/alert.jblade.html --}}
 * @props(String type = "info")
 * <div {{ attributes.merge("class", "alert alert-" + type) }}>{{ slot }}</div>
 * }</pre>
 *
 * <pre>{@code
 * <x-alert type="error" id="login-error" class="mt-4" data-test="x">…</x-alert>
 *   → <div id="login-error" data-test="x" class="alert alert-error mt-4">…</div>
 * }</pre>
 *
 * <h2>它是 {@link Content},不是 String</h2>
 * <p>{@code {{ attributes }}} 要输出的是 {@code id="x" class="y"} ——
 * 当成 String 走 jte 的转义会变成 {@code id=&quot;x&quot;},
 * 也就是页面上一串可见的乱码。
 *
 * <p>所以它自己渲染,并**自己转义每个值** —— 整体不转义但逐值转义,
 * 既能输出引号又关得住 {@code "><script>}。不是「因为是框架内部的东西
 * 所以可以跳过转义」:属性值直接来自调用方,随时可能是用户输入。
 *
 * <h2>不可变</h2>
 * <p>{@link #merge}、{@link #only}、{@link #except} 都返回新实例。
 * 一个组件被渲染多次(循环里)时,共享可变状态会让第二次渲染带上
 * 第一次的 merge 结果 —— 一个只在「列表页」出现的错。
 */
public final class Attributes implements Content {

    /** 属性名 → 值。值为 null 表示**布尔属性**(只输出名字,如 {@code disabled}) */
    private final Map<String, String> values;

    private Attributes(Map<String, String> values) {
        this.values = values;
    }

    private static final Attributes EMPTY = new Attributes(Map.of());

    public static Attributes empty() {
        return EMPTY;
    }

    /**
     * 转写器生成的构造调用:{@code Attributes.of("id", "x", "disabled", true)}。
     *
     * <p>值的类型是 Object 而不是 String,因为属性值可以是表达式
     * ({@code :count="n"})。转换规则:
     * <ul>
     *   <li>{@code null} / {@code false} → **不输出这个属性**
     *       (Blade 同款:{@code <x-i :disabled="false" />} 不该渲染出 disabled);
     *   <li>{@code true} → 布尔属性,只输出名字;
     *   <li>其他 → {@code String.valueOf}。
     * </ul>
     */
    public static Attributes of(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "属性袋要成对的名值,收到 " + pairs.length + " 个");
        }
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value == null || Boolean.FALSE.equals(value)) continue;
            map.put(name, Boolean.TRUE.equals(value) ? null : String.valueOf(value));
        }
        return map.isEmpty() ? EMPTY : new Attributes(map);
    }

    // ================= 读 =================

    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** 不存在时返回 null;布尔属性返回空串(它「有值」只是没内容) */
    public String get(String name) {
        if (!values.containsKey(name)) return null;
        String v = values.get(name);
        return v == null ? "" : v;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public boolean isEmptyContent() {
        return values.isEmpty();
    }

    // ================= 变换(都返回新实例)=================

    /**
     * 合并一个默认值。
     *
     * <p>{@code class} 与 {@code style} 是**追加**(组件的默认值在前、
     * 调用方的在后 —— 后者才能覆盖前者,这是 CSS 的规则);
     * 其他属性是**调用方优先**(调用方给了就不动)。
     *
     * <p>⚠ 这个「class 特殊」不是臆造的:组件写
     * {@code merge("class", "btn")} 而调用方写 {@code class="mt-4"} 时,
     * 两边都要留下 —— 这正是属性袋存在的理由。换成覆盖语义的话,
     * 调用方加一个外边距就会**悄悄删掉**组件自己的样式。
     */
    public Attributes merge(String name, Object defaultValue) {
        String incoming = values.get(name);
        boolean appendable = name.equals("class") || name.equals("style");

        var map = new LinkedHashMap<>(values);
        if (defaultValue == null || Boolean.FALSE.equals(defaultValue)) {
            return this;
        }
        String def = Boolean.TRUE.equals(defaultValue) ? null : String.valueOf(defaultValue);

        if (!values.containsKey(name)) {
            map.put(name, def);
        } else if (appendable && incoming != null && def != null) {
            String joiner = name.equals("style") ? "; " : " ";
            String base = def.endsWith(";") ? def.substring(0, def.length() - 1) : def;
            map.put(name, base + joiner + incoming);
        }
        return new Attributes(map);
    }

    public Attributes except(String... names) {
        Set<String> drop = Set.of(names);
        var map = new LinkedHashMap<String, String>();
        values.forEach((k, v) -> { if (!drop.contains(k)) map.put(k, v); });
        return new Attributes(map);
    }

    public Attributes only(String... names) {
        List<String> keep = Arrays.asList(names);
        var map = new LinkedHashMap<String, String>();
        values.forEach((k, v) -> { if (keep.contains(k)) map.put(k, v); });
        return new Attributes(map);
    }

    // ================= 渲染 =================

    @Override
    public void writeTo(TemplateOutput output) {
        output.writeContent(toString());
    }

    /**
     * 转写器生成的渲染入口:{@code {{ attributes }}} →
     * {@code $unsafe{Attributes.render(attributes)}}。
     *
     * <p>为什么不直接 {@code $unsafe{attributes}}:jte 的
     * {@code writeUnsafeContent} 只收 String,给它一个 Content 是编译错误
     * (实测)。而这里是转写器生成的调用,用户看不到。
     *
     * <p>null 安全:组件可能把属性袋传给别人,路上变成 null。
     */
    public static String render(Attributes attributes) {
        return attributes == null ? "" : attributes.toString();
    }

    /** {@code id="x" class="a b" disabled} —— 不带前导空格(与 Blade 一致) */
    @Override
    public String toString() {
        var parts = new ArrayList<String>(values.size());
        values.forEach((name, value) ->
                parts.add(value == null ? name : name + "=\"" + escape(value) + '"'));
        return String.join(" ", parts);
    }

    /**
     * 属性值的转义。
     *
     * <p>必须包括单引号:属性在这里始终用双引号包,但值里的 {@code '}
     * 会流到 {@code onclick='…'} 这类上下文里。少转一个引号就是一个 XSS,
     * 而它在「正常输入」下完全看不出来。
     */
    private static String escape(String value) {
        var sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&'  -> sb.append("&amp;");
                case '<'  -> sb.append("&lt;");
                case '>'  -> sb.append("&gt;");
                case '"'  -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
