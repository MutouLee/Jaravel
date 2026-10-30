package io.jaravel.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.cache.CacheManager;
import io.jaravel.cache.Repository;
import io.jaravel.cache.events.CacheEvents;
import io.jaravel.cache.store.ArrayStore;
import io.jaravel.core.Application;
import io.jaravel.core.container.SimpleContainer;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.events.QueryExecuted;
import io.jaravel.database.events.TransactionEvents;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.queue.JobInvoker;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.Queue;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.QueueWorker;
import io.jaravel.queue.ShouldQueue;
import io.jaravel.queue.WorkerOptions;
import io.jaravel.queue.events.QueueEvents;
import io.jaravel.queue.store.DatabaseFailedJobStore;
import io.jaravel.queue.store.DatabaseQueue;

/**
 * 框架事件是不是真的从它们的调用点发出来了(docs/10 §2.5)。
 *
 * <h2>为什么这个测试必须存在</h2>
 * <p>「事件类存在」与「它真的被发出来」是两件事,而前者看起来和后者
 * 一模一样 —— 一个 record 定义得再漂亮,如果没人调
 * {@code Event.dispatch} 就永远不会触发,而**没有任何测试会变红**。
 *
 * <p>这正是 v0.3/v0.4 反复修的那类缺陷(18 个,全部是接线)。
 * 框架事件尤其危险:它们分散在 5 个模块的内部调用点上,
 * 每一个都是一次独立的接线机会。
 *
 * <p>所以这里的每个测试都走**真实路径**:真的跑一条 SQL、
 * 真的读一次缓存、真的让一个任务失败 —— 然后断言事件到了。
 * 直接 {@code Event.dispatch(new QueryExecuted(...))} 的测试
 * 什么都证明不了。
 */
class FrameworkEventsTest {

    static final List<Object> RECEIVED = new CopyOnWriteArrayList<>();

    private java.sql.Connection jdbc;
    private io.jaravel.database.Connection db;
    private EventBus bus;

    @BeforeEach
    void setUp() throws SQLException {
        RECEIVED.clear();
        FailsOnce.reset();

        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
            st.execute("create table widgets (id integer primary key, name text)");
            st.execute("""
                    create table jobs (
                        id integer primary key autoincrement,
                        uuid text not null unique, queue text not null,
                        payload text not null, attempts integer not null default 0,
                        reserved_at text null, available_at text not null,
                        created_at text not null)
                    """);
            st.execute("""
                    create table failed_jobs (
                        id integer primary key autoincrement,
                        uuid text not null unique, connection text not null,
                        queue text not null, display_name text not null,
                        payload text not null, exception text not null,
                        failed_at text not null)
                    """);
        }

        db = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());

        var container = new SimpleContainer();
        container.instance(io.jaravel.database.Connection.class, db);
        Application.setContainer(container);

        bus = EventBus.standalone();
        Event.use(bus);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Event.reset();
        Queue.reset();
        Application.reset();
        if (jdbc != null) jdbc.close();
    }

    /** 收下某个类型的全部事件 */
    private <E> void collect(Class<E> type) {
        bus.listen(type, RECEIVED::add);
    }

    @SuppressWarnings("unchecked")
    private <E> List<E> received(Class<E> type) {
        var out = new ArrayList<E>();
        for (Object e : RECEIVED) {
            if (type.isInstance(e)) out.add((E) e);
        }
        return out;
    }

    // ================= 数据库 =================

    @Nested
    @DisplayName("数据库事件")
    class Database {

        @Test
        @DisplayName("★ 跑一条真的 SQL → QueryExecuted")
        void queryExecuted() {
            collect(QueryExecuted.class);

            db.table("widgets").insert(Map.of("id", 1, "name", "螺丝"));
            db.table("widgets").get();

            var events = received(QueryExecuted.class);
            assertThat(events).hasSizeGreaterThanOrEqualTo(2);

            assertThat(events.get(0).sql()).containsIgnoringCase("insert");
            assertThat(events.get(0).duration()).isNotNull();
            assertThat(events.get(0).caller())
                    .as("要能定位到「这条 SQL 是哪行代码发的」")
                    .isNotBlank();
        }

        @Test
        @DisplayName("★ bindings 含 null 时不炸")
        void nullBindingsAreFine() {
            collect(QueryExecuted.class);

            db.table("widgets").insert(Map.of("id", 2));
            db.update("update widgets set name = ? where id = ?",
                    java.util.Arrays.asList(null, 2));

            assertThat(received(QueryExecuted.class))
                    .as("`set x = null` 是完全正常的绑定 —— "
                            + "List.copyOf 会在这里 NPE")
                    .anyMatch(e -> e.bindings().contains(null));
        }

        @Test
        @DisplayName("★ 没人监听时不构造事件(热路径)")
        void noListenerNoAllocation() {
            // 不注册任何监听器
            assertThat(Event.listening(QueryExecuted.class)).isFalse();

            db.table("widgets").get();      // 不该抛任何东西

            assertThat(RECEIVED).isEmpty();
        }

        @Test
        @DisplayName("★ 事务提交 → Beginning + Committed(level=1)")
        void transactionCommitted() {
            collect(TransactionEvents.Beginning.class);
            collect(TransactionEvents.Committed.class);

            db.transaction(() -> db.table("widgets").insert(Map.of("id", 3)));

            assertThat(received(TransactionEvents.Beginning.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.isOutermost()).isTrue());
            assertThat(received(TransactionEvents.Committed.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.level()).isEqualTo(1));
        }

        @Test
        @DisplayName("★ 事务回滚 → RolledBack,带上原因")
        void transactionRolledBack() {
            collect(TransactionEvents.RolledBack.class);
            collect(TransactionEvents.Committed.class);

            try {
                db.transaction(() -> {
                    db.table("widgets").insert(Map.of("id", 4));
                    throw new IllegalStateException("业务失败");
                });
            } catch (IllegalStateException expected) {
                // 预期
            }

            assertThat(received(TransactionEvents.RolledBack.class))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.level()).isEqualTo(1);
                        assertThat(e.cause()).hasMessage("业务失败");
                    });
            assertThat(received(TransactionEvents.Committed.class))
                    .as("回滚了就不该有 Committed")
                    .isEmpty();
        }

        @Test
        @DisplayName("★ 嵌套事务的 level > 1 —— 那时数据还没落库")
        void nestedTransactionLevels() {
            collect(TransactionEvents.Committed.class);

            db.transaction(() -> {
                db.table("widgets").insert(Map.of("id", 5));
                db.transaction(() -> db.table("widgets").insert(Map.of("id", 6)));
                return null;
            });

            var committed = received(TransactionEvents.Committed.class);
            assertThat(committed).hasSize(2);

            assertThat(committed.get(0).level())
                    .as("内层先结束,它的 level 是 2 —— "
                            + "那时只是释放了保存点,数据还没落库")
                    .isEqualTo(2);
            assertThat(committed.get(0).isOutermost()).isFalse();
            assertThat(committed.get(1).isOutermost())
                    .as("外层才是「真的提交了」")
                    .isTrue();
        }

        @Test
        @DisplayName("内层回滚、外层提交 —— 两个事件都发")
        void innerRollbackOuterCommit() {
            collect(TransactionEvents.RolledBack.class);
            collect(TransactionEvents.Committed.class);

            db.transaction(() -> {
                db.table("widgets").insert(Map.of("id", 7));
                try {
                    db.transaction(() -> {
                        throw new IllegalStateException("内层失败");
                    });
                } catch (IllegalStateException ignored) {
                    // 内层回滚了,外层继续
                }
                return null;
            });

            assertThat(received(TransactionEvents.RolledBack.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.level()).isEqualTo(2));
            assertThat(received(TransactionEvents.Committed.class))
                    .as("框架不替监听器过滤 level —— "
                            + "正是为了让这类审计能做")
                    .singleElement()
                    .satisfies(e -> assertThat(e.isOutermost()).isTrue());
        }
    }

    // ================= 缓存 =================

    @Nested
    @DisplayName("缓存事件")
    class Cache {

        private Repository cache;

        @BeforeEach
        void cacheSetUp() {
            cache = new CacheManager()
                    .register("array", new ArrayStore())
                    .defaultStore("array")
                    .prefix("myapp")
                    .store();
        }

        @Test
        @DisplayName("★ 真的读一次 → Hit / Missed")
        void hitAndMiss() {
            collect(CacheEvents.Hit.class);
            collect(CacheEvents.Missed.class);

            cache.get("absent");
            cache.put("present", "值");
            cache.get("present");

            assertThat(received(CacheEvents.Missed.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.key()).isEqualTo("absent"));
            assertThat(received(CacheEvents.Hit.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.key()).isEqualTo("present"));
        }

        @Test
        @DisplayName("★ key 不带 cache.prefix")
        void keyHasNoPrefix() {
            collect(CacheEvents.Hit.class);

            cache.put("k", "v");
            cache.get("k");

            assertThat(received(CacheEvents.Hit.class).get(0).key())
                    .as("前缀随环境变(dev/prod 常配不同的),"
                            + "带上它会让同一个指标在两个环境下名字不同")
                    .isEqualTo("k")
                    .doesNotContain("myapp");
        }

        @Test
        @DisplayName("★ 写入 → KeyWritten,带 TTL")
        void keyWritten() {
            collect(CacheEvents.KeyWritten.class);

            cache.put("with-ttl", "v", Duration.ofMinutes(5));
            cache.put("forever", "v");

            var events = received(CacheEvents.KeyWritten.class);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).ttl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(events.get(1).isForever())
                    .as("null TTL 表示永久")
                    .isTrue();
        }

        @Test
        @DisplayName("★ 删除 → KeyForgotten")
        void keyForgotten() {
            collect(CacheEvents.KeyForgotten.class);

            cache.put("gone", "v");
            cache.forget("gone");

            assertThat(received(CacheEvents.KeyForgotten.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.key()).isEqualTo("gone"));
        }

        @Test
        @DisplayName("★ 负 TTL 走的是删除路径,所以发 KeyForgotten 而不是 KeyWritten")
        void negativeTtlForgets() {
            collect(CacheEvents.KeyWritten.class);
            collect(CacheEvents.KeyForgotten.class);

            cache.put("expired", "v", Duration.ofSeconds(-1));

            assertThat(received(CacheEvents.KeyForgotten.class))
                    .as("负 TTL 等于「立刻过期」,实现是直接删 —— "
                            + "事件要如实反映实际发生的操作")
                    .hasSize(1);
            assertThat(received(CacheEvents.KeyWritten.class)).isEmpty();
        }
    }

    // ================= 队列 =================

    @Nested
    @DisplayName("队列事件")
    class QueueEventsWiring {

        private QueueManager queues;
        private DatabaseFailedJobStore failedJobs;

        @BeforeEach
        void queueSetUp() {
            var serializer = new JobSerializer();
            failedJobs = new DatabaseFailedJobStore(db);
            queues = new QueueManager(serializer)
                    .register("database", m -> new DatabaseQueue(db, serializer))
                    .defaultConnection("database")
                    .defaultQueue("default")
                    .afterCommitByDefault(false);
            Queue.use(queues);
        }

        private void drain() {
            new QueueWorker(queues, failedJobs, JobInvoker.withoutInjection(),
                    WorkerOptions.onQueues("default")
                            .withConcurrency(1)
                            .withRetryAfter(Duration.ofSeconds(30))
                            .withSleep(Duration.ofMillis(5))
                            .withStopWhenEmpty(true)
                            .withMaxJobs(20))
                    .run();
        }

        @Test
        @DisplayName("★ 投递 → JobQueued")
        void jobQueued() {
            collect(QueueEvents.JobQueued.class);

            Queue.dispatch(new Succeeds());

            assertThat(received(QueueEvents.JobQueued.class))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.connection()).isEqualTo("database");
                        assertThat(e.job().jobClass()).contains("Succeeds");
                    });
        }

        @Test
        @DisplayName("★ 执行 → JobProcessing + JobProcessed(带耗时)")
        void processingAndProcessed() {
            collect(QueueEvents.JobProcessing.class);
            collect(QueueEvents.JobProcessed.class);

            Queue.dispatch(new Succeeds());
            drain();

            assertThat(received(QueueEvents.JobProcessing.class)).hasSize(1);
            assertThat(received(QueueEvents.JobProcessed.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.duration()).isNotNull());
        }

        @Test
        @DisplayName("★ 中途重试与最终失败是**两个不同的事件**,各发一次")
        void retryAndFailureAreDistinct() {
            collect(QueueEvents.JobRetryRequested.class);
            collect(QueueEvents.JobFailed.class);

            Queue.dispatch(new AlwaysFails());
            drain();

            // tries=2:第一次失败 → 重试事件;第二次失败 → 最终失败事件。
            //
            // 关键是**各一次**而不是「失败事件两次」。混在一起的话
            // 一个失败告警监听器会在每次重试都响,于是一个最终成功的
            // 任务也会触发告警
            assertThat(received(QueueEvents.JobRetryRequested.class))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.job().attempts()).isEqualTo(1);
                        assertThat(e.delay()).isEqualTo(Duration.ZERO);
                    });
            assertThat(received(QueueEvents.JobFailed.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.job().attempts()).isEqualTo(2));
        }

        @Test
        @DisplayName("★ 最终成功的任务不发 JobFailed")
        void eventualSuccessNeverFails() {
            collect(QueueEvents.JobFailed.class);
            collect(QueueEvents.JobProcessed.class);
            collect(QueueEvents.JobRetryRequested.class);

            Queue.dispatch(new FailsOnce());
            drain();

            assertThat(received(QueueEvents.JobRetryRequested.class))
                    .as("第一次失败了,所以该有一次重试事件")
                    .hasSize(1);
            assertThat(received(QueueEvents.JobProcessed.class)).hasSize(1);
            assertThat(received(QueueEvents.JobFailed.class))
                    .as("这正是两个事件必须分开的理由 —— "
                            + "一个最终成功的任务不该触发失败告警")
                    .isEmpty();
        }

        @Test
        @DisplayName("★ JobFailed 在归档之后才发 —— failed_jobs 里已经有记录")
        void failedEventComesAfterArchiving() {
            var countsAtEvent = new ArrayList<Long>();
            bus.listen(QueueEvents.JobFailed.class,
                    e -> countsAtEvent.add(db.table("failed_jobs").count()));

            Queue.dispatch(new AlwaysFails());
            drain();

            assertThat(countsAtEvent)
                    .as("一个「去 failed_jobs 查详情」的监听器需要"
                            + "那条记录在事件发出时已经在了")
                    .containsExactly(1L);
        }
    }

    public static class Succeeds implements ShouldQueue {
        public void handle() {
            // 什么都不做 —— 只是为了让 Processed 发出来
        }
    }

    /** 第一次失败、第二次成功 —— 用来证明「重试 ≠ 最终失败」 */
    public static class FailsOnce implements ShouldQueue {
        private static final java.util.concurrent.atomic.AtomicInteger RUNS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override public int tries() { return 3; }

        @Override public Duration[] backoff() { return new Duration[] {Duration.ZERO}; }

        public void handle() {
            // 静态计数器:任务每次执行都是**反序列化出来的新实例**,
            // 实例字段记不住「跑过几次」
            if (RUNS.incrementAndGet() == 1) {
                throw new IllegalStateException("第一次总是失败");
            }
        }

        static void reset() {
            RUNS.set(0);
        }
    }

    public static class AlwaysFails implements ShouldQueue {
        @Override public int tries() { return 2; }

        @Override public Duration[] backoff() { return new Duration[] {Duration.ZERO}; }

        public void handle() {
            throw new IllegalStateException("总是失败");
        }
    }

    // ================= 工具 =================

    private static DataSource nonClosing(java.sql.Connection shared) {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                java.sql.Connection.class.getClassLoader(),
                                new Class<?>[] {java.sql.Connection.class},
                                (p2, m2, a2) -> m2.getName().equals("close")
                                        ? null
                                        : m2.invoke(shared, a2));
                    }
                    if (method.getName().equals("unwrap")) return shared;
                    return null;
                });
    }
}
