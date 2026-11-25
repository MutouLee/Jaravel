package io.jaravel.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 默认安全响应头(SECURITY-AUDIT §6)。
 *
 * <p>这组测试里最重要的两条是「CSP / HSTS 默认不发」与「已设过的头不覆盖」——
 * 它们是这个中间件能默认挂在全局链上的前提。一个默认就发 CSP 的框架
 * 会让一部分应用的页面在升级后静默少一块。
 */
class SecurityHeadersTest {

    private java.util.Map<String, String> headers(SecurityHeaders policy, boolean secure) {
        return policy.headersFor(secure);
    }

    @Test
    @DisplayName("默认三个头都在")
    void defaultsArePresent() {
        var sent = headers(new SecurityHeaders(), false);

        assertThat(sent.get("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(sent.get("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(sent.get("Referrer-Policy"))
                .isEqualTo("strict-origin-when-cross-origin");
    }

    @Test
    @DisplayName("★ CSP 默认不发 —— 框架猜的策略会挡掉应用自己的脚本")
    void cspIsOptIn() {
        var sent = headers(new SecurityHeaders(), false);

        assertThat(sent.get("Content-Security-Policy")).isNull();
    }

    @Test
    @DisplayName("配了 CSP 就发")
    void cspWhenConfigured() {
        var sent = headers(new SecurityHeaders().withCsp("default-src 'self'"), false);

        assertThat(sent.get("Content-Security-Policy")).isEqualTo("default-src 'self'");
    }

    @Test
    @DisplayName("report-only 的 CSP 是另一个头 —— 上线前先观察")
    void cspReportOnlyIsADifferentHeader() {
        var sent = headers(new SecurityHeaders().withCspReportOnly("default-src 'self'"), false);

        assertThat(sent.get("Content-Security-Policy-Report-Only"))
                .isEqualTo("default-src 'self'");
        assertThat(sent.get("Content-Security-Policy")).isNull();
    }

    @Nested
    @DisplayName("HSTS")
    class Hsts {

        @Test
        @DisplayName("默认不发 —— 它是粘的,开错了用户浏览器会记住")
        void optInByDefault() {
            var sent = headers(new SecurityHeaders(), true);

            assertThat(sent.get("Strict-Transport-Security")).isNull();
        }

        @Test
        @DisplayName("https 上按配置发")
        void sentOverHttps() {
            var sent = headers(new SecurityHeaders().withHsts(Duration.ofDays(180), true, false),
                    true);

            assertThat(sent.get("Strict-Transport-Security"))
                    .isEqualTo("max-age=15552000; includeSubDomains");
        }

        @Test
        @DisplayName("★ 明文请求上不发 —— 否则开发环境里一次误配看不出来")
        void notSentOverHttp() {
            var sent = headers(new SecurityHeaders().withHsts(Duration.ofDays(180), false, false),
                    false);

            assertThat(sent.get("Strict-Transport-Security")).isNull();
        }

        @Test
        @DisplayName("max-age 为 0 等于关掉")
        void zeroMaxAgeDisablesIt() {
            var sent = headers(new SecurityHeaders().withHsts(Duration.ZERO, true, true), true);

            assertThat(sent.get("Strict-Transport-Security")).isNull();
        }
    }

    /*
     * 「已经设过的头不覆盖」与「控制器的覆盖要赢」不在这里 ——
     * 它们是 SecurityHeadersFilter 与 ResponseWriter 的行为
     * (containsHeader / setHeader),由 examples/auth 的
     * LocaleAndSecurityHeadersTest 在真实响应上验收。
     */

    @Test
    @DisplayName("可以逐项覆盖默认值,传空等于不发这个头")
    void defaultsAreOverridable() {
        var sent = headers(new SecurityHeaders()
                        .withFrameOptions("DENY")
                        .withReferrerPolicy("")
                        .withPermissionsPolicy("geolocation=()"),
                false);

        assertThat(sent.get("X-Frame-Options")).isEqualTo("DENY");
        assertThat(sent.get("Referrer-Policy")).isNull();
        assertThat(sent.get("Permissions-Policy")).isEqualTo("geolocation=()");
    }
}
