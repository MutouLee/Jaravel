package io.jaravel.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import io.jaravel.support.Cast;
import io.jaravel.support.TypeRef;

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
        var value = store.get(prefixed(key));
        emitHitOrMiss(key, value.isPresent());
        return value;
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        var raw = store.get(prefixed(key));
        emitHitOrMiss(key, raw.isPresent());
        return raw.map(v -> coerce(key, v, type));
    }

    /**
     * 发命中/未命中。
     *
     * <p>**key 不带前缀** —— 见 {@link io.jaravel.cache.events.CacheEvents}
     * 的类注释:前缀随环境变,带上它会让同一个指标在 dev 与 prod
     * 下名字不同。
     *
     * <p>这是整个框架最热的一条路径(每次缓存读一次),所以先问
     * 有没有人听,没人听时连 record 都不构造。
     */
    private void emitHitOrMiss(String key, boolean hit) {
        if (hit) {
            io.jaravel.events.Event.tryDispatchIfListening(
                    io.jaravel.cache.events.CacheEvents.Hit.class,
                    () -> new io.jaravel.cache.events.CacheEvents.Hit(key));
        } else {
            io.jaravel.events.Event.tryDispatchIfListening(
                    io.jaravel.cache.events.CacheEvents.Missed.class,
                    () -> new io.jaravel.cache.events.CacheEvents.Missed(key));
        }
    }

    public <T> T get(String key, Class<T> type, T fallback) {
        return get(key, type).orElse(fallback);
    }

    /**
     * 取泛型容器(docs/09 §1.5)—— {@code Class<T>} 装不下 {@code List<Post>}。
     *
     * <pre>{@code
     * List<Post> posts = Cache.get("posts", new TypeRef<List<Post>>() {})
     *                         .orElse(List.of());
     * }</pre>
     *
     * <p>只给 {@code List.class} 的后果是一个 {@code List<Map<String,Object>>},
     * 而它在第一次 {@code posts.get(0).title} 时才抛 ClassCastException ——
     * 离真正的原因(取缓存时没说元素类型)很远。
     */
    public <T> Optional<T> get(String key, TypeRef<T> type) {
        var raw = store.get(prefixed(key));
        emitHitOrMiss(key, raw.isPresent());
        return raw.map(v -> coerce(key, v, type));
    }

    public <T> T get(String key, TypeRef<T> type, T fallback) {
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
            emitForgotten(key);
            return;
        }
        store.put(prefixed(key), value, ttl);
        emitWritten(key, ttl);
    }

    public void put(String key, Object value) {
        store.put(prefixed(key), value, null);
        emitWritten(key, null);
    }

    private void emitWritten(String key, Duration ttl) {
        io.jaravel.events.Event.tryDispatchIfListening(
                io.jaravel.cache.events.CacheEvents.KeyWritten.class,
                () -> new io.jaravel.cache.events.CacheEvents.KeyWritten(key, ttl));
    }

    private void emitForgotten(String key) {
        io.jaravel.events.Event.tryDispatchIfListening(
                io.jaravel.cache.events.CacheEvents.KeyForgotten.class,
                () -> new io.jaravel.cache.events.CacheEvents.KeyForgotten(key));
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

    /** 泛型容器版的 remember(docs/09 §1.5) */
    public <T> T remember(String key, Duration ttl, TypeRef<T> type, Supplier<T> callback) {
        Optional<T> cached = get(key, type);
        if (cached.isPresent()) return cached.get();

        T value = callback.get();
        if (value != null) put(key, value, ttl);
        return value;
    }

    /**
     * 跨进程防击穿的 remember(docs/09 §1.6)。
     *
     * <pre>{@code
     * Cache.rememberLocked("home-feed", Duration.ofMinutes(5),
     *                      Duration.ofSeconds(10), () -> expensive());
     * }</pre>
     *
     * <h2>它与 {@link #remember} 的区别,以及为什么默认那个不加锁</h2>
     * <p>{@code remember} 刻意不加锁(理由写在它的注释里:加锁会把「缓存失效」
     * 变成「所有请求排队等一个计算」)。本方法是**显式**选择另一边:
     * 只有一个进程算,其余的等它算完,然后读缓存。
     *
     * <p>值得付这个代价的是「算一次很贵、而且贵到不能同时算几份」的键 ——
     * 全站首页、报表、聚合统计。普通的热键用 {@code remember} 就好。
     *
     * <h2>★ 等不到锁时**自己算**,而不是抛异常</h2>
     * <p>这是本方法唯一真正需要决策的地方。{@code Cache.lock(...).block(...)}
     * 超时会抛 {@link Lock.LockTimeoutException} —— 对一个「防重复计算」的
     * 工具那是对的。但这里是缓存:让用户的请求因为**另一个进程算得慢**而
     * 失败,比多算一份昂贵得多。
     *
     * <p>所以超时后再查一次缓存(等待期间对方很可能已经写好了),仍然没有
     * 就自己算 —— 退化成 {@code remember} 的行为,而不是失败。
     *
     * <h2>锁的 TTL 与等待时长是同一个值</h2>
     * <p>一个旋钮:{@code Duration.ofSeconds(10)} 表示「至多等 10 秒,
     * 锁也最多持有 10 秒」。计算超过这个时长时锁会过期,于是可能有第二个
     * 进程开始算 —— 代价是重复计算,不是数据错误。两者需要不同时用
     * {@link #rememberLocked(String, Duration, Duration, Duration, Supplier)}。
     */
    public <T> T rememberLocked(String key, Duration ttl, Duration lockWait,
                                Supplier<T> callback) {
        return rememberLocked(key, ttl, lockWait, lockWait, callback);
    }

    /**
     * 同上,但锁的 TTL 与等待时长分开给。
     *
     * @param lockTtl  锁最多持有多久 —— 要大于计算本身耗时
     * @param lockWait 拿不到锁的请求最多等多久
     */
    public <T> T rememberLocked(String key, Duration ttl, Duration lockTtl, Duration lockWait,
                                Supplier<T> callback) {
        @SuppressWarnings("unchecked")
        Optional<T> cached = (Optional<T>) store.get(prefixed(key));
        if (cached.isPresent()) return cached.get();

        // 锁名带前缀以外的命名空间:锁与值不能共用一个键,否则
        // 「加锁」会把缓存值挤掉(add 是 put-if-absent,而值通常已被 forget)
        Lock lock = lock("remember:" + key, lockTtl);

        try {
            return lock.block(lockWait, () -> {
                // ★ 拿到锁后**再查一遍**:等待期间很可能已经有人写好了。
                // 少了这一步,排在后面的每个请求都会各算一遍 ——
                // 锁只是让它们排队,并没有减少计算次数。
                @SuppressWarnings("unchecked")
                Optional<T> again = (Optional<T>) store.get(prefixed(key));
                if (again.isPresent()) return again.get();

                T value = callback.get();
                if (value != null) put(key, value, ttl);
                return value;
            });
        } catch (Lock.LockTimeoutException timeout) {
            @SuppressWarnings("unchecked")
            Optional<T> afterWaiting = (Optional<T>) store.get(prefixed(key));
            if (afterWaiting.isPresent()) return afterWaiting.get();

            // 见类注释:宁可重复计算,不要让请求失败
            T value = callback.get();
            if (value != null) put(key, value, ttl);
            return value;
        }
    }

    // ================= 删 =================

    public void forget(String key) {
        store.forget(prefixed(key));
        emitForgotten(key);
    }

    /**
     * 删一批。
     *
     * <p>★ 叫 {@code forgetMany} 是为了与 {@link CacheStore#forgetMany} 一致。
     * 它原来叫 {@code deleteMultiple}(PSR-16 的名字),于是同一个操作在
     * 驱动层叫 forgetMany、在门面层叫 deleteMultiple —— 读 SPI 学到一个名字、
     * 读门面学到另一个,而 PSR-16 是 PHP 的接口,在 Java 这边没有任何约束力。
     * 现在这一族是 {@code many} / {@code putMany} / {@code forget} / {@code forgetMany}。
     */
    public void forgetMany(List<String> keys) {
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
    /**
     * 泛型容器的转换 —— 一律交给 Jackson。
     *
     * <p>不像 {@link #coerce(String, Object, Class)} 那样先试
     * {@code type.isInstance(value)}:擦除之后 {@code TypeRef<List<Post>>}
     * 没法问「这个 List 的元素是不是 Post」。于是 caffeine 驱动(存的是原对象)
     * 也会走一遍 Jackson 的转换 —— 多一次往返,换来的是「caffeine 上能用的
     * 代码换到 redis 上也能用」。驱动之间行为分叉是这个模块最贵的一类 bug。
     */
    private <T> T coerce(String key, Object value, TypeRef<T> type) {
        try {
            return Json.mapper().convertValue(value,
                    Json.mapper().getTypeFactory().constructType(type.type()));
        } catch (RuntimeException jacksonFailed) {
            throw new IllegalStateException("""
                    缓存键 "%s" 里存的是 %s,取的时候要求 %s,两者无法转换。
                      常见原因:
                        · 元素类型写错了(TypeRef<List<Post>> 而里面存的是别的);
                        · 同一个键被两处用不同类型写过。
                      修复:用 Cache.flush() 清掉旧格式,或统一该键的类型。"""
                    .formatted(key, value.getClass().getName(), type),
                    jacksonFailed);
        }
    }

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
