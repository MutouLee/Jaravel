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
    private final Map<String, CacheStore> resolvedStores = new ConcurrentHashMap<>();
    private final Map<String, Repository> resolved = new ConcurrentHashMap<>();

    private String defaultStore = "array";
    private String prefix = "";

    public CacheManager register(String name, Function<CacheManager, CacheStore> factory) {
        factories.put(name, factory);
        resolvedStores.remove(name);     // 重新注册时丢弃旧实例
        resolved.remove(name);
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
        return resolved.computeIfAbsent(name, n -> new Repository(storeRaw(n), prefix));
    }

    /**
     * 取**原始** store,不套 Repository。
     *
     * <p>给「组合型驱动」用:{@code tiered} 需要 L1 / L2 两个
     * {@link CacheStore},而不是两个 Repository(它会自己决定回填与
     * 失效传播)。同一个名字只创建一次 —— 与 Repository 共用实例,
     * 否则 tiered 的 L1 会和直接用的 caffeine 是两个缓存。
     *
     * <p><b>synchronized 而不是 computeIfAbsent</b>:tiered 的工厂会在
     * 解析过程中回调 {@code storeRaw(l1)} —— ConcurrentHashMap 的
     * 映射函数里递归更新同一张表是未定义行为(JDK 会抛
     * "Recursive update")。synchronized 可重入,递归解析安全;
     * 而且只在第一次取用时发生,不热。
     */
    public synchronized CacheStore storeRaw(String name) {
        CacheStore existing = resolvedStores.get(name);
        if (existing != null) return existing;

        var factory = factories.get(name);
        if (factory == null) throw unknownStore(name);

        CacheStore created = factory.apply(this);
        resolvedStores.put(name, created);
        return created;
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
        resolvedStores.clear();
        fake = null;
        previousDefaultStore = null;
    }

    // ================= 测试(docs/14 §4) =================

    /** fake 之前的默认 store 名,{@link #stopFaking} 用它还原 */
    private String previousDefaultStore;

    private CacheFake fake;

    /**
     * 把默认 store 换成一个记录型的空缓存。
     *
     * <p>边界(注入了 {@code Repository} 的代码不受影响)写在
     * {@link CacheFake} 的类注释里。
     */
    public CacheFake fake() {
        var recorder = new CacheFake(new io.jaravel.cache.store.ArrayStore(), prefix);
        if (previousDefaultStore == null) previousDefaultStore = defaultStore;

        // register 会丢弃这个名字下已解析的 store 与 Repository;
        // defaultStore 改名后 store() 解析到的就是 fake
        register(CacheFake.STORE_NAME, recorder);
        this.defaultStore = CacheFake.STORE_NAME;
        this.fake = recorder;
        return recorder;
    }

    /** 还原 fake 之前的默认 store */
    public CacheManager stopFaking() {
        if (previousDefaultStore != null) {
            this.defaultStore = previousDefaultStore;
            this.previousDefaultStore = null;
        }
        this.fake = null;
        return this;
    }

    public boolean isFaking() {
        return fake != null;
    }

    public CacheFake recorder() {
        if (fake == null) {
            throw new IllegalStateException("""
                    当前不在 fake 模式,没有记录可查。
                      在被测代码**之前**调 Cache.fake()。""");
        }
        return fake;
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
