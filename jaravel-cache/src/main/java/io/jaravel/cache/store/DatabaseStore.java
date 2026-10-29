package io.jaravel.cache.store;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.cache.CacheStore;
import io.jaravel.database.Connection;

/**
 * 数据库驱动:一张 {@code cache} 表。
 *
 * <p>适合「没有 Redis 的小项目」—— 多实例共享,且不引入新组件。
 * 代价是每次读写一条 SQL,比 Redis 慢一个量级。
 *
 * <pre>{@code
 * create table cache (
 *   key        varchar(255) primary key,
 *   value      text    not null,
 *   expires_at bigint  not null    -- 0 = 永久
 * )
 * }</pre>
 */
public class DatabaseStore implements CacheStore {

    private final Connection connection;
    private final String table;

    public DatabaseStore(Connection connection) {
        this(connection, "cache");
    }

    public DatabaseStore(Connection connection, String table) {
        this.connection = connection;
        this.table = table;
    }

    @Override public String name() { return "database"; }

    @Override public boolean isShared() { return true; }

    @Override
    public Optional<Object> get(String key) {
        var row = connection.table(table).where("key", key).get().first();
        if (row.isEmpty()) return Optional.empty();

        long expiresAt = asLong(row.get().get("expires_at"));
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
            // 惰性清理。不在读路径上发 DELETE 会让过期行越积越多,
            // 而积多了之后 flush 与 count 都会变慢
            forget(key);
            return Optional.empty();
        }
        return Optional.ofNullable(FileStore.JsonBridge.read(
                String.valueOf(row.get().get("value"))));
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        long expiresAt = ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis();
        String json = FileStore.JsonBridge.write(value);

        // 先删再插,而不是 upsert:upsert 的语法三个方言各不相同
        // (ON CONFLICT / ON DUPLICATE KEY / MERGE),而缓存写入不值得
        // 为此在 Grammar 里再开一个分支。两条语句在同一连接上足够快。
        connection.transaction(() -> {
            connection.table(table).where("key", key).delete();
            connection.table(table).insert(Map.of(
                    "key", key, "value", json, "expires_at", expiresAt));
        });
    }

    /**
     * 原子 put-if-absent —— 靠主键冲突。
     *
     * <p>不是「先 select 再 insert」:那在并发下两个进程会同时 select 到空。
     * 这里直接 insert,让数据库的唯一约束来裁决,捕获冲突即表示没抢到。
     */
    @Override
    public boolean add(String key, Object value, Duration ttl) {
        // 先清掉已过期的同名行,否则过期的锁会永远挡住新的获取
        var existing = connection.table(table).where("key", key).get().first();
        if (existing.isPresent()) {
            long expiresAt = asLong(existing.get().get("expires_at"));
            if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
                connection.table(table).where("key", key).delete();
            } else {
                return false;
            }
        }

        long expiresAt = ttl == null ? 0 : System.currentTimeMillis() + ttl.toMillis();
        try {
            connection.table(table).insert(Map.of(
                    "key", key,
                    "value", FileStore.JsonBridge.write(value),
                    "expires_at", expiresAt));
            return true;
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) return false;
            throw e;
        }
    }

    /**
     * 判断异常是否为主键/唯一约束冲突。
     *
     * <p>按消息文本匹配很脆弱,但 JDBC 的 SQLState 在各驱动上同样不统一
     * (SQLite 给 {@code SQLITE_CONSTRAINT_PRIMARYKEY},MySQL 给 1062,
     * PG 给 23505)。这里两条都看:先看 SQLState 的 23 类,再退回文本。
     */
    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("23")) return true;
            }
            String message = t.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase();
            if (lower.contains("unique") || lower.contains("duplicate")
                    || lower.contains("primary key") || lower.contains("constraint")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void forget(String key) {
        connection.table(table).where("key", key).delete();
    }

    @Override
    public void flush() {
        connection.table(table).truncate();
    }

    @Override
    public long increment(String key, long delta) {
        // 在事务里读-改-写。不用 SQL 的 `value = value + ?`:value 列存的是
        // JSON 信封(带类型信息),数据库层面没法直接加。
        return connection.transaction(() -> {
            long base = get(key).map(v -> v instanceof Number n ? n.longValue()
                                        : Long.parseLong(String.valueOf(v)))
                                .orElse(0L);
            long updated = base + delta;

            Long remaining = remainingMillis(key);
            put(key, updated, remaining == null ? null : Duration.ofMillis(remaining));
            return updated;
        });
    }

    private Long remainingMillis(String key) {
        var row = connection.table(table).where("key", key).get().first();
        if (row.isEmpty()) return null;
        long expiresAt = asLong(row.get().get("expires_at"));
        if (expiresAt <= 0) return null;
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining > 0 ? remaining : 1L;
    }

    @Override
    public Map<String, Object> many(List<String> keys) {
        if (keys.isEmpty()) return Map.of();

        // 一条 IN 查询而不是 N 条 —— database 驱动最大的成本是往返次数
        var rows = connection.table(table).whereIn("key", keys).get();
        var out = new LinkedHashMap<String, Object>();
        long now = System.currentTimeMillis();

        for (var row : rows) {
            long expiresAt = asLong(row.get("expires_at"));
            if (expiresAt > 0 && now >= expiresAt) continue;
            out.put(String.valueOf(row.get("key")),
                    FileStore.JsonBridge.read(String.valueOf(row.get("value"))));
        }
        return out;
    }

    @Override
    public void forgetMany(List<String> keys) {
        if (keys.isEmpty()) return;
        connection.table(table).whereIn("key", keys).delete();
    }

    /** 删掉所有已过期的行。供定时清理任务调用。 */
    public int purgeExpired() {
        return connection.table(table)
                .where("expires_at", io.jaravel.database.query.QueryState.Op.GT, 0)
                .where("expires_at", io.jaravel.database.query.QueryState.Op.LTE,
                       System.currentTimeMillis())
                .delete();
    }

    private static long asLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
