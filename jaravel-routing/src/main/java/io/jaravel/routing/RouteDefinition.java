package io.jaravel.routing;

/**
 * 路由定义入口。实现类由 starter 在启动时发现并调用。
 *
 * <pre>{@code
 * public class WebRoutes implements RouteDefinition {
 *     @Override public void define(Router r) {
 *         r.get("/", HomeController::index).name("home");
 *     }
 * }
 * }</pre>
 *
 * <p>v0.1 通过 Spring 的组件扫描发现实现类;v0.2 改为 APT 生成的
 * {@code META-INF/jaravel/routes.idx} 索引,零扫描、零反射。
 *
 * <p>多个实现类按 {@link #order()} 再按类名排序加载,保证注册顺序可重现 ——
 * 路由顺序影响动态路由的匹配优先级,不可随 classpath 扫描顺序浮动。
 */
public interface RouteDefinition {

    void define(Router r);

    /** 越小越先注册。同值按类名字典序。 */
    default int order() {
        return 0;
    }
}
