package io.jaravel.session.store;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.session.SessionStore;

/**
 * 进程内 Map。测试与单实例开发用。
 *
 * <p>{@link #isShared()} 返回 false —— 多实例部署时框架会在启动时拒绝它,
 * 因为「刷新页面随机掉登录」这个症状极难归因。
 */
public class MemoryStore implements SessionStore {

    private record Entry(Map<String, Object> data, long expiresAtMillis) {}

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    @Override public String name() { return "memory"; }

    @Override public boolean isShared() { return false; }

    @Override
    public Optional<Map<String, Object>> read(String id) {
        Entry entry = sessions.get(id);
        if (entry == null) return Optional.empty();
        if (entry.expiresAtMillis() > 0 && System.currentTimeMillis() >= entry.expiresAtMillis()) {
            sessions.remove(id, entry);
            return Optional.empty();
        }
        // 返回副本:调用方改了不该影响存储里的那一份
        return Optional.of(new LinkedHashMap<>(entry.data()));
    }

    @Override
    public void write(String id, Map<String, Object> data, Duration lifetime) {
        long expiresAt = lifetime == null ? 0 : System.currentTimeMillis() + lifetime.toMillis();
        sessions.put(id, new Entry(new LinkedHashMap<>(data), expiresAt));
    }

    @Override
    public void destroy(String id) {
        sessions.remove(id);
    }

    @Override
    public int purgeExpired(Duration lifetime) {
        long now = System.currentTimeMillis();
        int before = sessions.size();
        sessions.values().removeIf(e -> e.expiresAtMillis() > 0 && now >= e.expiresAtMillis());
        return before - sessions.size();
    }

    public int size() {
        return sessions.size();
    }
}
