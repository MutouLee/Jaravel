package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.core.container.Container;
import io.jaravel.core.container.SimpleContainer;
import io.jaravel.http.BasicRequest;
import io.jaravel.http.HttpException;
import io.jaravel.http.Request;
import io.jaravel.http.annotations.Header;
import io.jaravel.http.annotations.Input;
import io.jaravel.http.annotations.Path;
import io.jaravel.http.annotations.Query;
import io.jaravel.routing.Route;
import io.jaravel.routing.RouteCollection;
import io.jaravel.routing.Router;

/**
 * 参数绑定。
 *
 * <p>核心用例是 {@link #namedBindingPreventsSilentSwap()} —— v0.1 的纯位置绑定
 * 会在那种情况下静默交换两个同类型参数,而静默错误是最难查的一类。
 */
class ArgumentBinderTest {

    private Container container;

    @BeforeEach
    void setUp() {
        container = new SimpleContainer();
        ArgumentBinderTest.Service.CALLS = 0;
        container.instance(Service.class, new Service());
    }

    // ================= 被测控制器 =================

    static class Service {
        static int CALLS;
        Service() { CALLS++; }
    }

    public static class TestController {

        /** 路径名与参数名**顺序相反** —— 位置绑定会交换,名字绑定不会 */
        public String swapped(Long postId, Long commentId) {
            return postId + "/" + commentId;
        }

        public String single(Long id) {
            return "id=" + id;
        }

        public String withRequest(Request request, Long id) {
            return request.method() + " id=" + id;
        }

        public String annotatedQuery(@Query(value = "page", defaultValue = "1") int page) {
            return "page=" + page;
        }

        public String requiredQuery(@Query(value = "token", required = true) String token) {
            return "token=" + token;
        }

        public String annotatedInput(@Input("q") String keyword) {
            return "q=" + keyword;
        }

        public String annotatedHeader(@Header(value = "X-Trace", defaultValue = "none") String trace) {
            return "trace=" + trace;
        }

        /** 路径名与参数名不一致,靠 @Path 显式对上 */
        public String renamed(@Path("id") Long identifier) {
            return "id=" + identifier;
        }

        /** 路径参数用完后,余下的简单参数按参数名从输入取 */
        public String pathThenInput(Long id, String sort) {
            return id + "/" + sort;
        }

        public String injected(Service service, Long id) {
            return service.getClass().getSimpleName() + "/" + id;
        }

        public String typed(java.time.LocalDate on, boolean active) {
            return on + "/" + active;
        }
    }

    // ================= 用例 =================

    @Test
    @DisplayName("★ 按名字绑定:路径顺序与参数顺序相反时不会静默交换")
    void namedBindingPreventsSilentSwap() {
        // 路径里 commentId 在前、postId 在后,方法签名反过来
        Route route = routeFor("/posts/{commentId}/c/{postId}",
                               "swapped", Long.class, Long.class);

        Object[] args = ArgumentBinder.bind(
                requestWith("/posts/7/c/42", Map.of("commentId", "7", "postId", "42")),
                route, container);

        // 按位置会得到 [7, 42](postId=7, commentId=42)—— 全错且无报错
        assertThat(args).containsExactly(42L, 7L);
    }

    @Test
    @DisplayName("单个路径参数")
    void singlePathParam() {
        Route route = routeFor("/posts/{id}",
                               "single", Long.class);

        Object[] args = ArgumentBinder.bind(
                requestWith("/posts/9", Map.of("id", "9")), route, container);

        assertThat(args).containsExactly(9L);
    }

    @Test
    @DisplayName("Request 参数直接注入,不占路径位置")
    void requestIsInjected() {
        Route route = routeFor("/posts/{id}",
                               "withRequest", Request.class, Long.class);

        Object[] args = ArgumentBinder.bind(
                requestWith("/posts/3", Map.of("id", "3")), route, container);

        assertThat(args[0]).isInstanceOf(Request.class);
        assertThat(args[1]).isEqualTo(3L);
    }

    @Test
    @DisplayName("@Query 带默认值")
    void queryWithDefault() {
        Route route = routeFor("/posts",
                               "annotatedQuery", int.class);

        assertThat(ArgumentBinder.bind(requestWith("/posts", Map.of()), route, container))
                .containsExactly(1);

        var withPage = BasicRequest.of("GET", "/posts?page=5");
        assertThat(ArgumentBinder.bind(withPage, route, container)).containsExactly(5);
    }

    @Test
    @DisplayName("@Query(required) 缺失时 400")
    void requiredQueryMissing() {
        Route route = routeFor("/posts",
                               "requiredQuery", String.class);

        assertThatThrownBy(() -> ArgumentBinder.bind(
                requestWith("/posts", Map.of()), route, container))
                .isInstanceOf(HttpException.class)
                .satisfies(e -> assertThat(((HttpException) e).status()).isEqualTo(400))
                .hasMessageContaining("token");
    }

    @Test
    @DisplayName("@Input 同时覆盖查询串与表单体")
    void inputCoversBothSources() {
        Route route = routeFor("/search",
                               "annotatedInput", String.class);

        var fromQuery = BasicRequest.of("GET", "/search?q=java");
        assertThat(ArgumentBinder.bind(fromQuery, route, container)).containsExactly("java");

        var fromBody = BasicRequest.of("POST", "/search").bodyParam("q", "kotlin");
        assertThat(ArgumentBinder.bind(fromBody, route, container)).containsExactly("kotlin");
    }

    @Test
    @DisplayName("@Header 带默认值")
    void headerWithDefault() {
        Route route = routeFor("/x",
                               "annotatedHeader", String.class);

        assertThat(ArgumentBinder.bind(BasicRequest.of("GET", "/x"), route, container))
                .containsExactly("none");

        var withHeader = BasicRequest.of("GET", "/x").header("X-Trace", "abc123");
        assertThat(ArgumentBinder.bind(withHeader, route, container)).containsExactly("abc123");
    }

    @Test
    @DisplayName("@Path 处理「参数名与路径名不一致」")
    void explicitPathName() {
        Route route = routeFor("/posts/{id}",
                               "renamed", Long.class);

        assertThat(ArgumentBinder.bind(
                requestWith("/posts/11", Map.of("id", "11")), route, container))
                .containsExactly(11L);
    }

    @Test
    @DisplayName("路径参数用完后,剩下的按参数名从输入取")
    void fallsBackToInputByName() {
        Route route = routeFor("/posts/{id}",
                               "pathThenInput", Long.class, String.class);

        var request = BasicRequest.of("GET", "/posts/4?sort=desc").routeParam("id", "4");

        assertThat(ArgumentBinder.bind(request, route, container))
                .containsExactly(4L, "desc");
    }

    @Test
    @DisplayName("复杂类型走容器")
    void complexTypeFromContainer() {
        Route route = routeFor("/posts/{id}",
                               "injected", Service.class, Long.class);

        Object[] args = ArgumentBinder.bind(
                requestWith("/posts/2", Map.of("id", "2")), route, container);

        assertThat(args[0]).isInstanceOf(Service.class);
        assertThat(args[1]).isEqualTo(2L);
    }

    @Test
    @DisplayName("时间与布尔类型的转换")
    void typedConversion() {
        Route route = routeFor("/r/{on}/{active}",
                               "typed", java.time.LocalDate.class, boolean.class);

        assertThat(ArgumentBinder.bind(
                requestWith("/r/2026-10-29/true",
                            Map.of("on", "2026-10-29", "active", "true")),
                route, container))
                .containsExactly(java.time.LocalDate.of(2026, 10, 29), true);
    }

    @Test
    @DisplayName("路径参数类型不符 → 404(而不是 400)")
    void badPathParamIs404() {
        Route route = routeFor("/posts/{id}",
                               "single", Long.class);

        assertThatThrownBy(() -> ArgumentBinder.bind(
                requestWith("/posts/abc", Map.of("id", "abc")), route, container))
                .isInstanceOf(HttpException.class)
                .satisfies(e -> assertThat(((HttpException) e).status()).isEqualTo(404))
                .hasMessageContaining("不是合法的 Long");
    }

    // ================= 夹具 =================

    /**
     * 直接构造 Route。
     *
     * <p>不走 Router 的 lambda 解析:那条路径测的是序列化 lambda introspection,
     * 这里要测的是**绑定**,两者分开才能在失败时立刻知道是哪一环坏了。
     */
    private static Route routeFor(String uri, String action, Class<?>... parameterTypes) {
        return Route.forTesting("GET", uri, TestController.class, action,
                                List.of(parameterTypes));
    }

    private static Request requestWith(String path, Map<String, String> routeParams) {
        var r = BasicRequest.of("GET", path);
        routeParams.forEach(r::routeParam);
        return r;
    }
}
