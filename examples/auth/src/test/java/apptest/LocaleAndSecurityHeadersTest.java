package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.testing.FeatureTest;

/**
 * 两个「小件」的端到端验收:按请求选语言,以及默认安全响应头。
 *
 * <p>两者都只有在**真实管道**上才有意义:中间件的 locale 要活到模板渲染那一步
 * (渲染发生在中间件链返回之后 —— 见 {@code JaravelHandlerAdapter}),
 * 而响应头要真的出现在响应上,不是出现在某个单元测试的假 Response 上。
 */
class LocaleAndSecurityHeadersTest extends FeatureTest {

    @Nested
    @DisplayName("按请求选语言(middleware \"locale\")")
    class Locale {

        @Test
        @DisplayName("默认走配置里的 zh_CN")
        void defaultsToConfiguredLocale() {
            get("/notes/page").assertOk().assertSee("我的笔记");
        }

        @Test
        @DisplayName("★ Accept-Language: en 让模板里的 @lang 变成英文")
        void acceptLanguageSwitchesTheTemplate() {
            String page = withHeader("Accept-Language", "en,zh-CN;q=0.5")
                    .get("/notes/page").assertOk().content();

            assertThat(page)
                    .as("""
                            渲染在中间件链返回之后发生,所以请求级的 locale 必须
                            活在执行作用域里而不是中间件的调用栈上 ——
                            后者的症状是「中间件生效了,页面还是默认语言」。""")
                    .contains("My notes")
                    .doesNotContain("我的笔记");
        }

        @Test
        @DisplayName("?lang=en 压过 Accept-Language —— 切换语言是一个链接")
        void queryParameterWins() {
            withHeader("Accept-Language", "zh-CN")
                    .get("/notes/page?lang=en").assertOk().assertSee("My notes");
        }

        @Test
        @DisplayName("没翻译的语言不生效,也不会把页面变成一堆键名")
        void unsupportedLanguageFallsBack() {
            withHeader("Accept-Language", "de,fr;q=0.8")
                    .get("/notes/page").assertOk().assertSee("我的笔记");
        }

        /*
         * 「上一个请求的语言不漏给下一个请求」不在这里。
         *
         * FeatureTest 的请求头在**一个测试方法内**是累积的(withHeader 加进去的
         * 东西到下一次 get 仍然带着),所以在同一个测试里发两次请求证明不了
         * 这件事 —— 第二次请求本身就还带着 Accept-Language: en。
         *
         * 真正钉住它的是 jaravel-http 的 SetLocaleTest:一条验证作用域结束后
         * 恢复默认语言,一条让两个线程同时各设一种语言、互相不串台。
         * 那两条才是「locale 存在执行作用域里」的证据。
         */
    }

    @Nested
    @DisplayName("默认安全响应头")
    class SecurityHeaders {

        @Test
        @DisplayName("三个默认头出现在真实响应上")
        void defaultsArePresent() {
            var response = get("/notes/page").assertOk();

            assertThat(response.header("X-Content-Type-Options")).contains("nosniff");
            assertThat(response.header("X-Frame-Options")).contains("SAMEORIGIN");
            assertThat(response.header("Referrer-Policy"))
                    .contains("strict-origin-when-cross-origin");
        }

        @Test
        @DisplayName("★ 在全局链上,所以不经过 web 组的 api 响应也有")
        void alsoOnApiResponses() {
            // /api/* 挂的是 api 组 + auth:api,没带令牌 → 401 JSON。
            // 这条路径一个 web 组的中间件都不经过
            var response = getJson("/api/me").assertStatus(401);

            assertThat(response.header("X-Content-Type-Options"))
                    .as("""
                            JSON 错误响应同样需要 nosniff —— 它也是一段会被浏览器
                            解析的内容。所以这个中间件在 global() 而不是 web 组里。""")
                    .contains("nosniff");
            assertThat(response.header("X-Frame-Options")).contains("SAMEORIGIN");
        }

        @Test
        @DisplayName("★ 控制器显式设的同名头赢 —— 那条路由自己说的话要算")
        void explicitHeadersWin() {
            // /notes/{id}/row 是局部刷新用的片段路由,没有设任何安全头,
            // 所以这里用的是「默认值在、而且只有一个值」这条断言:
            // ResponseWriter 用 setHeader 写第一个值,会替换掉 filter 预设的,
            // 而不是与它并存(并存的后果是浏览器按最严的那个算)
            var response = get("/notes/page").assertOk();

            assertThat(response.raw().getHeaders("X-Frame-Options"))
                    .as("两个值并存时,那条路由上的覆盖就不生效了")
                    .hasSize(1);
        }

        @Test
        @DisplayName("CSP 与 HSTS 默认不发 —— 配错它们会让站点隐蔽地坏掉")
        void cspAndHstsAreOptIn() {
            var response = get("/notes/page").assertOk();

            assertThat(response.header("Content-Security-Policy")).isEmpty();
            assertThat(response.header("Strict-Transport-Security")).isEmpty();
        }
    }
}
