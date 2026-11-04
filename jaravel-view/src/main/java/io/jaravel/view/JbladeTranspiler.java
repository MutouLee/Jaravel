package io.jaravel.view;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blade 语法 → jte 源码的转写器(ADR-011)。
 *
 * <p>**行导向**而非完整 AST:绝大多数 Blade 指令可在同一行转换完,
 * 因此行导向处理既简单又让行号映射几乎恒等 —— 这对错误定位至关重要。
 * 真正需要重构结构的只有 {@code @extends}(布局调用要挪到末尾)。
 *
 * <p>转写产物由 jte 负责:解析、生成 Java、转义(含上下文感知)、编译、热重载、
 * Java 错误 → jte 行号映射。本类只做前端。
 */
public final class JbladeTranspiler {

    // ---------- 行内 token ----------
    private static final Pattern COMMENT    = Pattern.compile("\\{\\{--(.*?)--}}", Pattern.DOTALL);
    private static final Pattern RAW_ECHO   = Pattern.compile("\\{!!\\s*(.+?)\\s*!!}");
    private static final Pattern ESC_ECHO   = Pattern.compile("\\{\\{\\s*(.+?)\\s*}}");
    /** @{{ x }} —— 转义掉,交给前端框架 */
    private static final Pattern VERBATIM_ECHO = Pattern.compile("@\\{\\{(.*?)}}", Pattern.DOTALL);

    // ---------- 指令 ----------
    private static final Pattern DIRECTIVE =
            Pattern.compile("@(\\w+)\\s*(?:\\(((?:[^()]|\\([^()]*\\))*)\\))?");

    private static final char SQUOTE = '\'';
    private static final char DQUOTE = '"';
    private static final char BACKSLASH = '\\';

    private static final Pattern FOREACH_EXPR =
            Pattern.compile("^\\s*(.+?)\\s+as\\s+(\\w+)\\s*$");

    private final String source;
    private final String templateName;

    private final StringBuilder out = new StringBuilder();
    private final LineMap.Builder lineMap;
    /** 待输出的 jte 行,与 sourceLines 一一对应后再拼接 */
    private final List<String> jteLines = new ArrayList<>();
    private final List<Integer> jteLineSources = new ArrayList<>();
    /**
     * 本行末尾是否追加换行。
     *
     * <p>带 @extends 的子模板里,顶层的指令行({@code ctx.section(...)} 等)
     * 自身不产出内容,但 jte 会为每行输出一个换行 —— 那些换行会跑到布局输出之前,
     * 让 {@code <!DOCTYPE html>} 不再是响应的第一个字节。因此顶层指令行不追加换行。
     */
    private final List<Boolean> jteLineNewline = new ArrayList<>();

    private String layout;
    private final List<String> params = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();
    /** 循环嵌套栈,存 LoopState 的局部变量名 */
    private final Deque<String> loopStack = new ArrayDeque<>();
    private int loopCounter = 0;
    /** 每层 forelse 是否已遇到 @empty —— 决定 @endforelse 要不要补 @endfor */
    private final Deque<Boolean> forelseHasEmpty = new ArrayDeque<>();
    /**
     * 本行是否含已识别的 jblade 指令。用于判断带 @extends 的子模板里
     * 哪些顶层行应当被丢弃(见 run() 中的 suppress 逻辑)。
     */
    private boolean lineHadDirective;

    /** @verbatim 块内不做任何转换 */
    private boolean inVerbatim = false;
    /** 处在一个跨行的 {{-- --}} 注释里 */
    private boolean inComment = false;
    /** 块结构校验栈 */
    private final Deque<OpenBlock> blocks = new ArrayDeque<>();

    private record OpenBlock(String directive, int line) {}

    // ---------- 组件帧(具名 slot 所需)----------

    /**
     * 一行翻译完后的文本碎片。{@code sourceLine} 一路带着走,
     * 所以把碎片**重排**不会破坏行号映射。
     */
    private record Piece(String text, int sourceLine, boolean newline) {}

    /**
     * 带 body 的组件调用 —— **在闭合标签处才拼出来**。
     *
     * <h2>★ 为什么不能像默认 slot 那样原地展开</h2>
     * <p>具名 slot({@code <x-slot:footer>})写在组件 body **里面**,
     * 却必须成为那次调用的**另一个参数** —— 要出现在参数表里、
     * 在默认 slot 的 Content 块**外面**。而转写是逐行的:读到
     * {@code <x-slot:footer>} 那一行时,
     * {@code @template.components.alert(ctx = ctx, slot = @`}
     * 早就发出去了,没法再往参数表里插东西。
     *
     * <p>于是:组件的开/闭标签在行里只留**哨兵**,body 的文本先攒成
     * {@link Piece} 存在帧里;闭合标签处按「具名 slot 在前、默认 slot 在后」
     * 拼装成完整调用。参数表的顺序对 jte 无所谓(命名参数),
     * 而每个碎片都带着自己的源行号 —— 所以重排**不动行号映射**,
     * 具名 slot 里写错属性名仍然指向模板里的那一行。
     *
     * <p>嵌套靠栈:内层组件闭合时,它拼好的碎片落进外层帧的当前缓冲区。
     */
    private static final class ComponentFrame {
        final String name;
        final int line;
        /** {@code @template.components.alert(ctx = ctx, type = "x"} —— 还没闭括号 */
        final String callPrefix;
        final List<Piece> defaultSlot = new ArrayList<>();
        /** slot 名 → 碎片。LinkedHashMap:参数表顺序与模板里的书写顺序一致,便于对读生成码 */
        final Map<String, List<Piece>> namedSlots = new LinkedHashMap<>();
        /** 当前正在收集哪个具名 slot;null = 收集默认 slot */
        String capturing;

        ComponentFrame(String name, int line, String callPrefix) {
            this.name = name;
            this.line = line;
            this.callPrefix = callPrefix;
        }

        List<Piece> target() {
            return capturing == null ? defaultSlot : namedSlots.get(capturing);
        }
    }

    private final Deque<ComponentFrame> componentFrames = new ArrayDeque<>();

    private final ComponentProps componentProps;
    /** 本模板调用了哪些组件 —— 引擎用它做热重载的依赖追踪(见 Result#components) */
    private final List<String> calledComponents = new ArrayList<>();

    private JbladeTranspiler(String source, String templateName, ComponentProps componentProps) {
        this.source = source;
        this.templateName = templateName;
        this.componentProps = componentProps;
        this.lineMap = LineMap.builder(templateName);
    }

    public record Result(String jteSource, LineMap lineMap, List<String> declaredParams,
                         Optional<String> layout, List<String> components) {}

    /**
     * 组件声明了哪些 {@code @props} —— **属性袋唯一需要的跨模板信息**。
     *
     * <p>为什么非要跨模板:属性袋的定义是「调用方传了、组件没声明的属性」,
     * 而转写器只看调用方那一行。不知道组件声明了什么,就分不出
     * {@code type="error"}(是 prop)和 {@code id="x"}(进袋)。
     *
     * <p>{@link Optional#empty()} 表示**不知道**(查不到、或那个模板用的是
     * {@code @params} 而不是 {@code @props})。此时退回「全部当成声明过的
     * 参数」—— 也就是属性袋之前的行为。退回而不是报错:
     * {@code @include} 的目标、还没写出来的组件都会走到这里。
     */
    public interface ComponentProps {
        Optional<List<String>> propsOf(String componentView);

        /** 不知道任何组件的声明 —— 直接用 transpile(source, name) 时的默认 */
        ComponentProps UNKNOWN = view -> Optional.empty();
    }

    public static Result transpile(String source, String templateName) {
        return transpile(source, templateName, ComponentProps.UNKNOWN);
    }

    public static Result transpile(String source, String templateName, ComponentProps props) {
        return new JbladeTranspiler(source, templateName, props).run();
    }

    private Result run() {
        String[] lines = source.split("\\R", -1);

        for (int i = 0; i < lines.length; i++) {
            int sourceLine = i + 1;
            String line = lines[i];

            if (inVerbatim) {
                if (line.trim().equals("@endverbatim")) { inVerbatim = false; emit("", sourceLine); }
                // @verbatim 内的内容必须对 jte **不可见**,否则其中的 @if / ${} 会被 jte 解析。
                // 包成 jte 的原样输出表达式即可。
                else emit("$unsafe{" + javaStringLiteral(line) + "}", sourceLine);
                continue;
            }
            if (line.trim().equals("@verbatim")) { inVerbatim = true; emit("", sourceLine); continue; }

            // ---- 跨行注释 ----
            //
            // ★ 单行的 {{-- … --}} 由 translateEchos 前面的 COMMENT 正则处理,
            // 但那是**逐行**的 —— 跨几行的注释块在任何一行上都匹配不上,
            // 于是整段注释正文会被当成普通文本流给 jte。
            //
            // 后果不是「注释没生效」那么温和:注释里要是写了 HTML
            // (给同事看的用法示例,这是注释最常见的内容),jte 会报一句
            // 「Unclosed tag <td>」—— 指着一个模板作者认为自己注释掉了的标签。
            // 而没有 HTML 的那些,会直接把注释印在页面上。
            //
            // 这里用一个状态位吞掉中间的行。吞掉的行**仍然 emit 一个空行**,
            // 为的是行号映射不动 —— 注释后面的内容出错时还要指对位置。
            // ★ 只有注释的行**连换行都不发**:否则模板顶上那段
            // 讲用法的注释块会在 <!DOCTYPE html> 前留下几十个空行 ——
            // 而 DOCTYPE 不是第一个字节会让一些客户端进怪异模式
            // (布局继承那边为同一件事专门有一条测试)。
            if (inComment) {
                int end = line.indexOf("--}}");
                if (end < 0) continue;
                inComment = false;
                line = line.substring(end + 4);           // 闭合之后的部分照常处理
                if (line.isBlank()) continue;
            }
            int commentStart = unclosedCommentAt(line);
            if (commentStart >= 0) {
                inComment = true;
                String before = line.substring(0, commentStart);   // 开头之前的部分照常处理
                if (before.isBlank()) continue;
                line = before;
            }
            if (!line.isBlank() && COMMENT.matcher(line).replaceAll("").isBlank()) continue;

            lineHadDirective = false;
            String translated = translateLine(line, sourceLine);
            if (translated == null) continue;

            // Blade 语义:带 @extends 的子模板在**顶层**不产出任何内容,
            // 只注册 section / push。否则顶层的空行与杂散文本会跑到
            // 布局输出之前(实测:<!DOCTYPE html> 前出现 9 个空行)。
            boolean topLevelOfChild = layout != null && blocks.isEmpty();
            boolean suppress = topLevelOfChild && !lineHadDirective;
            if (suppress) continue;

            // 顶层指令行不追加换行(见 jteLineNewline 的注释)。
            // @endsection 会先关闭块再走到这里,blocks 已空,因此一并覆盖。
            emitRouted(translated, sourceLine, !topLevelOfChild);
        }

        if (!blocks.isEmpty()) throw unclosedAtEof(blocks.peek());

        // 具名 slot:**读到就声明**。
        //
        // 必须在这里(行循环之后)而不是在 @props 处 —— @props 在第一行,
        // 而 slot("footer") 出现在后面。参数表要到 assemble() 才用,所以赶得上。
        //
        // 默认 null:没传这个 slot 的调用方什么都不用写,
        // 而 hasSlot("footer") 翻成的 (… != null) 正好读它。
        for (String param : namedSlotReads.values()) {
            params.add("gg.jte.Content " + param + " = null");
        }

        // 布局调用必须在最后:此刻全部 section/push 已注册
        if (layout != null) {
            emitGenerated("@template." + viewToTemplatePath(layout) + "(ctx = ctx)");
        }

        return new Result(assemble(), buildLineMap(), List.copyOf(params),
                          Optional.ofNullable(layout), List.copyOf(calledComponents));
    }

    /**
     * 文件结束仍有未闭合的块。
     *
     * <p>组件与具名 slot 单独说 —— 它们是 HTML 标签而不是 {@code @指令},
     * 「@component 没有对应的结束指令 @endcomponent」会把人送去找一个
     * 根本不存在的指令。
     */
    private JbladeException unclosedAtEof(OpenBlock b) {
        String what = switch (b.directive()) {
            case "component" -> {
                var frame = componentFrames.peek();
                yield "组件标签 <x-" + (frame == null ? "…" : frame.name) + "> 没有闭合"
                    + "\n  补上 </x-" + (frame == null ? "…" : frame.name) + ">";
            }
            case "slot" -> "具名 slot <x-slot:…> 没有闭合\n  补上 </x-slot>";
            default -> "@" + b.directive() + " 没有对应的结束指令 @end" + b.directive();
        };
        return new JbladeException(templateName, b.line(), what
                + "\n  在 " + templateName + " 第 " + b.line() + " 行打开,到文件结束仍未闭合");
    }

    /**
     * 本行有没有一个**没闭合**的 {@code {{--}} —— 返回它的位置,没有则 -1。
     *
     * <p>成对的那些留给逐行的 COMMENT 正则(它们在同一行里闭合了)。
     * 这里找的是跨到下一行去的那个。
     */
    private static int unclosedCommentAt(String line) {
        int cursor = 0;
        while (true) {
            int open = line.indexOf("{{--", cursor);
            if (open < 0) return -1;
            int close = line.indexOf("--}}", open + 4);
            if (close < 0) return open;
            cursor = close + 4;
        }
    }

    // ================= 行翻译 =================

    /** 返回 null 表示该行不产出 jte 内容(如 @params 已提取到头部) */
    private String translateLine(String line, int sourceLine) {
        String trimmed = line.trim();

        // 头部声明只贡献元信息,不产出 jte 行(返回 null = 不发往 jte)
        if (trimmed.startsWith("@params")) { collectParams(trimmed, sourceLine); return null; }
        if (trimmed.startsWith("@props"))  { collectProps(trimmed, sourceLine); return null; }
        if (trimmed.startsWith("@param "))  { params.add(trimmed.substring(7).trim()); return null; }
        if (trimmed.startsWith("@import"))  { collectImport(trimmed); return null; }
        if (trimmed.startsWith("@extends")) { collectExtends(trimmed, sourceLine); return null; }

        // ★ 注释**连内容一起丢掉**,不是转成 jte 的 <%-- --%>。
        //
        // 转成 jte 注释的话,注释正文仍然留在这一行里,后面的
        // translateComponents / translateDirectives 照样会看见它 ——
        // 于是注释里一句给同事看的用法示例(「<x-mail::table> 这样用」)
        // 会被当成一个真的组件开标签,报「<x-mail::table> 没有闭合」;
        // 注释里的 @foreach 会压进块栈,报「到文件结束仍未闭合」。
        // 两者都指着模板作者认为自己注释掉了的东西。
        //
        // 代价是生成的 jte 源码里看不到原注释。那只在读生成码时有一点用,
        // 换一个「注释里不能写什么」的隐形规则不值得。
        String work = COMMENT.matcher(line).replaceAll("");

        // 组件在**指令之前**翻译。
        //
        // 顺序是必须的:组件翻译出来的 @template.components.x(...) 本身
        // 带一个 @,而 translateDirectives 会把它当成一个未知指令。
        // 未知指令是原样保留的(邮件模板里的 @media 靠这条),
        // 所以反过来的顺序不会报错 —— 它会**静默地**少做一层转换,
        // 而那正是最难排查的一类。
        //
        // 反过来说,组件的属性值(:count="n")要过表达式重写,
        // 而那一步在 translateComponents 里自己调 rewriteExpression,
        // 不依赖后面的 translateEchos
        work = translateComponents(work, sourceLine);

        work = translateDirectives(work, sourceLine);
        work = translateEchos(work);
        return work;
    }

    /**
     * 一处输出 → jte 的 {@code ${}} 或 {@code $unsafe{}}。
     *
     * <h2>★ 属性袋走 {@code $unsafe}</h2>
     * <p>{@code {{ attributes }}} 要输出的是 {@code id="x" class="y"} ——
     * 一串**属性**,而不是一个值。两件事逼出这个特例:
     * <ul>
     *   <li>jte 的 HTML 模式**禁止**在属性名位置写 {@code ${}}
     *       (「Illegal HTML attribute name」),而那正是属性袋唯一的用处;
     *   <li>整体走转义会变成 {@code id=&quot;x&quot;},也就是页面上的乱码。
     * </ul>
     *
     * <p>**不转义 ≠ 不安全**:{@link Attributes} 自己转义每一个值
     * (见那边的注释)。这里不转义的只有属性之间的空格和等号。
     *
     * <p>于是组件作者写的还是文档里那句 {@code {{ attributes }}} ——
     * {@code $unsafe} 是生成码里的事。
     */
    private String echo(String expr, boolean raw) {
        String rewritten = rewriteExpression(expr);
        if (ATTRIBUTES_ROOT.matcher(rewritten).find()) {
            return "$unsafe{io.jaravel.view.Attributes.render(" + rewritten + ")}";
        }
        return (raw ? "$unsafe{" : "${") + rewritten + "}";
    }

    /** 以 {@code attributes} 这个标识符开头的表达式 —— 属性袋本身或它的变换 */
    private static final Pattern ATTRIBUTES_ROOT =
            Pattern.compile("^attributes\\b(?!\\s*=)");

    private String translateEchos(String line) {
        // @{{ x }} 先替换成占位,避免被 ESC_ECHO 吃掉
        // 哨兵用不可能出现在模板里的控制字符,写成转义序列以保持源文件为纯文本
        final String GUARD = "JBVERBATIM";
        List<String> guarded = new ArrayList<>();
        Matcher vm = VERBATIM_ECHO.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (vm.find()) {
            guarded.add("{{" + vm.group(1) + "}}");
            vm.appendReplacement(sb, Matcher.quoteReplacement(GUARD));
        }
        vm.appendTail(sb);
        String work = sb.toString();

        work = RAW_ECHO.matcher(work).replaceAll(m ->
                Matcher.quoteReplacement(echo(m.group(1), true)));
        work = ESC_ECHO.matcher(work).replaceAll(m ->
                Matcher.quoteReplacement(echo(m.group(1), false)));

        for (String g : guarded) {
            work = work.replaceFirst(Pattern.quote(GUARD), Matcher.quoteReplacement(g));
        }
        return work;
    }

    private String translateDirectives(String line, int sourceLine) {
        Matcher m = DIRECTIVE.matcher(line);
        StringBuilder sb = new StringBuilder();
        int last = 0;

        while (m.find()) {
            // @{{ 不是指令
            if (m.end() < line.length() && line.startsWith("@{{", m.start())) continue;

            String name = m.group(1);
            String args = m.group(2);
            String replacement = translateDirective(name, args, sourceLine);

            if (replacement == null) continue;   // 不是已知指令,原样保留
            lineHadDirective = true;

            sb.append(line, last, m.start()).append(replacement);
            last = m.end();
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    /** 返回 null = 非 jblade 指令,原样保留(如邮件模板里的 @media) */
    private String translateDirective(String name, String args, int line) {
        return switch (name) {
            // ---- 条件 ----
            case "if"       -> { open("if", line);        yield "@if(" + expr(args, name, line) + ")"; }
            case "elseif"   -> "@elseif(" + expr(args, name, line) + ")";
            case "else"     -> "@else";
            case "endif"    -> { close("if", "endif", line);    yield "@endif"; }

            case "unless"   -> { open("unless", line);    yield "@if(!(" + expr(args, name, line) + "))"; }
            case "endunless"-> { close("unless", "endunless", line); yield "@endif"; }

            case "isset"    -> { open("isset", line);     yield "@if(io.jaravel.view.Jb.isset(" + expr(args, name, line) + "))"; }
            case "endisset" -> { close("isset", "endisset", line);  yield "@endif"; }

            case "empty"    -> handleEmpty(args, line);
            case "endempty" -> { close("empty", "endempty", line);  yield "@endif"; }

            // ---- 循环 ----
            case "foreach"    -> openForeach(args, line, false);
            case "endforeach" -> { close("foreach", "endforeach", line); loopStack.pop(); yield "@endfor"; }

            case "forelse"    -> openForeach(args, line, true);
            case "endforelse" -> closeForelse(line);

            case "for"      -> { open("for", line);       yield "@for(" + expr(args, name, line) + ")"; }
            case "endfor"   -> { close("for", "endfor", line);      yield "@endfor"; }

            // jte 没有 @while,用 for(;cond;) 表达
            case "while"    -> { open("while", line);     yield "@for(;" + expr(args, name, line) + ";)"; }
            case "endwhile" -> { close("while", "endwhile", line);  yield "@endfor"; }

            case "continue" -> args == null ? "!{continue;}" : "@if(" + expr(args, name, line) + ")!{continue;}@endif";
            case "break"    -> args == null ? "!{break;}"    : "@if(" + expr(args, name, line) + ")!{break;}@endif";

            // ---- 布局 ----
            case "section"      -> openSection(args, line);
            case "endsection", "stop", "show" -> { close("section", "end" + name, line); yield "`)}"; }
            case "yield"        -> handleYield(args, line);
            case "push"         -> { open("push", line);  yield "${ctx.push(" + firstStringArg(args, name, line) + ", @`"; }
            case "endpush"      -> { close("push", "endpush", line); yield "`)}"; }
            case "prepend"      -> { open("prepend", line); yield "${ctx.prepend(" + firstStringArg(args, name, line) + ", @`"; }
            case "endprepend"   -> { close("prepend", "endprepend", line); yield "`)}"; }
            case "stack"        -> "${ctx.stack(" + firstStringArg(args, name, line) + ")}";
            case "hasSection"   -> { open("hasSection", line); yield "@if(ctx.hasSection(" + firstStringArg(args, name, line) + "))"; }
            case "endhasSection"-> { close("hasSection", "endhasSection", line); yield "@endif"; }

            // ---- 包含 ----
            case "include"      -> handleInclude(args, line, false);
            case "includeIf"    -> handleInclude(args, line, true);

            // ---- 输出 ----
            case "json"         -> "$unsafe{io.jaravel.view.Jb.json(" + expr(args, name, line) + ")}";

            // ---- 明确不支持 ----
            case "php", "endphp" -> throw new JbladeException(templateName, line,
                    "@php 不被支持。模板里写 Java 代码块会破坏关注点分离。"
                  + "\n  修复:把逻辑移到控制器,或用视图组装器(View composer)预计算。");

            default -> null;   // 非 jblade 指令,原样保留
        };
    }

    // ================= 各指令的细节 =================

    private String handleEmpty(String args, int line) {
        // @empty 在 @forelse 内部是「else 分支」,在外部是「判空」
        if (!loopStack.isEmpty() && "forelse".equals(currentBlockDirective())) {
            if (!forelseHasEmpty.isEmpty()) { forelseHasEmpty.pop(); forelseHasEmpty.push(true); }
            return "@endfor@else";
        }
        open("empty", line);
        return "@if(io.jaravel.view.Jb.isEmpty(" + expr(args, "empty", line) + "))";
    }

    /**
     * {@code @endforelse} 的收尾取决于有没有写 {@code @empty}:
     * <ul>
     *   <li>有 @empty → @empty 已经发出过 @endfor,这里只补 @endif;
     *   <li>无 @empty → 这里要补 @endfor 再补 @endif。
     * </ul>
     */
    private String closeForelse(int line) {
        close("forelse", "endforelse", line);
        loopStack.pop();
        boolean hadEmpty = !forelseHasEmpty.isEmpty() && forelseHasEmpty.pop();
        return hadEmpty ? "@endif" : "@endfor@endif";
    }

    private String currentBlockDirective() {
        OpenBlock b = blocks.peek();
        return b == null ? null : b.directive();
    }

    private String openForeach(String args, int line, boolean forelse) {
        if (args == null) {
            throw new JbladeException(templateName, line,
                    "@" + (forelse ? "forelse" : "foreach") + " 需要参数,形如 "
                  + "@" + (forelse ? "forelse" : "foreach") + " (posts as post)");
        }
        Matcher m = FOREACH_EXPR.matcher(args);
        if (!m.matches()) {
            throw new JbladeException(templateName, line,
                    "@" + (forelse ? "forelse" : "foreach") + " 的语法是 (集合 as 变量),收到:(" + args + ")"
                  + "\n  正确写法:@" + (forelse ? "forelse" : "foreach") + " (posts as post)");
        }
        String collection = rewriteExpression(m.group(1).trim());
        String var = m.group(2);

        open(forelse ? "forelse" : "foreach", line);
        if (forelse) forelseHasEmpty.push(false);

        String loopVar = "__loop" + (++loopCounter);
        String parent = loopStack.isEmpty() ? "null" : loopStack.peek();
        loopStack.push(loopVar);

        // 全部挤在一行,保持行号 1:1
        var sb = new StringBuilder();
        if (forelse) {
            sb.append("@if(io.jaravel.view.Jb.isNotEmpty(").append(collection).append("))");
        }
        sb.append("!{var ").append(loopVar).append(" = io.jaravel.view.LoopState.ofSize(")
          .append("io.jaravel.view.Jb.sizeOf(").append(collection).append("), ")
          .append(parent).append(");}")
          .append("@for(var ").append(var).append(" : io.jaravel.view.Jb.iter(").append(collection).append("))")
          .append("!{").append(loopVar).append(".advance();}");
        return sb.toString();
    }

    private String openSection(String args, int line) {
        List<String> parts = splitArgs(args);
        if (parts.isEmpty()) {
            throw new JbladeException(templateName, line, "@section 需要一个名字,如 @section('content')");
        }
        if (parts.size() >= 2) {
            // @section('title', 'Posts') —— 单值形式,不开块
            return "${ctx.sectionValue(" + normalizeString(parts.get(0)) + ", "
                 + rewriteExpression(parts.get(1)) + ")}";
        }
        open("section", line);
        return "${ctx.section(" + normalizeString(parts.get(0)) + ", @`";
    }

    private String handleYield(String args, int line) {
        List<String> parts = splitArgs(args);
        if (parts.isEmpty()) {
            throw new JbladeException(templateName, line, "@yield 需要一个名字,如 @yield('content')");
        }
        if (parts.size() >= 2) {
            return "${ctx.yield_(" + normalizeString(parts.get(0)) + ", "
                 + rewriteExpression(parts.get(1)) + ")}";
        }
        return "${ctx.yield_(" + normalizeString(parts.get(0)) + ")}";
    }

    private String handleInclude(String args, int line, boolean ifExists) {
        List<String> parts = splitArgs(args);
        if (parts.isEmpty()) {
            throw new JbladeException(templateName, line,
                    "@include 需要视图名,如 @include('partials.nav')");
        }
        String rawFirst = parts.get(0).trim();
        if (!isStringLiteral(rawFirst)) {
            throw new JbladeException(templateName, line,
                    "@include 的视图名必须是字符串字面量,不能是表达式(收到 " + rawFirst + ")。"
                  + "\n  原因:模板引用在编译期解析,动态名称无法静态检查。"
                  + "\n  修复:用 @if 分支分别 @include 各个候选模板。");
        }
        String view = stripQuotes(rawFirst);

        var sb = new StringBuilder("@template.").append(viewToTemplatePath(view)).append("(ctx = ctx");
        for (int i = 1; i < parts.size(); i++) {
            String p = parts.get(i).trim();
            int eq = p.indexOf('=');
            if (eq < 0) {
                throw new JbladeException(templateName, line,
                        "@include 的额外参数必须是命名形式 key = value,收到:" + p);
            }
            sb.append(", ").append(p.substring(0, eq).trim()).append(" = ")
              .append(rewriteExpression(p.substring(eq + 1).trim()));
        }
        sb.append(')');
        return sb.toString();
    }

    private void collectParams(String trimmed, int line) {
        String args = extractParens(trimmed);
        if (args == null) {
            throw new JbladeException(templateName, line,
                    "@params 需要括号,如 @params(User user, int page)");
        }
        for (String p : splitArgs(args)) {
            if (!p.isBlank()) params.add(p.trim());
        }
    }

    /**
     * {@code @props(...)} —— 组件模板的参数声明(docs/04 §1.7)。
     *
     * <p>它就是 {@code @params} **加上一个隐式的 slot 参数**:
     *
     * <pre>{@code
     * @props(String type = "info", String title = null)
     * }</pre>
     *
     * <p>→
     *
     * <pre>{@code
     * @param String type = "info"
     * @param String title = null
     * @param gg.jte.Content slot = null
     * }</pre>
     *
     * <h2>为什么 slot 是隐式的、而且默认 null</h2>
     * <p>隐式:每个组件都可能被带 body 调用,要求作者每次都写一遍
     * {@code gg.jte.Content slot = null} 是纯噪音。
     *
     * <p>默认 null:自闭合调用({@code <x-alert />})根本不传 slot,
     * 而 jte 的参数没有默认值时**调用方必须给** ——
     * 于是每个自闭合调用都要写 {@code slot = null}。
     *
     * <p>⚠ 后果:组件模板里 {@code ${slot}} 在没有 body 时输出空
     * (jte 对 null 的 Content 输出空),而不是报错。
     * 要求 body 的组件得自己判断:{@code @if(slot == null) ... @endif}。
     *
     * <h2>与 Blade 的偏差:要写类型</h2>
     * <p>Blade 是 {@code @props(['type' => 'info'])} —— 无类型。
     * 这里必须写 Java 类型,因为它落到 jte 的 {@code @param} 上。
     *
     * <p>那是 Java 的固有成本,但它换来一件 Blade 没有的事:
     * **传错类型是编译错误**。Blade 里把一个 User 传给期望 string 的
     * 组件,症状是页面上出现 "Object of class User could not be
     * converted to string" —— 而且是在生产的那个页面上。
     */
    private void collectProps(String trimmed, int line) {
        String args = extractParens(trimmed);
        if (args == null) {
            throw new JbladeException(templateName, line,
                    "@props 需要括号,如 @props(String type = \"info\")"
                  + "\n  ⚠ 与 Blade 不同,这里要写 Java 类型 —— 它落到 jte 的"
                  + " @param 上,于是传错类型是编译错误而不是线上的一行乱码。");
        }
        for (String p : splitArgs(args)) {
            if (!p.isBlank()) params.add(p.trim());
        }

        // 隐式 slot。放在最后 —— 它是「body」,语义上就该在参数列表末尾
        boolean declared = params.stream().anyMatch(p -> p.endsWith(" slot")
                || p.contains(" slot ") || p.contains(" slot="));
        if (!declared) params.add("gg.jte.Content slot = null");

        // 隐式属性袋。每个组件都有,默认空 —— 没有未声明属性的调用方什么都不用传。
        // 与 slot 同理:要求每个组件作者写一遍声明是纯噪音。
        params.add("io.jaravel.view.Attributes attributes = io.jaravel.view.Attributes.empty()");
    }

    private void collectImport(String trimmed) {
        String args = extractParens(trimmed);
        imports.add(args != null ? args.trim() : trimmed.substring(7).trim());
    }

    private void collectExtends(String trimmed, int line) {
        if (layout != null) {
            throw new JbladeException(templateName, line,
                    "一个模板只能有一个 @extends,已在前面声明过 '" + layout + "'");
        }
        List<String> parts = splitArgs(extractParens(trimmed));
        if (parts.isEmpty()) {
            throw new JbladeException(templateName, line,
                    "@extends 需要布局名,如 @extends('layouts.app')");
        }
        layout = stripQuotes(parts.get(0));
    }

    // ================= 组件(docs/04 §1.7)=================

    /** {@code <x-name ...>} / {@code <x-name ... />} / {@code </x-name>} */
    private static final Pattern COMPONENT_OPEN =
            Pattern.compile("<x-([A-Za-z0-9_.:-]+)((?:\\s+[^>]*?)?)(/?)>");
    private static final Pattern COMPONENT_CLOSE =
            Pattern.compile("</x-([A-Za-z0-9_.:-]+)\\s*>");

    /**
     * 具名 slot 的开闭标签。
     *
     * <p>闭合允许 {@code </x-slot>} 与 {@code </x-slot:footer>} 两种 ——
     * Blade 两种都收,而「我按另一种写法写的,于是整段跑到页面上」
     * 是个很难自己看出来的错。
     */
    private static final Pattern SLOT_OPEN  = Pattern.compile("<x-slot:([A-Za-z0-9_-]+)\\s*>");
    private static final Pattern SLOT_CLOSE = Pattern.compile("</x-slot(?::[A-Za-z0-9_-]+)?\\s*>");

    /**
     * 一个属性:{@code :prop="expr"} / {@code prop="literal"} / {@code prop}
     *
     * <p>单引号也收 —— Blade 两种都允许,而「我用了单引号所以属性没生效」
     * 是一个很难自己看出来的错误。
     */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "(:?)([A-Za-z_][A-Za-z0-9_-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'))?");

    /**
     * 把组件标签翻译成 jte 的模板调用。
     *
     * <h2>映射</h2>
     * <pre>
     *   &lt;x-alert type="danger" :count="n" dismissible /&gt;
     *     → @template.components.alert(ctx = ctx, type = "danger", count = n, dismissible = true)
     *
     *   &lt;x-card&gt;BODY&lt;/x-card&gt;
     *     → @template.components.card(ctx = ctx, slot = @`BODY`)
     * </pre>
     *
     * <p>两件事都是**实测过**才这么设计的:jte 支持把 {@code @`...`}
     * 作为命名参数传给模板调用,也支持 kebab-case 的模板文件名
     * (于是 {@code <x-post-card>} 能直接对应
     * {@code components/post-card.jblade.html},与 Blade 一致)。
     *
     * <h2>★ 为什么复用 {@code @template} 而不另造一套</h2>
     * <p>组件与 {@code @include} 在 jte 这一层是同一件事:
     * 带命名参数的模板调用。另造一套意味着两条路径上的
     * 参数传递、行号映射、编译期检查都要各写一遍 —— 而它们注定分叉。
     *
     * <p>代价是组件拿不到 Blade 的属性袋({@code {{ $attributes }}})——
     * 那需要把未声明的属性收集成一个对象,而 jte 的参数是静态的。
     * 这一条记在 docs/04。
     */
    private String translateComponents(String line, int sourceLine) {
        if (line.indexOf("<x-") < 0 && line.indexOf("</x-") < 0) return line;

        var sb = new StringBuilder();
        int cursor = 0;

        while (cursor < line.length()) {
            var slotOpen   = SLOT_OPEN.matcher(line);
            var slotClose  = SLOT_CLOSE.matcher(line);
            var open       = COMPONENT_OPEN.matcher(line);
            var close      = COMPONENT_CLOSE.matcher(line);

            int slotOpenAt  = slotOpen.find(cursor)  ? slotOpen.start()  : Integer.MAX_VALUE;
            int slotCloseAt = slotClose.find(cursor) ? slotClose.start() : Integer.MAX_VALUE;
            int openAt      = open.find(cursor)      ? open.start()      : Integer.MAX_VALUE;
            int closeAt     = close.find(cursor)     ? close.start()     : Integer.MAX_VALUE;

            int at = Math.min(Math.min(slotOpenAt, slotCloseAt), Math.min(openAt, closeAt));
            if (at == Integer.MAX_VALUE) {
                sb.append(line, cursor, line.length());
                break;
            }
            sb.append(line, cursor, at);

            // slot 标签优先于组件标签:`<x-slot:footer>` 两个模式都能匹上
            // (组件模式会把它读成名叫 `slot:footer` 的组件),在同一位置必须让 slot 赢。
            if (at == slotOpenAt) {
                sb.append(openNamedSlot(slotOpen.group(1), sourceLine));
                cursor = slotOpen.end();
            } else if (at == slotCloseAt) {
                sb.append(closeNamedSlot(sourceLine));
                cursor = slotClose.end();
            } else if (at == openAt) {
                sb.append(openTag(open.group(1), open.group(2),
                                  !open.group(3).isEmpty(), sourceLine));
                cursor = open.end();
            } else {
                // 组件闭合时还有具名 slot 开着 —— 通用的块不匹配消息会说
                // 「应先写 @endslot」,而那个指令不存在。
                if (!blocks.isEmpty() && "slot".equals(blocks.peek().directive())) {
                    throw new JbladeException(templateName, sourceLine,
                            "</x-" + close.group(1) + "> 之前有一个具名 slot 没闭合"
                          + "\n  第 " + blocks.peek().line() + " 行的 <x-slot:…> 还开着 ——"
                          + " 先补上 </x-slot>");
                }
                close("component", "</x-" + close.group(1) + ">", sourceLine);
                sb.append(sentinel("CC", tag(close.group(1), sourceLine, "")));
                cursor = close.end();
            }
        }
        return sb.toString();
    }

    private String openTag(String name, String rawAttributes, boolean selfClosing,
                           int sourceLine) {
        String view = componentPath(name);
        calledComponents.add(view);

        var call = new StringBuilder("@template.").append(view).append("(ctx = ctx");

        for (String attribute : parseAttributes(view, rawAttributes, sourceLine)) {
            call.append(", ").append(attribute);
        }

        if (selfClosing) {
            call.append(')');
            return call.toString();
        }

        // 带 body:调用本体先不发出去 —— 具名 slot 可能还要往参数表里加东西。
        // 这里只留哨兵,真正的拼装在 assembleComponent。
        open("component", sourceLine);
        return sentinel("CO", tag(name, sourceLine, call.toString()));
    }

    /**
     * 具名 slot(docs/04 §1.7):{@code <x-slot:footer>…</x-slot:footer>}
     * → 那次组件调用的 {@code slots} 参数里的一项。
     *
     * <p>组件里用 {@code hasSlot("footer")} 与 {@code slot("footer")} 读 ——
     * 见 {@link #collectProps} 里隐式声明的 {@code slots} 参数。
     *
     * <p>名字**原样**作 Map 的键,不转驼峰:它是字符串而不是 Java 参数名,
     * 转了反而让 {@code <x-slot:sub-copy>} 与 {@code slot("sub-copy")} 对不上。
     */
    private String openNamedSlot(String name, int sourceLine) {
        if (!blocks.isEmpty() && "slot".equals(blocks.peek().directive())) {
            throw new JbladeException(templateName, sourceLine,
                    "具名 slot 不能直接套在另一个具名 slot 里"
                  + "\n  上一个在第 " + blocks.peek().line() + " 行还没闭合 ——"
                  + " 少了 </x-slot> ?"
                  + "\n  (slot 里嵌**组件**是可以的,组件再带自己的 slot 也可以。)");
        }
        if (blocks.isEmpty() || !"component".equals(blocks.peek().directive())) {
            throw new JbladeException(templateName, sourceLine,
                    "<x-slot:" + name + "> 必须写在组件标签内部"
                  + "\n  它是「给这次组件调用的一个具名 slot」,脱离组件没有意义。"
                  + "\n  正确写法:"
                  + "\n    <x-alert>"
                  + "\n      正文"
                  + "\n      <x-slot:" + name + ">…</x-slot:" + name + ">"
                  + "\n    </x-alert>");
        }
        open("slot", sourceLine);
        return sentinel("SO", tag(name, sourceLine, ""));
    }

    private String closeNamedSlot(int sourceLine) {
        OpenBlock b = blocks.peek();
        if (b == null || !"slot".equals(b.directive())) {
            throw new JbladeException(templateName, sourceLine,
                    "</x-slot> 没有对应的 <x-slot:…>"
                  + (b == null ? "" : "\n  这里未闭合的是 @" + b.directive()
                                    + "(第 " + b.line() + " 行开始)"));
        }
        blocks.pop();
        return sentinel("SC", tag("", sourceLine, ""));
    }

    // ---------- 哨兵 ----------

    /**
     * 组件标签在行里留下的占位。
     *
     * <p>为什么要占位:组件翻译跑在指令/输出翻译**之前**(见
     * {@link #translateLine} 的注释),所以这一步拿不到最终文本;
     * 而帧的拼装必须在最终文本上做。哨兵让组件标签的位置
     * 一路穿过后面两步翻译,到 {@link #emitRouted} 再被切开。
     *
     * <p>形状:不含 {@code @}、{@code {{}}}、引号或反引号 ——
     * 否则它会被后面的翻译步骤或 jte 自己吃掉。
     *
     * <p>★ 两端是 {@code ~} 而**不是下划线**:下划线是 word 字符,
     * 于是 {@code @endforeach__JB_CC_2__} 会被指令正则的 {@code \w+}
     * 整段吃进指令名 —— @foreach 永远等不到它的 @endforeach,
     * 而报错是「@foreach 到文件结束仍未闭合」,与真正的原因
     * (紧跟在后面的一个组件闭合标签)毫无关系。
     */
    private static final Pattern SENTINEL = Pattern.compile("~JBTAG_(CO|CC|SO|SC)_(\\d+)~");

    private record TagInfo(String name, int line, String text) {}

    private final Map<Integer, TagInfo> tags = new HashMap<>();
    private int tagCounter = 0;

    private int tag(String name, int line, String text) {
        int id = ++tagCounter;
        tags.put(id, new TagInfo(name, line, text));
        return id;
    }

    private static String sentinel(String kind, int id) {
        return "~JBTAG_" + kind + "_" + id + "~";
    }

    /**
     * {@code x-post-card} → {@code components.post-card};
     * {@code x-mail::message} → {@code components.mail.message}。
     *
     * <p>{@code ::} 是 Blade 的命名空间写法(邮件组件用它:
     * {@code <x-mail::message>})。这里映射成一层子目录 ——
     * 于是 {@code components/mail/message.jblade.html}。
     */
    private static String componentPath(String name) {
        return "components." + name.replace("::", ".");
    }

    /**
     * 属性 → jte 的命名参数。
     *
     * <ul>
     *   <li>{@code :count="n"} → {@code count = n}(表达式,过一遍重写);</li>
     *   <li>{@code type="danger"} → {@code type = "danger"}(字面量);</li>
     *   <li>{@code dismissible} → {@code dismissible = true}(布尔简写)。</li>
     * </ul>
     *
     * <p>⚠ 属性名里的连字符会被转成驼峰:{@code :user-id="x"} →
     * {@code userId = x}。理由:Java 的参数名不能带连字符,而
     * Blade 的习惯是 kebab。不转的话这个最自然的写法会变成一个
     * 语法错误,而错误会指向生成的 jte 代码。
     */
    private List<String> parseAttributes(String componentView, String rawAttributes,
                                         int sourceLine) {
        var out = new ArrayList<String>();
        if (rawAttributes == null || rawAttributes.isBlank()) return out;

        // 组件声明了哪些 prop。empty = 不知道 → 全部当成声明过的(属性袋之前的行为)
        Optional<List<String>> declared = componentProps.propsOf(componentView);
        var bag = new ArrayList<String>();

        var matcher = ATTRIBUTE.matcher(rawAttributes);
        while (matcher.find()) {
            boolean bound = !matcher.group(1).isEmpty();
            String rawName = matcher.group(2);
            String name = camelAttribute(rawName);
            String doubleQuoted = matcher.group(3);
            String singleQuoted = matcher.group(4);
            String value = doubleQuoted != null ? doubleQuoted : singleQuoted;

            if (value == null && bound) {
                throw new JbladeException(templateName, sourceLine,
                        "组件属性 :" + rawName + " 用了冒号前缀"
                      + "(表示传表达式)但没给值。"
                      + "\n  传表达式:  :" + rawName + "=\"user.id\""
                      + "\n  布尔简写:  " + rawName + "(不带冒号)");
            }

            String javaValue = value == null ? "true"
                             : bound ? rewriteExpression(value)
                             : '"' + value.replace("\"", "\\\"") + '"';

            // 组件没声明 → 进属性袋,**用原名**(id、data-test、class 要原样出现在 HTML 里)
            if (declared.isPresent() && !declared.get().contains(name)) {
                bag.add(javaStringLiteral(rawName) + ", " + javaValue);
            } else {
                out.add(name + " = " + javaValue);
            }
        }

        if (!bag.isEmpty()) {
            out.add("attributes = io.jaravel.view.Attributes.of("
                    + String.join(", ", bag) + ")");
        }
        return out;
    }

    private static String camelAttribute(String name) {
        if (name.indexOf('-') < 0) return name;

        var sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '-') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    // ================= 表达式重写 =================

    /**
     * Blade 表达式 → Java 表达式。
     *
     * <p>jblade **不发明表达式语言**(docs/04 §1.3):表达式原样作为 Java 编译,
     * javac 就是免费的类型检查器。只做两项必要的语法糖转换:
     * <ul>
     *   <li>{@code a ?? b} → {@code Jb.coalesce(a, b)}
     *   <li>{@code loop.xxx} → {@code __loopN.xxx()}
     * </ul>
     *
     * <p>刻意**不做** {@code user.name → user.getName()} 的转换 ——
     * 模型的标量列是 public 字段(ADR-010),{@code user.name} 本身就是合法 Java。
     * 这是 V1 验证带来的简化:少了一整套属性路径解析。
     */
    String rewriteExpression(String expr) {
        if (expr == null) return "";
        String work = expr.trim();

        work = rewriteSingleQuotedStrings(work);
        work = rewriteLoopReferences(work);
        work = rewriteSlotAccess(work);
        work = rewriteNullCoalescing(work);
        return work;
    }

    /**
     * {@code slot("footer")} → {@code slots.get("footer")};
     * {@code hasSlot("footer")} → {@code slots.containsKey("footer")}。
     *
     * <p>为什么不让模板直接写 {@code slots.get(...)}:那是实现细节
     * (具名 slot 走一个 Map 而默认 slot 走一个参数),而 docs/04 承诺的
     * 写法是 {@code slot("footer")}。两者差一次重写,换来的是
     * 以后换掉容器类型不用改模板。
     *
     * <p>⚠ 前面不能是点号或标识符字符:否则 {@code booking.slot("am")}
     * 这种**模型自己的方法**会被改成具名 slot 的读取 —— 一个只在恰好有
     * 同名方法时才出现的错,而症状(编译错误指向生成码)与原因
     * (模板里一个普通的方法调用)离得很远。
     * 同理 {@code hasSlot} 不会被当成 {@code slot}。
     */
    private static final Pattern SLOT_CALL =
            Pattern.compile("(?<![.\\w])(has)?[Ss]lot\\s*\\(\\s*(['\"])(.*?)\\2\\s*\\)");

    /**
     * 组件模板里读到的具名 slot → 它的 Java 参数名。
     *
     * <p>**用到就自动声明**(见 {@link #slotParams} 的用法):组件模板自己
     * 就在转写器眼前,它读了哪些具名 slot 是本地可见的,不需要跨模板分析。
     */
    private final Map<String, String> namedSlotReads = new LinkedHashMap<>();

    String rewriteSlotAccess(String expr) {
        if (expr.indexOf("lot") < 0) return expr;   // 绝大多数表达式在这里就走了
        Matcher m = SLOT_CALL.matcher(expr);
        var sb = new StringBuilder();
        while (m.find()) {
            String param = slotParam(m.group(3));
            namedSlotReads.put(m.group(3), param);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    m.group(1) == null ? param : "(" + param + " != null)"));
        }
        m.appendTail(sb);

        // slot 名必须是字面量:它要变成一个 Java 参数名,而那是编译期的事。
        // 不拦的话 javac 会说「找不到方法 slot(String)」—— 指向生成码,
        // 而模板里那一行看着很合理。
        if (SLOT_RESIDUAL.matcher(sb).find()) {
            throw new JbladeException(templateName, 0,
                    "slot(...) / hasSlot(...) 的名字必须是字面量"
                  + "\n  在:" + expr.trim()
                  + "\n  具名 slot 在编译期变成组件的一个参数,所以名字不能是变量。"
                  + "\n  要按条件选内容的话,在组件里用 @if 分几个 slot 写。");
        }
        return sb.toString();
    }

    /** 没被上面吃掉的 {@code slot(} / {@code hasSlot(} —— 名字不是字面量 */
    private static final Pattern SLOT_RESIDUAL =
            Pattern.compile("(?<![.\\w])(has)?[Ss]lot\\s*\\(");

    /**
     * slot 名 → Java 参数名:{@code sub-copy} → {@code subCopy}。
     *
     * <p>模板里始终写**原名**({@code slot("sub-copy")}、
     * {@code <x-slot:sub-copy>});驼峰只活在生成码里,因为 Java 的参数名
     * 带不了连字符。两边用同一个函数转,所以不会对不上。
     */
    private static String slotParam(String slotName) {
        return "__slot_" + camelAttribute(slotName);
    }

    /**
     * Blade 习惯用单引号写字符串,但 Java 里多字符单引号是非法的字符字面量。
     * 统一转成双引号。
     *
     * <p><b>已知取舍:</b>真想写 char 字面量的场景(极少)会被一并转换。
     * 需要 char 时请写 {@code "x".charAt(0)}。
     */
    static String rewriteSingleQuotedStrings(String expr) {
        if (expr.indexOf(SQUOTE) < 0) return expr;
        var sb = new StringBuilder(expr.length());
        boolean inDouble = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == BACKSLASH && i + 1 < expr.length()) {   // 转义序列整体照抄
                sb.append(c).append(expr.charAt(++i));
                continue;
            }
            if (c == DQUOTE) { inDouble = !inDouble; sb.append(c); continue; }
            if (c == SQUOTE && !inDouble) {
                int end = findClosingSingleQuote(expr, i);
                if (end < 0) { sb.append(c); continue; }      // 不成对,原样保留
                String body = expr.substring(i + 1, end);
                sb.append(DQUOTE).append(body.replace("\"", "\\\"")).append(DQUOTE);
                i = end;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int findClosingSingleQuote(String s, int from) {
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == BACKSLASH) { i++; continue; }
            if (c == SQUOTE) return i;
        }
        return -1;
    }

    private static boolean isStringLiteral(String s) {
        String t = s.trim();
        if (t.length() < 2) return false;
        char q = t.charAt(0);
        return (q == SQUOTE || q == DQUOTE) && t.charAt(t.length() - 1) == q;
    }

    private String rewriteLoopReferences(String expr) {
        if (!expr.contains("loop.")) return expr;
        if (loopStack.isEmpty()) return expr;      // 循环外的 loop. 原样交给 javac 报错

        String current = loopStack.peek();
        // loop.parent.index → __loopN.parent().index()
        String work = expr.replaceAll("\\bloop\\.parent\\.(\\w+)\\b", current + ".parent().$1()");
        work = work.replaceAll("\\bloop\\.parent\\b", current + ".parent()");
        work = work.replaceAll("\\bloop\\.(\\w+)\\b", current + ".$1()");
        return work;
    }

    /** {@code a ?? b} → {@code Jb.coalesce(a, b)}。只处理顶层的一个 ??,嵌套场景建议显式写。 */
    private String rewriteNullCoalescing(String expr) {
        int idx = indexOfTopLevel(expr, "??");
        if (idx < 0) return expr;
        String left = expr.substring(0, idx).trim();
        String right = expr.substring(idx + 2).trim();
        return "io.jaravel.view.Jb.coalesce(" + left + ", " + rewriteNullCoalescing(right) + ")";
    }

    /** 找括号/引号之外的 token 位置 */
    private static int indexOfTopLevel(String s, String token) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote && (i == 0 || s.charAt(i - 1) != '\\')) quote = 0; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (depth == 0 && s.startsWith(token, i)) return i;
        }
        return -1;
    }

    // ================= 块结构校验 =================

    private void open(String directive, int line) {
        blocks.push(new OpenBlock(directive, line));
    }

    private void close(String expected, String endDirective, int line) {
        OpenBlock b = blocks.poll();
        if (b == null) {
            throw new JbladeException(templateName, line,
                    "@" + endDirective + " 没有对应的 @" + expected);
        }
        if (!b.directive().equals(expected)) {
            throw new JbladeException(templateName, line,
                    "@" + endDirective + " 与未闭合的 @" + b.directive()
                  + "(第 " + b.line() + " 行)不匹配"
                  + "\n  应先写 @end" + b.directive());
        }
    }

    // ================= 输出装配 =================

    private void emit(String jteLine, int sourceLine) {
        emit(jteLine, sourceLine, true);
    }

    /**
     * 组件 body 内的行不直接落到产物里,而是进当前帧的缓冲区
     * —— 见 {@link ComponentFrame}。
     */
    private void emit(String jteLine, int sourceLine, boolean newline) {
        var piece = new Piece(jteLine, sourceLine, newline);
        ComponentFrame frame = componentFrames.peek();
        if (frame != null) {
            frame.target().add(piece);
            return;
        }
        append(piece);
    }

    private void append(Piece p) {
        jteLines.add(p.text());
        jteLineSources.add(p.sourceLine());
        jteLineNewline.add(p.newline());
    }

    /**
     * 把翻译完的一行按组件哨兵切开,逐片分派(开组件 → 压帧,
     * 闭组件 → 拼装并落到上一层)。
     *
     * <p>**换行标志只给最后一片** —— 否则一行里的 {@code <x-card>x</x-card>}
     * 会在中间多出换行,HTML 里是空白噪音,而在 {@code <pre>} 里是可见的错。
     */
    private void emitRouted(String finished, int sourceLine, boolean newline) {
        Matcher m = SENTINEL.matcher(finished);
        int cursor = 0;
        while (m.find()) {
            String text = finished.substring(cursor, m.start());
            if (!text.isEmpty()) emit(text, sourceLine, false);
            handleSentinel(m.group(1), Integer.parseInt(m.group(2)), sourceLine);
            cursor = m.end();
        }
        // 尾片无条件发出:哪怕是空串,本行的换行也得落下去
        emit(finished.substring(cursor), sourceLine, newline);
    }

    private void handleSentinel(String kind, int id, int sourceLine) {
        switch (kind) {
            case "CO" -> componentFrames.push(
                    new ComponentFrame(tags.get(id).name(), tags.get(id).line(),
                                       tags.get(id).text()));
            case "SO" -> {
                ComponentFrame frame = componentFrames.peek();
                frame.capturing = tags.get(id).name();
                frame.namedSlots.putIfAbsent(frame.capturing, new ArrayList<>());
            }
            case "SC" -> componentFrames.peek().capturing = null;
            case "CC" -> assembleComponent(componentFrames.pop(), sourceLine);
            default -> throw new IllegalStateException(kind);
        }
    }

    /**
     * 帧 → 完整的 jte 调用。
     *
     * <pre>
     * @template.components.alert(ctx = ctx, type = "error"
     *     , __slot_footer = @`
     *   &lt;a href="/help"&gt;帮助&lt;/a&gt;        ← 原样,带原行号
     * `
     *     , slot = @`
     *   操作失败                            ← 原样,带原行号
     * `)
     * </pre>
     *
     * <h2>★ 为什么每个具名 slot 一个参数,而不是一个 Map</h2>
     * <p>{@code slots = java.util.Map.of("footer", @`…`)} 也能跑(实测过),
     * 选直接参数的理由是**报错响亮**:{@code <x-slot:footr>} 打错名字时
     * jte 报「没有 __slot_footr 这个参数」,而 Map 方案下它会**静默**
     * 变成一个没人读的键 —— 症状是「那块内容不见了」。
     *
     * <h2>⚠ 具名 slot 内的行号是近似的(实测结论,不是推测)</h2>
     * <p>本类的映射是对的:上面那个例子里,{@code post.titel} 所在的生成码
     * 行确实映回模板里那一行。失准在 **jte 自己的 Java→jte 映射**:
     * 一次调用带两个 Content 块时,前一个块内的编译错误被归到调用的收尾处。
     *
     * <ul>
     *   <li>默认 slot(调用里只有一个 Content 块):**精确**;
     *   <li>具名 slot:落在组件调用的范围内,靠代码片段给上下文。
     * </ul>
     *
     * <p>换成 Map 或先提到局部变量都试过,同样不准 —— 这一跳不在我们手里。
     */
    private void assembleComponent(ComponentFrame frame, int closeLine) {
        var glue = new StringBuilder(frame.callPrefix);
        var pieces = new ArrayList<Piece>();

        for (var entry : frame.namedSlots.entrySet()) {
            glue.append(", ").append(slotParam(entry.getKey())).append(" = @`");
            pieces.add(new Piece(glue.toString(), frame.line, false));
            glue.setLength(0);
            pieces.addAll(entry.getValue());
            glue.append('`');
        }

        glue.append(", slot = @`");
        pieces.add(new Piece(glue.toString(), frame.line, false));
        pieces.addAll(frame.defaultSlot);
        pieces.add(new Piece("`)", closeLine, false));

        // 落到上一层:外层帧的缓冲区,或产物
        ComponentFrame parent = componentFrames.peek();
        if (parent != null) parent.target().addAll(pieces);
        else pieces.forEach(this::append);
    }

    private void emitGenerated(String jteLine) {
        jteLines.add(jteLine);
        jteLineSources.add(0);
        jteLineNewline.add(true);
    }

    private String assemble() {
        var sb = new StringBuilder();
        // 头部:imports → params。ctx 参数恒存在。
        for (String imp : imports) sb.append("@import ").append(imp).append('\n');
        sb.append("@param io.jaravel.view.JbContext ctx\n");
        for (String p : params) sb.append("@param ").append(p).append('\n');
        for (int i = 0; i < jteLines.size(); i++) {
            sb.append(jteLines.get(i));
            // 顶层指令行不追加换行,否则那些换行会跑到布局输出之前
            if (jteLineNewline.get(i)) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 行号映射:**每一行生成码一条记录**。
     *
     * <p>★ 不能按 emit 的次数来数 —— {@code newline == false} 的碎片会
     * 几条合成一行(组件调用的胶水碎片就是),按次数数的话映射会整体
     * 往后漂,而症状是「报错指向模板里另一行」—— 行号看着像对的,
     * 于是最难怀疑到映射本身。
     *
     * <p>一行里有多个来源时,取**第一条有内容的**:生成码里那行的
     * 可见文本通常就是它,而胶水碎片(参数表、反引号)没有对应的模板文本。
     */
    private LineMap buildLineMap() {
        // 头部行数 = imports + ctx 参数 + params
        int header = imports.size() + 1 + params.size();
        for (int i = 0; i < header; i++) lineMap.emitGenerated();

        int chosen = 0;
        int fallback = 0;
        for (int i = 0; i < jteLines.size(); i++) {
            int src = jteLineSources.get(i);
            if (src > 0) {
                if (fallback == 0) fallback = src;
                if (chosen == 0 && !jteLines.get(i).isBlank()) chosen = src;
            }
            if (!jteLineNewline.get(i)) continue;   // 这一行还没结束

            int use = chosen > 0 ? chosen : fallback;
            if (use > 0) lineMap.emit(use); else lineMap.emitGenerated();
            chosen = 0;
            fallback = 0;
        }
        // 末尾没有换行的残行也算一行
        if (!jteLines.isEmpty() && !jteLineNewline.get(jteLines.size() - 1)) {
            int use = chosen > 0 ? chosen : fallback;
            if (use > 0) lineMap.emit(use); else lineMap.emitGenerated();
        }
        return lineMap.build();
    }

    // ================= 工具 =================

    /** {@code posts.index} → {@code posts.index}(jte 的 @template 用点号路径) */
    static String viewToTemplatePath(String viewName) {
        return viewName.replace('/', '.');
    }

    private static String extractParens(String s) {
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        return (open < 0 || close < open) ? null : s.substring(open + 1, close);
    }

    /** 按顶层逗号切分参数,忽略括号与引号内的逗号 */
    static List<String> splitArgs(String args) {
        List<String> out = new ArrayList<>();
        if (args == null || args.isBlank()) return out;
        int depth = 0;
        char quote = 0;
        var cur = new StringBuilder();
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote && (i == 0 || args.charAt(i - 1) != '\\')) quote = 0;
                continue;
            }
            switch (c) {
                case '"', '\'' -> { quote = c; cur.append(c); }
                case '(', '[', '<' -> { depth++; cur.append(c); }
                case ')', ']', '>' -> { depth--; cur.append(c); }
                case ',' -> {
                    if (depth == 0) { out.add(cur.toString().trim()); cur.setLength(0); }
                    else cur.append(c);
                }
                default -> cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString().trim());
        return out;
    }

    /** 把任意文本变成合法的 Java 字符串字面量(含引号) */
    static String javaStringLiteral(String text) {
        var sb = new StringBuilder(text.length() + 2).append(DQUOTE);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.append(DQUOTE).toString();
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"')
                && t.charAt(t.length() - 1) == t.charAt(0)) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** Blade 的单引号字符串 → Java 的双引号字符串 */
    private static String normalizeString(String s) {
        return rewriteSingleQuotedStrings(s.trim());
    }

    private String firstStringArg(String args, String directive, int line) {
        List<String> parts = splitArgs(args);
        if (parts.isEmpty()) {
            throw new JbladeException(templateName, line,
                    "@" + directive + " 需要一个名字参数");
        }
        return normalizeString(parts.get(0));
    }

    private String expr(String args, String directive, int line) {
        if (args == null || args.isBlank()) {
            throw new JbladeException(templateName, line,
                    "@" + directive + " 需要一个表达式,如 @" + directive + " (user.isAdmin())");
        }
        return rewriteExpression(args);
    }
}
