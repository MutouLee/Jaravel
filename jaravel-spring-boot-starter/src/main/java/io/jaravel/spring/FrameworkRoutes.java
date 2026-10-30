package io.jaravel.spring;

import io.jaravel.routing.RouteDefinition;

/**
 * 标记:这是**框架自带**的路由,不是应用的。
 *
 * <p>存在的理由只有一个:让「没有找到任何 RouteDefinition」那条警告
 * 继续有效。框架开始自己贡献路由(存储的临时 URL)之后,
 * 一个只按「列表是否为空」判断的检查永远不会再触发 ——
 * 而它要提示的情况(忘了给 {@code WebRoutes} 加 {@code @Component})
 * 依然会发生,只是症状变成了「所有路由都 404」。
 *
 * <p>不按包名判断({@code io.jaravel.} 前缀):那会把一个恰好放在
 * 这个包下的应用类误判成框架的。
 */
public interface FrameworkRoutes extends RouteDefinition {

    /**
     * 框架路由排在**应用路由之后**。
     *
     * <p>匹配是先注册先赢,所以一个应用想接管
     * {@code /storage/temporary}(比如加一层额外的授权)
     * 只要声明同一条路径即可,不需要先关掉框架的这一条。
     */
    @Override
    default int order() {
        return Integer.MAX_VALUE;
    }
}
