package io.jaravel.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.jaravel.support.Arr;

/**
 * 按名字管理多个 store。
 *
 * <pre>{@code
 * Cache.get("k");                    // 默认 store
 * Cache.store("redis").get("k");     // 指定 store
 * }</pre>
 *
 * <p>store 是**惰性创建**的:配置里写了 redis 但从没用过,就不会去连它。
 * 否则本地开发时启动就会因为连不上 Redis 而失败 —— 而那个 store 根本没人用。
 */
public final class CacheManager {

    private final Map<String, Function<CacheManager, CacheStore>> factories =
            new LinkedHashMap<>();
    private final Map<String, Repository> resolved = new ConcurrentHashMap<>();

    private String defaultStore = "array";
    private String prefix = "";

    public CacheManager register(String name, Function<CacheManager, CacheStore> factory) {
        factories.put(name, factory);
        resolved.remove(name);      // 重新注册时丢弃旧实例
        return this;
    }

    public CacheManager register(String name, CacheStore store) {
        return register(name, manager -> store);
    }

    public CacheManager defaultStore(String name) {
        this.defaultStore = name;
        return this;
    }

    public CacheManager prefix(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
        resolved.clear();           // 前缀变了,已建的 Repository 全部作废
        return this;
    }

    public String defaultStoreName() {
        return defaultStore;
    }

    public java.util.Set<String> registeredStores() {
        return java.util.Set.copyOf(factories.keySet());
    }

    // ================= 解析 =================

    public Repository store() {
        return store(defaultStore);
    }

    public Repository store(String name) {
        return resolved.computeIfAbsent(name, n -> {
            var factory = factories.get(n);
            if (factory == null) throw unknownStore(n);
            return new Repository(factory.apply(this), prefix);
        });
    }

    private IllegalStateException unknownStore(String name) {
        String hint = Arr.closest(name, factories.keySet().stream().toList())
                .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse("");

        return new IllegalStateException("""
                没有名为 "%s" 的缓存 store。%s
                  已注册:%s
                  修复:在 config/cache.yml 的 cache.stores 下登记它,
                        或改用已注册的名字。"""
                .formatted(name, hint, factories.keySet()));
    }

    /** 测试与热重载用:丢弃所有已创建的实例 */
    public void reset() {
        resolved.clear();
    }

    /**
     * 只带一个 array store 的最小管理器。
     *
     * <p>默认值 —— 让「没配 cache.yml 也能用 Cache」成立。
     * array 是进程内且不持久,所以它只适合开发与测试;
     * 真实部署必须显式配置,这个默认不会被误当成生产配置,
     * 因为重启就丢数据的现象会立刻暴露。
     */
    public static CacheManager inMemory() {
        return new CacheManager()
                .register("array", new io.jaravel.cache.store.ArrayStore())
                .defaultStore("array");
    }
}
