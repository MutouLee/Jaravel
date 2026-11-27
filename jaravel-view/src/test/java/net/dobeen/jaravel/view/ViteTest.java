package net.dobeen.jaravel.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.core.Application;
import net.dobeen.jaravel.core.config.ConfigRepository;
import net.dobeen.jaravel.core.config.MapConfigRepository;
import net.dobeen.jaravel.core.container.SimpleContainer;

/**
 * {@code @vite}(docs/04 §1.9、ADR-020)。
 *
 * <p>manifest 来自 {@code src/test/resources/public/build/.vite/manifest.json} ——
 * 一份**真的** vite 产物形状(带哈希的文件名、chunk 的 css、递归的 imports),
 * 不是一个两行的桩:这个指令全部的难点就在那张图上。
 */
class ViteTest {

    @BeforeEach
    @AfterEach
    void reset() {
        Vite.clearCache();
        Application.reset();
    }

    private static void config(Map<String, Object> values) {
        var container = new SimpleContainer();
        container.instance(ConfigRepository.class, new MapConfigRepository(values));
        Application.setContainer(container);
    }

    @Nested
    @DisplayName("build 模式")
    class Build {

        @Test
        @DisplayName("★ CSS 进 link、JS 进 module script,文件名是带哈希的那个")
        void emitsHashedTags() {
            String html = Vite.tags("resources/css/app.css", "resources/js/app.js");

            assertThat(html)
                    .contains("<link rel=\"stylesheet\" href=\"/build/assets/app-1111.css\">")
                    .contains("<script type=\"module\" src=\"/build/assets/app-2222.js\">");
            assertThat(html)
                    .as("URL 是 /build/... —— 与 Laravel 相同,靠的是产物放在 classpath:/public/ 下")
                    .doesNotContain("/public/build");
        }

        @Test
        @DisplayName("★ 样式在脚本前,preload 在脚本前")
        void orderIsStylesThenPreloadsThenScripts() {
            String html = Vite.tags("resources/js/app.js");

            int style = html.indexOf("rel=\"stylesheet\"");
            int preload = html.indexOf("rel=\"modulepreload\"");
            int script = html.indexOf("<script");

            assertThat(style).isNotNegative();
            assertThat(preload).isNotNegative();
            assertThat(style)
                    .as("样式在后会让浏览器先执行脚本再回头重排(FOUC)")
                    .isLessThan(script);
            assertThat(preload)
                    .as("preload 在后就没意义了:浏览器要等入口解析完才知道还要下别的")
                    .isLessThan(script);
        }

        @Test
        @DisplayName("★ 入口 chunk 自带的 css 也要吐出来 —— 它不在 @vite 的参数里")
        void chunkCssIsIncluded() {
            String html = Vite.tags("resources/js/app.js");

            assertThat(html)
                    .as("app.js 里 import 的 .css 由 vite 抽成 chunk 的 css[],"
                      + "漏掉它的症状是「样式少了一半」")
                    .contains("/build/assets/app-chunk-3333.css");
        }

        @Test
        @DisplayName("★ imports 递归 preload —— 第二层漏了只是慢,没人会发现")
        void preloadsAreRecursive() {
            String html = Vite.tags("resources/js/app.js");

            assertThat(html)
                    .contains("<link rel=\"modulepreload\" href=\"/build/assets/shared-4444.js\">")
                    .contains("<link rel=\"modulepreload\" href=\"/build/assets/deep-5555.js\">");
        }

        @Test
        @DisplayName("自己 import 自己的 chunk 不会让 preload 绕圈")
        void cyclicImportsTerminate() {
            String html = Vite.tags("resources/js/cyclic.js");

            assertThat(html).contains("/build/assets/cyclic-6666.js");
        }

        @Test
        @DisplayName("★ manifest 里没这个入口:报错并列出有哪些")
        void unknownEntryFailsLoudly() {
            assertThatThrownBy(() -> Vite.tags("resources/js/nope.js"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("manifest 里没有入口")
                    .hasMessageContaining("resources/js/app.js");
        }

        @Test
        @DisplayName("★ 根本没有 manifest:报错而不是吐空标签")
        void missingManifestFailsLoudly() {
            config(Map.of("vite", Map.of("build-directory", "no-such-build")));

            assertThatThrownBy(() -> Vite.tags("resources/js/app.js"))
                    .isInstanceOf(IllegalStateException.class)
                    .as("吐空标签的页面长得像「CSS 坏了」,而真正的原因是没构建过")
                    .hasMessageContaining("npm run build")
                    .hasMessageContaining("VITE_DEV_SERVER");
        }
    }

    @Nested
    @DisplayName("dev 模式")
    class Dev {

        @BeforeEach
        void devServer() {
            config(Map.of("vite", Map.of("dev-server", "http://localhost:5173")));
        }

        @Test
        @DisplayName("★ 先 @vite/client,再按**源码路径**加载入口(不读 manifest)")
        void pointsAtTheDevServer() {
            String html = Vite.tags("resources/css/app.css", "resources/js/app.js");

            assertThat(html).startsWith(
                    "<script type=\"module\" src=\"http://localhost:5173/@vite/client\">");
            assertThat(html)
                    .contains("href=\"http://localhost:5173/resources/css/app.css\"")
                    .contains("src=\"http://localhost:5173/resources/js/app.js\"")
                    .as("dev 模式下没有哈希文件名")
                    .doesNotContain("1111");
        }

        @Test
        @DisplayName("dev 模式下不存在的入口也照样指过去 —— 404 由 vite 报,比这里猜得准")
        void unknownEntryIsTheDevServersProblem() {
            assertThat(Vite.tags("resources/js/brand-new.js"))
                    .contains("http://localhost:5173/resources/js/brand-new.js");
        }

        @Test
        @DisplayName("地址末尾的斜杠不会拼出 //@vite/client")
        void trailingSlashIsTrimmed() {
            config(Map.of("vite", Map.of("dev-server", "http://localhost:5173/")));

            assertThat(Vite.tags("resources/js/app.js"))
                    .contains("http://localhost:5173/@vite/client")
                    .doesNotContain("5173//");
        }

        @Test
        @DisplayName("@viteReactRefresh 只在 dev 有输出")
        void reactRefreshOnlyInDev() {
            assertThat(Vite.reactRefresh())
                    .contains("http://localhost:5173/@react-refresh")
                    .contains("__vite_plugin_react_preamble_installed__");

            Application.reset();
            assertThat(Vite.reactRefresh())
                    .as("build 模式下 refresh runtime 不存在,吐出来就是 404 加一条控制台报错")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("整条路:模板 → jte → HTML")
    class EndToEnd {

        @Test
        @DisplayName("★ 布局里的 @vite 真的渲染成标签(走 $unsafe,不被转义)")
        void rendersThroughTheEngine(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
            String out = JbladeEngine.forMap(Map.of(
                            "layouts.app", """
                                    <!doctype html>
                                    <head>
                                    @vite(['resources/css/app.css', 'resources/js/app.js'])
                                    </head>
                                    """),
                            tmp.resolve("c"), ViteTest.class.getClassLoader())
                    .render("layouts.app", Map.of());

            assertThat(out)
                    .as("走转义的话页面上会出现 &lt;link ...&gt;")
                    .contains("<link rel=\"stylesheet\" href=\"/build/assets/app-1111.css\">")
                    .contains("<script type=\"module\" src=\"/build/assets/app-2222.js\">");
        }
    }

    @Nested
    @DisplayName("转写")
    class Transpilation {

        @Test
        @DisplayName("数组与单个字符串两种写法都收")
        void bothArgumentShapes() {
            assertThat(JbladeTranspiler.transpile(
                            "@vite(['resources/css/app.css', 'resources/js/app.js'])\n", "t")
                    .jteSource())
                    .contains("Jb.vite(\"resources/css/app.css\", \"resources/js/app.js\")");

            assertThat(JbladeTranspiler.transpile("@vite('resources/js/app.js')\n", "t")
                    .jteSource())
                    .contains("Jb.vite(\"resources/js/app.js\")");
        }

        @Test
        @DisplayName("★ 入口不能是表达式 —— 它在构建期必须已知")
        void entriesMustBeLiterals() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile(
                            "@vite(['resources/js/' + name])\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("字面量字符串");
        }

        @Test
        @DisplayName("@vite 不带参数时报错,并给出正确的写法")
        void needsAtLeastOneEntry() {
            assertThatThrownBy(() -> JbladeTranspiler.transpile("@vite()\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("至少一个入口");
        }

        @Test
        @DisplayName("@viteReactRefresh 不带参数")
        void reactRefreshTakesNoArguments() {
            assertThat(JbladeTranspiler.transpile("@viteReactRefresh\n", "t").jteSource())
                    .contains("Jb.viteReactRefresh()");

            assertThatThrownBy(() -> JbladeTranspiler.transpile("@viteReactRefresh('x')\n", "t"))
                    .isInstanceOf(JbladeException.class)
                    .hasMessageContaining("不带参数");
        }
    }
}
