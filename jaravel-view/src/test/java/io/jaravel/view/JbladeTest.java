package io.jaravel.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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

    /** Map.of 不收 null 值,而「属性值是 null」正是要测的一种情况 */
    private static Map<String, Object> nullable(Object... pairs) {
        var map = new java.util.HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return map;
    }

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
        @DisplayName("★ 注释里的组件标签与指令不被翻译(注释里最常写的就是用法示例)")
        void commentsAreNotTranslated(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                    {{-- 用法:<x-card>正文</x-card>,循环用 @foreach (xs as x) --}}
                    <p>正常</p>
                    """));
            assertThat(e.render("t", Map.of()))
                    .as("""
                            转成 jte 的 <%-- --%> 而把正文留在行里的话,
                            后面的组件/指令翻译照样看得见它 —— 报错会指着
                            一个作者认为自己注释掉了的标签。""")
                    .contains("<p>正常</p>")
                    .doesNotContain("用法").doesNotContain("x-card");
        }

        @Test
        @DisplayName("★ 跨行的注释块整段消失(注释里常常写 HTML 用法示例)")
        void multiLineComments(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                    <p>前</p>
                    {{--
                      用法:
                      <x-card><td>不该被 jte 看见</td></x-card>
                    --}}
                    <p>后</p>
                    """));
            String out = e.render("t", Map.of());
            assertThat(out)
                    .as("""
                            逐行的注释正则匹配不上跨行的块。漏掉的后果不是
                            「注释没生效」那么温和:注释里的 HTML 会让 jte 报
                            「Unclosed tag」,指着一个作者认为自己注释掉了的标签。""")
                    .contains("<p>前</p>", "<p>后</p>")
                    .doesNotContain("用法").doesNotContain("不该被");
        }

        @Test
        @DisplayName("★ 只有注释的行连换行都不发 —— 顶上的注释块不该把 <!DOCTYPE> 推下去")
        void commentOnlyLinesEmitNothing(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                    {{--
                      这个组件怎么用:
                      <x-card>…</x-card>
                    --}}
                    <!DOCTYPE html>
                    <html><body>正文</body></html>
                    """));
            assertThat(e.render("t", Map.of()))
                    .as("""
                        DOCTYPE 不是第一个字节会让一些客户端进怪异模式 ——
                        框架自带的邮件组件每个都带一段讲用法的注释,
                        那是几十行。""")
                    .startsWith("<!DOCTYPE html>");
        }

        @Test
        @DisplayName("跨行注释的前后同一行上的内容保留")
        void multiLineCommentKeepsSurroundingText(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                    <p>甲</p>{{-- 开始
                    中间
                    结束 --}}<p>乙</p>
                    """));
            String out = e.render("t", Map.of());
            assertThat(out).contains("<p>甲</p>").contains("<p>乙</p>")
                           .doesNotContain("中间");
        }

        @Test
        @DisplayName("跨行注释不打乱行号映射")
        void multiLineCommentKeepsLineNumbers(@TempDir Path tmp) {
            var e = engine(tmp, Map.of("t", """
                    @params(io.jaravel.view.TestPost post)
                    {{--
                      注释
                    --}}
                    <p>{{ post.titel }}</p>
                    """));
            assertThatThrownBy(() -> e.render("t", Map.of("post", posts().get(0))))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("t.jblade.html:5");
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
        @DisplayName("★ 组件标签转成 jte 的模板调用")
        void componentBecomesTemplateCall() {
            var r = JbladeTranspiler.transpile(
                    "<x-post-card :post=\"post\" />\n", "t");

            assertThat(r.jteSource())
                    .as("""
                            复用 @template 而不另造一套 —— 组件与 @include 在
                            jte 这一层是同一件事:带命名参数的模板调用。""")
                    .contains("@template.components.post-card(ctx = ctx, post = post)");
        }

        @Test
        @DisplayName("★ 组件在指令之前翻译 —— 否则会被当成未知指令静默保留")
        void componentsTranslateBeforeDirectives() {
            var r = JbladeTranspiler.transpile("<x-alert />\n", "t");

            assertThat(r.jteSource())
                    .as("""
                            翻译出来的 @template.components.alert 本身带一个 @。
                            反过来的顺序不会报错 —— 未知指令是原样保留的 ——
                            它会**静默地**少做一层转换,而那是最难排查的一类。""")
                    .doesNotContain("<x-alert");
        }

        @Test
        @DisplayName("@verbatim 里的 <x- 不翻译 —— 文档模板的出路")
        void verbatimSkipsComponents() {
            var r = JbladeTranspiler.transpile("""
                    @verbatim
                    <x-post-card :post="post" />
                    @endverbatim
                    """, "t");

            assertThat(r.jteSource())
                    .as("@verbatim 内的内容对 jte 不可见,也不过组件翻译")
                    .contains("x-post-card");
        }
    }

    // ================= 组件(docs/04 §1.7)=================

    /**
     * ★ 组件系统 —— v0.4 末尾补上的。
     *
     * <p>在这之前 docs/04 §1.7 整节都没实现,而 `<x-card>` 会被当成
     * 普通 HTML **原样输出**:浏览器对未知标签不报错,只是什么都不渲染,
     * 于是症状是「那块内容是空的」。
     *
     * <p>这一组测试大部分走**真实渲染**而不只看转写产物 ——
     * 组件的全部风险在「jte 能不能接住这个调用」,而转写产物看起来
     * 对但 jte 编译不过的情况是真实存在的(属性名带连字符就是一例)。
     */
    @Nested
    @DisplayName("组件")
    class Components {

        private String render(Map<String, String> templates, String entry,
                              Map<String, Object> data, Path tmp) {
            return JbladeEngine.forMap(templates, tmp.resolve("c"),
                            JbladeTest.class.getClassLoader())
                    .render(entry, data);
        }

        @Test
        @DisplayName("★ 匿名组件:属性 + 默认 slot 真的渲染出来")
        void anonymousComponentRenders(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.alert", """
                            @props(String type = "info")
                            <div class="alert-{{ type }}">${slot}</div>
                            """,
                    "page", """
                            <x-alert type="danger">出错了</x-alert>
                            """), "page", Map.of(), tmp);

            assertThat(out)
                    .contains("alert-danger")
                    .contains("出错了");
        }

        @Test
        @DisplayName("★ 自闭合调用:slot 默认 null,不用写 slot = null")
        void selfClosingNeedsNoSlot(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.badge", """
                            @props(String label = "新")
                            <span>{{ label }}</span>
                            """,
                    "page", "<x-badge label=\"热\" />\n"), "page", Map.of(), tmp);

            assertThat(out)
                    .as("""
                            jte 的参数没有默认值时调用方必须给 ——
                            于是每个自闭合调用都要写 slot = null。
                            @props 隐式补上默认值就是为了免掉它。""")
                    .contains("热");
        }

        @Test
        @DisplayName("★ :前缀传表达式,不带冒号传字面量")
        void boundVersusLiteral(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.row", """
                            @props(String name = "", int count = 0)
                            <td>{{ name }}</td><td>{{ count }}</td>
                            """,
                    "page", """
                            @params(int n)
                            <x-row name="固定" :count="n" />
                            """), "page", Map.of("n", 7), tmp);

            assertThat(out).contains("固定").contains("7");
        }

        @Test
        @DisplayName("★ 布尔简写:写了属性名就是 true")
        void booleanShorthand(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.flag", """
                            @props(boolean active = false)
                            <i>{{ active }}</i>
                            """,
                    "page", "<x-flag active />\n"), "page", Map.of(), tmp);

            assertThat(out).contains("true");
        }

        @Test
        @DisplayName("★ 属性名的连字符转驼峰 —— Java 的参数名带不了连字符")
        void kebabAttributeBecomesCamel(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.link", """
                            @props(long userId = 0)
                            <a href="/u/{{ userId }}">u</a>
                            """,
                    "page", """
                            @params(long id)
                            <x-link :user-id="id" />
                            """), "page", Map.of("id", 42L), tmp);

            assertThat(out)
                    .as("""
                            不转的话这个最自然的写法会变成一个语法错误,
                            而错误会指向生成的 jte 代码 —— 与模板里写的
                            那一行对不上。""")
                    .contains("/u/42");
        }

        @Test
        @DisplayName("★ kebab 的组件名直接对应 kebab 的文件名 —— 与 Blade 一致")
        void kebabComponentName(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.post-card", """
                            @props(String title = "")
                            <article>{{ title }}</article>
                            """,
                    "page", "<x-post-card title=\"标题\" />\n"), "page", Map.of(), tmp);

            assertThat(out).contains("<article>标题</article>");
        }

        @Test
        @DisplayName("★ :: 命名空间映射成子目录(邮件组件用它)")
        void namespacedComponent(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.mail.message", """
                            @props(String greeting = "")
                            <p>{{ greeting }}</p>${slot}
                            """,
                    "page", "<x-mail::message greeting=\"你好\">正文</x-mail::message>\n"),
                    "page", Map.of(), tmp);

            assertThat(out).contains("你好").contains("正文");
        }

        @Test
        @DisplayName("组件嵌套组件")
        void nestedComponents(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.outer", "@props()\n<div class=\"o\">${slot}</div>\n",
                    "components.inner", "@props()\n<span class=\"i\">${slot}</span>\n",
                    "page", "<x-outer><x-inner>深处</x-inner></x-outer>\n"),
                    "page", Map.of(), tmp);

            assertThat(out).contains("class=\"o\"").contains("class=\"i\"").contains("深处");
        }

        @Test
        @DisplayName("组件 body 里的 jblade 指令照常工作")
        void directivesInsideSlot(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.box", "@props()\n<div>${slot}</div>\n",
                    "page", """
                            @params(java.util.List<String> items)
                            <x-box>@foreach(items as it){{ it }},@endforeach</x-box>
                            """), "page",
                    Map.of("items", java.util.List.of("甲", "乙")), tmp);

            assertThat(out).contains("甲,").contains("乙,");
        }

        @Test
        @DisplayName("★ 组件的输出照样过转义 —— 组件不是绕过安全的后门")
        void componentOutputIsEscaped(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.show", "@props(String text = \"\")\n<p>{{ text }}</p>\n",
                    "page", """
                            @params(String evil)
                            <x-show :text="evil" />
                            """), "page",
                    Map.of("evil", "<script>alert(1)</script>"), tmp);

            assertThat(out)
                    .as("转义由 jte 做,而组件走的是同一条输出路径")
                    .doesNotContain("<script>")
                    .contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("★ {{ slot }} 不会把 body 里的 HTML 转义掉")
        void slotIsNotDoubleEscaped(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.box", """
                            @props()
                            <div>{{ slot }}</div>
                            """,
                    "page", "<x-box><strong>加粗</strong></x-box>\n"),
                    "page", Map.of(), tmp);

            assertThat(out)
                    .as("""
                            slot 是**已经渲染好**的 Content,不是用户输入 ——
                            再转义一次会让 <strong> 变成页面上的字面文本。
                            这一条针对的是 Blade 作者会写 {{ slot }}
                            而不是原始 jte 的那种写法。""")
                    .contains("<strong>加粗</strong>")
                    .doesNotContain("&lt;strong&gt;");
        }

        @Test
        @DisplayName("★ 但 body 里的**用户输入**仍然被转义")
        void userInputInsideSlotIsStillEscaped(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.box", """
                            @props()
                            <div>{{ slot }}</div>
                            """,
                    "page", """
                            @params(String evil)
                            <x-box><em>{{ evil }}</em></x-box>
                            """), "page",
                    Map.of("evil", "<script>alert(1)</script>"), tmp);

            assertThat(out)
                    .as("转义发生在 body 渲染的时候 —— slot 只是把结果透传")
                    .contains("<em>")
                    .doesNotContain("<script>")
                    .contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("★ 未闭合的组件标签在编译期报错")
        void unclosedComponentFails() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                    "<x-card>没有闭合\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .as("""
                            说的是 HTML 标签而不是 @指令 ——
                            「@component 没有对应的 @endcomponent」会把人
                            送去找一个根本不存在的指令。""")
                    .hasMessageContaining("<x-card>")
                    .hasMessageContaining("</x-card>");
        }

        @Test
        @DisplayName("★ :前缀但没给值 —— 报错并指出两种正确写法")
        void boundWithoutValueFails() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                    "<x-alert :dismissible />\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("布尔简写");
        }

        @Test
        @DisplayName("★ 目录模式:views/components/*.jblade.html 能被解析到")
        void componentsResolveFromDirectory(@TempDir Path tmp) throws Exception {
            Path views = tmp.resolve("views");
            java.nio.file.Files.createDirectories(views.resolve("components"));
            java.nio.file.Files.writeString(
                    views.resolve("components/alert.jblade.html"),
                    """
                    @props(String type = "info")
                    <div class="a-{{ type }}">{{ slot }}</div>
                    """);
            java.nio.file.Files.writeString(views.resolve("page.jblade.html"),
                    "<x-alert type=\"warn\">注意</x-alert>\n");

            var engine = JbladeEngine.forDirectory(views, tmp.resolve("c"),
                    JbladeTest.class.getClassLoader());

            assertThat(engine.render("page", Map.of()))
                    .as("""
                            这条路径与 forMap 不同:jte 会反过来向仓库
                            要 components/alert.jte,而那要求引擎**按需转写**
                            一个还没被渲染过的模板。
                            真实应用走的是这一条。""")
                    .contains("a-warn").contains("注意");
        }

        @Test
        @DisplayName("@props 不带括号时报错,并说清要写类型")
        void propsNeedsParens() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile("@props\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("Java 类型");
        }
    }

    // ================= 具名 slot =================

    /**
     * 具名 slot(docs/04 §1.7)。
     *
     * <p>实现上的难点与解法见 {@code JbladeTranspiler.ComponentFrame}:
     * slot 写在 body 里,却要成为那次调用的另一个参数。
     */
    @Nested
    @DisplayName("具名 slot(<x-slot:footer>)")
    class NamedSlots {

        /** 带两个具名 slot 的卡片组件 */
        private static final String CARD = """
                @props(String title = "")
                <section>
                  @if (hasSlot("head")) <h1>{{ slot("head") }}</h1> @endif
                  <main>{{ slot }}</main>
                  @if (hasSlot("footer")) <footer>{{ slot("footer") }}</footer> @endif
                </section>
                """;

        private String render(Map<String, String> templates, Map<String, Object> data, Path tmp) {
            return JbladeEngine.forMap(templates, tmp.resolve("c"),
                            JbladeTest.class.getClassLoader())
                    .render("page", data);
        }

        @Test
        @DisplayName("★ 具名 slot 与默认 slot 各就各位")
        void namedAndDefaultSlots(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.card", CARD,
                    "page", """
                            <x-card>
                              正文
                              <x-slot:footer>
                                <a href="/help">帮助</a>
                              </x-slot:footer>
                            </x-card>
                            """), Map.of(), tmp);

            assertThat(out).contains("<main>").contains("正文")
                           .contains("<footer>").contains("/help");
            assertThat(out).as("没传的具名 slot 不渲染").doesNotContain("<h1>");
            assertThat(out)
                    .as("具名 slot 的内容必须从默认 slot 里**挪走**,不能两边都出现")
                    .containsOnlyOnce("/help");
            assertThat(out.indexOf("正文"))
                    .as("默认 slot 在 <main> 里、footer 在它后面 —— 顺序由组件决定,不由书写顺序决定")
                    .isLessThan(out.indexOf("/help"));
        }

        @Test
        @DisplayName("多个具名 slot + 书写顺序与组件里的使用顺序无关")
        void multipleNamedSlots(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.card", CARD,
                    "page", """
                            <x-card>
                              <x-slot:footer>底部</x-slot:footer>
                              正文
                              <x-slot:head>标题</x-slot:head>
                            </x-card>
                            """), Map.of(), tmp);

            assertThat(out).contains("<h1>").contains("标题")
                           .contains("<footer>").contains("底部")
                           .contains("正文");
            assertThat(out.indexOf("标题")).isLessThan(out.indexOf("正文"));
        }

        @Test
        @DisplayName("</x-slot> 与 </x-slot:name> 两种闭合都收")
        void bothClosingForms(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.card", CARD,
                    "page", """
                            <x-card>
                              <x-slot:head>甲</x-slot>
                              <x-slot:footer>乙</x-slot:footer>
                            </x-card>
                            """), Map.of(), tmp);

            assertThat(out).contains("甲").contains("乙");
        }

        @Test
        @DisplayName("slot 名原样作键 —— kebab 不转驼峰,否则与 slot(\"sub-copy\") 对不上")
        void kebabSlotNameKeptAsIs(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.mail", """
                            @props()
                            <div>{{ slot }}@if (hasSlot("sub-copy"))<small>{{ slot("sub-copy") }}</small>@endif</div>
                            """,
                    "page", """
                            <x-mail>正文<x-slot:sub-copy>退订</x-slot></x-mail>
                            """), Map.of(), tmp);

            assertThat(out).contains("<small>").contains("退订");
        }

        @Test
        @DisplayName("具名 slot 里可以写指令与输出,且照样转义")
        void directivesAndEscapingInsideNamedSlot(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.card", CARD,
                    "page", """
                            @params(java.util.List<String> items, String evil)
                            <x-card>
                              <x-slot:footer>
                                @foreach (items as it){{ it }};@endforeach
                                {{ evil }}
                              </x-slot:footer>
                              正文
                            </x-card>
                            """),
                    Map.of("items", List.of("甲", "乙"), "evil", "<script>x</script>"), tmp);

            assertThat(out).contains("甲;").contains("乙;")
                           .contains("&lt;script&gt;").doesNotContain("<script>");
        }

        @Test
        @DisplayName("★ 具名 slot 里能嵌组件,内层组件还能有自己的具名 slot")
        void nestedComponentInsideNamedSlot(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.card", CARD,
                    "components.badge", """
                            @props()
                            <b>{{ slot }}@if (hasSlot("tip"))<i>{{ slot("tip") }}</i>@endif</b>
                            """,
                    "page", """
                            <x-card>
                              <x-slot:footer>
                                <x-badge>热<x-slot:tip>说明</x-slot></x-badge>
                              </x-slot:footer>
                              正文
                            </x-card>
                            """), Map.of(), tmp);

            assertThat(out).contains("<footer>").contains("<b>").contains("热")
                           .contains("<i>").contains("说明").contains("正文");
        }

        @Test
        @DisplayName("组件可以完全不要默认 slot,只给具名的")
        void onlyNamedSlots(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.card", CARD,
                    "page", "<x-card><x-slot:head>只有标题</x-slot></x-card>\n"),
                    Map.of(), tmp);

            assertThat(out).contains("只有标题");
        }

        @Test
        @DisplayName("★ 错误定位:具名 slot 里拼错属性名 —— 报对模板、给出建议,行号落在组件调用内")
        void errorInsideNamedSlotKeepsLineNumber(@TempDir Path tmp) {
            var thrown = assertThatThrownBy(() -> render(Map.of(
                    "components.card", CARD,
                    "page", """
                            @params(io.jaravel.view.TestPost post)
                            <x-card>
                              正文
                              <x-slot:footer>
                                {{ post.titel }}
                              </x-slot:footer>
                            </x-card>
                            """), Map.of("post", posts().get(0)), tmp));

            thrown.isInstanceOf(JbladeException.class)
                  .as("报的是出错的那个模板,不是组件模板")
                  .hasMessageContaining("page.jblade.html")
                  .as("相似度建议照样给")
                  .hasMessageContaining("是否想写 title");

            // ⚠ 行号精确到「哪一行」这件事,在具名 slot 里**做不到**。
            //
            // 实测(jte 3.x):我们自己的映射是对的 —— 生成码第 4 行就是
            // 模板第 5 行,dump 过。失准在 jte 自己的 Java→jte 映射里:
            // 一次调用带两个 Content 块时,前一个块里的错误被归到调用的收尾处。
            //   · 默认 slot(单个 Content 块):精确,见 Components 里那几条;
            //   · 具名 slot:落在组件调用的范围内,靠代码片段给上下文。
            // 不假装它准 —— 这条断言就是现状的样子。
            String message = catchThrowableOfType(
                    () -> render(Map.of(
                            "components.card", CARD,
                            "page", """
                                    @params(io.jaravel.view.TestPost post)
                                    <x-card>
                                      正文
                                      <x-slot:footer>
                                        {{ post.titel }}
                                      </x-slot:footer>
                                    </x-card>
                                    """), Map.of("post", posts().get(0)), tmp),
                    JbladeException.class).getMessage();

            int line = Integer.parseInt(message.replaceFirst("(?s)^page\\.jblade\\.html:(\\d+).*", "$1"));
            assertThat(line)
                    .as("落在 <x-card> … </x-card> 这几行里(2~7),不是文件开头或末尾")
                    .isBetween(2, 7);
        }

        @Test
        @DisplayName("★ <x-slot:> 写在组件外面 —— 报错并给出正确写法")
        void slotOutsideComponentFails() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                    "<div>\n  <x-slot:footer>底部</x-slot>\n</div>\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("必须写在组件标签内部");
        }

        @Test
        @DisplayName("具名 slot 套具名 slot —— 报错并提示漏了 </x-slot>")
        void nestedNamedSlotFails() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                    "<x-card>\n  <x-slot:a>甲\n  <x-slot:b>乙</x-slot>\n</x-card>\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("</x-slot>");
        }

        @Test
        @DisplayName("未闭合的具名 slot —— 报错说的是 </x-slot> 而不是某个 @指令")
        void unclosedNamedSlotFails() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                    "<x-card>\n  <x-slot:footer>底部\n</x-card>\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("</x-slot>");
        }

        @Test
        @DisplayName("</x-slot> 没有对应的开标签时报错")
        void strayClosingSlotFails() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                    "<x-card>正文</x-slot></x-card>\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("没有对应的 <x-slot:…>");
        }

        @Test
        @DisplayName("★ 模型自己的 slot(...) 方法不被改写 —— 只有裸 slot( 才是具名 slot")
        void modelMethodNamedSlotIsNotRewritten() {
            String jte = JbladeTranspiler.transpile(
                    "@props()\n<p>{{ booking.slot(\"am\") }}</p>\n", "t").jteSource();

            assertThat(jte)
                    .as("""
                            把它当成具名 slot 的话,错误会指向生成的 Java 代码,
                            而模板里那一行看着完全正常。""")
                    .contains("booking.slot(\"am\")")
                    .doesNotContain("__slot_am");
        }

        @Test
        @DisplayName("★ slot 名不是字面量 —— 报错说明为什么不行")
        void dynamicSlotNameFails() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                    "@props(String which = \"a\")\n<p>{{ slot(which) }}</p>\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("必须是字面量");
        }

        @Test
        @DisplayName("组件里读了哪个具名 slot,参数就自动声明 —— 不用手写 @props(gg.jte.Content …)")
        void slotReadsAreDeclaredAutomatically() {
            String jte = JbladeTranspiler.transpile("""
                    @props(String title = "")
                    @if (hasSlot("sub-copy")) {{ slot("sub-copy") }} @endif
                    """, "t").jteSource();

            assertThat(jte).contains("@param gg.jte.Content __slot_subCopy = null");
            assertThat(jte)
                    .as("hasSlot 就是判空 —— 没传的 slot 是 null")
                    .contains("(__slot_subCopy != null)");
        }
    }

    // ================= 属性袋 =================

    /**
     * 属性袋(docs/04 §1.7 的 {@code {{ attributes }}})。
     *
     * <p>docs/04 曾把这个列为「不是暂时没做」,理由是转写器只看调用方那一行,
     * 不知道组件声明了什么。解法是给转写器一个 {@code ComponentProps} 查询 ——
     * 组件源码在仓库里,扫它的 {@code @props} 一行就够了。
     */
    @Nested
    @DisplayName("属性袋({{ attributes }})")
    class AttributeBag {

        private String render(Map<String, String> templates, Map<String, Object> data, Path tmp) {
            return JbladeEngine.forMap(templates, tmp.resolve("c"),
                            JbladeTest.class.getClassLoader())
                    .render("page", data);
        }

        @Test
        @DisplayName("★ 组件没声明的属性原样透传,声明过的不进袋")
        void undeclaredAttributesPassThrough(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.alert", """
                            @props(String type = "info")
                            <div class="alert-{{ type }}" {{ attributes }}>{{ slot }}</div>
                            """,
                    "page", """
                            <x-alert type="error" id="login-error" data-test="x">出错了</x-alert>
                            """), Map.of(), tmp);

            assertThat(out).contains("id=\"login-error\"")
                           .as("kebab 的属性名原样留着 —— 它要出现在 HTML 里")
                           .contains("data-test=\"x\"")
                           .as("声明过的 prop 走参数,不该再出现在袋里")
                           .contains("alert-error")
                           .doesNotContain("type=\"error\"");
        }

        @Test
        @DisplayName("★ merge(\"class\", …):组件的默认类与调用方的类都留下")
        void mergeClassKeepsBoth(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.btn", """
                            @props()
                            <button {{ attributes.merge("class", "btn btn-primary") }}>{{ slot }}</button>
                            """,
                    "page", "<x-btn class=\"mt-4\">点我</x-btn>\n"), Map.of(), tmp);

            assertThat(out)
                    .as("""
                            覆盖语义的话,调用方加一个外边距就会悄悄删掉组件自己的样式。
                            组件的默认值在前、调用方的在后 —— 后者才能覆盖前者。""")
                    .contains("class=\"btn btn-primary mt-4\"");
        }

        @Test
        @DisplayName("merge 的非 class 属性:调用方给了就不动")
        void mergeNonClassPrefersCaller(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.input", """
                            @props()
                            <input {{ attributes.merge("type", "text") }}>
                            """,
                    "page", "<x-input type=\"password\" />\n<x-input />\n"), Map.of(), tmp);

            assertThat(out).contains("type=\"password\"").contains("type=\"text\"");
        }

        @Test
        @DisplayName("★ 属性值照样转义 —— 属性袋不是绕过安全的后门")
        void valuesAreEscaped(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.box", "@props()\n<div {{ attributes }}>x</div>\n",
                    "page", """
                            @params(String evil)
                            <x-box :title="evil" />
                            """), Map.of("evil", "\"><script>alert(1)</script>"), tmp);

            assertThat(out).doesNotContain("<script>")
                           .contains("&quot;").contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("布尔简写 → 裸属性;false / null → 不输出")
        void booleanAndNullAttributes(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.box", "@props()\n<div {{ attributes }}>x</div>\n",
                    "page", """
                            @params(Boolean off, String nothing)
                            <x-box disabled />
                            <x-box :disabled="off" />
                            <x-box :title="nothing" />
                            """), nullable("off", false, "nothing", null), tmp);

            String[] divs = out.split("<div");
            assertThat(divs[1]).as("裸属性只输出名字,这才是合法 HTML").contains("disabled>");
            assertThat(divs[2]).as(":disabled=\"false\" 不该渲染出 disabled").doesNotContain("disabled");
            assertThat(divs[3]).as("null 值的属性不输出").doesNotContain("title");
        }

        @Test
        @DisplayName("表达式属性进袋时会求值")
        void boundExpressionInBag(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.box", "@props()\n<div {{ attributes }}>x</div>\n",
                    "page", """
                            @params(int n)
                            <x-box :data-count="n * 2" />
                            """), Map.of("n", 21), tmp);

            assertThat(out).contains("data-count=\"42\"");
        }

        @Test
        @DisplayName("except / only 挑属性")
        void exceptAndOnly(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.box", """
                            @props()
                            <div {{ attributes.except("id") }}>甲</div>|<span {{ attributes.only("id") }}>乙</span>|
                            """,
                    "page", "<x-box id=\"a\" title=\"b\" />\n"), Map.of(), tmp);

            String[] parts = out.split("\\|");
            assertThat(parts[0]).contains("title=\"b\"").doesNotContain("id=\"a\"");
            assertThat(parts[1]).contains("id=\"a\"").doesNotContain("title=\"b\"");
        }

        @Test
        @DisplayName("组件用 @params(而不是 @props)时没有属性袋 —— 全当声明过的参数")
        void paramsTemplatesHaveNoBag(@TempDir Path tmp) {
            String out = render(Map.of(
                    "components.plain", """
                            @params(String title)
                            <p>{{ title }}</p>
                            """,
                    "page", "<x-plain title=\"甲\" />\n"), Map.of(), tmp);

            assertThat(out)
                    .as("""
                            @include 的目标、还没写出来的组件都走这条路 ——
                            退回旧行为而不是报错。""")
                    .contains("甲");
        }

        @Test
        @DisplayName("★ 开发模式:给组件加一个 prop 后,调用方的路由跟着变(不用去动调用方的文件)")
        void addingAPropReroutesCaller(@TempDir Path tmp) throws Exception {
            Path views = tmp.resolve("views");
            java.nio.file.Files.createDirectories(views.resolve("components"));
            Path alert = views.resolve("components/alert" + JbladeEngine.SUFFIX);
            java.nio.file.Files.writeString(views.resolve("page" + JbladeEngine.SUFFIX),
                    "<x-alert type=\"warn\" />\n");
            java.nio.file.Files.writeString(alert,
                    "@props()\n<div {{ attributes }}>袋</div>\n");

            var engine = JbladeEngine.forDirectory(views, tmp.resolve("c"),
                    JbladeTest.class.getClassLoader());

            assertThat(engine.render("page", Map.of()))
                    .as("组件没声明 type,于是它进袋,渲染成一个 HTML 属性")
                    .contains("type=\"warn\"");

            // 只改组件,不碰调用方
            java.nio.file.Files.writeString(alert,
                    "@props(String type = \"info\")\n<div {{ attributes }}>prop={{ type }}</div>\n");
            java.nio.file.Files.setLastModifiedTime(alert,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));

            assertThat(engine.render("page", Map.of()))
                    .as("""
                            路由是在转写调用方的时候决定的。调用方的文件没动过,
                            所以它的时间戳必须算上组件的 —— 否则症状是
                            「我声明了 prop,组件里却读不到」,而改完组件再随便
                            动一下调用方的文件就好了,极难复现。""")
                    .contains("prop=warn")
                    .doesNotContain("type=\"warn\"");
        }

        @Test
        @DisplayName("直接调 transpile(不给 ComponentProps)时不认属性袋 —— 全当参数")
        void transpileWithoutLookupKeepsOldBehaviour() {
            String jte = JbladeTranspiler.transpile(
                    "<x-alert id=\"a\" />\n", "t").jteSource();

            assertThat(jte).contains("id = \"a\"").doesNotContain("Attributes.of");
        }

        @Test
        @DisplayName("转写结果带上调用了哪些组件 —— 引擎靠它追踪热重载依赖")
        void resultReportsCalledComponents() {
            var result = JbladeTranspiler.transpile(
                    "<x-alert />\n<x-mail::message>x</x-mail::message>\n", "t");

            assertThat(result.components())
                    .containsExactly("components.alert", "components.mail.message");
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

    // ================= 模板来源 =================

    /**
     * 开发模式(目录)下的 classpath 回落。
     *
     * <p>这些测试钉住的是**框架自带视图**(邮件组件、将来的分页/错误页)
     * 在开发模式下也能被找到 —— 否则它们会「打包后能用、本地跑不了」。
     *
     * <p>固定件:{@code src/test/resources/views/fixtures/framework-provided.jblade.html}
     * 扮演「框架带的那一份」。
     */
    @Nested
    @DisplayName("模板来源:开发模式回落到 classpath")
    class DirectoryFallback {

        private static final String VIEW = "fixtures.framework-provided";

        private JbladeEngine dirEngine(Path tmp) {
            return JbladeEngine.forDirectory(tmp.resolve("views"), tmp.resolve("jte-classes"),
                                             JbladeTest.class.getClassLoader());
        }

        @Test
        @DisplayName("应用 views 目录里没有时,渲染 classpath 上的那一份")
        void fallsBackToClasspath(@TempDir Path tmp) throws Exception {
            java.nio.file.Files.createDirectories(tmp.resolve("views"));

            assertThat(dirEngine(tmp).render(VIEW, Map.of()))
                    .contains("来自 classpath 的框架自带视图");
        }

        @Test
        @DisplayName("★ 应用目录优先:同名模板覆盖框架自带的那一份(零配置的 vendor:publish)")
        void appDirectoryWins(@TempDir Path tmp) throws Exception {
            Path f = tmp.resolve("views/fixtures/framework-provided" + JbladeEngine.SUFFIX);
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, "<p>应用自己改过的版本</p>\n");

            assertThat(dirEngine(tmp).render(VIEW, Map.of()))
                    .contains("应用自己改过的版本")
                    .doesNotContain("来自 classpath");
        }

        @Test
        @DisplayName("两头都没有时仍报「模板不存在」,不被回落吞掉")
        void missingEverywhereStillFails(@TempDir Path tmp) throws Exception {
            java.nio.file.Files.createDirectories(tmp.resolve("views"));

            assertThatThrownBy(() -> dirEngine(tmp).render("fixtures.nobody-has-this", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("fixtures.nobody-has-this");
        }

        @Test
        @DisplayName("lastModified 与 read 同源:回落命中的模板时间戳不是 0")
        void lastModifiedFollowsFallback(@TempDir Path tmp) {
            var repo = new JbladeEngine.DirectoryRepository(tmp.resolve("views"),
                                                            JbladeTest.class.getClassLoader());

            assertThat(repo.read(VIEW)).isPresent();
            assertThat(repo.lastModified(VIEW)).isNotZero();
        }
    }

    // ================= 类组件 =================

    @Nested
    @DisplayName("类组件")
    class ClassComponents {

        private static final String CARD = """
                @params(io.jaravel.view.TestCard self)
                <article class="card"><h2>{{ self.upperTitle() }}</h2><span>{{ self.count }}</span></article>
                """;

        @Test
        @DisplayName("★ 构造参数按属性名配对,顺序无关")
        void constructorArgsMatchByName(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.test-card", CARD,
                    "t", """
                        @params(int n)
                        <x-test-card :count="n" title="Hello" />
                        """));

            assertThat(e.render("t", Map.of("n", 3)))
                    .contains("<h2>HELLO</h2>")
                    .contains("<span>3</span>");
        }

        @Test
        @DisplayName("类名可以走 @import")
        void resolvesImportedType(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.test-card", """
                        @import(io.jaravel.view.TestCard)
                        @params(TestCard self)
                        <p>{{ self.title }}</p>
                        """,
                    "t", """
                        @params(int n)
                        <x-test-card title="x" :count="n" />
                        """));

            assertThat(e.render("t", Map.of("n", 1))).contains("<p>x</p>");
        }

        @Test
        @DisplayName("布尔简写与 kebab 属性名")
        void booleanShorthandAndKebab(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.test-button", """
                        @params(io.jaravel.view.TestButton self)
                        <button data-user="{{ self.userId }}">{{ self.disabled }}</button>
                        """,
                    "t", """
                        <x-test-button user-id="u1" disabled />
                        """));

            assertThat(e.render("t", Map.of()))
                    .contains("data-user=\"u1\"")
                    .as("布尔简写 disabled → true")
                    .contains(">true</button>");
        }

        @Test
        @DisplayName("★ 缺属性 → 转写期报错,并列出构造函数参数")
        void missingAttribute(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.test-card", CARD,
                    "t", """
                        <x-test-card title="x" />
                        """));

            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("缺少属性 count")
                    .hasMessageContaining("title(String), count(int)");
        }

        @Test
        @DisplayName("多属性 → 报错(类组件没有属性袋)")
        void unknownAttribute(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.test-card", CARD,
                    "t", """
                        <x-test-card title="x" :count="1" class="y" />
                        """));

            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("不认识属性 class")
                    .hasMessageContaining("没有属性袋");
        }

        @Test
        @DisplayName("属性写两遍 → 报错")
        void duplicateAttribute(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.test-card", CARD,
                    "t", """
                        <x-test-card title="a" title="b" :count="1" />
                        """));

            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("属性 title 写了两遍");
        }

        @Test
        @DisplayName("带 body 的类组件 → 明确说只支持自闭合")
        void bodyIsRejected(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.test-card", CARD,
                    "t", """
                        <x-test-card title="x" :count="1">body</x-test-card>
                        """));

            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("只支持自闭合");
        }

        @Test
        @DisplayName("多个 public 构造函数 → 报错并说怎么改")
        void multipleConstructors(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.multi", """
                        @params(io.jaravel.view.MultiCtorComponent self)
                        <p>x</p>
                        """,
                    "t", "<x-multi />\n"));

            assertThatThrownBy(() -> e.render("t", Map.of()))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("2 个 public 构造函数")
                    .hasMessageContaining("只保留一个");
        }

        @Test
        @DisplayName("转写产物是 new 组件类,不是匿名模板调用")
        void transpilesToConstructorCall(@TempDir Path tmp) {
            var e = engine(tmp, Map.of(
                    "components.test-card", CARD,
                    "t", """
                        @params(int n)
                        <x-test-card title="Hello" :count="n" />
                        """));

            assertThat(e.transpiledSource("t"))
                    .contains("self = new io.jaravel.view.TestCard(\"Hello\", n)");
        }
    }
}
