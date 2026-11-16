package io.jaravel.spring;

import io.jaravel.cache.CacheManager;

/**
 * 给 {@link CacheManager} 追加驱动。
 *
 * <p>这是「optional 驱动」的接缝:caffeine / redis / 以后可能的
 * memcached 都在各自的模块里,starter 不应该为了注册它们而把依赖
 * 硬拉进来。每个驱动自己的自动配置提供一个 {@code CacheCustomizer} bean,
 * spring 的 {@code @ConditionalOnClass} 决定它在不在。
 *
 * <p>顺序:在 starter 内置的 array / null / file / database 之后应用,
 * 于是自定义驱动可以覆盖同名 store(register 会替换工厂)。
 */
@FunctionalInterface
public interface CacheCustomizer {

    void customize(CacheManager caches);
}
