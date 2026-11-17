package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.cache.CacheInvalidator;
import io.jaravel.cache.CacheManager;
import io.jaravel.cache.CacheStore;
import io.jaravel.cache.store.ArrayStore;
import io.jaravel.cache.store.TieredStore;
import io.jaravel.core.config.MapConfigRepository;
import io.jaravel.session.SessionStore;

/**
 * cache / session 的驱动选择。
 *
 * <p>重点是「session.driver 点名了什么,就必须用什么」:
 * 以前 {@code driver: redis} 会走默认 cache store,于是
 * {@code cache.default: caffeine} + {@code session.driver: redis}
 * 会把会话静默放进进程内缓存 —— 多实例下刷新页面就掉登录。
 */
class CacheAndSessionWiringTest {

    private static CacheStore named(String name) {
        return new ArrayStore() {
            @Override public String name() { return name; }
        };
    }

    private static CacheManager caches() {
        return new CacheManager()
                .register("array", named("array"))
                .register("caffeine", named("caffeine"))
                .register("redis", named("redis"))
                .register("database", named("database"))
                .defaultStore("caffeine");
    }

    private static SessionStore session(String driver, Map<String, Object> extra,
                                        CacheManager caches) {
        var tree = new java.util.LinkedHashMap<String, Object>();
        var session = new java.util.LinkedHashMap<String, Object>();
        session.put("driver", driver);
        session.putAll(extra);
        tree.put("session", session);
        return new JaravelAuthAutoConfiguration()
                .jaravelSessionStore(new MapConfigRepository(tree), caches, null);
    }

    @Test
    @DisplayName("★ driver: redis 用 redis store,而不是默认 store")
    void redisDriverUsesRedisStore() {
        assertThat(session("redis", Map.of(), caches()).name())
                .as("cache.default 是 caffeine;会话必须在 redis 里")
                .isEqualTo("cache(redis)");
    }

    @Test
    @DisplayName("driver: database 用 database store")
    void databaseDriverUsesDatabaseStore() {
        assertThat(session("database", Map.of(), caches()).name())
                .isEqualTo("cache(database)");
    }

    @Test
    @DisplayName("driver: cache 时 session.store 才有意义")
    void storeOverrideOnlyForCacheDriver() {
        assertThat(session("cache", Map.of("store", "redis"), caches()).name())
                .isEqualTo("cache(redis)");
        assertThat(session("cache", Map.of(), caches()).name())
                .isEqualTo("cache(caffeine)");
        assertThat(session("redis", Map.of("store", "caffeine"), caches()).name())
                .as("driver 已经点名 redis,session.store 不该把它换成 caffeine")
                .isEqualTo("cache(redis)");
    }

    @Test
    @DisplayName("未知驱动报错里列出 redis / database")
    void unknownDriverListsRedis() {
        assertThatThrownBy(() -> session("redsi", Map.of(), caches()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis")
                .hasMessageContaining("database");
    }

    @Test
    @DisplayName("Lettuce 在 classpath 上时注册 redis store(惰性连接)")
    void redisAutoConfigurationRegistersStore() {
        var config = new MapConfigRepository(Map.of(
                "cache", Map.of("stores", Map.of("redis",
                        Map.of("uri", "redis://127.0.0.1:6379/15")))));
        var manager = new CacheManager().register("array", named("array"))
                .defaultStore("array");

        new JaravelRedisAutoConfiguration().jaravelRedisCacheStore(config)
                .customize(manager);

        assertThat(manager.registeredStores()).contains("redis");
        assertThat(manager.store("redis").store().name()).isEqualTo("redis");
    }

    @Test
    @DisplayName("tiered 配置复用 L1/L2 的同一实例")
    void tieredUsesTheSameL1AndL2() {
        var manager = new CacheManager()
                .register("l1", named("l1"))
                .register("l2", named("l2"))
                .defaultStore("l1");
        var config = new MapConfigRepository(Map.of(
                "cache", Map.of("stores", Map.of("tiered", Map.of(
                        "l1", "l1",
                        "l2", "l2",
                        "l1-ttl", "30s")))));

        JaravelAuthAutoConfiguration.registerTiered(manager, config, null);
        manager.store("tiered").put("k", "v", java.time.Duration.ofMinutes(1));

        assertThat(manager.store("l1").get("k", Object.class)).isPresent();
        assertThat(manager.store("l2").get("k", Object.class)).isPresent();
    }

    @Test
    @DisplayName("★ tiered 的 L2 是 redis 才注册失效广播;forget 会发布 key")
    void tieredInvalidationWiring() {
        var registered = new AtomicInteger();
        var published = new ArrayList<String>();
        CacheInvalidator invalidator = new CacheInvalidator() {
            @Override public void register(TieredStore store) { registered.incrementAndGet(); }
            @Override public void publish(String key) { published.add(key); }
        };

        var manager = new CacheManager()
                .register("l1", named("l1"))
                .register("redis", named("redis"))
                .defaultStore("l1");
        var config = new MapConfigRepository(Map.of("cache", Map.of("stores", Map.of("tiered",
                Map.of("l1", "l1", "l2", "redis", "l1-ttl", "30s")))));

        JaravelAuthAutoConfiguration.registerTiered(manager, config, invalidator);
        manager.store("tiered").put("k", "v", java.time.Duration.ofMinutes(1));
        manager.store("tiered").forget("k");

        assertThat(registered.get()).isEqualTo(1);
        assertThat(published).containsExactly("k");

        // L2 不是 redis → 不注册、也不发布(否则每个实例会连一台无关的 Redis)
        var plain = new CacheManager()
                .register("l1", named("l1"))
                .register("plain", named("plain"))
                .defaultStore("l1");
        var plainConfig = new MapConfigRepository(Map.of("cache", Map.of("stores", Map.of(
                "tiered", Map.of("l1", "l1", "l2", "plain", "l1-ttl", "30s")))));

        JaravelAuthAutoConfiguration.registerTiered(plain, plainConfig, invalidator);
        plain.store("tiered").forget("x");

        assertThat(registered.get()).isEqualTo(1);
        assertThat(published).containsExactly("k");
    }
}
