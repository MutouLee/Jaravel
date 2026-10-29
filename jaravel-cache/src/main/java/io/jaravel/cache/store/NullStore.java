package io.jaravel.cache.store;

import java.time.Duration;
import java.util.Optional;

import io.jaravel.cache.CacheStore;

/**
 * 黑洞:写入即丢弃,读取永远为空。
 *
 * <p>用于「临时关掉缓存」而不改一行业务代码。
 *
 * <p>注意 {@link #add} 永远返回 {@code true} —— 于是基于它的锁**永远拿得到**,
 * 等于没有互斥。这是 null 驱动的必然结果(它不记住任何东西),
 * 所以在禁用缓存时不要同时依赖 Cache.lock 做互斥。
 */
public class NullStore implements CacheStore {

    @Override public String name() { return "null"; }

    @Override public Optional<Object> get(String key) { return Optional.empty(); }

    @Override public void put(String key, Object value, Duration ttl) { }

    @Override public boolean add(String key, Object value, Duration ttl) { return true; }

    @Override public void forget(String key) { }

    @Override public void flush() { }

    @Override public long increment(String key, long delta) { return delta; }
}
