package io.jaravel.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.core.Application;
import io.jaravel.core.container.SimpleContainer;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.queue.store.DatabaseFailedJobStore;
import io.jaravel.queue.store.DatabaseQueue;
import io.jaravel.queue.store.SyncQueue;

/** Worker:并发消费、重试、失败归档、超时、停机 */
class QueueWorkerTest {

    static final ConcurrentLinkedQueue<String> LOG = new ConcurrentLinkedQueue<>();
    static final AtomicInteger ATTEMPTS = new AtomicInteger();
    static final AtomicInteger FAILED_CALLBACKS = new AtomicInteger();

    // ================= 测试任务 =================

    public static class Succeeds implements ShouldQueue {
        private final int n;

        public Succeeds(int n) {
            this.n = n;
        }

        public void handle() {
            LOG.add("ok:" + n);
        }
    }

    public static class FailsAlways implements ShouldQueue {
        private final int n;

        public FailsAlways(int n) {
            this.n = n;
        }

        @Override public int tries() { return 3; }

        @Override public Duration[] backoff() {
            // 测试里不能等 10 秒
            return new Duration[] {Duration.ZERO};
        }

        public void handle() {
            ATTEMPTS.incrementAndGet();
            throw new IllegalStateException("第 " + ATTEMPTS.get() + " 次失败");
        }

        @Override public void failed(Throwable cause) {
            FAILED_CALLBACKS.incrementAndGet();
            LOG.add("failed:" + n);
        }
    }

    /** 第一次失败,之后成功 —— 验证重试真的会重新执行 */
    public static class FailsOnce implements ShouldQueue {
        private final int n;

        public FailsOnce(int n) {
            this.n = n;
        }

        @Override public int tries() { return 3; }

        @Override public Duration[] backoff() {
            return new Duration[] {Duration.ZERO};
        }

        public void handle() {
            if (ATTEMPTS.incrementAndGet() == 1) {
                throw new IllegalStateException("第一次总是失败");
            }
            LOG.add("eventually-ok:" + n);
        }
    }

    public static class Sleeps implements ShouldQueue {
        private final long millis;

        public Sleeps(long millis) {
            this.millis = millis;
        }

        @Override public Duration timeout() { return Duration.ofMillis(200); }

        @Override public int tries() { return 1; }

        public void handle() {
            try {
                Thread.sleep(millis);
                LOG.add("slept:" + millis);
            } catch (InterruptedException e) {
                LOG.add("interrupted:" + millis);
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 字段类型在 payload 里对不上 —— 不可重试的失败 */
    public static class Undecodable implements ShouldQueue {
        private final String value;

        public Undecodable(String value) {
            this.value = value;
        }

        public void handle() {
            LOG.add("never:" + value);
        }
    }

    // ================= 脚手架 =================

    private java.sql.Connection jdbc;
    private io.jaravel.database.Connection connection;
    private JobSerializer serializer;
    private QueueManager queues;
    private DatabaseFailedJobStore failedJobs;

    @BeforeEach
    void setUp() throws SQLException {
        LOG.clear();
        ATTEMPTS.set(0);
        FAILED_CALLBACKS.set(0);
        BlocksUntilReleased.reset();

        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
            st.execute("""
                    create table jobs (
                        id integer primary key autoincrement,
                        uuid text not null unique,
                        queue text not null,
                        payload text not null,
                        attempts integer not null default 0,
                        reserved_at text null,
                        available_at text not null,
                        created_at text not null
                    )
                    """);
            st.execute("""
                    create table failed_jobs (
                        id integer primary key autoincrement,
                        uuid text not null unique,
                        connection text not null,
                        queue text not null,
                        display_name text not null,
                        payload text not null,
                        exception text not null,
                        failed_at text not null
                    )
                    """);
        }

        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        serializer = new JobSerializer();
        failedJobs = new DatabaseFailedJobStore(connection);

        var container = new SimpleContainer();
        container.instance(io.jaravel.database.Connection.class, connection);
        Application.setContainer(container);

        queues = new QueueManager(serializer)
                .register("database", m -> new DatabaseQueue(connection, serializer))
                .register("sync", m -> new SyncQueue(serializer,
                        new JobInvoker(Application::make)))
                .defaultConnection("database")
                .defaultQueue("default")
                // 测试里不走 afterCommit:没有事务,但显式关掉更清楚
                .afterCommitByDefault(false);
        Queue.use(queues);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Queue.reset();
        Application.reset();
        if (jdbc != null) jdbc.close();
    }

    private QueueWorker worker(WorkerOptions options) {
        return new QueueWorker(queues, failedJobs,
                new JobInvoker(Application::make), options);
    }

    /** 跑到队列空就退出 —— 测试里不要常驻 */
    private QueueWorker drainOnce(WorkerOptions options) {
        var w = worker(options.withStopWhenEmpty(true).withSleep(Duration.ofMillis(5)));
        w.run();
        return w;
    }

    private WorkerOptions base() {
        return WorkerOptions.onQueues("default")
                .withConcurrency(1)
                .withRetryAfter(Duration.ofSeconds(30))
                .withShutdownGrace(Duration.ofSeconds(5));
    }

    // ================= 基本消费 =================

    @Nested
    @DisplayName("基本消费")
    class Consuming {

        @Test
        @DisplayName("取出、执行、删除")
        void happyPath() {
            Queue.dispatch(new Succeeds(1));

            var w = drainOnce(base());

            assertThat(LOG).containsExactly("ok:1");
            assertThat(w.succeededCount()).isEqualTo(1);
            assertThat(Queue.size("default")).isZero();
        }

        @Test
        @DisplayName("按投递顺序处理")
        void fifo() {
            for (int i = 1; i <= 5; i++) Queue.dispatch(new Succeeds(i));

            drainOnce(base());

            assertThat(LOG).containsExactly("ok:1", "ok:2", "ok:3", "ok:4", "ok:5");
        }

        @Test
        @DisplayName("★ 队列顺序就是优先级 —— 严格优先")
        void strictPriority() {
            Queue.job(new Succeeds(99)).onQueue("low").dispatch();
            Queue.job(new Succeeds(1)).onQueue("high").dispatch();
            Queue.job(new Succeeds(2)).onQueue("high").dispatch();

            drainOnce(WorkerOptions.onQueues("high", "low")
                    .withConcurrency(1)
                    .withRetryAfter(Duration.ofSeconds(30)));

            // high 清空之前不碰 low。会让 low 饿死 —— 那是刻意的,
            // 见 WorkerOptions 的类注释
            assertThat(LOG).containsExactly("ok:1", "ok:2", "ok:99");
        }

        @Test
        @DisplayName("maxJobs 到了就退出")
        void maxJobs() {
            for (int i = 1; i <= 5; i++) Queue.dispatch(new Succeeds(i));

            var w = worker(base().withMaxJobs(2).withSleep(Duration.ofMillis(5)));
            w.run();

            assertThat(w.processedCount()).isEqualTo(2);
            assertThat(Queue.size("default")).as("剩下的还在队列里").isEqualTo(3);
        }

        @Test
        @DisplayName("★ 并发消费:50 个任务,10 个并发")
        void concurrency() throws Exception {
            for (int i = 0; i < 50; i++) Queue.dispatch(new Succeeds(i));

            var w = drainOnce(base().withConcurrency(10));

            // 虚拟线程下 10 个并发几乎零成本 —— 这是相对 PHP 的硬优势
            assertThat(w.succeededCount()).isEqualTo(50);
            assertThat(LOG).hasSize(50);
            assertThat(Queue.size("default")).isZero();
        }

        @Test
        @DisplayName("★ 不能在 sync 连接上起 worker")
        void refusesSyncConnection() {
            // 队列里永远是空的 —— 任务在投递时就执行完了。
            // 静默空转会让人以为 worker 在工作
            assertThatThrownBy(() -> new QueueWorker(queues, failedJobs,
                    new JobInvoker(Application::make), base(), "sync"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("同步驱动");
        }
    }

    // ================= 重试 =================

    @Nested
    @DisplayName("重试")
    class Retrying {

        @Test
        @DisplayName("★ 失败后放回队列,重试真的会重新执行")
        void retriesAndEventuallySucceeds() {
            Queue.dispatch(new FailsOnce(1));

            var w = drainOnce(base());

            assertThat(ATTEMPTS.get()).isEqualTo(2);
            assertThat(LOG).containsExactly("eventually-ok:1");
            assertThat(w.releasedCount()).isEqualTo(1);
            assertThat(w.succeededCount()).isEqualTo(1);
            assertThat(w.failedCount()).isZero();
        }

        @Test
        @DisplayName("★ 重试耗尽后进 failed_jobs 并调 failed()")
        void exhaustsAndArchives() {
            Queue.dispatch(new FailsAlways(1));

            var w = drainOnce(base());

            assertThat(ATTEMPTS.get()).as("tries=3").isEqualTo(3);
            assertThat(w.failedCount()).isEqualTo(1);
            assertThat(FAILED_CALLBACKS.get()).isEqualTo(1);

            assertThat(failedJobs.count()).isEqualTo(1);
            assertThat(Queue.size("default"))
                    .as("归档后必须从队列里删掉,否则保留超时后又被取一次,无限循环")
                    .isZero();
        }

        @Test
        @DisplayName("★ 失败记录带完整堆栈与「为什么不再重试」")
        void failureRecordIsUseful() {
            Queue.dispatch(new FailsAlways(1));
            drainOnce(base());

            var record = failedJobs.all().get(0);

            assertThat(record.exception())
                    .contains("已尝试 3 次")            // 耗尽原因
                    .contains("IllegalStateException")  // cause 链
                    .contains("第 3 次失败");
            assertThat(record.queue()).isEqualTo("default");
            assertThat(record.connection()).isEqualTo("database");
            assertThat(record.payload()).as("retry 要靠它还原任务").contains("FailsAlways");
        }

        @Test
        @DisplayName("★ backoff 数组比重试次数短时重复最后一个值")
        void backoffRepeatsLast() {
            // 回到第一个会让退避变成震荡,失去退避的意义
            var job = new ShouldQueue() {
                @Override public Duration[] backoff() {
                    return new Duration[] {Duration.ofSeconds(1), Duration.ofSeconds(5)};
                }
            };

            assertThat(backoffOf(job, 1)).isEqualTo(Duration.ofSeconds(1));
            assertThat(backoffOf(job, 2)).isEqualTo(Duration.ofSeconds(5));
            assertThat(backoffOf(job, 3)).isEqualTo(Duration.ofSeconds(5));
            assertThat(backoffOf(job, 9)).isEqualTo(Duration.ofSeconds(5));
        }

        /** backoffFor 是私有的 —— 通过一次真实重试的 delay 来观察它 */
        private Duration backoffOf(ShouldQueue job, int attempts) {
            Duration[] backoff = job.backoff();
            int index = Math.min(attempts - 1, backoff.length - 1);
            return backoff[Math.max(0, index)];
        }

        @Test
        @DisplayName("★ backoff 真的推后了可取时间")
        void backoffDelaysVisibility() {
            var longBackoff = new LongBackoff();
            Queue.dispatch(longBackoff);

            var w = worker(base().withMaxJobs(1).withSleep(Duration.ofMillis(5)));
            w.run();

            assertThat(w.releasedCount()).isEqualTo(1);
            assertThat(Queue.size("default")).as("还在队列里").isEqualTo(1);

            // 但现在取不到 —— backoff 把 available_at 推到了 10 分钟后
            assertThat(queues.connection().pop("default", Duration.ofSeconds(30)))
                    .isEmpty();
        }
    }

    public static class LongBackoff implements ShouldQueue {
        public LongBackoff() { }

        @Override public int tries() { return 3; }

        @Override public Duration[] backoff() {
            return new Duration[] {Duration.ofMinutes(10)};
        }

        public void handle() {
            throw new IllegalStateException("x");
        }
    }

    // ================= 不可重试的失败 =================

    @Nested
    @DisplayName("不可重试的失败")
    class Undecodables {

        @Test
        @DisplayName("★ payload 还原不了 → 直接归档,不浪费重试配额")
        void unserializableGoesStraightToFailed() {
            Queue.dispatch(new Undecodable("x"));

            // 手工把 payload 改坏,模拟「任务类改过而队列里还有旧 payload」
            connection.update(
                    "update jobs set payload = replace(payload, '\"value\":\"x\"',"
                  + " '\"goneField\":\"x\"')", List.of());

            var w = drainOnce(base());

            assertThat(LOG).as("从未执行").isEmpty();
            assertThat(w.failedCount()).isEqualTo(1);
            assertThat(failedJobs.all().get(0).exception())
                    .contains("payload 无法还原");

            // 再试一百次结果一样,所以不该占着重试配额在队列里打转
            assertThat(Queue.size("default")).isZero();
        }
    }

    // ================= 超时 =================

    @Nested
    @DisplayName("超时")
    class Timeouts {

        @Test
        @DisplayName("★ 超时的任务被判失败,并发额度被释放")
        void timesOut() {
            Queue.dispatch(new Sleeps(3000));

            var w = drainOnce(base());

            assertThat(w.timedOutCount()).isEqualTo(1);
            assertThat(w.failedCount()).isEqualTo(1);
            assertThat(w.inFlightCount()).as("额度已释放").isZero();

            assertThat(failedJobs.all().get(0).exception())
                    .as("语义限制要写在失败记录里,排查的人需要知道")
                    .contains("可能仍在后台运行");
        }

        @Test
        @DisplayName("没超时的任务正常完成")
        void withinTimeout() {
            Queue.dispatch(new Sleeps(10));

            var w = drainOnce(base());

            assertThat(LOG).containsExactly("slept:10");
            assertThat(w.timedOutCount()).isZero();
        }
    }

    // ================= 被别的 worker 接手 =================

    @Nested
    @DisplayName("★ 续约与接手")
    class Heartbeats {

        @Test
        @DisplayName("心跳续约让长任务不被判失联")
        void heartbeatKeepsReservation() throws Exception {
            var store = new DatabaseQueue(connection, serializer);
            Queue.dispatch(new Sleeps(10));

            var popped = store.pop("default", Duration.ofSeconds(30)).orElseThrow();

            // 没有续约的话,极短的 retryAfter 会让它立刻可被别人取走
            assertThat(store.pop("default", Duration.ZERO)).isPresent();

            // 续约之后,reserved_at 被推到现在
            Thread.sleep(20);
            assertThat(store.touch(popped, Duration.ofSeconds(30))).isTrue();
        }

        @Test
        @DisplayName("★ 任务已被别人接手时续约返回 false")
        void touchFailsWhenTakenOver() {
            var store = new DatabaseQueue(connection, serializer);
            Queue.dispatch(new Succeeds(1));

            var popped = store.pop("default", Duration.ofSeconds(30)).orElseThrow();

            // 模拟「别人删掉了它」(执行完了)
            store.delete(popped);

            assertThat(store.touch(popped, Duration.ofSeconds(30)))
                    .as("返回 true 会让本 worker 继续跑一条已经不属于它的任务")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 被接手的任务不会被放回队列")
        void abandonedIsNotReleased() throws Exception {
            // 这一条守住的是写第一版时的 bug:心跳中断线程产生的
            // InterruptedException 与「任务自己失败了」在 catch 里
            // 长得一样,于是会走重试路径,把一条已被接手的任务
            // 又放回队列 —— 重复执行
            var slow = new BlocksUntilReleased();
            Queue.dispatch(slow);

            var w = worker(base()
                    .withRetryAfter(Duration.ofMillis(60))   // heartbeat = 20ms
                    .withMaxJobs(1)
                    .withSleep(Duration.ofMillis(5))
                    .withShutdownGrace(Duration.ofSeconds(3)));

            var thread = w.runInBackground();

            // 等它开始跑
            assertThat(BlocksUntilReleased.STARTED[0].await(3, TimeUnit.SECONDS)).isTrue();

            // 把它从队列里删掉 —— 下一次续约会失败
            queues.connection().clear("default");

            BlocksUntilReleased.RELEASE[0].countDown();
            thread.join(10_000);

            assertThat(w.abandonedCount()).isEqualTo(1);
            assertThat(w.releasedCount()).as("不该被放回").isZero();
            assertThat(w.failedCount()).as("也不该进 failed_jobs").isZero();
        }
    }

    public static class BlocksUntilReleased implements ShouldQueue {

        /**
         * ★ 这两个 latch **不能在构造函数里重置**。
         *
         * <p>worker 反序列化任务时会再调一次构造函数,于是测试
         * {@code await} 的是被替换掉的那个旧 latch —— 永远等不到。
         * 这是写这个测试时踩的坑,记在这里免得以后有人「优化」回去。
         *
         * <p>重置放在 {@code @BeforeEach}。
         */
        static final CountDownLatch[] STARTED = {new CountDownLatch(1)};
        static final CountDownLatch[] RELEASE = {new CountDownLatch(1)};

        static void reset() {
            STARTED[0] = new CountDownLatch(1);
            RELEASE[0] = new CountDownLatch(1);
        }

        public BlocksUntilReleased() {
        }

        @Override public Duration timeout() { return Duration.ofSeconds(10); }

        @Override public int tries() { return 3; }

        @Override public Duration[] backoff() {
            return new Duration[] {Duration.ZERO};
        }

        public void handle() {
            STARTED[0].countDown();
            try {
                RELEASE[0].await(5, TimeUnit.SECONDS);
                // 再等一会,让心跳有机会发现失联
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("被中断", e);
            }
        }
    }

    // ================= 停机 =================

    @Nested
    @DisplayName("优雅停机")
    class Shutdown {

        @Test
        @DisplayName("stop 之后不再取新任务")
        void stopsTakingNewJobs() throws Exception {
            for (int i = 0; i < 20; i++) Queue.dispatch(new Succeeds(i));

            var w = worker(base().withSleep(Duration.ofMillis(5)));
            var thread = w.runInBackground();

            Thread.sleep(50);
            w.stop();
            thread.join(10_000);

            assertThat(w.isRunning()).isFalse();
            // 剩下的还在队列里,等下一个 worker
            assertThat(w.processedCount() + Queue.size("default")).isEqualTo(20);
        }

        @Test
        @DisplayName("停机时等在跑的任务完成")
        void waitsForInFlight() throws Exception {
            Queue.dispatch(new Sleeps(150));

            var w = worker(base()
                    .withSleep(Duration.ofMillis(5))
                    .withShutdownGrace(Duration.ofSeconds(5)));
            var thread = w.runInBackground();

            Thread.sleep(60);
            w.stop();
            thread.join(10_000);

            assertThat(LOG).as("它应该跑完了").containsExactly("slept:150");
            assertThat(w.inFlightCount()).isZero();
        }

        @Test
        @DisplayName("重复 run 直接报错")
        void cannotRunTwice() throws Exception {
            var w = worker(base().withStopWhenEmpty(true).withSleep(Duration.ofMillis(5)));
            w.run();

            // 第一次已经结束了,可以再跑 —— 这里测的是并发 run
            var blocking = worker(base().withSleep(Duration.ofMillis(5)));
            var thread = blocking.runInBackground();
            Thread.sleep(50);

            assertThatThrownBy(blocking::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已经在跑");

            blocking.stop();
            thread.join(5000);
        }
    }

    // ================= 失败任务的管理 =================

    @Nested
    @DisplayName("failed_jobs 的管理")
    class FailedJobs {

        @Test
        @DisplayName("列出、查找、删除、清空")
        void crud() {
            Queue.dispatch(new FailsAlways(1));
            drainOnce(base());

            var all = failedJobs.all();
            assertThat(all).hasSize(1);

            long id = all.get(0).id();
            assertThat(failedJobs.find(id)).isPresent();
            assertThat(failedJobs.forget(id)).isTrue();
            assertThat(failedJobs.count()).isZero();
            assertThat(failedJobs.forget(id)).as("删第二次返回 false").isFalse();
        }

        @Test
        @DisplayName("★ 同一个 uuid 的失败覆盖而不是追加")
        void sameUuidOverwrites() {
            var envelope = QueuedJob.of(new Succeeds(1), "default",
                    serializer.toData(new Succeeds(1)),
                    java.time.Instant.now(), null);

            long first = failedJobs.record(envelope, "database",
                    new IllegalStateException("第一次"));
            long second = failedJobs.record(envelope, "database",
                    new IllegalStateException("第二次"));

            // 追加会让 failed_jobs 里同一个 uuid 出现十几条,
            // 于是 queue:retry 不知道该重试哪一条
            assertThat(second).isEqualTo(first);
            assertThat(failedJobs.count()).isEqualTo(1);
            assertThat(failedJobs.find(first).orElseThrow().exception())
                    .contains("第二次");
        }

        @Test
        @DisplayName("prune 清理旧记录")
        void prune() {
            Queue.dispatch(new FailsAlways(1));
            drainOnce(base());

            assertThat(failedJobs.prune(Duration.ofDays(7)))
                    .as("刚失败的不该被清掉").isZero();
            assertThat(failedJobs.prune(Duration.ZERO)).isEqualTo(1);
        }

        @Test
        @DisplayName("flush 全删")
        void flush() {
            Queue.dispatch(new FailsAlways(1));
            Queue.dispatch(new FailsAlways(2));
            drainOnce(base());

            assertThat(failedJobs.count()).isEqualTo(2);
            assertThat(failedJobs.flush()).isEqualTo(2);
        }
    }

    // ================= 任务作用域 =================

    @Nested
    @DisplayName("任务作用域")
    class Scope {

        @Test
        @DisplayName("★ 任务里没有请求作用域 —— 请求级门面要报错而不是返回 null")
        void noRequestScopeInJobs() {
            Queue.dispatch(new ChecksScope());
            drainOnce(base());

            // 这正是 docs/02 要消除的那个 Laravel 经典坑:
            // 队列任务里 auth()->user() 莫名为空
            assertThat(LOG).containsExactly("scope:queue", "request:absent");
        }
    }

    public static class ChecksScope implements ShouldQueue {
        public ChecksScope() { }

        public void handle() {
            LOG.add("scope:" + io.jaravel.core.context.ExecutionScope.current().kind());
            LOG.add("request:" + (io.jaravel.http.RequestScope.isActive()
                    ? "present" : "absent"));
        }
    }

    // ================= 工具 =================

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
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() { return Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) { return iface.cast(proxy); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
