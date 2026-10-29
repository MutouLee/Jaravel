package io.jaravel.cache.store;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.cache.CacheStore;

/**
 * 进程内 HashMap,带过期判定。测试与 {@code array} 驱动用它。
 *
 * <p>**也是其它驱动的正确性基准**:所有驱动必须对同一组测试给出相同行为,
 * 而这个实现足够简单到可以认定它就是对的。
 */
public class ArrayStore implements CacheStore {

    private record Entry(Object value, long expiresAtMillis) {
        boolean isExpired(long now) {
            return expiresAtMillis > 0 && now >= expiresAtMillis;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override public String name() { return "array"; }

    @Override
    public Optional<Object> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (entry.isExpired(now())) {
            // 惰性清理:读到过期条目时顺手删掉,省掉一个后台线程
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value());
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        entries.put(key, new Entry(value, expiryOf(ttl)));
    }

    /**
     * 原子 put-if-absent。
     *
     * <p>不能写成「先 get 再 put」—— {@link io.jaravel.cache.Lock} 的互斥性
     * 完全依赖这里的原子性。{@code compute} 在 ConcurrentHashMap 上对同一个
     * 键是串行的,这是本方法成立的依据。
     */
    @Override
    public boolean add(String key, Object value, Duration ttl) {
        long now = now();
        var inserted = new boolean[1];

        entries.compute(key, (k, existing) -> {
            if (existing != null && !existing.isExpired(now)) return existing;
            inserted[0] = true;
            return new Entry(value, expiryOf(ttl));
        });
        return inserted[0];
    }

    @Override
    public Optional<Duration> ttl(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (entry.expiresAtMillis() == 0) return Optional.of(Duration.ZERO);
        long remaining = entry.expiresAtMillis() - now();
        return remaining > 0 ? Optional.of(Duration.ofMillis(remaining)) : Optional.empty();
    }

    @Override
    public void forget(String key) {
        entries.remove(key);
    }

    @Override
    public void flush() {
        entries.clear();
    }

    @Override
    public long increment(String key, long delta) {
        long now = now();
        var result = new long[1];

        entries.compute(key, (k, existing) -> {
            long base = 0;
            long expiry = 0;
            if (existing != null && !existing.isExpired(now)) {
                base = toLong(key, existing.value());
                // 自增**保留原有的过期时间**:限流计数器的窗口不能被每次计数续命,
                // 否则持续的请求会让窗口永不结束,限流也就永不重置。
                expiry = existing.expiresAtMillis();
            }
            result[0] = base + delta;
            return new Entry(result[0], expiry);
        });
        return result[0];
    }

    private static long toLong(String key, Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "缓存键 \"" + key + "\" 里存的是 " + value
                  + ",不能做 increment/decrement。"
                  + "\n  计数器键不要与普通值共用同一个名字。", e);
        }
    }

    private static long expiryOf(Duration ttl) {
        return ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** 诊断用:当前条目数(含尚未惰性清理的过期条目) */
    public int size() {
        return entries.size();
    }
}
