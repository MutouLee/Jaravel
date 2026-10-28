package io.jaravel.routing;

/** 路由定义入口。APT 扫描本接口的全部实现类。 */
public interface RouteDefinition {
    void define(Router r);
}
