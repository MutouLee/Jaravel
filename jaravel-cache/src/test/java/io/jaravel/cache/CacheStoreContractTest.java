package io.jaravel.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.jaravel.cache.store.ArrayStore;
import io.jaravel.cache.store.CaffeineStore;
import io.jaravel.cache.store.DatabaseStore;
import io.jaravel.cache.store.FileStore;
import io.jaravel.cache.store.TieredStore;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;

/**
 * **驱动契约测试**:同一组断言跑在所有驱动上。
 *
 * <p>这样写的理由:缓存最危险的 bug 是「换驱动后行为变了」—— 本地 caffeine
 * 一切正常,线上 redis/database 上 TTL 不生效或 add 不原子。把断言写一份、
 * 驱动当参数,是唯一能挡住这类问题的办法。
 *
 * <p>{@code null} 驱动不在列表里 —— 它的契约就是「什么都不记」,与其余相反。
 */
class CacheStoreContractTest {

    // ================= 驱动工厂 =================

    private static final Path FILE_ROOT;
    private static java.sql.Connection sqlite;

    static {
        try {
            FILE_ROOT = Files.createTempDirectory("jaravel-cache-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<Arguments> stores() {
        return Stream.of(
                Arguments.of("array", (Supplier<CacheStore>) ArrayStore::new),
                Arguments.of("caffeine", (Supplier<CacheStore>) () -> new CaffeineStore(10_000)),
                Arguments.of("file", (Supplier<CacheStore>) () -> freshFileStore()),
                Arguments.of("database", (Supplier<CacheStore>) () -> freshDatabaseStore()),
                Arguments.of("tiered", (Supplier<CacheStore>) () -> new TieredStore(
                        new ArrayStore(), new ArrayStore(), Duration.ofSeconds(10))));
    }

    private static CacheStore freshFileStore() {
        try {
            var dir = Files.createTempDirectory(FILE_ROOT, "store");
            return new FileStore(dir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CacheStore freshDatabaseStore() {
        try {
            if (sqlite == null || sqlite.isClosed()) {
                sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
            }
            String table = "cache_" + Math.abs(java.util.UUID.randomUUID()
                    .toString().hashCode());
            try (Statement st = sqlite.createStatement()) {
                st.execute("create table " + table + " ("
                         + "\"key\" varchar(255) primary key, "
                         + "value text not null, "
                         + "expires_at bigint not null)");
            }
            var connection = new JdbcConnection(nonClosing(sqlite), new SqliteGrammar());
            return new DatabaseStore(connection, table);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (sqlite != null && !sqlite.isClosed()) sqlite.close();
        try (var walk = Files.walk(FILE_ROOT)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // 临时目录,删不掉就算了
                }
            });
        }
    }

    // ================= 契约 =================

    @ParameterizedTest(name = "[{0}] 写入后能读回")
    @MethodSource("stores")
    void putThenGet(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.put("k", "v");
        assertThat(cache.get("k", String.class)).contains("v");
        assertThat(cache.has("k")).isTrue();
    }

    @ParameterizedTest(name = "[{0}] 未写入的键读不到")
    @MethodSource("stores")
    void missingKey(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        assertThat(cache.get("nope", String.class)).isEmpty();
        assertThat(cache.has("nope")).isFalse();
        assertThat(cache.missing("nope")).isTrue();
    }

    @ParameterizedTest(name = "[{0}] TTL 到了就读不到")
    @MethodSource("stores")
    void ttlExpires(String name, Supplier<CacheStore> factory) throws InterruptedException {
        var cache = new Repository(factory.get());

        cache.put("k", "v", Duration.ofMillis(60));
        assertThat(cache.get("k", String.class)).contains("v");

        Thread.sleep(120);
        assertThat(cache.get("k", String.class)).isEmpty();
    }

    @ParameterizedTest(name = "[{0}] 永久条目不过期")
    @MethodSource("stores")
    void foreverDoesNotExpire(String name, Supplier<CacheStore> factory)
            throws InterruptedException {
        var cache = new Repository(factory.get());

        cache.forever("k", "v");
        Thread.sleep(80);
        assertThat(cache.get("k", String.class)).contains("v");
    }

    @ParameterizedTest(name = "[{0}] 负 TTL 等于删除,而不是永久")
    @MethodSource("stores")
    void negativeTtlDeletes(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.put("k", "old");
        cache.put("k", "new", Duration.ofSeconds(-1));

        // 若驱动把负 TTL 当永久,这里会读到 "new" 并永不过期 —— 最糟的结果
        assertThat(cache.get("k", String.class)).isEmpty();
    }

    @ParameterizedTest(name = "[{0}] forget 删单个,flush 清全部")
    @MethodSource("stores")
    void forgetAndFlush(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.put("a", 1);
        cache.put("b", 2);
        cache.forget("a");

        assertThat(cache.has("a")).isFalse();
        assertThat(cache.has("b")).isTrue();

        cache.flush();
        assertThat(cache.has("b")).isFalse();
    }

    @ParameterizedTest(name = "[{0}] add 只在键不存在时写入")
    @MethodSource("stores")
    void addIsPutIfAbsent(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        assertThat(cache.add("k", "first", Duration.ofMinutes(1))).isTrue();
        assertThat(cache.add("k", "second", Duration.ofMinutes(1))).isFalse();
        assertThat(cache.get("k", String.class)).contains("first");
    }

    @ParameterizedTest(name = "[{0}] add 对已过期的键视同不存在")
    @MethodSource("stores")
    void addAfterExpiry(String name, Supplier<CacheStore> factory) throws InterruptedException {
        var cache = new Repository(factory.get());

        assertThat(cache.add("k", "first", Duration.ofMillis(60))).isTrue();
        Thread.sleep(120);

        // 过期的锁必须能被重新获取,否则一次超时就永久死锁
        assertThat(cache.add("k", "second", Duration.ofMinutes(1))).isTrue();
        assertThat(cache.get("k", String.class)).contains("second");
    }

    @ParameterizedTest(name = "[{0}] increment / decrement")
    @MethodSource("stores")
    void counters(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        assertThat(cache.increment("n")).isEqualTo(1);
        assertThat(cache.increment("n")).isEqualTo(2);
        assertThat(cache.increment("n", 5)).isEqualTo(7);
        assertThat(cache.decrement("n", 3)).isEqualTo(4);
        assertThat(cache.decrement("n")).isEqualTo(3);
    }

    @ParameterizedTest(name = "[{0}] increment 不给计数器续命")
    @MethodSource("stores")
    void incrementDoesNotExtendTtl(String name, Supplier<CacheStore> factory)
            throws InterruptedException {
        var cache = new Repository(factory.get());

        cache.put("n", 1L, Duration.ofMillis(120));
        Thread.sleep(60);
        cache.increment("n");          // 若这里续命,窗口将永不结束
        Thread.sleep(100);

        // 总共 160ms > 120ms TTL,应该已过期
        assertThat(cache.get("n", Long.class)).isEmpty();
    }

    @ParameterizedTest(name = "[{0}] remember:未命中则计算并写入")
    @MethodSource("stores")
    void rememberComputesOnce(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());
        var calls = new int[1];

        Supplier<String> compute = () -> {
            calls[0]++;
            return "computed";
        };

        assertThat(cache.remember("k", Duration.ofMinutes(1), String.class, compute))
                .isEqualTo("computed");
        assertThat(cache.remember("k", Duration.ofMinutes(1), String.class, compute))
                .isEqualTo("computed");
        assertThat(calls[0]).isEqualTo(1);
    }

    @ParameterizedTest(name = "[{0}] remember 不缓存 null")
    @MethodSource("stores")
    void rememberDoesNotCacheNull(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());
        var calls = new int[1];

        Supplier<String> computeNull = () -> {
            calls[0]++;
            return null;
        };

        assertThat(cache.remember("k", Duration.ofMinutes(1), String.class, computeNull)).isNull();
        assertThat(cache.remember("k", Duration.ofMinutes(1), String.class, computeNull)).isNull();
        // 缓存了 null 的话第二次就不会再算
        assertThat(calls[0]).isEqualTo(2);
    }

    @ParameterizedTest(name = "[{0}] pull 取出并删除")
    @MethodSource("stores")
    void pullRemoves(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.put("k", "v");
        assertThat(cache.pull("k", String.class)).contains("v");
        assertThat(cache.has("k")).isFalse();
    }

    @ParameterizedTest(name = "[{0}] many / putMany")
    @MethodSource("stores")
    void batchOperations(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.putMany(Map.of("a", "1", "b", "2"), Duration.ofMinutes(1));
        var got = cache.many(List.of("a", "b", "missing"), String.class);

        assertThat(got).containsOnlyKeys("a", "b");
        assertThat(got.get("a")).isEqualTo("1");

        cache.deleteMultiple(List.of("a", "b"));
        assertThat(cache.many(List.of("a", "b"), String.class)).isEmpty();
    }

    @ParameterizedTest(name = "[{0}] 前缀隔离不同应用")
    @MethodSource("stores")
    void prefixIsolates(String name, Supplier<CacheStore> factory) {
        var store = factory.get();
        var appA = new Repository(store, "app-a");
        var appB = new Repository(store, "app-b");

        appA.put("shared-name", "A");
        appB.put("shared-name", "B");

        assertThat(appA.get("shared-name", String.class)).contains("A");
        assertThat(appB.get("shared-name", String.class)).contains("B");
    }

    // ================= 标签 =================

    @ParameterizedTest(name = "[{0}] 标签读写")
    @MethodSource("stores")
    void taggedReadWrite(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.tags("users").put("u1", "alice", Duration.ofMinutes(1));
        assertThat(cache.tags("users").get("u1", String.class)).contains("alice");

        // 不带标签读不到同名键 —— 标签是命名空间
        assertThat(cache.get("u1", String.class)).isEmpty();
    }

    @ParameterizedTest(name = "[{0}] flush 一个标签使该组全部失效")
    @MethodSource("stores")
    void tagFlush(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.tags("users").put("u1", "alice", Duration.ofMinutes(1));
        cache.tags("users").put("u2", "bob", Duration.ofMinutes(1));
        cache.tags("posts").put("p1", "hello", Duration.ofMinutes(1));

        cache.tags("users").flush();

        assertThat(cache.tags("users").get("u1", String.class)).isEmpty();
        assertThat(cache.tags("users").get("u2", String.class)).isEmpty();
        // 别的标签不受影响
        assertThat(cache.tags("posts").get("p1", String.class)).contains("hello");
    }

    @ParameterizedTest(name = "[{0}] 多标签条目:任一标签 flush 即失效")
    @MethodSource("stores")
    void multiTagFlushFromEither(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.tags("users", "posts").put("k", "v", Duration.ofMinutes(1));
        assertThat(cache.tags("users", "posts").get("k", String.class)).contains("v");

        cache.tags("posts").flush();
        assertThat(cache.tags("users", "posts").get("k", String.class)).isEmpty();
    }

    @ParameterizedTest(name = "[{0}] 标签顺序不影响命中")
    @MethodSource("stores")
    void tagOrderDoesNotMatter(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        cache.tags("a", "b").put("k", "v", Duration.ofMinutes(1));
        // 顺序不同必须命中同一条,否则同一份数据会被存两份
        assertThat(cache.tags("b", "a").get("k", String.class)).contains("v");
    }

    // ================= 锁 =================

    @ParameterizedTest(name = "[{0}] 锁互斥")
    @MethodSource("stores")
    void lockIsExclusive(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        var first = cache.lock("job", Duration.ofMinutes(1));
        var second = cache.lock("job", Duration.ofMinutes(1));

        assertThat(first.get()).isTrue();
        assertThat(second.get()).isFalse();

        first.release();
        assertThat(second.get()).isTrue();
    }

    @ParameterizedTest(name = "[{0}] 闭包式锁保证释放")
    @MethodSource("stores")
    void lockClosureReleases(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());
        var ran = new boolean[1];

        // 写成语句块,否则 `() -> ran[0] = true` 会被推成 Supplier<Boolean> 重载
        Runnable body = () -> ran[0] = true;
        assertThat(cache.lock("job", Duration.ofMinutes(1)).get(body)).isTrue();
        assertThat(ran[0]).isTrue();
        // 执行完就该释放
        assertThat(cache.lock("job", Duration.ofMinutes(1)).get()).isTrue();
    }

    @ParameterizedTest(name = "[{0}] 闭包抛异常时仍然释放")
    @MethodSource("stores")
    void lockReleasesOnException(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        try {
            Runnable boom = () -> { throw new IllegalStateException("boom"); };
            cache.lock("job", Duration.ofMinutes(1)).get(boom);
        } catch (IllegalStateException ignored) {
            // 预期
        }
        assertThat(cache.lock("job", Duration.ofMinutes(1)).get()).isTrue();
    }

    @ParameterizedTest(name = "[{0}] release 不会释放别人的锁")
    @MethodSource("stores")
    void releaseChecksOwner(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        var mine = cache.lock("job", Duration.ofMinutes(1));
        assertThat(mine.get()).isTrue();

        // 另一个持有者句柄(模拟别的进程),它不该能释放我的锁
        var other = cache.lock("job", Duration.ofMinutes(1));
        assertThat(other.release()).isFalse();
        assertThat(mine.isHeld()).isTrue();

        assertThat(mine.release()).isTrue();
    }

    @ParameterizedTest(name = "[{0}] restoreLock 能用 owner 跨句柄释放")
    @MethodSource("stores")
    void restoreLockByOwner(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());

        var lock = cache.lock("job", Duration.ofMinutes(1));
        assertThat(lock.get()).isTrue();

        assertThat(cache.restoreLock("job", lock.owner()).release()).isTrue();
        assertThat(cache.lock("job", Duration.ofMinutes(1)).get()).isTrue();
    }

    // ================= 限流 =================

    @ParameterizedTest(name = "[{0}] 限流器计数与重置")
    @MethodSource("stores")
    void rateLimiter(String name, Supplier<CacheStore> factory) {
        var cache = new Repository(factory.get());
        var limiter = new RateLimiter(cache);

        for (int i = 0; i < 3; i++) limiter.hit("login:a", Duration.ofMinutes(1));

        assertThat(limiter.attempts("login:a")).isEqualTo(3);
        assertThat(limiter.tooManyAttempts("login:a", 3)).isTrue();
        assertThat(limiter.tooManyAttempts("login:a", 5)).isFalse();
        assertThat(limiter.remaining("login:a", 5)).isEqualTo(2);
        assertThat(limiter.availableInSeconds("login:a")).isBetween(1L, 60L);

        limiter.clear("login:a");
        assertThat(limiter.attempts("login:a")).isZero();
    }

    @ParameterizedTest(name = "[{0}] 限流窗口会真的过期 —— 否则用户被永久封锁")
    @MethodSource("stores")
    void rateLimitWindowExpires(String name, Supplier<CacheStore> factory)
            throws InterruptedException {
        var limiter = new RateLimiter(new Repository(factory.get()));

        limiter.hit("login:b", Duration.ofMillis(80));
        limiter.hit("login:b", Duration.ofMillis(80));
        assertThat(limiter.attempts("login:b")).isEqualTo(2);

        Thread.sleep(140);
        // 计数器若没带上 TTL(常见实现失误),这里会仍然是 2
        assertThat(limiter.attempts("login:b")).isZero();
    }

    @ParameterizedTest(name = "[{0}] 持续命中不会把窗口往后推")
    @MethodSource("stores")
    void windowIsNotExtendedByHits(String name, Supplier<CacheStore> factory)
            throws InterruptedException {
        var limiter = new RateLimiter(new Repository(factory.get()));

        limiter.hit("login:c", Duration.ofMillis(150));
        Thread.sleep(80);
        limiter.hit("login:c", Duration.ofMillis(150));
        Thread.sleep(100);

        // 总共 180ms。若第二次命中重置了窗口,这里还会是 2
        assertThat(limiter.attempts("login:c")).isZero();
    }

    // ================= 夹具 =================

    private static DataSource nonClosing(java.sql.Connection real) {
        java.sql.Connection proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (p, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("isClosed")) return false;
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new DataSource() {
            @Override public java.sql.Connection getConnection() { return proxy; }
            @Override public java.sql.Connection getConnection(String u, String p) { return proxy; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() { return Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) { return iface.cast(proxy); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
