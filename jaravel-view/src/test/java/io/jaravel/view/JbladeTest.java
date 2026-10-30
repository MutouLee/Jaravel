package io.jaravel.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JbladeTest {

    private static java.util.List<TestPost> posts() { return TestPost.sample(); }

    private JbladeEngine engine(@TempDir Path tmp, Map<String, String> templates) {
        return JbladeEngine.forMap(templates, tmp.resolve("jte-classes"),
                                   JbladeTest.class.getClassLoader());
    }

    // ================= 输出 =================

    @Nested
    @DisplayName("输出与转义")
    class Output {

        @Test void escapedByDefault(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(String name)
                <p>{{ name }}</p>
                """));
            assertThat(e.render("t", Map.of("name", "<b>x</b>")))
                    .contains("&lt;b&gt;x&lt;/b&gt;")
                    .doesNotContain("<b>x</b>");
        }

        @Test void rawOutput(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(String html)
                <div>{!! html !!}</div>
                """));
            assertThat(e.render("t", Map.of("html", "<b>bold</b>"))).contains("<b>bold</b>");
        }

        @Test void comments(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                <p>a</p>
                {{-- 这段不应出现在产物里 --}}
                <p>b</p>
                """));
            String out = e.render("t", Map.of());
            assertThat(out).contains("<p>a</p>", "<p>b</p>")
                           .doesNotContain("不应出现");
        }

        @Test
        @DisplayName("@{{ x }} 原样输出,交给前端框架")
        void verbatimEcho(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", "<span>@{{ vue.value }}</span>\n"));
            assertThat(e.render("t", Map.of())).contains("{{ vue.value }}");
        }

        @Test
        @DisplayName("public 字段直接访问 —— ADR-010 的收益:无需属性路径转换")
        void publicFieldAccess(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(io.jaravel.view.TestPost post)
                <h1>{{ post.title }}</h1>
                <p>{{ post.excerpt() }}</p>
                """));
            String out = e.render("t", Map.of("post", posts().get(0)));
            assertThat(out).contains("第一篇").contains("正文甲");
        }

        @Test
        @DisplayName("?? 空值合并")
        void nullCoalescing(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(String name)
                <p>{{ name ?? "Guest" }}</p>
                """));
            assertThat(e.render("t", new java.util.HashMap<>(Map.of()) {{ put("name", null); }}))
                    .contains("Guest");
        }

        @Test void verbatimBlock(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @verbatim
                {{ 不解析 }} @if(也不解析)
                @endverbatim
                """));
            assertThat(e.render("t", Map.of())).contains("{{ 不解析 }}").contains("@if(也不解析)");
        }
    }

    // ================= 控制结构 =================

    @Nested
    @DisplayName("控制结构")
    class ControlFlow {

        @Test void ifElseifElse(@TempDir Path tmp) {
            String tpl = """
                @params(int n)
                @if (n > 1)
                many
                @elseif (n == 1)
                one
                @else
                none
                @endif
                """;
            var e = engine(tmp, Map.of("t", tpl));
            assertThat(e.render("t", Map.of("n", 5))).contains("many");
            assertThat(e.render("t", Map.of("n", 1))).contains("one");
            assertThat(e.render("t", Map.of("n", 0))).contains("none");
        }

        @Test void unless(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(boolean verified)
                @unless (verified)
                请先验证邮箱
                @endunless
                """));
            assertThat(e.render("t", Map.of("verified", false))).contains("请先验证");
            assertThat(e.render("t", Map.of("verified", true))).doesNotContain("请先验证");
        }

        @Test void foreachWithLoopVariable(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(java.util.List<io.jaravel.view.TestPost> posts)
                @foreach (posts as post)
                <li>{{ loop.iteration }}/{{ loop.count }} {{ post.title }} first={{ loop.first }} last={{ loop.last }}</li>
                @endforeach
                """));
            String out = e.render("t", Map.of("posts", posts()));
            assertThat(out).contains("1/3", "2/3", "3/3")
                           .contains("first=true").contains("last=true");
            // 第一篇是 first 但不是 last
            assertThat(out).contains("1/3 第一篇 first=true last=false");
        }

        @Test
        @DisplayName("嵌套循环的 loop 变量互不干扰,且 loop.parent 可用")
        void nestedLoops(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(java.util.List<String> outer, java.util.List<String> inner)
                @foreach (outer as o)
                @foreach (inner as i)
                <span>{{ loop.parent.iteration }}.{{ loop.iteration }} depth={{ loop.depth }}</span>
                @endforeach
                @endforeach
                """));
            String out = e.render("t", Map.of("outer", List.of("A", "B"), "inner", List.of("x", "y")));
            assertThat(out).contains("1.1", "1.2", "2.1", "2.2").contains("depth=2");
        }

        @Test
        @DisplayName("@forelse 空集合走 @empty 分支")
        void forelse(@TempDir Path tmp) {
            String tpl = """
                @params(java.util.List<String> items)
                @forelse (items as item)
                <li>{{ item }}</li>
                @empty
                <p>暂无内容</p>
                @endforelse
                """;
            var e = engine(tmp, Map.of("t", tpl));
            assertThat(e.render("t", Map.of("items", List.of("a")))).contains("<li>a</li>")
                    .doesNotContain("暂无内容");
            assertThat(e.render("t", Map.of("items", List.of()))).contains("暂无内容");
        }

        @Test void emptyDirective(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(java.util.List<String> items)
                @empty (items)
                <p>空的</p>
                @endempty
                """));
            assertThat(e.render("t", Map.of("items", List.of()))).contains("空的");
            assertThat(e.render("t", Map.of("items", List.of("x")))).doesNotContain("空的");
        }

        @Test void classicForAndWhile(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @for (int i = 0; i < 3; i++)
                <li>{{ i }}</li>
                @endfor
                """));
            assertThat(e.render("t", Map.of())).contains("<li>0</li>", "<li>1</li>", "<li>2</li>");
        }

        @Test
        @DisplayName("foreach 可遍历 null,当作空集合(模板不该因 null 炸)")
        void foreachToleratesNull(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(java.util.List<String> items)
                @foreach (items as item)
                <li>{{ item }}</li>
                @endforeach
                done
                """));
            var data = new java.util.HashMap<String, Object>();
            data.put("items", null);
            assertThat(e.render("t", data)).contains("done").doesNotContain("<li>");
        }
    }

    // ================= 布局继承 =================

    @Nested
    @DisplayName("布局继承(@extends/@section/@yield/@stack)")
    class Layouts {

        private Map<String, String> layoutTemplates() {
            return Map.of(
                "layouts.app", """
                    <!DOCTYPE html>
                    <html>
                    <head>
                    <title>@yield('title', '默认标题')</title>
                    @stack('styles')
                    </head>
                    <body>
                    <main>@yield('content')</main>
                    @stack('scripts')
                    </body>
                    </html>
                    """,
                "posts.index", """
                    @params(java.util.List<io.jaravel.view.TestPost> posts)
                    @extends('layouts.app')
                    @section('title', '文章列表')
                    @section('content')
                    @foreach (posts as post)
                    <article>{{ post.title }}</article>
                    @endforeach
                    @endsection
                    @push('scripts')
                    <script src="/js/posts.js"></script>
                    @endpush
                    @prepend('styles')
                    <link rel="stylesheet" href="/css/posts.css">
                    @endprepend
                    """);
        }

        @Test void sectionsAndYields(@TempDir Path tmp) {
            var e = engine(tmp, layoutTemplates());
            String out = e.render("posts.index", Map.of("posts", posts()));

            assertThat(out).contains("<title>文章列表</title>");
            assertThat(out).contains("<article>第一篇</article>");
            assertThat(out).contains("/js/posts.js");
            assertThat(out).contains("/css/posts.css");
        }

        @Test
        @DisplayName("★ 子模板的输出必须以布局的第一个字符开头 —— 顶层不得泄漏空行")
        void childEmitsNothingAtTopLevel(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                "layouts.app", """
                    <!DOCTYPE html>
                    <html><head><title>@yield('title')</title>@stack('styles')</head>
                    <body>@yield('content')@stack('scripts')</body></html>
                    """,
                "page", """
                    @params(String name)
                    @extends('layouts.app')

                    @section('title', '标题')

                    @section('content')
                    <p>你好 {{ name }}</p>
                    @endsection

                    @push('scripts')
                    <script src="/a.js"></script>
                    @endpush
                    """));

            String out = e.render("page", Map.of("name", "世界"));

            // 浏览器会忽略 DOCTYPE 前的空白,但框架不该产出它 ——
            // 这是「子模板顶层无输出」这条 Blade 语义的直接体现
            assertThat(out).startsWith("<!DOCTYPE html>");
            assertThat(out).contains("<title>标题</title>")
                           .contains("<p>你好 世界</p>")
                           .contains("/a.js");
        }

        @Test
        @DisplayName("@yield 的默认值在 section 未定义时生效")
        void yieldDefault(@TempDir Path tmp) {
            var t = new java.util.HashMap<>(layoutTemplates());
            t.put("posts.index", """
                @extends('layouts.app')
                @section('content')
                hi
                @endsection
                """);
            assertThat(engine(tmp, t).render("posts.index", Map.of()))
                    .contains("<title>默认标题</title>");
        }

        @Test
        @DisplayName("★ V3 的关键验证:被 @include 的深层模板也能向布局的 @stack 贡献内容")
        void deepIncludeContributesToStack(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                "layouts.app", """
                    <head>@stack('styles')</head>
                    <body>@yield('content')@stack('scripts')</body>
                    """,
                "partials.widget", """
                    @params(String name)
                    <div class="widget">{{ name }}</div>
                    @push('scripts')
                    <script src="/js/{{ name }}.js"></script>
                    @endpush
                    @push('styles')
                    <link href="/css/{{ name }}.css">
                    @endpush
                    """,
                "page", """
                    @extends('layouts.app')
                    @section('content')
                    @include('partials.widget', name = "alpha")
                    @include('partials.widget', name = "beta")
                    @endsection
                    """));

            String out = e.render("page", Map.of());

            // 深层组件的 JS 进了 body 尾部
            assertThat(out).contains("/js/alpha.js").contains("/js/beta.js");
            // ★ 深层组件的 CSS 进了 <head> —— 即使 <head> 比 content 先输出
            assertThat(out).contains("/css/alpha.css").contains("/css/beta.css");
            int headEnd = out.indexOf("</head>");
            assertThat(out.indexOf("/css/alpha.css")).isLessThan(headEnd);
        }

        @Test
        @DisplayName("一个模板声明两次 @extends 应报错")
        void doubleExtendsIsRejected(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @extends('a')
                @extends('b')
                """, "a", "x", "b", "y"));
            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("只能有一个 @extends");
        }
    }

    // ================= 包含 =================

    @Nested
    @DisplayName("@include")
    class Includes {

        @Test void simpleInclude(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                "partials.nav", "<nav>菜单</nav>\n",
                "page", "@include('partials.nav')\n<p>正文</p>\n"));
            assertThat(e.render("page", Map.of())).contains("<nav>菜单</nav>").contains("<p>正文</p>");
        }

        @Test void includeWithNamedParams(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                "partials.alert", """
                    @params(String type, String message)
                    <div class="alert-{{ type }}">{{ message }}</div>
                    """,
                "page", "@include('partials.alert', type = \"error\", message = \"出错了\")\n"));
            assertThat(e.render("page", Map.of()))
                    .contains("alert-error").contains("出错了");
        }

        @Test
        @DisplayName("动态视图名应报错并说明原因")
        void dynamicIncludeNameIsRejected(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("page", "@include(someVar)\n"));
            assertThatThrownBy(() -> e.render("page", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("必须是字符串字面量");
        }
    }

    // ================= ★ 错误定位(v0.1 头号验收项) =================

    @Nested
    @DisplayName("★ 错误定位(docs/04 的头号质量要求)")
    class ErrorReporting {

        @Test
        @DisplayName("拼错属性名 → 报错指向 .jblade.html 的正确行号")
        void typoPointsToCorrectBladeLine(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("posts.show", """
                @params(io.jaravel.view.TestPost post)
                <article>
                  <h1>{{ post.title }}</h1>
                  <p>{{ post.body }}</p>
                  <span>{{ post.titel }}</span>
                </article>
                """));
            //                                        ↑ 第 5 行拼错

            assertThatThrownBy(() -> e.render("posts.show", Map.of("post", posts().get(0))))
                    .isInstanceOf(JbladeException.class)
                    .satisfies(ex -> {
                        var je = (JbladeException) ex;
                        assertThat(je.template()).isEqualTo("posts.show.jblade.html");
                        assertThat(je.line()).isEqualTo(5);
                    });
        }

        @Test
        @DisplayName("报错消息里带模板名:行号,且不泄漏生成的 Java 路径")
        void messageIsCleanAndLocated(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(io.jaravel.view.TestPost post)
                <p>{{ post.nope }}</p>
                """));
            assertThatThrownBy(() -> e.render("t", Map.of("post", posts().get(0))))
                    .hasMessageContaining("t.jblade.html:2")
                    .hasMessageNotContaining("Generated.java")
                    .hasMessageNotContaining("jte-classes");
        }

        @Test
        @DisplayName("报错带源码片段,出错行用 → 标记")
        void messageIncludesSourceSnippet(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(io.jaravel.view.TestPost post)
                <div>
                <p>{{ post.wrongName }}</p>
                </div>
                """));
            assertThatThrownBy(() -> e.render("t", Map.of("post", posts().get(0))))
                    .hasMessageContaining("→")
                    .hasMessageContaining("post.wrongName");
        }

        @Test
        @DisplayName("★ 给出『是否想写 X ?』的相似度建议")
        void similaritySuggestion(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @params(io.jaravel.view.TestPost post)
                <h1>{{ post.titel }}</h1>
                """));
            assertThatThrownBy(() -> e.render("t", Map.of("post", posts().get(0))))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("是否想写")
                    .hasMessageContaining("title");
        }

        @Test
        @DisplayName("被 @include 的子模板出错时,定位到子模板而非入口模板")
        void errorInIncludedTemplate(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                "page", "@include('partials.broken')\n",
                "partials.broken", """
                    <p>ok</p>
                    <p>{{ undefinedVariable }}</p>
                    """));
            assertThatThrownBy(() -> e.render("page", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("partials.broken.jblade.html");
        }

        @Test
        @DisplayName("@include 不传被包含模板声明的参数 → 编译期报错(jte 带来的收益)")
        void includeMissingParamIsCompileError(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                "page", "@include('partials.card')\n",
                "partials.card", """
                    @params(io.jaravel.view.TestPost post)
                    <p>{{ post.title }}</p>
                    """));
            assertThatThrownBy(() -> e.render("page", Map.of()))
                    .isInstanceOf(JbladeException.class);
        }

        @Test
        @DisplayName("未闭合的 @if 在转写阶段就报错,并指出在哪一行打开")
        void unclosedBlockIsReported(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                <p>a</p>
                @if (true)
                <p>b</p>
                """));
            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("没有对应的结束指令")
                    .hasMessageContaining("第 2 行");
        }

        @Test
        @DisplayName("结束指令不匹配时指出应该写哪个")
        void mismatchedEndIsReported(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                @foreach (items as i)
                x
                @endif
                """));
            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("@endforeach");
        }

        @Test
        @DisplayName("@php 明确不支持,报错说明原因与替代做法")
        void phpDirectiveIsRejected(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", "@php\nint x = 1;\n@endphp\n"));
            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("@php 不被支持")
                    .hasMessageContaining("控制器");
        }

        @Test
        @DisplayName("@foreach 语法写错时给出正确写法")
        void malformedForeach(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", "@foreach (posts)\nx\n@endforeach\n"));
            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("(集合 as 变量)");
        }
    }

    // ================= 行号映射 =================

    @Nested
    @DisplayName("行号映射")
    class LineMapping {

        @Test
        @DisplayName("内容行逐行映射回 .jblade.html 的原行号")
        void mapsContentLinesBackToSource() {
            String blade = """
                @params(String a)
                <p>{{ a }}</p>
                @if (true)
                <p>x</p>
                @endif
                """;
            var r = JbladeTranspiler.transpile(blade, "t.jblade.html");
            var map = r.lineMap();

            // 头部(@param ctx + @param String a)是生成行;
            // @params 声明行本身不产出 jte 行,因此内容从第 3 个 jte 行开始
            assertThat(map.toSourceLine(3)).isEqualTo(2);   // <p>{{ a }}</p>
            assertThat(map.toSourceLine(4)).isEqualTo(3);   // @if
            assertThat(map.toSourceLine(5)).isEqualTo(4);   // <p>x</p>
            assertThat(map.toSourceLine(6)).isEqualTo(5);   // @endif
        }

        @Test
        @DisplayName("带 @extends 时,顶层的空行与杂散文本被丢弃(Blade 语义)")
        void extendsSuppressesTopLevelOutput() {
            var r = JbladeTranspiler.transpile("""
                @extends('layouts.app')

                这行杂散文本在 Blade 里会被丢弃

                @section('content')
                <p>正文</p>
                @endsection
                """, "t.jblade.html");

            assertThat(r.jteSource()).doesNotContain("这行杂散文本");
            assertThat(r.jteSource()).contains("<p>正文</p>");
        }

        @Test
        @DisplayName("超出范围时返回 0 而非抛异常")
        void outOfRangeIsSafe() {
            var r = JbladeTranspiler.transpile("<p>x</p>\n", "t.jblade.html");
            assertThat(r.lineMap().toSourceLine(9999)).isZero();
            assertThat(r.lineMap().toSourceLine(0)).isZero();
            assertThat(r.lineMap().toSourceLine(-1)).isZero();
        }
    }

    // ================= 转写产物 =================

    @Nested
    @DisplayName("转写产物(便于排查)")
    class Transpilation {

        @Test void paramsBecomeJteParams() {
            var r = JbladeTranspiler.transpile("@params(String name, int page)\n", "t");
            assertThat(r.jteSource())
                    .contains("@param io.jaravel.view.JbContext ctx")
                    .contains("@param String name")
                    .contains("@param int page");
            assertThat(r.declaredParams()).containsExactly("String name", "int page");
        }

        @Test void extendsEmitsLayoutCallAtEnd() {
            var r = JbladeTranspiler.transpile("""
                @extends('layouts.app')
                @section('content')
                x
                @endsection
                """, "t");
            assertThat(r.layout()).contains("layouts.app");
            assertThat(r.jteSource().trim()).endsWith("@template.layouts.app(ctx = ctx)");
        }

        @Test void echoBecomesJteOutput() {
            var r = JbladeTranspiler.transpile("{{ a }} {!! b !!}\n", "t");
            assertThat(r.jteSource()).contains("${a}").contains("$unsafe{b}");
        }

        @Test void importsArePassedThrough() {
            var r = JbladeTranspiler.transpile("@import(java.util.List)\n@params(List<String> x)\n", "t");
            assertThat(r.jteSource()).contains("@import java.util.List");
        }

        @Test
        @DisplayName("未知的 @ 指令原样保留(邮件模板里的 @media 等)")
        void unknownDirectivesArePreserved() {
            var r = JbladeTranspiler.transpile("<style>@media (max-width: 600px) { }</style>\n", "t");
            assertThat(r.jteSource()).contains("@media");
        }

        @Test
        @DisplayName("★ 组件标签 <x- 直接报错,而不是原样输出")
        void componentTagsFailLoudly() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                    "<div>\n  <x-post-card :post=\"post\" />\n</div>\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("还没实现")
                    .as("错误要指向出问题的那一行")
                    .hasMessageContaining(":2");
        }

        @Test
        @DisplayName("★ 为什么不能原样输出:浏览器对未知标签不报错,只是什么都不渲染")
        void whyNotPassThrough() {
            // 这一条没有断言可写 —— 它记录的是一个**取舍**。
            //
            // docs/04 §1.7 完整描述了组件系统,而它一行都没实现。
            // 原样输出的话 <x-post-card> 会进到 HTML 里,浏览器忽略它,
            // 于是症状是「那块内容是空的」—— 而从那个症状到
            // 「组件系统没实现」之间没有任何线索。
            //
            // 代价是误报:被注释掉的组件用法也会被拦下。
            // 出路是 @verbatim,下面那条测试守着它
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("@verbatim 里的 <x- 不报错 —— 文档模板的出路")
        void verbatimEscapesTheCheck() {
            var r = JbladeTranspiler.transpile("""
                    @verbatim
                    <x-post-card :post="post" />
                    @endverbatim
                    """, "t");

            assertThat(r.jteSource())
                    .as("@verbatim 里的内容对 jte 不可见,也不过组件检查")
                    .contains("x-post-card");
        }
    }

    // ================= 热重载 =================

    @Nested
    @DisplayName("热重载")
    class HotReload {

        @Test
        @DisplayName("模板内容变更后下次渲染自动生效")
        void templateChangeTakesEffect(@TempDir Path tmp) {
            var repo = new JbladeEngine.MapRepository(new java.util.HashMap<>(Map.of("t", "<p>版本一</p>\n")));
            var e = JbladeEngine.forMap(Map.of("t", "<p>版本一</p>\n"),
                                        tmp.resolve("c"), JbladeTest.class.getClassLoader());

            assertThat(e.render("t", Map.of())).contains("版本一");

            ((JbladeEngine.MapRepository) e.repository()).put("t", "<p>版本二</p>\n");
            assertThat(e.render("t", Map.of())).contains("版本二");
        }
    }
}
