package app;
import java.util.*;
import gg.jte.Content;
import gg.jte.html.HtmlContent;
import gg.jte.html.OwaspHtmlTemplateOutput;
import gg.jte.output.StringOutput;

/** 模拟 jblade 的渲染上下文:收集 section / push,供布局延后渲染。 */
public class JbContext {
    private final Map<String, Content> sections = new LinkedHashMap<>();
    private final Map<String, List<Content>> stacks = new LinkedHashMap<>();

    /**
     * @section('x') ... @endsection  →  ctx.section("x", @`...`)
     *
     * 关键:**立即渲染**成字符串而非惰性存储。
     * 这让 section 体内(含深层组件)的 @push 副作用在布局渲染之前就全部发生,
     * 因此渲染在 <head> 的 @stack 也能拿到深层组件的贡献。
     * body 只渲染一次 —— 与 Blade 的两遍模型语义一致,无额外开销。
     */
    public String section(String name, Content c) {
        var buf = new StringOutput();
        // HTML 模式下 Content 需要 HtmlTemplateOutput —— 用 jte 自带的 Owasp 包装
        var htmlBuf = new OwaspHtmlTemplateOutput(buf);
        c.writeTo(htmlBuf);
        String rendered = buf.toString();
        sections.put(name, out -> out.writeContent(rendered));   // 已转义,原样写出
        return "";
    }

    /** @push('x') ... @endpush  →  ctx.push("x", @`...`) */
    public String push(String name, Content c) {
        stacks.computeIfAbsent(name, k -> new ArrayList<>()).add(c); return "";
    }

    /** @yield('x')  →  ${ctx.yield_("x")} —— 返回 Content,由 jte 渲染 */
    public Content yield_(String name) { return sections.getOrDefault(name, EMPTY); }

    /** @stack('x')  →  ${ctx.stack("x")} */
    public Content stack(String name) {
        List<Content> list = stacks.getOrDefault(name, List.of());
        return out -> { for (Content c : list) c.writeTo(out); };
    }

    public boolean has(String name) { return sections.containsKey(name); }

    private static final Content EMPTY = out -> {};
}
