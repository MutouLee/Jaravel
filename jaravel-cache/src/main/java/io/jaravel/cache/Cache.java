package io.jaravel.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 缓存的静态门面(docs/09 §1.1、docs/02 §2.3)。
 *
 * <pre>{@code
 * Optional<User> u = Cache.get("user:1", User.class);
 * Cache.put("user:1", user, Duration.ofMinutes(10));
 * User cached = Cache.remember("user:1", Duration.ofMinutes(10), () -> load(1));
 * Cache.store("redis").get("user:1", User.class);
 * Cache.tags("users").flush();
 * Cache.lock("report", Duration.ofMinutes(5)).get(() -> generateReport());
 * }</pre>
 *
 * <h2>★ 为什么是手写转发,而不是动态代理</h2>
 * <p>与 {@link io.jaravel.storage.Storage} / {@code Auth} / {@code Queue}
 * 同一套写法:薄转发类,IDE 能跳转、能重构,native-image 不需要任何
 * 反射配置。理由与取舍写在 docs/02 §2.2。
 *
 * <h2>不想用门面的话</h2>
 * <p>{@link Repository} 是一个普通 bean({@code jaravelCache}),
 * 构造注入它即可 —— 门面是便利,不是唯一入口。库代码(会被别人的
 * 应用依赖的那种)应当注入,因为门面是 JVM 全局状态。
 */
public final class Cache {

    private static volatile CacheManager manager;

    private Cache() {
    }

    public static void use(CacheManager configured) {
        manager = configured;
    }

    public static CacheManager manager() {
        CacheManager current = manager;
        if (current == null) {
            throw new IllegalStateException("""
                    缓存尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调
                        Cache.use(CacheManager.inMemory());""");
        }
        return current;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        manager = null;
    }

    /** 默认 store */
    public static Repository store() {
        return manager().store();
    }

    /** 指定 store */
    public static Repository store(String name) {
        return manager().store(name);
    }

    // ================= 读 =================

    public static Optional<Object> get(String key) {
        return store().get(key);
    }

    public static <T> Optional<T> get(String key, Class<T> type) {
        return store().get(key, type);
    }

    public static <T> T get(String key, Class<T> type, T fallback) {
        return store().get(key, type, fallback);
    }

    public static boolean has(String key) {
        return store().has(key);
    }

    public static boolean missing(String key) {
        return store().missing(key);
    }

    public static <T> Map<String, T> many(List<String> keys, Class<T> type) {
        return store().many(keys, type);
    }

    // ================= 写 =================

    public static void put(String key, Object value, Duration ttl) {
        store().put(key, value, ttl);
    }

    /** 永久(直到被挤出或显式删除) */
    public static void put(String key, Object value) {
        store().put(key, value);
    }

    public static void forever(String key, Object value) {
        store().forever(key, value);
    }

    public static void putMany(Map<String, ?> values, Duration ttl) {
        store().putMany(values, ttl);
    }

    /** 仅当不存在时写入(原子) */
    public static boolean add(String key, Object value, Duration ttl) {
        return store().add(key, value, ttl);
    }

    // ================= remember =================

    public static <T> T remember(String key, Duration ttl, Supplier<T> callback) {
        return store().remember(key, ttl, callback);
    }

    public static <T> T remember(String key, Duration ttl, Class<T> type, Supplier<T> callback) {
        return store().remember(key, ttl, type, callback);
    }

    public static <T> T rememberForever(String key, Supplier<T> callback) {
        return store().rememberForever(key, callback);
    }

    // ================= 删 =================

    public static void forget(String key) {
        store().forget(key);
    }

    public static void deleteMultiple(List<String> keys) {
        store().deleteMultiple(keys);
    }

    public static void flush() {
        store().flush();
    }

    public static <T> Optional<T> pull(String key, Class<T> type) {
        return store().pull(key, type);
    }

    public static Optional<Object> pull(String key) {
        return store().pull(key);
    }

    // ================= 计数 =================

    public static long increment(String key) {
        return store().increment(key);
    }

    public static long increment(String key, long delta) {
        return store().increment(key, delta);
    }

    public static long decrement(String key) {
        return store().decrement(key);
    }

    public static long decrement(String key, long delta) {
        return store().decrement(key, delta);
    }

    // ================= 标签 / 锁 =================

    public static TaggedCache tags(String... tags) {
        return store().tags(tags);
    }

    public static Lock lock(String name, Duration ttl) {
        return store().lock(name, ttl);
    }

    /** 用已知的 owner 重建锁句柄,用于跨进程/跨任务释放 */
    public static Lock restoreLock(String name, String owner) {
        return store().restoreLock(name, owner);
    }

    // ================= 测试(docs/14 §4) =================

    /**
     * 把默认 store 换成一个记录型的空缓存,并返回它。
     *
     * <pre>{@code
     * var cache = Cache.fake();
     * service.dashboard(7L);
     * cache.assertMissed("dashboard:7");
     * cache.assertPut("dashboard:7");
     * }</pre>
     *
     * <p>边界(注入了 {@link Repository} 的代码持有旧实例、不受影响)
     * 写在 {@link CacheFake} 的类注释里。
     */
    public static CacheFake fake() {
        return manager().fake();
    }

    /** 还原 fake 之前的默认 store */
    public static void stopFaking() {
        manager().stopFaking();
    }

    public static boolean isFaking() {
        return manager().isFaking();
    }

    public static CacheFake recorder() {
        return manager().recorder();
    }
}
