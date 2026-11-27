package net.dobeen.jaravel.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Redirect.back 的同源校验(v0.9 安全审计)")
class RedirectBackTest {

    private static BasicRequest request() {
        return BasicRequest.of("POST", "/login")
                .scheme("http").host("app.example").port(80);
    }

    private static String location(BasicRequest request, String referer) {
        if (referer != null) request.header("Referer", referer);
        return Redirect.back(request, "/dashboard").location();
    }

    @Test
    @DisplayName("相对路径原样返回")
    void relativeRefererIsKept() {
        assertThat(location(request(), "/posts/42?tab=comments"))
                .isEqualTo("/posts/42?tab=comments");
    }

    @Test
    @DisplayName("同源绝对 URL 归约成相对路径")
    void sameOriginAbsoluteIsReduced() {
        assertThat(location(request(), "http://app.example/posts?page=2"))
                .isEqualTo("/posts?page=2");
    }

    @Test
    @DisplayName("跨域、协议相对、反斜杠、协议不一致都回落")
    void hostileReferersFallBack() {
        assertThat(location(request(), "https://evil.example/steal"))
                .isEqualTo("/dashboard");
        assertThat(location(request(), "//evil.example/steal"))
                .isEqualTo("/dashboard");
        assertThat(location(request(), "/\\evil.example/steal"))
                .isEqualTo("/dashboard");
        assertThat(location(request(), "https://app.example/steal"))
                .as("同 host 但 scheme 不同,也不是同源")
                .isEqualTo("/dashboard");
        assertThat(location(request(), "http://app.example:8080/steal"))
                .as("端口不同,不是同源")
                .isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("没有 Referer 时用 fallback")
    void missingRefererUsesFallback() {
        assertThat(location(request(), null)).isEqualTo("/dashboard");
        assertThat(location(request(), "   ")).isEqualTo("/dashboard");
    }
}
