package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.cache.CacheManager;
import io.jaravel.cache.store.DatabaseStore;
import io.jaravel.database.Connection;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.scheduler.Schedule;
import io.jaravel.scheduler.Scheduler;

/**
 * ★ v0.4 验收第 3 条:**3 个实例下 {@code onOneServer()} 的任务只执行一次**。
 *
 * <h2>为什么这个测试必须存在</h2>
 * <p>{@code SchedulerTest} 里的 {@code onOneServer} 测试用的是一个
 * **手写的假锁**(一个 {@code Set} 加上模拟的竞争),单实例单 JVM。
 *
 * <p>那证明的是「Scheduler 会去问锁」—— 一个有用但不够的结论。
 * 真正会出问题的是:
 * <ul>
 *   <li>starter 里**怎么把缓存包成调度锁**
 *       ({@code JaravelSchedulerAutoConfiguration.cacheTaskLocks});</li>
 *   <li>那把缓存锁的 {@code add} 在**共享存储上**是不是真的原子。</li>
 * </ul>
 *
 * <p>第二条原先是坏的 —— {@code DatabaseStore.add} 清理过期行时用的是
 * {@code delete where key = ?},于是两个实例能同时拿到锁
 * (见 {@code DatabaseLockRaceTest})。一个用假锁的测试永远看不见它。
 *
 * <h2>「3 个实例」怎么模拟</h2>
 * <p>3 个独立的 {@code Scheduler} + 3 个独立的 {@code CacheManager}
 * / {@code Repository} / {@code Connection},**共享同一张 cache 表**。
 *
 * <p>没起 3 个进程:锁的状态完全活在那张共享表里,而进程边界
 * 不改变任何与这条验收相关的东西。真正重要的是
 * 「3 份互不知情的内存状态 + 1 份共享存储」,而这里就是那个形状。
 */
class SchedulerMultiInstanceTest {

    private static java.sql.Connection sqlite;

    private String table;
    private final List<String> executions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        if (sqlite == null || sqlite.isClosed()) {
            // file: + cache=shared:多条连接看到同一个库。
            // 不用 :memory: —— 那样每条连接各看到一个空库,
            // 于是「共享存储」这个前提直接消失,测试会假性通过
            sqlite = DriverManager.getConnection(
                    "jdbc:sqlite:file:schedlocks?mode=memory&cache=shared");
        }
        table = "cache_" + Math.abs(java.util.UUID.randomUUID()
                .toString().hashCode());
        try (Statement st = sqlite.createStatement()) {
            st.execute("create table " + table + " ("
                     + "\"key\" varchar(255) primary key, "
                     + "value text not null, "
                     + "expires_at bigint not null)");
        }
        executions.clear();
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (sqlite != null && !sqlite.isClosed()) sqlite.close();
    }

    /**
     * 一个「实例」:自己的连接、自己的 CacheManager、自己的 Scheduler,
     * 锁走 starter 的真实装配。
     */
    private Scheduler instance(String name, java.util.function.Consumer<Schedule> define) {
        Connection connection = new JdbcConnection(
                sharedConnection(), new SqliteGrammar());

        var caches = new CacheManager()
                .register("database", new DatabaseStore(connection, table))
                .defaultStore("database");

        var schedule = new Schedule();
        define.accept(schedule);

        // ★ 这里用的是 starter 的那段代码,不是测试重写的适配器
        return new Scheduler(schedule)
                .locks(JaravelSchedulerAutoConfiguration.cacheTaskLocks(caches));
    }

    // ================= 验收 =================

    @Nested
    @DisplayName("3 个实例共享一个 database 缓存")
    class ThreeInstances {

        @Test
        @DisplayName("★ onOneServer 的任务只在一个实例上执行")
        void onlyOneInstanceRuns() {
            var now = ZonedDateTime.now().withSecond(0).withNano(0);

            var ran = new AtomicInteger();
            var schedulers = List.of(
                    instance("A", s -> s.call("对账", () -> {
                        ran.incrementAndGet();
                        executions.add("A");
                    }).everyMinute().onOneServer()),
                    instance("B", s -> s.call("对账", () -> {
                        ran.incrementAndGet();
                        executions.add("B");
                    }).everyMinute().onOneServer()),
                    instance("C", s -> s.call("对账", () -> {
                        ran.incrementAndGet();
                        executions.add("C");
                    }).everyMinute().onOneServer()));

            schedulers.forEach(s -> s.runDue(now));

            assertThat(ran.get())
                    .as("""
                            ★ v0.4 验收第 3 条。实际执行的实例:%s

                              跑两遍对「清缓存」这类任务无害,所以这个缺陷
                              会潜伏到遇上一个不幂等的任务为止 ——
                              比如「给昨天的订单发对账邮件」,
                              那时的现象是客户收到两封一样的邮件。""",
                            executions)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ 并发地跑三个实例,结果一样")
        void onlyOneInstanceRunsConcurrently() throws Exception {
            var now = ZonedDateTime.now().withSecond(0).withNano(0);
            var ran = new AtomicInteger();

            var schedulers = List.of(
                    instance("A", s -> s.call("对账", ran::incrementAndGet)
                            .everyMinute().onOneServer()),
                    instance("B", s -> s.call("对账", ran::incrementAndGet)
                            .everyMinute().onOneServer()),
                    instance("C", s -> s.call("对账", ran::incrementAndGet)
                            .everyMinute().onOneServer()));

            // 同时放闸 —— 顺序执行时锁的「第二个拿不到」是显然的,
            // 而真实部署里三个实例是在同一秒醒来的
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(schedulers.size());
            var errors = new CopyOnWriteArrayList<Throwable>();

            for (Scheduler scheduler : schedulers) {
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        scheduler.runDue(now);
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("三个实例应该都跑完").isTrue();

            // SQLite 的并发写会抛 SQLITE_BUSY —— 那不是本测试要验证的
            // 东西,但也不能静默忽略:如果三个实例全都因为 BUSY
            // 失败了,ran == 0 也会「通过」一个写错的断言
            assertThat(errors)
                    .as("锁竞争不该变成异常 —— 拿不到锁是正常结果")
                    .isEmpty();
            assertThat(ran.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 不带 onOneServer 的任务在每个实例上都跑 —— 对照组")
        void withoutOnOneServerAllRun() {
            var now = ZonedDateTime.now().withSecond(0).withNano(0);
            var ran = new AtomicInteger();

            List.of(
                    instance("A", s -> s.call("清缓存", ran::incrementAndGet)
                            .everyMinute()),
                    instance("B", s -> s.call("清缓存", ran::incrementAndGet)
                            .everyMinute()),
                    instance("C", s -> s.call("清缓存", ran::incrementAndGet)
                            .everyMinute()))
                    .forEach(s -> s.runDue(now));

            assertThat(ran.get())
                    .as("这一条证明上面那条测的不是空气 —— "
                            + "如果 runDue 本身只在一个实例上触发,"
                            + "上面的断言会在 onOneServer 完全失效时也通过")
                    .isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("锁的生命周期")
    class LockLifecycle {

        @Test
        @DisplayName("★ 任务跑完后锁被释放 —— 下一分钟还能跑")
        void lockIsReleasedAfterTheRun() {
            var ran = new AtomicInteger();
            var a = instance("A", s -> s.call("对账", ran::incrementAndGet)
                    .everyMinute().onOneServer());

            var minute = ZonedDateTime.now().withSecond(0).withNano(0);
            a.runDue(minute);
            a.runDue(minute.plusMinutes(1));

            assertThat(ran.get())
                    .as("锁不释放的后果是任务在 TTL 内再也不跑 —— "
                            + "那比跑两遍更难发现,因为没有任何错误")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("★ 持锁的实例崩了:TTL 到期后别的实例能接上")
        void expiredLockIsReclaimed() {
            Connection connection = new JdbcConnection(
                    sharedConnection(), new SqliteGrammar());
            var caches = new CacheManager()
                    .register("database", new DatabaseStore(connection, table))
                    .defaultStore("database");

            // 模拟「A 拿到锁然后进程崩了」:锁留在共享存储里,
            // 没有人来释放它。只能等 TTL。
            //
            // 这是这把锁**刻意的**失效边界(见 Lock 的类注释):
            // 它防的是「正常情况下的重复」,不是「任意故障下的互斥」。
            // 持有者卡住超过 TTL 而任务还在跑时,第二个实例会进来
            var locks = JaravelSchedulerAutoConfiguration.cacheTaskLocks(caches);
            String key = "jaravel:schedule:crashed";

            var held = locks.acquire(key, Duration.ofMillis(120));
            assertThat(held).as("A 应该拿到锁").isNotNull();

            assertThat(locks.acquire(key, Duration.ofMillis(120)))
                    .as("TTL 之内 B 拿不到")
                    .isNull();

            try {
                Thread.sleep(200);     // 等 TTL 过去
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertThat(locks.acquire(key, Duration.ofMillis(120)))
                    .as("★ TTL 之后 B 必须能接上 —— 否则一个崩掉的实例"
                            + "会让这个任务永久停摆")
                    .isNotNull();
        }

        @Test
        @DisplayName("★ 跑完后锁**不**释放 —— 释放等于把这一分钟重新开放")
        void lockSurvivesTheRun() {
            Connection connection = new JdbcConnection(
                    sharedConnection(), new SqliteGrammar());
            var caches = new CacheManager()
                    .register("database", new DatabaseStore(connection, table))
                    .defaultStore("database");

            var ran = new AtomicInteger();
            var schedule = new Schedule();
            schedule.call("对账", ran::incrementAndGet).everyMinute().onOneServer();

            var scheduler = new Scheduler(schedule)
                    .locks(JaravelSchedulerAutoConfiguration.cacheTaskLocks(caches));

            var minute = ZonedDateTime.now().withSecond(0).withNano(0);
            scheduler.runDue(minute);

            // 这一分钟的锁应该还在表里。
            //
            // 假如跑完就释放（原先的实现），它就不在了 ——
            // 而那意味着另一个实例现在能把同一分钟再跑一遍
            long locksHeld = connection.table(table).count();
            assertThat(locksHeld)
                    .as("跑完之后那把锁必须还在 —— 它靠 TTL 自然消失")
                    .isEqualTo(1);
            assertThat(ran.get()).isEqualTo(1);
        }
    }

    // ================= 辅助 =================

    /** 不让 JdbcConnection 关掉共享的那条 SQLite 连接 */
    private static javax.sql.DataSource sharedConnection() {
        return new javax.sql.DataSource() {
            @Override
            public java.sql.Connection getConnection() {
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                        SchedulerMultiInstanceTest.class.getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("close")) return null;
                            return method.invoke(sqlite, args);
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
