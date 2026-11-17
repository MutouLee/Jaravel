package io.jaravel.spring.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.routing.Router;
import io.jaravel.spring.JaravelDashboardAutoConfiguration;

/**
 * 面板路由的注册与关闭。
 *
 * <p>关闭时**一条路由都不能注册** —— 生产环境默认关掉,
 * 一个「关了但某条 API 还在」的面板是信息泄露。
 */
class DashboardRoutesTest {

    private static final String BASE = "/_jaravel/dashboard";

    @Test
    @DisplayName("开启时注册 5 条,全部带 web 中间件")
    void registersAllRoutes() {
        var router = new Router();
        new DashboardRoutes(true, BASE, List.of("web")).define(router);

        var routes = router.compile().all();

        assertThat(routes).hasSize(5);
        assertThat(routes).extracting(r -> r.method() + " " + r.uri())
                .containsExactlyInAnyOrder(
                        "GET " + BASE,
                        "GET " + BASE + "/api/overview",
                        "POST " + BASE + "/failed/{id}/retry",
                        "POST " + BASE + "/failed/{id}/forget",
                        "POST " + BASE + "/failed/flush");

        assertThat(routes).allSatisfy(r ->
                assertThat(r.middlewareNames()).containsExactly("web"));

        assertThat(router.compile().byName("jaravel.dashboard")).isPresent();
        assertThat(router.compile().byName("jaravel.dashboard.api")).isPresent();
    }

    @Test
    @DisplayName("★ 关闭时一条都不注册")
    void disabledRegistersNothing() {
        var router = new Router();
        new DashboardRoutes(false, BASE, List.of("web")).define(router);

        assertThat(router.compile().all()).isEmpty();
    }

    @Test
    @DisplayName("路径规整:补前导斜杠、去尾随斜杠")
    void normalizesBasePath() {
        assertThat(JaravelDashboardAutoConfiguration.normalizeBase("x/dashboard/"))
                .isEqualTo("/x/dashboard");
        assertThat(JaravelDashboardAutoConfiguration.normalizeBase("/x/"))
                .isEqualTo("/x");
        assertThat(JaravelDashboardAutoConfiguration.normalizeBase("  "))
                .isEqualTo(BASE);
    }
}
