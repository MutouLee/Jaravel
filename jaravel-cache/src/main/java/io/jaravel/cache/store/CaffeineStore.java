package io.jaravel.cache.store;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import io.jaravel.cache.CacheStore;

/**
 * Caffeine 驱动:进程内,带容量上限与 LRU 淘汰。开发期默认。
 *
 * <h2>为什么要自定义 Expiry 而不是 expireAfterWrite</h2>
 * <p>{@code expireAfterWrite} 是**整个 cache 一个 TTL**,而缓存 API 的语义是
 * 每个条目各自的 TTL({@code put(k, v, Duration.ofMinutes(10))})。用全局 TTL
 * 近似会让「永久条目」被过早淘汰、或「短期条目」活得过久 —— 都是静默的行为偏差。
 *
 * <h2>条目存「绝对过期时刻」而不是「剩余时长」</h2>
 * <p>这一点踩过坑:最初 {@code Entry} 存的是 TTL 时长,于是
 * {@code expireAfterUpdate} 返回它时**把计时从头重算**了。后果是
 * {@code increment} 每次都给计数器续命 —— 限流窗口永不结束,
 * 被限流的用户永久封锁。契约测试里的
 * 「increment 不给计数器续命」正是为此而写。
 *
 * <p>存绝对时刻后,三个 Expiry 回调都只是「算还剩多久」,
 * 更新操作天然保留原过期时刻。
 */
public class CaffeineStore implements CacheStore {

    /** @param expiresAtMillis 0 = 永久 */
    private record Entry(Object value, long expiresAtMillis) {}

    private final Cache<String, Entry> cache;

    public CaffeineStore(long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new Expiry<String, Entry>() {
                    @Override public long expireAfterCreate(String k, Entry e, long now) {
                        return remainingNanos(e);
                    }
                    @Override public long expireAfterUpdate(String k, Entry e, long now,
                                                            long currentDuration) {
                        return remainingNanos(e);
                    }
                    @Override public long expireAfterRead(String k, Entry e, long now,
                                                          long currentDuration) {
                        // 读不续命 —— TTL 的语义是「写入后多久过期」
                        return currentDuration;
                    }
                })
                .build();
    }

    @Override public String name() { return "caffeine"; }

    private static long remainingNanos(Entry e) {
        if (e.expiresAtMillis() == 0) return Long.MAX_VALUE;
        long remaining = e.expiresAtMillis() - System.currentTimeMillis();
        // 已过期的给 1ns,让 Caffeine 立刻淘汰而不是当成永久
        return remaining > 0 ? TimeUnit.MILLISECONDS.toNanos(remaining) : 1L;
    }

    @Override
    public Optional<Object> get(String key) {
        Entry entry = cache.getIfPresent(key);
        if (entry == null) return Optional.empty();
        // Caffeine 的淘汰是惰性的,自己再判一次,避免读到刚过期的值
        if (entry.expiresAtMillis() > 0
                && System.currentTimeMillis() >= entry.expiresAtMillis()) {
            cache.invalidate(key);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value());
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        cache.put(key, new Entry(value, expiryOf(ttl)));
    }

    /** {@code asMap().compute} 对同一键是原子的 —— 锁的互斥靠它 */
    @Override
    public boolean add(String key, Object value, Duration ttl) {
        long now = System.currentTimeMillis();
        var inserted = new boolean[1];

        cache.asMap().compute(key, (k, existing) -> {
            if (existing != null
                    && (existing.expiresAtMillis() == 0 || now < existing.expiresAtMillis())) {
                return existing;
            }
            inserted[0] = true;
            return new Entry(value, expiryOf(ttl));
        });
        return inserted[0];
    }

    @Override
    public Optional<Duration> ttl(String key) {
        Entry entry = cache.getIfPresent(key);
        if (entry == null) return Optional.empty();
        if (entry.expiresAtMillis() == 0) return Optional.of(Duration.ZERO);
        long remaining = entry.expiresAtMillis() - System.currentTimeMillis();
        return remaining > 0 ? Optional.of(Duration.ofMillis(remaining)) : Optional.empty();
    }

    @Override
    public void forget(String key) {
        cache.invalidate(key);
    }

    @Override
    public void flush() {
        cache.invalidateAll();
    }

    @Override
    public long increment(String key, long delta) {
        long now = System.currentTimeMillis();
        var result = new long[1];

        cache.asMap().compute(key, (k, existing) -> {
            boolean alive = existing != null
                    && (existing.expiresAtMillis() == 0 || now < existing.expiresAtMillis());
            long base = alive ? toLong(key, existing.value()) : 0;
            // 保留原**绝对**过期时刻,自增不续命
            long expiry = alive ? existing.expiresAtMillis() : 0;
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
                    "缓存键 \"" + key + "\" 里存的是 " + value + ",不能做 increment。"
                  + "\n  计数器键不要与普通值共用同一个名字。", e);
        }
    }

    private static long expiryOf(Duration ttl) {
        return ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis();
    }

    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
