package net.dobeen.jaravel.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("登录后回跳地址的校验(v0.9 开放重定向防护)")
class IntendedTest {

    @Test
    @DisplayName("本站相对路径通过")
    void relativePathsAreSafe() {
        assertThat(Intended.isSafe("/dashboard")).isTrue();
        assertThat(Intended.isSafe("/posts?tag=a&page=2")).isTrue();
        assertThat(Intended.isSafe("/a/b/c")).isTrue();
    }

    @Test
    @DisplayName("绝对 URL、协议相对 URL、反斜杠与换行都拒绝")
    void hostileValuesAreRejected() {
        assertThat(Intended.isSafe(null)).isFalse();
        assertThat(Intended.isSafe("")).isFalse();
        assertThat(Intended.isSafe("   ")).isFalse();
        assertThat(Intended.isSafe("https://evil.example/")).isFalse();
        assertThat(Intended.isSafe("//evil.example/")).isFalse();
        assertThat(Intended.isSafe("/\\evil.example/")).isFalse();
        assertThat(Intended.isSafe("/%5Cevil.example/")).isFalse();
        assertThat(Intended.isSafe("/ok\r\nSet-Cookie: x=y")).isFalse();
        assertThat(Intended.isSafe("dashboard")).isFalse();
    }
}
