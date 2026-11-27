package net.dobeen.jaravel.spring.dashboard;

import java.util.List;

import net.dobeen.jaravel.routing.Route;
import net.dobeen.jaravel.routing.Router;
import net.dobeen.jaravel.spring.FrameworkRoutes;

/**
 * 队列面板的路由(docs/10 §1.8)。
 *
 * <p>实现 {@link FrameworkRoutes} 而不是普通 {@code RouteDefinition}:
 * 应用只写了 {@code WebRoutes} 时,「没有找到任何应用路由」的警告
 * 仍然要能触发;而且框架路由排在应用路由之后,应用想接管
 * {@code /_jaravel/dashboard}(加自己的授权)只需声明同一条路径。
 *
 * <p>默认中间件是 {@code web}(会话 + CSRF):面板上的重试/清空是
 * 改状态的操作,不能没有 CSRF。生产环境请把 `dashboard.enabled`
 * 保持关闭,或把 `dashboard.middleware` 设成 {@code web,auth}。
 */
public final class DashboardRoutes implements FrameworkRoutes {

    private final boolean enabled;
    private final String base;
    private final List<String> middleware;

    public DashboardRoutes(boolean enabled, String base, List<String> middleware) {
        this.enabled = enabled;
        this.base = base;
        this.middleware = List.copyOf(middleware);
    }

    @Override
    public void define(Router r) {
        if (!enabled) return;

        // 方法引用指向**类**而不是实例:路由层通过容器解析控制器,
        // 由 starter 把 DashboardController 注册成一个 @Bean
        route(r.get(base, DashboardController::index), "jaravel.dashboard");
        route(r.get(base + "/api/overview", DashboardController::overview),
                "jaravel.dashboard.api");
        route(r.post(base + "/failed/{id}/retry", DashboardController::retry), null);
        route(r.post(base + "/failed/{id}/forget", DashboardController::forget), null);
        route(r.post(base + "/failed/flush", DashboardController::flush), null);
    }

    private void route(Route route, String name) {
        if (name != null) route.name(name);
        if (!middleware.isEmpty()) {
            route.middleware(middleware.toArray(String[]::new));
        }
    }

    public String basePath() {
        return base;
    }
}
