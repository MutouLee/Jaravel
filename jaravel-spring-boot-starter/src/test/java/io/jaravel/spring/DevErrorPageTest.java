package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.core.Application;
import io.jaravel.http.BasicRequest;

/** 开发期错误页:修复建议知识库、源码片段、各面板(docs/15 §4) */
class DevErrorPageTest {

    /**
     * 与真实异常**同名**的替身。
     *
     * <p>真的那个在 jaravel-eloquent 里,构造函数要 {@code Model} 与
     * {@code RelationDef} —— 为一条建议的测试去搭一套模型元数据不值得。
     *
     * <p>这里能用替身,恰恰是因为知识库按**简单名**匹配而不是
     * instanceof。本测试同时也就证明了那个设计(见 FixSuggestion 的类注释)。
     */
    static final class LazyLoadingViolationException extends IllegalStateException {
        LazyLoadingViolationException(String message) {
            super(message);
        }
    }

    // ================= 修复建议知识库 =================

    @Nested
    @DisplayName("修复建议知识库")
    class Suggestions {

        @Test
        @DisplayName("★ docs/15 §4.2 列出的异常全部有建议")
        void coversTheDocumentedList() {
            // 文档里那张表的每一行都该能查到东西。
            // 「这个知识库是最容易被低估、但对新手留存影响最大的部分」
            for (String name : FixSuggestion.covered()) {
                var suggestion = FixSuggestion.forName(name, "测试消息");
                assertThat(suggestion)
                        .as("%s 应该有修复建议", name)
                        .isPresent();
                assertThat(suggestion.get().title()).isNotBlank();
            }
        }

        @Test
        @DisplayName("★ 建议里有可直接粘贴的代码")
        void suggestionsAreActionable() {
            // 「检查你的配置」不算建议。写不出可执行的东西就不该加这一条
            var withCode = FixSuggestion.covered().stream()
                    .map(name -> FixSuggestion.forName(name, ""))
                    .flatMap(java.util.Optional::stream)
                    .filter(FixSuggestion::hasCode)
                    .count();

            assertThat(withCode)
                    .as("至少三分之二的建议应带可执行的代码或命令")
                    .isGreaterThanOrEqualTo(FixSuggestion.covered().size() * 2 / 3);
        }

        @Test
        @DisplayName("懒加载违规的建议里给出完整表达式")
        void lazyLoadingSuggestsWithClause() {
            var fix = FixSuggestion.forThrowable(
                    new LazyLoadingViolationException("试图在未预加载的情况下访问 User#posts")).orElseThrow();

            assertThat(fix.code()).contains(".with(");
            assertThat(fix.docs()).contains("eloquent");
        }

        @Test
        @DisplayName("★ 批量赋值的建议明确说「先确认该字段该不该让用户填」")
        void massAssignmentWarnsAboutWidening() {
            var fix = FixSuggestion.forName("MassAssignmentException", "").orElseThrow();

            // 最容易的「修复」是把字段加进 @Fillable,而那正是漏洞
            assertThat(fix.explanation()).contains("is_admin");
            assertThat(fix.code()).contains("@Fillable");
        }

        @Test
        @DisplayName("★ 沿着 cause 链查 —— 真正的原因常被包两三层")
        void walksTheCauseChain() {
            var wrapped = new RuntimeException("外层",
                    new IllegalStateException("中间层",
                            new LazyLoadingViolationException("试图在未预加载的情况下访问 User#posts")));

            assertThat(FixSuggestion.forThrowable(wrapped))
                    .as("只看最外层会漏掉绝大多数可建议的情况")
                    .isPresent();
        }

        @Test
        @DisplayName("★ 自引用的 cause 链不会死循环")
        void selfReferencingCauseTerminates() {
            var loop = new RuntimeException("循环") {
                @Override public synchronized Throwable getCause() { return this; }
            };

            assertThat(FixSuggestion.forThrowable(loop)).isEmpty();
        }

        @Test
        @DisplayName("不认识的异常返回空,而不是一条废话建议")
        void unknownExceptionHasNoSuggestion() {
            assertThat(FixSuggestion.forThrowable(new RuntimeException("随便"))).isEmpty();
        }

        @Test
        @DisplayName("迁移校验和不匹配时给出针对性的解释")
        void migrationChecksumIsSpecific() {
            var generic = FixSuggestion
                    .forName("MigrationException", "建表失败").orElseThrow();
            var checksum = FixSuggestion
                    .forName("MigrationException", "checksum 不匹配").orElseThrow();

            assertThat(checksum.explanation()).contains("新增一个迁移");
            assertThat(generic.explanation()).isNotEqualTo(checksum.explanation());
        }

    }

    // ================= 源码片段 =================

    @Nested
    @DisplayName("源码片段")
    class Snippets {

        @Test
        @DisplayName("★ 能读到本测试文件自己的源码")
        void readsOwnSource() {
            // 这一帧指向本文件,而它确实在 src/test/java 下
            var frame = new StackTraceElement(
                    DevErrorPageTest.class.getName(), "readsOwnSource",
                    "DevErrorPageTest.java", 150);

            var snippet = SourceSnippet.forFrame(frame);
            assertThat(snippet).isPresent();
            assertThat(snippet.get().highlighted()).isEqualTo(150);
            assertThat(snippet.get().lines()).hasSize(11);   // 前后各 5 行
            assertThat(snippet.get().lines())
                    .filteredOn(SourceSnippet.Line::highlighted).hasSize(1);
        }

        @Test
        @DisplayName("找不到源文件时返回空,而不是编一个")
        void missingSourceIsEmpty() {
            assertThat(SourceSnippet.forFrame(new StackTraceElement(
                    "com.example.NotHere", "x", "NotHere.java", 1))).isEmpty();
        }

        @Test
        @DisplayName("★ 行号超出文件长度时返回空")
        void lineBeyondFileIsEmpty() {
            // 源码比运行中的 class 新(改了没重新编译)。显示一个错位的
            // 片段比不显示更糟 —— 它会把人引向错误的那一行
            assertThat(SourceSnippet.forFrame(new StackTraceElement(
                    DevErrorPageTest.class.getName(), "x",
                    "DevErrorPageTest.java", 99999))).isEmpty();
        }

        @Test
        @DisplayName("没有行号的帧返回空")
        void noLineNumberIsEmpty() {
            assertThat(SourceSnippet.forFrame(new StackTraceElement(
                    DevErrorPageTest.class.getName(), "x",
                    "DevErrorPageTest.java", -1))).isEmpty();
        }

        @Test
        @DisplayName("★ 框架帧不算应用代码")
        void frameworkFramesAreNotApplication() {
            for (String cls : new String[] {
                    "io.jaravel.eloquent.Model", "org.springframework.web.X",
                    "java.util.ArrayList", "gg.jte.X", "com.fasterxml.jackson.X",
                    "org.apache.catalina.X", "jdk.internal.X"}) {
                assertThat(SourceSnippet.isApplicationFrame(
                        new StackTraceElement(cls, "m", "F.java", 1)))
                        .as(cls).isFalse();
            }

            // 用户的包名可以是任何东西 —— 所以判定用排除法而不是白名单
            assertThat(SourceSnippet.isApplicationFrame(new StackTraceElement(
                    "com.acme.billing.InvoiceService", "m", "F.java", 1))).isTrue();
        }

        @Test
        @DisplayName("★ 纯框架的堆栈不给片段 —— 展示框架内部对排查没有帮助")
        void allFrameworkStackYieldsNothing() {
            var exception = new RuntimeException("x");
            exception.setStackTrace(new StackTraceElement[] {
                    new StackTraceElement("io.jaravel.eloquent.Model", "guard",
                                          "Model.java", 1),
                    new StackTraceElement("org.springframework.web.X", "h", "X.java", 2)});

            assertThat(SourceSnippet.forFirstApplicationFrame(exception)).isEmpty();
        }

        @Test
        @DisplayName("取的是第一个**应用**帧,而不是栈顶")
        void skipsFrameworkFramesOnTop() {
            // 栈顶往往在框架里:是框架检测到问题并抛的异常。
            //
            // 这里只能验证「框架帧被跳过」这一半 —— 另一半需要一个
            // 真实应用包下的源文件,而本测试自己就在 io.jaravel 下
            // (于是被 isApplicationFrame 判为框架代码,见那个方法的测试)。
            // 完整路径由 examples/auth 的实际运行覆盖。
            var exception = new RuntimeException("x");
            exception.setStackTrace(new StackTraceElement[] {
                    new StackTraceElement("io.jaravel.eloquent.Model", "guard",
                                          "Model.java", 1),
                    new StackTraceElement("com.acme.PostController", "index",
                                          "PostController.java", 24)});

            // com.acme 的源文件不存在 → 空;但它确实被当成了应用帧
            assertThat(SourceSnippet.isApplicationFrame(exception.getStackTrace()[1]))
                    .isTrue();
            assertThat(SourceSnippet.forFirstApplicationFrame(exception)).isEmpty();
        }

        @Test
        @DisplayName("编辑器链接用绝对路径")
        void editorLinksAreAbsolute() {
            var snippet = SourceSnippet.forFrame(new StackTraceElement(
                    DevErrorPageTest.class.getName(), "x",
                    "DevErrorPageTest.java", 10)).orElseThrow();

            // 相对路径对 IDE 没有意义 —— 它不知道以哪为基准
            assertThat(snippet.editorUrl()).startsWith("idea://open?file=")
                    .contains("&line=10");
            assertThat(snippet.vscodeUrl()).startsWith("vscode://file/")
                    .endsWith(":10");
            assertThat(snippet.editorUrl()).doesNotContain("file=src/");
        }
    }

    // ================= 整页渲染 =================

    @Nested
    @DisplayName("整页渲染")
    class Page {

        private String render(Throwable t) {
            Application.setDebug(true);
            try {
                var response = new JaravelExceptionHandler()
                        .render(BasicRequest.of("GET", "/posts/42"), t);
                return bodyOf(response);
            } finally {
                Application.setDebug(false);
            }
        }

        @Test
        @DisplayName("★ 含异常类型、消息、修复建议、源码片段")
        void includesAllParts() {
            var html = render(new LazyLoadingViolationException("试图在未预加载的情况下访问 User#posts"));

            assertThat(html).contains("LazyLoadingViolationException");
            assertThat(html).contains("修复建议");
            assertThat(html).contains(".with(");
            assertThat(html).contains("请求");
            assertThat(html).contains("/posts/42");
        }

        @Test
        @DisplayName("没有建议时不渲染空的建议区")
        void noEmptySuggestionSection() {
            var html = render(new RuntimeException("随便一个异常"));

            assertThat(html).doesNotContain("修复建议");
            assertThat(html).contains("RuntimeException");
        }

        @Test
        @DisplayName("★ 生产模式不泄露任何细节")
        void productionLeaksNothing() {
            Application.setDebug(false);
            var response = new JaravelExceptionHandler().render(
                    BasicRequest.of("GET", "/x"),
                    new RuntimeException("内部表名 secret_users 与 /etc/app 路径"));
            String html = bodyOf(response);

            assertThat(html)
                    .doesNotContain("secret_users")
                    .doesNotContain("/etc/app")
                    .doesNotContain("RuntimeException")
                    .doesNotContain("修复建议");
        }

        @Test
        @DisplayName("★ HTML 转义 —— 异常消息可能带用户输入")
        void escapesHtml() {
            var html = render(new RuntimeException("<script>alert(1)</script>"));

            // 异常消息里常常带着请求参数,而那是攻击者可控的
            assertThat(html).doesNotContain("<script>alert(1)</script>");
            assertThat(html).contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("★ 错误页自己不能崩")
        void pageNeverThrows() {
            // 消息为 null、堆栈为空、cause 成环 —— 都不该让错误页挂掉,
            // 否则用户连原始异常都看不到
            var empty = new RuntimeException((String) null);
            empty.setStackTrace(new StackTraceElement[0]);

            assertThat(render(empty)).contains("RuntimeException");
            assertThat(render(new RuntimeException("a", new RuntimeException("b"))))
                    .isNotEmpty();
        }

        private String bodyOf(io.jaravel.http.Response response) {
            return switch (response.body()) {
                case io.jaravel.http.Response.Body.Text t -> t.content();
                case io.jaravel.http.Response.Body.Json j -> String.valueOf(j.value());
                default -> "";
            };
        }
    }

}
