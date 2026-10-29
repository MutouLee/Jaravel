package io.jaravel.session.store;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.jaravel.cache.Repository;
import io.jaravel.session.SessionStore;

/**
 * 把会话存进缓存。
 *
 * <p>**一个实现覆盖四个驱动**:缓存层已经有 redis / file / database /
 * caffeine,会话不必各写一遍。文档里列的 {@code redis} / {@code file} /
 * {@code database} 会话驱动都由它配不同的缓存 store 得到。
 *
 * <p>共享性继承自底层缓存 store —— 这样「用 caffeine 存会话」这个错误
 * 能被启动检查发现。
 */
public class CacheSessionStore implements SessionStore {

    private static final String PREFIX = "session:";

    private final Repository cache;

    public CacheSessionStore(Repository cache) {
        this.cache = cache;
    }

    @Override public String name() { return "cache(" + cache.store().name() + ")"; }

    @Override public boolean isShared() { return cache.store().isShared(); }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> read(String id) {
        return cache.get(PREFIX + id).map(value -> {
            if (value instanceof Map<?, ?> map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) map);
            }
            // 存进去的一定是 Map;不是的话说明这个键被别处用过
            throw new IllegalStateException(
                    "会话 " + id + " 在缓存里不是 Map,而是 "
                  + value.getClass().getSimpleName()
                  + "。检查是否有别的代码写了 \"" + PREFIX + "\" 前缀的键。");
        });
    }

    @Override
    public void write(String id, Map<String, Object> data, Duration lifetime) {
        cache.put(PREFIX + id, new LinkedHashMap<>(data), lifetime);
    }

    @Override
    public void destroy(String id) {
        cache.forget(PREFIX + id);
    }
}
