package io.jaravel.view;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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

    private String layout;
    private final List<String> params = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();
    /** 循环嵌套栈,存 LoopState 的局部变量名 */
    private final Deque<String> loopStack = new ArrayDeque<>();
    private int loopCounter = 0;
    /** 每层 forelse 是否已遇到 @empty —— 决定 @endforelse 要不要补 @endfor */
    private final Deque<Boolean> forelseHasEmpty = new ArrayDeque<>();
    /** @verbatim 块内不做任何转换 */
    private boolean inVerbatim = false;
    /** 块结构校验栈 */
    private final Deque<OpenBlock> blocks = new ArrayDeque<>();

    private record OpenBlock(String directive, int line) {}

    private JbladeTranspiler(String source, String templateName) {
        this.source = source;
        this.templateName = templateName;
        this.lineMap = LineMap.builder(templateName);
    }

    public record Result(String jteSource, LineMap lineMap, List<String> declaredParams,
                         Optional<String> layout) {}

    public static Result transpile(String source, String templateName) {
        return new JbladeTranspiler(source, templateName).run();
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

            String translated = translateLine(line, sourceLine);
            if (translated != null) emit(translated, sourceLine);
        }

        if (!blocks.isEmpty()) {
            OpenBlock b = blocks.peek();
            throw new JbladeException(templateName, b.line(),
                    "@" + b.directive() + " 没有对应的结束指令 @end" + b.directive()
                  + "\n  在 " + templateName + " 第 " + b.line() + " 行打开,到文件结束仍未闭合");
        }

        // 布局调用必须在最后:此刻全部 section/push 已注册
        if (layout != null) {
            emitGenerated("@template." + viewToTemplatePath(layout) + "(ctx = ctx)");
        }

        return new Result(assemble(), buildLineMap(), List.copyOf(params),
                          Optional.ofNullable(layout));
    }

    // ================= 行翻译 =================

    /** 返回 null 表示该行不产出 jte 内容(如 @params 已提取到头部) */
    private String translateLine(String line, int sourceLine) {
        String trimmed = line.trim();

        // 头部声明单独处理
        if (trimmed.startsWith("@params")) { collectParams(trimmed, sourceLine); return ""; }
        if (trimmed.startsWith("@param "))  { params.add(trimmed.substring(7).trim()); return ""; }
        if (trimmed.startsWith("@import"))  { collectImport(trimmed); return ""; }
        if (trimmed.startsWith("@extends")) { collectExtends(trimmed, sourceLine); return ""; }

        // 注释先去掉,避免其中的 @ 与 {{ 被当作指令
        // 注意:replaceAll(Function) 的返回值**整体**会按 replacement 语法解析($、\ 有特殊含义),
        // 因此必须对整个结果调用 quoteReplacement,而不是只包其中一部分。
        String work = COMMENT.matcher(line)
                .replaceAll(m -> Matcher.quoteReplacement("<%--" + m.group(1) + "--%>"));

        work = translateDirectives(work, sourceLine);
        work = translateEchos(work);
        return work;
    }

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
                Matcher.quoteReplacement("$unsafe{" + rewriteExpression(m.group(1)) + "}"));
        work = ESC_ECHO.matcher(work).replaceAll(m ->
                Matcher.quoteReplacement("${" + rewriteExpression(m.group(1)) + "}"));

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
        work = rewriteNullCoalescing(work);
        return work;
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
        jteLines.add(jteLine);
        jteLineSources.add(sourceLine);
    }

    private void emitGenerated(String jteLine) {
        jteLines.add(jteLine);
        jteLineSources.add(0);
    }

    private String assemble() {
        var sb = new StringBuilder();
        // 头部:imports → params。ctx 参数恒存在。
        for (String imp : imports) sb.append("@import ").append(imp).append('\n');
        sb.append("@param io.jaravel.view.JbContext ctx\n");
        for (String p : params) sb.append("@param ").append(p).append('\n');
        for (String l : jteLines) sb.append(l).append('\n');
        return sb.toString();
    }

    private LineMap buildLineMap() {
        // 头部行数 = imports + ctx 参数 + params
        int header = imports.size() + 1 + params.size();
        for (int i = 0; i < header; i++) lineMap.emitGenerated();
        for (int src : jteLineSources) {
            if (src > 0) lineMap.emit(src); else lineMap.emitGenerated();
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
