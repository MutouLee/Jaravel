package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link MethodOverrideFilter} —— HTML 表单的方法伪装(docs/04 §1.8)。
 *
 * <p>这些断言里最重要的是**不该改写**的那几条:方法伪装的经典漏洞是
 * 「GET 也能被升级」与「能伪装成 GET」,两者各自对应一种绕过。
 */
class MethodOverrideFilterTest {

    /** 跑一次 filter,返回下游看到的方法 */
    private String methodSeenBy(MockHttpServletRequest request) throws Exception {
        var chain = new MockFilterChain();
        new MethodOverrideFilter().doFilter(request, new MockHttpServletResponse(), chain);
        return ((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getMethod();
    }

    private MockHttpServletRequest form(String method, String spoofed) {
        var request = new MockHttpServletRequest(method, "/notes/7");
        request.setContentType("application/x-www-form-urlencoded");
        if (spoofed != null) request.setParameter(MethodOverrideFilter.FIELD, spoofed);
        return request;
    }

    @Test
    @DisplayName("★ POST + _method=DELETE → 下游看到 DELETE")
    void postIsOverridden() throws Exception {
        assertThat(methodSeenBy(form("POST", "DELETE"))).isEqualTo("DELETE");
        assertThat(methodSeenBy(form("POST", "PUT"))).isEqualTo("PUT");
        assertThat(methodSeenBy(form("POST", "PATCH"))).isEqualTo("PATCH");
    }

    @Test
    @DisplayName("大小写与空白不敏感")
    void caseInsensitive() throws Exception {
        assertThat(methodSeenBy(form("POST", " delete "))).isEqualTo("DELETE");
    }

    @Test
    @DisplayName("★ GET **不能**被改写 —— 否则一个链接就能删数据")
    void getIsNeverOverridden() throws Exception {
        var request = new MockHttpServletRequest("GET", "/notes/7");
        request.setParameter(MethodOverrideFilter.FIELD, "DELETE");

        assertThat(methodSeenBy(request))
                .as("链接会被浏览器预取、被爬虫抓、被聊天软件展开预览")
                .isEqualTo("GET");
    }

    @Test
    @DisplayName("★ 不允许伪装成 GET / HEAD —— 那等于绕过 CSRF")
    void cannotSpoofIntoSafeMethods() throws Exception {
        assertThat(methodSeenBy(form("POST", "GET")))
                .as("VerifyCsrfToken 的安全方法是 GET/HEAD/OPTIONS —— "
                  + "伪装成它们就不再校验令牌了")
                .isEqualTo("POST");
        assertThat(methodSeenBy(form("POST", "HEAD"))).isEqualTo("POST");
        assertThat(methodSeenBy(form("POST", "OPTIONS"))).isEqualTo("POST");
    }

    @Test
    @DisplayName("无意义的值静默忽略,按原方法处理")
    void nonsenseIsIgnored() throws Exception {
        assertThat(methodSeenBy(form("POST", "NONSENSE"))).isEqualTo("POST");
        assertThat(methodSeenBy(form("POST", ""))).isEqualTo("POST");
        assertThat(methodSeenBy(form("POST", null))).isEqualTo("POST");
    }

    @Test
    @DisplayName("★ 非表单体不读参数 —— multipart 的解析该由上传那一层做")
    void nonFormBodiesOnlyUseTheHeader() throws Exception {
        var multipart = new MockHttpServletRequest("POST", "/notes/7");
        multipart.setContentType("multipart/form-data; boundary=x");
        multipart.setParameter(MethodOverrideFilter.FIELD, "DELETE");

        assertThat(methodSeenBy(multipart))
                .as("在 filter 里 getParameter 会触发容器解析 multipart,"
                  + "而那一步本该带着大小上限与临时目录配置")
                .isEqualTo("POST");

        var withHeader = new MockHttpServletRequest("POST", "/notes/7");
        withHeader.setContentType("multipart/form-data; boundary=x");
        withHeader.addHeader(MethodOverrideFilter.HEADER, "DELETE");

        assertThat(methodSeenBy(withHeader))
                .as("显式的头不需要读体 —— 它是 JS 客户端的常用做法")
                .isEqualTo("DELETE");
    }

    @Test
    @DisplayName("头优先于表单字段(两者都在时)")
    void headerWins() throws Exception {
        var request = form("POST", "PUT");
        request.addHeader(MethodOverrideFilter.HEADER, "DELETE");

        assertThat(methodSeenBy(request)).isEqualTo("DELETE");
    }

    @Test
    @DisplayName("其它东西原样透过去:参数、头、路径")
    void everythingElsePassesThrough() throws Exception {
        var request = form("POST", "DELETE");
        request.setParameter("title", "标题");
        request.addHeader("X-Custom", "v");

        var chain = new MockFilterChain();
        new MethodOverrideFilter().doFilter(request, new MockHttpServletResponse(), chain);
        var seen = (jakarta.servlet.http.HttpServletRequest) chain.getRequest();

        assertThat(seen.getParameter("title")).isEqualTo("标题");
        assertThat(seen.getHeader("X-Custom")).isEqualTo("v");
        assertThat(seen.getRequestURI()).isEqualTo("/notes/7");
        assertThat(seen.getParameter(MethodOverrideFilter.FIELD))
                .as("_method 留在原处 —— 删掉它要重建参数表,代价远大于收益")
                .isEqualTo("DELETE");
    }
}
