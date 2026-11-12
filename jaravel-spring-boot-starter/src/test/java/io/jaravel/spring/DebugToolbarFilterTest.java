package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.jaravel.core.config.MapConfigRepository;
import io.jaravel.http.Response;
import io.jaravel.routing.Route;

/**
 * 开发期调试工具栏(docs/15 §4.3)。
 *
 * <p>这里直接驱动 filter + Mock servlet:注入、跳过、关闭三条路径。
 */
class DebugToolbarFilterTest {

    static class DummyController {
        public Object index() {
            return null;
        }
    }

    private static DebugToolbarFilter filter(boolean local) {
        return new DebugToolbarFilter(new MapConfigRepository(
                Map.of("app", Map.of("env", local ? "local" : "production",
                                     "debug", local))));
    }

    private static MockHttpServletRequest request(String accept) {
        var request = new MockHttpServletRequest("GET", "/posts");
        if (accept != null) request.addHeader("Accept", accept);
        request.setAttribute(JaravelHandlerAdapter.ROUTE_ATTRIBUTE,
                Route.forTesting("GET", "/posts", DummyController.class, "index", List.of()));
        request.setAttribute(JaravelHandlerAdapter.RESPONSE_ATTRIBUTE,
                Response.of(new Response.Body.ViewRef("posts.index",
                        Map.of("posts", List.of("a", "b"))), 200));
        return request;
    }

    private static String run(DebugToolbarFilter filter, MockHttpServletRequest request,
                              String body, String contentType, int status) throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            var http = (jakarta.servlet.http.HttpServletResponse) res;
            http.setContentType(contentType);
            http.setStatus(status);
            http.getWriter().write(body);
        });

        return response.getContentAsString();
    }

    @Test
    @DisplayName("★ HTML 页面注入:路由、视图、耗时、内存")
    void injectsIntoHtml() throws Exception {
        String html = run(filter(true), request("text/html"),
                "<html><body>hi</body></html>", "text/html;charset=UTF-8", 200);

        assertThat(html)
                .contains("id=\"jaravel-debugbar\"")
                .contains("GET /posts")
                .contains("DummyController#index")
                .contains("posts.index")
                .contains(" ms")
                .contains(" MB")
                .contains("</body>");
        assertThat(html.indexOf("jaravel-debugbar"))
                .as("工具栏在 </body> 之前")
                .isLessThan(html.lastIndexOf("</body>"));
    }

    @Test
    @DisplayName("★ JSON 响应一个字节都不动 —— 不为 API 缓冲")
    void leavesJsonAlone() throws Exception {
        String json = "{\"message\":\"ok\"}";

        String body = run(filter(true), request("application/json"),
                json, "application/json;charset=UTF-8", 200);

        assertThat(body).isEqualTo(json);
    }

    @Test
    @DisplayName("HTML 但客户端只接受 JSON → 不注入")
    void respectsAccept() throws Exception {
        String html = run(filter(true), request("application/json"),
                "<html><body>hi</body></html>", "text/html;charset=UTF-8", 200);

        assertThat(html).doesNotContain("jaravel-debugbar");
    }

    @Test
    @DisplayName("生产环境默认关闭")
    void disabledInProduction() throws Exception {
        String html = run(filter(false), request("text/html"),
                "<html><body>hi</body></html>", "text/html;charset=UTF-8", 200);

        assertThat(html).doesNotContain("jaravel-debugbar");
    }

    @Test
    @DisplayName("没有 </body> 时追加到末尾")
    void appendsWithoutBodyTag() throws Exception {
        String html = run(filter(true), request("text/html"),
                "<div>fragment</div>", "text/html;charset=UTF-8", 200);

        assertThat(html).startsWith("<div>fragment</div>")
                .contains("jaravel-debugbar");
    }

    @Test
    @DisplayName("错误页不注入 —— 开发错误页自己有面板")
    void skipsErrorPages() throws Exception {
        String html = run(filter(true), request("text/html"),
                "<html><body>boom</body></html>", "text/html;charset=UTF-8", 500);

        assertThat(html).doesNotContain("jaravel-debugbar");
    }
}
