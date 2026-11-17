package io.jaravel.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.jaravel.cache.CacheInvalidator;
import io.jaravel.cache.store.RedisCacheInvalidator;
import io.jaravel.core.config.ConfigRepository;

/**
 * 两级缓存的 L1 跨实例失效(docs/09 §7)。
 *
 * <p>只有 Lettuce 在 classpath 上时才提供 {@link CacheInvalidator}。
 * 它**惰性连接**:构造只记 URI/频道;第一次有 tiered store 注册
 * (即第一次用到 tiered)或第一次 forget 时才连 Redis。
 *
 * <pre>{@code
 * cache:
 *   stores:
 *     redis:
 *       uri: redis://localhost:6379/0
 *       invalidate-channel: jaravel:cache:invalidate
 * }</pre>
 *
 * <p>单实例、或 L2 不是 redis 时不会注册任何 store,也就不会连接。
 */
@AutoConfiguration(after = JaravelAuthAutoConfiguration.class)
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
public class JaravelCacheInvalidationAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CacheInvalidator jaravelCacheInvalidator(ConfigRepository config) {
        return new RedisCacheInvalidator(
                config.string("cache.stores.redis.uri", "redis://localhost:6379/0"),
                config.string("cache.stores.redis.invalidate-channel",
                        RedisCacheInvalidator.DEFAULT_CHANNEL));
    }
}
