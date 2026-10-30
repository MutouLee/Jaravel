package io.jaravel.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.database.Connection;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.cache.store.DatabaseStore;

/**
 * ★ {@code DatabaseStore.add} 在「上一把锁已过期」时的竞态。
 *
 * <p>这是 v0.4 验收第 3 条(「3 个实例下 {@code onOneServer} 只执行一次」)
 * 落到实现上的那一层 —— 而它原先是坏的。
 *
 * <p>原先的实现看到一行已过期就 {@code delete where key = ?}。
 * 两个实例同时走到这一步时:
 *
 * <pre>
 *   1. A select → 看到一行已过期
 *   2. B select → 也看到那一行已过期
 *   3. A delete → 删掉过期行
 *   4. A insert → 成功,A 持有锁
 *   5. B delete → 按 key 删,**把 A 刚插入的有效行删了**
 *   6. B insert → 成功,B 也持有锁
 * </pre>
 *
 * <p>于是两个实例都认为自己拿到了锁,{@code onOneServer} 的任务跑两遍。
 *
 * <h2>为什么用「手工重放第 5 步」而不是起线程压测</h2>
 * <p>这个竞态的窗口是「B 的 select 在 A 的 insert 之前、B 的 delete 在
 * A 的 insert 之后」—— 压测能不能撞上它取决于调度,而
 * **一个有时撞不上的测试证明不了修复**。更糟的是
 * SQLite 的内存库在并发写下本来就不可靠(这个项目之前就为此
 * 删掉过一个 flaky 测试),于是失败信号会被归因到 SQLite 而不是竞态。
 *
 * <p>所以这里把第 5 步**直接发出来**:先让 A 正常拿到锁,
 * 再模拟一个「迟到的 B」去删。那是竞态里唯一有危害的那一步,
 * 而它是完全确定的。
 */
class DatabaseLockRaceTest {

    private static java.sql.Connection sqlite;

    private Connection connection;
    private String table;
    private DatabaseStore store;

    @BeforeEach
    void setUp() throws SQLException {
        if (sqlite == null || sqlite.isClosed()) {
            sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        }
        table = "cache_" + Math.abs(UUID.randomUUID().toString().hashCode());
        try (Statement st = sqlite.createStatement()) {
            st.execute("create table " + table + " ("
                     + "\"key\" varchar(255) primary key, "
                     + "value text not null, "
                     + "expires_at bigint not null)");
        }
        connection = new JdbcConnection(nonClosing(sqlite), new SqliteGrammar());
        store = new DatabaseStore(connection, table);
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (sqlite != null && !sqlite.isClosed()) sqlite.close();
    }

    /** 让一行立刻过期 —— 比 sleep 可靠,也不浪费时间 */
    private void insertExpiredRow(String key) {
        connection.table(table).insert(java.util.Map.of(
                "key", key,
                "value", "\"stale\"",
                "expires_at", System.currentTimeMillis() - 1));
    }

    private long expiresAtOf(String key) {
        return connection.table(table).where("key", key).get().first()
                .map(row -> Long.parseLong(String.valueOf(row.get("expires_at"))))
                .orElse(-1L);
    }

    // ================= 用例 =================

    /**
     * 一个把那个交错**重放出来**的 store。
     *
     * <p>它扮演实例 B:在自己的「清过期行」这一步**之前**,
     * 让实例 A 跑完一次完整的 {@code add}。于是 B 的 delete 与
     * insert 都发生在 A 已经持有锁之后 —— 正是竞态里有危害的那段。
     *
     * <p>只让它发生一次({@code once}),否则 A 的 add 会再次进到
     * 这个钩子里无限递归。
     */
    private final class InstanceB extends DatabaseStore {

        private final DatabaseStore instanceA;
        private boolean once = true;
        boolean aAcquired;

        InstanceB(Connection connection, String table, DatabaseStore instanceA) {
            super(connection, table);
            this.instanceA = instanceA;
        }

        @Override
        protected void deleteIfExpired(String key) {
            if (once) {
                once = false;
                // ★ A 在这里整个跑完:它清掉过期行、插入自己的锁
                aAcquired = instanceA.add(key, "A", Duration.ofMinutes(5));
            }
            super.deleteIfExpired(key);
        }
    }

    @Test
    @DisplayName("★ 重放竞态:A 抢到锁之后,B 的 add 必须返回 false")
    void twoInstancesCannotBothAcquire() {
        insertExpiredRow("lock:report");

        var b = new InstanceB(connection, table, store);

        // B 发起 add —— 它会在内部让 A 先跑完(见 InstanceB)
        boolean bAcquired = b.add("lock:report", "B", Duration.ofMinutes(5));

        assertThat(b.aAcquired)
                .as("A 应该拿到锁:过期的锁不该永远挡住新的获取")
                .isTrue();

        assertThat(bAcquired)
                .as("""
                        ★ 这是 v0.4 验收第 3 条的落点:第二个实例必须拿不到锁。

                          原先的实现在这里返回 true —— 它的「清过期行」是
                          delete where key = ?,于是 B 把 A 刚插入的**有效**行
                          删掉了,再 insert 成功。两个实例同时持有锁,
                          onOneServer 的任务跑两遍。""")
                .isFalse();

        assertThat(expiresAtOf("lock:report"))
                .as("锁还是 A 的那一把,没被 B 改写")
                .isGreaterThan(System.currentTimeMillis());
    }

    @Test
    @DisplayName("★ 对照:不带过期条件的 delete 确实会删掉有效的锁")
    void unconditionalDeleteWouldStealIt() {
        store.add("lock:report", "A", Duration.ofMinutes(5));

        int deleted = connection.table(table).where("key", "lock:report").delete();

        assertThat(deleted)
                .as("这一条存在的意义是证明上面那条测的不是空气 —— "
                        + "原先的实现发的就是这条语句,而它删得掉")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("过期的锁仍然能被重新获取 —— 修复不能把这条弄坏")
    void expiredLockIsStillReacquirable() {
        insertExpiredRow("lock:nightly");

        assertThat(store.add("lock:nightly", "A", Duration.ofMinutes(5)))
                .as("一个过期的锁永远挡住获取,后果是那个定时任务再也不跑了 —— "
                        + "比跑两遍更难发现")
                .isTrue();
    }

    @Test
    @DisplayName("★ 永久锁(expires_at = 0)不会被当成过期删掉")
    void permanentLockIsNotTreatedAsExpired() {
        assertThat(store.add("lock:forever", "A", null)).isTrue();
        assertThat(expiresAtOf("lock:forever"))
                .as("null TTL 存成 0")
                .isZero();

        assertThat(store.add("lock:forever", "B", null))
                .as("0 表示永不过期,而 `0 <= now` 恒成立 —— "
                        + "过期条件里漏掉 expires_at > 0 会把永久锁删掉")
                .isFalse();
    }

    @Test
    @DisplayName("未过期的锁:连 select 都不该让它消失")
    void liveLockBlocksAdd() {
        store.add("lock:x", "A", Duration.ofMinutes(5));

        assertThat(store.add("lock:x", "B", Duration.ofMinutes(5))).isFalse();
        assertThat(store.add("lock:x", "C", Duration.ofMinutes(5))).isFalse();
        assertThat(expiresAtOf("lock:x")).isGreaterThan(System.currentTimeMillis());
    }

    // ================= 辅助 =================

    /**
     * 包一层不让 JdbcConnection 关掉共享的那条 SQLite 连接。
     *
     * <p>与 {@code CacheStoreContractTest} 里的那一份一样 ——
     * 内存库在连接关闭时整个消失。
     */
    private static javax.sql.DataSource nonClosing(java.sql.Connection shared) {
        return new javax.sql.DataSource() {
            @Override
            public java.sql.Connection getConnection() {
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("close")) return null;
                            return method.invoke(shared, args);
                        });
            }

            @Override
            public java.sql.Connection getConnection(String u, String p) {
                return getConnection();
            }

            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
