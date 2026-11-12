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
        return filter(local, null);
    }

    private static DebugToolbarFilter filter(boolean local,
                                             io.jaravel.database.Connection connection) {
        return new DebugToolbarFilter(new MapConfigRepository(
                Map.of("app", Map.of("env", local ? "local" : "production",
                                     "debug", local))), connection);
    }

    /** 只要 collectedQueries 的 Connection 替身 —— 为一条面板断言实现整个接口不值得 */
    private static io.jaravel.database.Connection connectionWith(
            io.jaravel.database.Connection.QueryLog... logs) {

        return (io.jaravel.database.Connection) java.lang.reflect.Proxy.newProxyInstance(
                DebugToolbarFilterTest.class.getClassLoader(),
                new Class<?>[]{io.jaravel.database.Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "collectedQueries" -> List.of(logs);
                    case "toString" -> "ConnectionStub";
                    case "hashCode" -> 1;
                    case "equals" -> false;
                    default -> {
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) yield false;
                        if (type == int.class) yield 0;
                        if (type == long.class) yield 0L;
                        yield null;
                    }
                });
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
    @DisplayName("★ SQL 面板:条数、总耗时、语句与绑定值(都转义)")
    void showsCollectedQueries() throws Exception {
        var connection = connectionWith(new io.jaravel.database.Connection.QueryLog(
                "select * from \"users\" where name = ?",
                List.of("Tom & Jerry"), 1200, "app.PostController.index"));

        String html = run(filter(true, connection), request("text/html"),
                "<html><body>hi</body></html>", "text/html;charset=UTF-8", 200);

        assertThat(html)
                .contains("SQL")
                .contains("1 条")
                .contains("select * from")
                .contains("app.PostController.index")
                .contains("&amp;")
                .contains("[Tom &amp; Jerry]");
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
