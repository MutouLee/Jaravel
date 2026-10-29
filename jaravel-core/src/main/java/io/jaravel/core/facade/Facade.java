package io.jaravel.core.facade;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.core.Application;

/**
 * 门面基础设施。
 *
 * <p><b>为什么要静态门面:</b>Laravel 优雅感的最大来源 —— {@code Cache.get("k")} 一行完事。
 *
 * <p><b>对「静态不可测试」的回答:</b>
 * <ol>
 *   <li>门面有一等公民的 {@link #swap} 支持,测试可替换任意契约实现;
 *   <li>门面**零状态** —— 状态全在容器里,因此不存在并发问题;
 *   <li>同时提供契约注入作为完全等价的替代途径。两条路并存,团队按偏好选。
 * </ol>
 *
 * <p><b>为什么具体门面手写转发而非动态代理:</b>保证 IDE 可跳转、可重构、
 * 零反射、GraalVM native 零配置。门面总量约 20 个,一次写完长期稳定。
 */
public abstract class Facade {

    /** 测试期替换。key 是契约类型。 */
    private static final Map<Class<?>, Object> SWAPPED = new ConcurrentHashMap<>();

    protected Facade() {}

    @SuppressWarnings("unchecked")
    protected static <C> C resolve(Class<C> contract) {
        Object swapped = SWAPPED.get(contract);
        if (swapped != null) return (C) swapped;
        return Application.make(contract);
    }

    protected static <C> C resolveNamed(Class<C> contract, String name) {
        return Application.container().make(contract, name);
    }

    /** 测试期把契约实现替换为假实现 */
    public static <C> void swap(Class<C> contract, C impl) {
        SWAPPED.put(contract, impl);
    }

    public static void clearSwap(Class<?> contract) {
        SWAPPED.remove(contract);
    }

    /** 由 JUnit 扩展在每个测试后自动调用 */
    public static void clearSwaps() {
        SWAPPED.clear();
    }

    public static boolean isSwapped(Class<?> contract) {
        return SWAPPED.containsKey(contract);
    }
}
