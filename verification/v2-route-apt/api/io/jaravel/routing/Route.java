package io.jaravel.routing;

/** 路由句柄,支持链式配置。 */
public interface Route {
    Route name(String name);
    Route middleware(String... middleware);
    Route where(String param, String regex);
    Route only(String... methods);
    Route except(String... methods);
}
