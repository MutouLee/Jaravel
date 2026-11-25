package io.jaravel.http.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.core.lang.Lang;
import io.jaravel.core.lang.Translator;
import io.jaravel.http.BasicRequest;
import io.jaravel.http.Response;

/**
 * 按请求选语言(KNOWN-ISSUES 的「按请求切 locale 的中间件」)。
 *
 * <p>测试分两层:协商规则(纯函数,好测)与「真的切了」(要一个执行作用域,
 * 因为请求级的 locale 存在作用域属性里 —— 那个设计的理由见
 * {@code Translator.overrideLocale})。
 */
class SetLocaleTest {

    @BeforeEach
    void installTranslator() {
        var translator = new Translator("zh_CN", "zh_CN");
        translator.put("zh_CN", Map.of("app.hello", "你好"));
        translator.put("en", Map.of("app.hello", "Hello"));
        Lang.use(translator);
    }

    @AfterEach
    void removeTranslator() {
        Lang.reset();
    }

    @Nested
    @DisplayName("Accept-Language 解析")
    class AcceptLanguage {

        @Test
        @DisplayName("按 q 值从高到低")
        void sortsByQuality() {
            assertThat(SetLocale.acceptedLanguages("en;q=0.5,de,fr;q=0.8"))
                    .containsExactly("de", "fr", "en");
        }

        @Test
        @DisplayName("没写 q 的算 1.0,同 q 保持书写顺序")
        void defaultQualityIsOne() {
            assertThat(SetLocale.acceptedLanguages("zh-CN,zh;q=0.9,en;q=0.8"))
                    .containsExactly("zh-CN", "zh", "en");
        }

        @Test
        @DisplayName("★ q=0 是「明确不要」,丢掉")
        void zeroQualityIsDropped() {
            assertThat(SetLocale.acceptedLanguages("de,en;q=0"))
                    .as("不丢的话 en;q=0 会被当成候选,与用户的意思相反")
                    .containsExactly("de");
        }

        @Test
        @DisplayName("* 与空头都当没说")
        void wildcardAndEmpty() {
            assertThat(SetLocale.acceptedLanguages("*")).isEmpty();
            assertThat(SetLocale.acceptedLanguages("")).isEmpty();
            assertThat(SetLocale.acceptedLanguages(null)).isEmpty();
        }

        @Test
        @DisplayName("坏掉的 q 当没写 —— 不为一个头里的格式错误拒绝请求")
        void malformedQualityIsTolerated() {
            assertThat(SetLocale.acceptedLanguages("en;q=abc,de;q=0.1"))
                    .containsExactly("en", "de");
        }
    }

    @Nested
    @DisplayName("协商")
    class Negotiation {

        private final SetLocale middleware = new SetLocale();

        @Test
        @DisplayName("只认已装载的语言 —— 否则整站回退到 fallback")
        void onlyLoadedLocales() {
            var request = BasicRequest.of("GET", "/").header("Accept-Language", "de,fr;q=0.8");

            assertThat(middleware.resolve(request))
                    .as("de/fr 没有目录,设过去的后果是每条消息都查不到")
                    .isEmpty();
        }

        @Test
        @DisplayName("★ zh-CN 匹配到 zh_CN —— 浏览器发连字符,目录名是下划线")
        void matchesAcrossSeparators() {
            var request = BasicRequest.of("GET", "/").header("Accept-Language", "zh-CN,en;q=0.5");

            assertThat(middleware.resolve(request)).contains("zh_CN");
        }

        @Test
        @DisplayName("★ zh-TW 退到语言段匹配 zh_CN —— 只做精确匹配中文用户全落空")
        void fallsBackToTheLanguageSubtag() {
            var request = BasicRequest.of("GET", "/").header("Accept-Language", "zh-TW");

            assertThat(middleware.resolve(request)).contains("zh_CN");
        }

        @Test
        @DisplayName("q 值高的那个先匹配")
        void picksTheHighestQualitySupportedOne() {
            var request = BasicRequest.of("GET", "/")
                    .header("Accept-Language", "de,en;q=0.9,zh-CN;q=0.1");

            assertThat(middleware.resolve(request)).contains("en");
        }

        @Test
        @DisplayName("查询参数压过 Accept-Language —— 「切换语言」是一个链接")
        void queryParameterWins() {
            var request = BasicRequest.of("GET", "/?lang=en")
                    .header("Accept-Language", "zh-CN");

            assertThat(middleware.resolve(request)).contains("en");
        }

        @Test
        @DisplayName("会话里存过的压过 Accept-Language")
        void storedSelectionWins() {
            var withSession = new SetLocale()
                    .readingSelectionFrom(key -> Optional.of("en"));
            var request = BasicRequest.of("GET", "/").header("Accept-Language", "zh-CN");

            assertThat(withSession.resolve(request)).contains("en");
        }

        @Test
        @DisplayName("会话来源抛异常(没挂 StartSession)时当作没选过,不让请求失败")
        void aFailingSelectionSourceIsIgnored() {
            var broken = new SetLocale().readingSelectionFrom(key -> {
                throw new IllegalStateException("没有会话");
            });
            var request = BasicRequest.of("GET", "/").header("Accept-Language", "en");

            assertThat(broken.resolve(request)).contains("en");
        }

        @Test
        @DisplayName("supporting(...) 限定候选集,顺序即优先级")
        void explicitCandidateSet() {
            var onlyChinese = new SetLocale().supporting(List.of("zh_CN"));
            var request = BasicRequest.of("GET", "/").header("Accept-Language", "en,zh-CN;q=0.5");

            assertThat(onlyChinese.resolve(request)).contains("zh_CN");
        }

        @Test
        @DisplayName("什么都不中时不动 —— 用配置里的默认语言")
        void noMatchLeavesTheDefault() {
            assertThat(middleware.resolve(BasicRequest.of("GET", "/"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("真的切了")
    class Applies {

        @Test
        @DisplayName("请求内 Lang.get 用协商出来的语言,作用域结束后恢复")
        void appliesWithinTheScope() throws Exception {
            var middleware = new SetLocale();
            var request = BasicRequest.of("GET", "/").header("Accept-Language", "en");

            String inside = io.jaravel.core.context.ExecutionScope.runIn("test", () ->
                    middleware.handle(request, r -> Response.text(Lang.get("app.hello")))
                            .headers() == null ? null : Lang.get("app.hello"));

            assertThat(inside).isEqualTo("Hello");
            assertThat(Lang.get("app.hello"))
                    .as("作用域结束后回到默认语言 —— 请求级的覆盖不会漏给下一个请求")
                    .isEqualTo("你好");
        }

        @Test
        @DisplayName("★ 并发的两个请求互不串台")
        void concurrentRequestsDoNotLeakIntoEachOther() throws Exception {
            var middleware = new SetLocale();
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);

            java.util.concurrent.Callable<String> english = () ->
                    io.jaravel.core.context.ExecutionScope.runIn("req-en", () -> {
                        middleware.handle(
                                BasicRequest.of("GET", "/").header("Accept-Language", "en"),
                                r -> Response.text("ok"));
                        barrier.await();                      // 等另一个也设好自己的语言
                        return Lang.get("app.hello");
                    });

            java.util.concurrent.Callable<String> chinese = () ->
                    io.jaravel.core.context.ExecutionScope.runIn("req-zh", () -> {
                        middleware.handle(
                                BasicRequest.of("GET", "/").header("Accept-Language", "zh-CN"),
                                r -> Response.text("ok"));
                        barrier.await();
                        return Lang.get("app.hello");
                    });

            try {
                var en = pool.submit(english);
                var zh = pool.submit(chinese);

                assertThat(en.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("Hello");
                assertThat(zh.get(10, java.util.concurrent.TimeUnit.SECONDS))
                        .as("""
                                ★ 这一条是 locale 存在执行作用域里的全部理由。
                                改那个 JVM 级的 volatile 字段时,这里会拿到 Hello ——
                                而那种 bug 在低并发下几乎不出现,出现也无法复现。""")
                        .isEqualTo("你好");
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
