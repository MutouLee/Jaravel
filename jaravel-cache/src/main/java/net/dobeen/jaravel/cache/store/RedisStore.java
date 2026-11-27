package net.dobeen.jaravel.cache.store;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import net.dobeen.jaravel.cache.CacheStore;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;

/**
 * Redis 驱动(docs/09 §1.4)。基于 Lettuce —— 线程安全、异步内核、
 * Spring Boot 的默认客户端。
 *
 * <h2>连接是惰性的</h2>
 * <p>{@link #RedisStore(String)} 只创建 {@link RedisClient},**不连网络**;
 * 第一次真正读写时才 {@code connect()}。这样:
 * <ul>
 *   <li>配置里有 redis 但从没用过的应用启动时不会因为 Redis 没起而失败;</li>
 *   <li>Spring 的 bean 创建顺序不会把「连 Redis」变成启动流程的一部分。</li>
 * </ul>
 *
 * <h2>★ 数字计数器存纯数字,其余值存 JSON 信封</h2>
 * <p>file / database 驱动把每个值都写成
 * <code>{"type":"java.lang.Long","value":1}</code> 形式的 JSON,
 * 于是 {@code increment} 只能读-改-写。Redis 有原子的 {@code INCRBY},
 * 但它不认识 JSON 信封 —— 所以 {@link #encode} 对整数类型写纯数字,
 * {@link #decode} 读纯数字时还原成 {@link Long}。其余类型仍走
 * {@link FileStore.JsonBridge},跨驱动语义一致。
 *
 * <p>代价:一个由别的客户端写进去的 JSON 字符串(不是信封)会被当作
 * 未命中;而一个非数字值上的 {@code increment} 会得到一条说清原因的
 * {@link IllegalStateException},而不是 Lettuce 的原始报错。
 * 非数字值仍然需要 Jackson —— 与 file / database 是同一套信封。
 *
 * <h2>前缀</h2>
 * <p>{@code prefix} 加在每个键前面。应用通常留空(Repository 已经有
 * {@code cache.prefix});它主要给测试与多租户隔离用。
 * {@link #flush()} 在空前缀下是 {@code FLUSHDB} —— **会清空整个 Redis
 * 库**,与其它驱动的「清空当前 store」语义一致。带前缀时只删前缀下的键。
 * 多个应用共用一个库时不要把 {@code flush()} 当清理入口 —— 用不同的
 * 库,或只删自己的前缀。
 */
public class RedisStore implements CacheStore, AutoCloseable {

    private final String uri;
    private final String prefix;

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> sync;

    /** 惰性连接:多个请求线程可能同时第一次访问 */
    private final ReentrantLock connectLock = new ReentrantLock();

    private volatile boolean closed;

    /** 自己管理连接的生命周期;第一次读写时才连 */
    public RedisStore(String uri) {
        this(uri, "");
    }

    public RedisStore(String uri, String prefix) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Redis URI 不能为空,例如 redis://localhost:6379/0");
        }
        this.uri = uri;
        this.prefix = prefix == null ? "" : prefix;
    }

    /**
     * 复用外部连接(测试或调用方自己管理生命周期)。
     *
     * <p>这种构造下 {@link #close()} **不会**关掉连接 ——
     * 谁创建谁关闭。
     */
    public RedisStore(RedisCommands<String, String> commands, String prefix) {
        this.uri = null;
        this.prefix = prefix == null ? "" : prefix;
        this.sync = commands;
    }

    @Override public String name() { return "redis"; }

    @Override public boolean isShared() { return true; }

    /**
     * 逃生口:直接用 Lettuce 的原生命令。
     *
     * <p>⚠ 键的前缀要自己加(Repository 的 {@code cache.prefix} 与
     * 本 store 的 prefix 不在这层自动应用)。
     */
    public RedisCommands<String, String> raw() {
        return commands();
    }

    // ================= 连接 =================

    private RedisCommands<String, String> commands() {
        RedisCommands<String, String> current = sync;
        if (current != null) return current;

        connectLock.lock();
        try {
            if (sync == null) {
                if (closed) throw new IllegalStateException("RedisStore 已关闭");
                try {
                    client = RedisClient.create(uri);
                    connection = client.connect(StringCodec.UTF8);
                    sync = connection.sync();
                } catch (RuntimeException e) {
                    closeQuietly();
                    throw new IllegalStateException("""
                            连不上 Redis(检查 cache.stores.redis.uri)。
                              %s
                              本地开发可以用 docker run -d -p 6379:6379 redis:7-alpine""".formatted(
                            e.getMessage()), e);
                }
            }
            return sync;
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (connection != null) connection.close();
        } catch (RuntimeException ignored) {
            // 关闭路径上不再抛 —— 已经在关连接了
        }
        try {
            if (client != null) client.shutdown();
        } catch (RuntimeException ignored) {
            // 同上
        }
        connection = null;
        client = null;
        sync = null;
    }

    private String key(String key) {
        return prefix + key;
    }

    private String unkey(String key) {
        return prefix.isEmpty() || !key.startsWith(prefix)
                ? key
                : key.substring(prefix.length());
    }

    // ================= 读 =================

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(decode(commands().get(key(key))));
    }

    @Override
    public Map<String, Object> many(List<String> keys) {
        if (keys.isEmpty()) return Map.of();

        String[] full = keys.stream().map(this::key).toArray(String[]::new);
        var out = new LinkedHashMap<String, Object>();
        for (KeyValue<String, String> value : commands().mget(full)) {
            if (value.hasValue()) out.put(unkey(value.getKey()), decode(value.getValue()));
        }
        return out;
    }

    @Override
    public Optional<Duration> ttl(String key) {
        long pttl = commands().pttl(key(key));
        // -2 不存在,-1 永久;0 表示已经到了过期边界 —— 当作不存在
        if (pttl == -2 || pttl == 0) return Optional.empty();
        if (pttl == -1) return Optional.of(Duration.ZERO);
        return pttl > 0
                ? Optional.of(Duration.ofMillis(pttl))
                : Optional.empty();
    }

    // ================= 写 =================

    @Override
    public void put(String key, Object value, Duration ttl) {
        String k = key(key);
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            commands().del(k);
            return;
        }

        String encoded = encode(value);
        if (ttl == null) {
            commands().set(k, encoded);
        } else {
            commands().psetex(k, Math.max(1, ttl.toMillis()), encoded);
        }
    }

    @Override
    public boolean add(String key, Object value, Duration ttl) {
        String k = key(key);
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            commands().del(k);
            return false;
        }

        SetArgs args = SetArgs.Builder.nx();
        if (ttl != null) args.px(Math.max(1, ttl.toMillis()));

        return "OK".equals(commands().set(k, encode(value), args));
    }

    @Override
    public long increment(String key, long delta) {
        String k = key(key);
        try {
            return commands().incrby(k, delta);
        } catch (RedisCommandExecutionException e) {
            throw new IllegalStateException("""
                    Redis 里 "%s" 不是整数,无法 increment。
                      redis 驱动的计数器必须是整数(put 一个 Long / Integer);
                      不要把字符串或 JSON 对象放在计数器的键上。
                      原始错误:%s""".formatted(unkey(k), e.getMessage()), e);
        }
    }

    @Override
    public void forget(String key) {
        commands().del(key(key));
    }

    @Override
    public void flush() {
        if (prefix.isEmpty()) {
            commands().flushdb();
            return;
        }

        // 只删这个前缀下的键 —— 共享 Redis 时 FLUSHDB 会清掉别人的数据
        var commands = commands();
        var args = ScanArgs.Builder.matches(prefix + "*").limit(500);
        var cursor = commands.scan(args);
        var keys = new ArrayList<String>(cursor.getKeys());
        while (!cursor.isFinished()) {
            cursor = commands.scan(cursor, args);
            keys.addAll(cursor.getKeys());
        }
        if (!keys.isEmpty()) commands.del(keys.toArray(String[]::new));
    }

    // ================= 序列化 =================

    static String encode(Object value) {
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            return value.toString();
        }
        return FileStore.JsonBridge.write(value);
    }

    static Object decode(String raw) {
        if (raw == null) return null;

        String text = raw.trim();
        if (text.matches("-?\\d+")) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                // 超出 long 范围 —— 交给下面的信封解析
            }
        }
        if (text.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                // 同上
            }
        }
        return FileStore.JsonBridge.read(raw);
    }
}
