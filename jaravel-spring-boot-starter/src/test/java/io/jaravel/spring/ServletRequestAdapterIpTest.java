package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("客户端 IP 不采信伪造的 X-Forwarded-For(v0.9 安全审计)")
class ServletRequestAdapterIpTest {

    @Test
    void forwardedHeaderIsIgnoredByDefault() {
        var servlet = new MockHttpServletRequest("GET", "/login");
        servlet.setRemoteAddr("10.0.0.7");
        servlet.addHeader("X-Forwarded-For", "203.0.113.9");
        servlet.addHeader("Forwarded", "for=203.0.113.9");

        var request = ServletRequestAdapter.adapt(servlet);

        assertThat(request.ip())
                .as("按 IP 的登录节流不能被一个可伪造的头绕过")
                .isEqualTo("10.0.0.7");
    }
}
