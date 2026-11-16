package io.jaravel.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import io.jaravel.cache.store.RedisStore;
import io.jaravel.core.config.ConfigRepository;

/**
 * Redis 缓存驱动(docs/09 §1.4)。
 *
 * <p>Lettuce 是 {@code jaravel-cache} 的 optional 依赖 —— 这个自动配置
 * 只在用户把 Lettuce 放进 classpath 时才生效。配置:
 *
 * <pre>
 * cache:
 *   default: redis
 *   stores:
 *     redis:
 *       uri: redis://localhost:6379/0
 * </pre>
 *
 * <p>连接是惰性的(见 {@link RedisStore}):配置了但没用到,启动时
 * 不会连 Redis。要密码 / 换库都写在 URI 里:
 * {@code redis://:password@host:6379/2}。
 */
@AutoConfiguration(after = JaravelAuthAutoConfiguration.class)
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
public class JaravelRedisAutoConfiguration {

    @Bean
    public CacheCustomizer jaravelRedisCacheStore(ConfigRepository config) {
        return caches -> caches.register("redis", manager ->
                new RedisStore(config.string("cache.stores.redis.uri",
                                             "redis://localhost:6379/0")));
    }
}
