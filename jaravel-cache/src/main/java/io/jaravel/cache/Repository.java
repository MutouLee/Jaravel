package io.jaravel.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.jaravel.support.Cast;

/**
 * 面向用户的缓存 API。架在 {@link CacheStore} 的 8 个原语之上。
 *
 * <p>所有驱动共用这一份实现,因此 {@code remember} 的语义只有一种。
 */
public class Repository {

    private final CacheStore store;
    private final String prefix;

    public Repository(CacheStore store) {
        this(store, "");
    }

    public Repository(CacheStore store, String prefix) {
        this.store = store;
        this.prefix = prefix == null ? "" : prefix;
    }

    public CacheStore store() {
        return store;
    }

    public String prefix() {
        return prefix;
    }

    // ================= 读 =================

    public Optional<Object> get(String key) {
        return store.get(prefixed(key));
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        return store.get(prefixed(key)).map(v -> coerce(key, v, type));
    }

    public <T> T get(String key, Class<T> type, T fallback) {
        return get(key, type).orElse(fallback);
    }

    public boolean has(String key) {
        return store.get(prefixed(key)).isPresent();
    }

    public boolean missing(String key) {
        return !has(key);
    }

    public <T> Map<String, T> many(List<String> keys, Class<T> type) {
        var raw = store.many(keys.stream().map(this::prefixed).toList());
        var out = new LinkedHashMap<String, T>();
        for (String key : keys) {
            Object value = raw.get(prefixed(key));
            if (value != null) out.put(key, coerce(key, value, type));
        }
        return out;
    }

    // ================= 写 =================

    public void put(String key, Object value, Duration ttl) {
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            // 负 TTL 等于「立刻过期」。写进去再让它马上失效是无意义的往返,
            // 而且有些驱动会把负 TTL 当成永久 —— 那是最糟的误解。
            store.forget(prefixed(key));
            return;
        }
        store.put(prefixed(key), value, ttl);
    }

    public void put(String key, Object value) {
        store.put(prefixed(key), value, null);
    }

    public void forever(String key, Object value) {
        put(key, value);
    }

    public void putMany(Map<String, ?> values, Duration ttl) {
        var mapped = new LinkedHashMap<String, Object>(values.size());
        values.forEach((k, v) -> mapped.put(prefixed(k), v));
        store.putMany(mapped, ttl);
    }

    /** 原子的「仅当不存在时写入」 */
    public boolean add(String key, Object value, Duration ttl) {
        return store.add(prefixed(key), value, ttl);
    }

    // ================= remember =================

    /**
     * 读不到就算、算完写入。缓存最常用的形态。
     *
     * <p><b>不加锁</b>:并发穿透时多个线程会各算一遍,然后写入同一个值。
     * 这是刻意的 —— 加锁会把「缓存失效」变成「所有请求排队等一个计算」,
     * 而那正是缓存雪崩的放大器。真要防重复计算,用
     * {@code Cache.lock(...)} 显式表达,因为那时你应该同时想清楚
     * 「等不到锁的请求该怎么办」。
     *
     * <p><b>null 不写入</b>:{@code callback} 返回 null 时不缓存,下次仍会重算。
     * 把 null 缓存起来需要区分「缓存了 null」与「没缓存」,而 {@code get}
     * 的返回类型是 {@code Optional} —— 两者无法区分。要缓存「查无此物」
     * 这个事实,请存一个显式的空对象。
     */
    public <T> T remember(String key, Duration ttl, Supplier<T> callback) {
        @SuppressWarnings("unchecked")
        Optional<T> cached = (Optional<T>) store.get(prefixed(key));
        if (cached.isPresent()) return cached.get();

        T value = callback.get();
        if (value != null) put(key, value, ttl);
        return value;
    }

    public <T> T rememberForever(String key, Supplier<T> callback) {
        return remember(key, null, callback);
    }

    /** 带类型转换的 remember —— 从 file / database 驱动读回来的是通用结构 */
    public <T> T remember(String key, Duration ttl, Class<T> type, Supplier<T> callback) {
        Optional<T> cached = get(key, type);
        if (cached.isPresent()) return cached.get();

        T value = callback.get();
        if (value != null) put(key, value, ttl);
        return value;
    }

    // ================= 删 =================

    public void forget(String key) {
        store.forget(prefixed(key));
    }

    public void deleteMultiple(List<String> keys) {
        store.forgetMany(keys.stream().map(this::prefixed).toList());
    }

    public void flush() {
        store.flush();
    }

    /** 取出并删除 */
    public <T> Optional<T> pull(String key, Class<T> type) {
        var value = get(key, type);
        if (value.isPresent()) forget(key);
        return value;
    }

    public Optional<Object> pull(String key) {
        var value = get(key);
        if (value.isPresent()) forget(key);
        return value;
    }

    // ================= 计数器 =================

    public long increment(String key) {
        return store.increment(prefixed(key), 1);
    }

    public long increment(String key, long delta) {
        return store.increment(prefixed(key), delta);
    }

    public long decrement(String key) {
        return store.increment(prefixed(key), -1);
    }

    public long decrement(String key, long delta) {
        return store.increment(prefixed(key), -delta);
    }

    // ================= 标签 =================

    /**
     * 标签化视图:{@code Cache.tags("users").flush()} 失效该标签下的所有条目。
     *
     * <p>实现见 {@link TaggedCache} —— 用「标签版本号」而非维护成员清单。
     */
    public TaggedCache tags(String... tags) {
        if (tags.length == 0) {
            throw new IllegalArgumentException(
                    "tags() 至少要一个标签。不带标签请直接用 Cache 本身。");
        }
        return new TaggedCache(this, List.of(tags));
    }

    // ================= 锁 =================

    public Lock lock(String name, Duration ttl) {
        return new Lock(this, name, ttl, Lock.newOwner());
    }

    /** 用已知的 owner 重建锁句柄,用于跨进程/跨任务释放 */
    public Lock restoreLock(String name, String owner) {
        return new Lock(this, name, null, owner);
    }

    // ================= 工具 =================

    String prefixed(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "缓存键不能为空。空键会让所有调用点互相覆盖,"
                  + "而症状是「缓存里的值莫名变成了别处写的」。");
        }
        return prefix.isEmpty() ? key : prefix + ":" + key;
    }

    /**
     * 把驱动返回的值转成请求的类型。
     *
     * <p>进程内驱动(caffeine / array)存的是原对象,直接转型即可;
     * file / database 驱动经过序列化,回来的是 Map/List/String,
     * 需要 {@link Cast} 或 Jackson 重建。两条路径在这里统一处理,
     * 这样「换驱动后取值炸了」不会成为用户的问题。
     */
    @SuppressWarnings("unchecked")
    private <T> T coerce(String key, Object value, Class<T> type) {
        if (type.isInstance(value)) return (T) value;

        try {
            return Cast.to(value, type);
        } catch (RuntimeException primitiveFailed) {
            // 复杂类型交给 Jackson
            try {
                return Json.mapper().convertValue(value, type);
            } catch (RuntimeException jacksonFailed) {
                var e = new IllegalStateException("""
                        缓存键 "%s" 里存的是 %s,取的时候要求 %s,两者无法转换。
                          常见原因:
                            · 换过驱动 —— caffeine 存原对象,file/database 存序列化结果,
                              后者读回来是 Map,需要目标类型有可反序列化的结构;
                            · 同一个键被两处用不同类型写过。
                          修复:用 Cache.flush() 清掉旧格式,或统一该键的类型。"""
                        .formatted(key, value.getClass().getName(), type.getName()));
                e.addSuppressed(primitiveFailed);
                e.addSuppressed(jacksonFailed);
                throw e;
            }
        }
    }
}
