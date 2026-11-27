package net.dobeen.jaravel.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.jte.Content;
import gg.jte.html.OwaspHtmlTemplateOutput;
import gg.jte.output.StringOutput;

/**
 * jblade 的渲染上下文:{@code @section} / {@code @yield} / {@code @push} / {@code @stack} 的编排。
 *
 * <p><b>为什么需要它(V3 验证的核心发现):</b>jte 原生的布局模型是「子模板调用布局、
 * 整页塞进 {@code content} 参数」,Content 参数只能自上而下流动,
 * 导致**深层组件无法向外层布局贡献内容** —— {@code @stack}/{@code @push} 无法表达。
 *
 * <p><b>解法:</b>{@link #section} 存入时**立即渲染**成字符串。
 * 此刻 section 体内(含嵌套的 include/组件)的全部 {@code @push} 副作用都已发生,
 * 因此布局随后渲染时,即使 {@code @stack('styles')} 位于 {@code <head>}(比 content 先输出),
 * 也能拿到深层贡献。这正是 Blade 的两遍语义,且 body 只渲染一次,无额外开销。
 *
 * <p>实例是**每次渲染一个**,不共享,因此无需线程安全。
 */
public final class JbContext {

    private final Map<String, Content> sections = new LinkedHashMap<>();
    private final Map<String, List<Content>> stacks = new LinkedHashMap<>();
    /** @once 的去重标记 */
    private final java.util.Set<String> onceKeys = new java.util.HashSet<>();

    private static final Content EMPTY = out -> {};

    // ---------- section / yield ----------

    /**
     * {@code @section('x') ... @endsection}
     *
     * <p>立即渲染(见类注释)。返回空串是因为它在模板里以 {@code ${ctx.section(...)}}
     * 的形式出现,其返回值会被输出 —— 必须是空串。
     */
    public String section(String name, Content body) {
        sections.put(name, prerender(body));
        return "";
    }

    /** {@code @section('title', 'Posts')} 的单值形式 */
    public String sectionValue(String name, Object value) {
        String s = value == null ? "" : String.valueOf(value);
        sections.put(name, out -> out.writeUserContent(s));
        return "";
    }

    /** {@code @yield('x')} */
    public Content yield_(String name) {
        return sections.getOrDefault(name, EMPTY);
    }

    /** {@code @yield('x', 'default')} */
    public Content yield_(String name, Object fallback) {
        Content c = sections.get(name);
        if (c != null) return c;
        String s = fallback == null ? "" : String.valueOf(fallback);
        return out -> out.writeUserContent(s);
    }

    public boolean hasSection(String name) {
        return sections.containsKey(name);
    }

    // ---------- stack / push ----------

    /** {@code @push('x') ... @endpush} —— 追加到栈尾 */
    public String push(String name, Content body) {
        stacks.computeIfAbsent(name, k -> new ArrayList<>()).add(prerender(body));
        return "";
    }

    /** {@code @prepend('x') ... @endprepend} —— 插到栈首 */
    public String prepend(String name, Content body) {
        stacks.computeIfAbsent(name, k -> new ArrayList<>()).add(0, prerender(body));
        return "";
    }

    /** {@code @push} 的去重版本,同 key 只生效一次({@code @once} 的用途) */
    public String pushOnce(String name, String key, Content body) {
        if (!onceKeys.add(name + "\u0000" + key)) return "";
        return push(name, body);
    }

    // ---------- once ----------

    /**
     * {@code @once ... @endonce} —— 同一次渲染里只输出一次。
     *
     * <p>用途是组件自带的那段 {@code <script>} / {@code <style>}:
     * 一个被用了 20 次的日期选择器组件,它的初始化脚本只该出现一次。
     *
     * <p>key 由转写器生成(模板名 + 行号),所以「同一处 {@code @once}」
     * 的判定与**写在哪**绑定,而不是与内容绑定 —— 两处恰好内容相同的
     * {@code @once} 仍然各输出一次,那是对的:它们是两段不同的声明。
     *
     * <p>★ 返回 {@link Content} 而**不是** String:模板里它以
     * {@code ${ctx.once(...)}} 出现,而 jte 对 String 返回值会再转义一次 ——
     * 于是标签变成页面上的字符。{@code stack(...)} 同理,
     * 这是这个类里统一的做法。
     *
     * <p>({@code $unsafe{...}} 不是选项:jte 的内容块语法
     * {@code @`...`} 只在 {@code ${...}} 里成立,写进 $unsafe 会在生成的
     * Java 里留下一个裸反引号,报「非法字符」。)
     *
     * <p>第二次之后 body **不会被求值**:jte 的 {@code @`...`} 是惰性的
     * {@code Content},不调 {@code writeTo} 就什么都不发生。于是
     * {@code @once} 里的副作用(比如嵌套的 {@code @push})也只发生一次。
     */
    public Content once(String key, Content body) {
        if (!onceKeys.add("once\u0000" + key)) return EMPTY;
        if (body == null) return EMPTY;
        return prerender(body);
    }

    // ---------- fragment ----------

    /** 片段名 → 已渲染的 HTML */
    private final Map<String, String> fragments = new LinkedHashMap<>();

    /**
     * {@code @fragment('row') ... @endfragment}(docs/04 §1.8)。
     *
     * <p>两件事同时做:**照常输出**(片段是页面的一部分),并把这一段
     * 单独记下来,于是同一个模板可以只渲染这一块 ——
     * 配合 htmx / turbo 的局部刷新:一次表单提交只换掉表格里那一行,
     * 而那一行的 HTML 与整页渲染出来的**完全是同一段代码**。
     *
     * <p>这是它比「再写一个 partial 模板」强的地方:两份模板会分叉,
     * 而分叉的症状是「整页刷新对、局部刷新不对」或者反过来。
     *
     * <p>重名**后者覆盖前者**并不报错:{@code @fragment} 出现在
     * {@code @foreach} 里是最常见的用法(每行一个),此时 N 次迭代
     * 写的是同一个名字。要取某一行就把下标拼进名字
     * ({@code @fragment('row-' + note.id)})。
     */
    public Content fragment(String name, Content body) {
        if (body == null) return EMPTY;

        var buffer = new StringOutput();
        body.writeTo(new OwaspHtmlTemplateOutput(buffer));
        String rendered = buffer.toString();
        fragments.put(name, rendered);

        // 与 once 同理:返回 Content,否则 jte 会把这段 HTML 再转义一次
        return out -> out.writeContent(rendered);
    }

    /** 渲染后取某个片段;没有这个名字时返回 empty */
    public java.util.Optional<String> fragmentValue(String name) {
        return java.util.Optional.ofNullable(fragments.get(name));
    }

    /** 这次渲染里出现过的片段名 —— 报错消息要把它们列出来 */
    public java.util.Set<String> fragmentNames() {
        return java.util.Set.copyOf(fragments.keySet());
    }

    /** {@code @stack('x')} */
    public Content stack(String name) {
        List<Content> list = stacks.get(name);
        if (list == null || list.isEmpty()) return EMPTY;
        List<Content> snapshot = List.copyOf(list);
        return out -> { for (Content c : snapshot) c.writeTo(out); };
    }

    // ---------- 内部 ----------

    /**
     * 把 Content 立即渲染成字符串并包回 Content。
     *
     * <p>HTML 模式下 {@code Content.writeTo} 要求 {@code HtmlTemplateOutput} 而非普通
     * {@code TemplateOutput},否则抛 ClassCastException —— 这是 V3 验证中踩到的坑,
     * 用 jte 自带的 {@link OwaspHtmlTemplateOutput} 包装即可。
     *
     * <p>渲染结果是**已转义**的 HTML,因此写回时必须用 {@code writeContent}(原样),
     * 不能再转义一次。
     */
    private static Content prerender(Content body) {
        if (body == null) return EMPTY;
        var buffer = new StringOutput();
        body.writeTo(new OwaspHtmlTemplateOutput(buffer));
        String rendered = buffer.toString();
        return out -> out.writeContent(rendered);
    }

    // ---------- @aware:组件数据栈(docs/04 §1.7) ----------

    /**
     * 每次组件调用一帧,内容是**那次调用写在标签上的属性**。
     *
     * <p>{@code @aware} 从栈顶往外找:自己那次调用的属性 → 外层组件的 →
     * 再外层…… 都没有就用默认值。
     *
     * <p>为什么是「栈顶往外」而不是「跳过自己从父级开始」:
     * {@code <x-menu.item color="red">} 显式给了颜色时,那一定是想要的值。
     * 跳过自己会让「显式传了却不生效」—— 而那是最难怀疑到的一类行为。
     */
    private final java.util.ArrayDeque<Map<String, Object>> componentData =
            new java.util.ArrayDeque<>();

    /**
     * 进入一次组件渲染。由转写器在组件调用点前后成对发出。
     *
     * <p>⚠ 这意味着**每次组件渲染都有一次 push/pop 与一个小 map** ——
     * 即使整个页面没有一个 {@code @aware}。为什么不按需:
     * {@code @aware} 要的是**祖先**的数据,而「孙子用了 @aware」这件事在
     * 祖父的调用点上看不见(跨模板)。按需就意味着跨模板分析整棵组件树,
     * 而那换来的只是省掉几个小对象。
     */
    public void pushComponentData(Map<String, Object> data) {
        componentData.push(data == null ? Map.of() : data);
    }

    public void popComponentData() {
        componentData.poll();
    }

    /**
     * {@code @aware(String color = "gray")} 读到的值。
     *
     * @param type 声明的类型 —— 用来在类型不符时给一条**说得清**的错误。
     *             不校验的话,一个 {@code :color="42"} 的调用会在模板里
     *             某个用到 color 的地方抛 ClassCastException,而那里与
     *             「谁传错了」隔着两个模板
     */
    public <T> T aware(String key, Class<T> type, T fallback) {
        for (Map<String, Object> frame : componentData) {
            if (!frame.containsKey(key)) continue;

            Object value = frame.get(key);
            if (value == null) return null;
            if (type.isInstance(value)) return type.cast(value);

            throw new IllegalStateException("""
                    @aware("%s") 声明的类型是 %s,但外层组件传进来的是 %s(%s)。
                      传值的那个标签写的是 :%s="…" —— 改成那个类型,
                      或者把 @aware 的类型改成 %s。"""
                    .formatted(key, type.getSimpleName(),
                            value.getClass().getSimpleName(), value,
                            key, value.getClass().getSimpleName()));
        }
        return fallback;
    }

    /**
     * 组件调用点的属性表。
     *
     * <p>{@code Map.of} 不收 null 值,而组件属性经常是 null
     * ({@code :title="post.subtitle"}),所以这里自己装。
     */
    public static Map<String, Object> data(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "JbContext.data 需要成对的键值,收到 " + keysAndValues.length + " 个参数");
        }
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return out;
    }

    /** 调试用 */
    public java.util.Set<String> sectionNames() { return sections.keySet(); }
    public java.util.Set<String> stackNames()   { return stacks.keySet(); }
    public int componentDepth()                 { return componentData.size(); }
}
