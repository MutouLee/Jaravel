package io.jaravel.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.cache.CacheManager;
import io.jaravel.cache.RateLimiter;
import io.jaravel.cache.Repository;
import io.jaravel.cache.store.ArrayStore;
import io.jaravel.core.Application;
import io.jaravel.core.container.SimpleContainer;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.queue.middleware.RateLimited;
import io.jaravel.queue.middleware.ThrottlesExceptions;
import io.jaravel.queue.middleware.WithoutOverlapping;
import io.jaravel.queue.store.DatabaseFailedJobStore;
import io.jaravel.queue.store.DatabaseQueue;

/** 任务中间件与唯一性任务 */
class JobMiddlewareTest {

    static final ConcurrentLinkedQueue<String> LOG = new ConcurrentLinkedQueue<>();

    /**
     * 静态的 —— 任务的 {@code middleware()} 在**反序列化出来的实例**上调用,
     * 那个实例拿不到测试的字段。
     *
     * <p>这正是 {@link ShouldQueue#middleware()} 注释里说的那个粗糙点:
     * 中间件的依赖没有注入通道。测试里用静态字段绕过,
     * 而生产代码只能用门面 —— 列入 v0.5 重新设计。
     */
    static Repository CACHE;
    static RateLimiter LIMITER;

    private java.sql.Connection jdbc;
    private io.jaravel.database.Connection connection;
    private QueueManager queues;
    private DatabaseFailedJobStore failedJobs;

    @BeforeEach
    void setUp() throws SQLException {
        LOG.clear();

        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
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

        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        failedJobs = new DatabaseFailedJobStore(connection);

        CACHE = new CacheManager().register("array", new ArrayStore())
                .defaultStore("array").store();
        LIMITER = new RateLimiter(CACHE);

        var container = new SimpleContainer();
        container.instance(io.jaravel.database.Connection.class, connection);
        Application.setContainer(container);

        var serializer = new JobSerializer();
        queues = new QueueManager(serializer)
                .register("database", m -> new DatabaseQueue(connection, serializer))
                .defaultConnection("database")
                .defaultQueue("default")
                .afterCommitByDefault(false)
                .uniqueLocks(CACHE);
        Queue.use(queues);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Queue.reset();
        Application.reset();
        if (jdbc != null) jdbc.close();
    }

    private QueueWorker drain(int maxJobs) {
        var w = new QueueWorker(queues, failedJobs, new JobInvoker(Application::make),
                WorkerOptions.onQueues("default")
                        .withConcurrency(1)
                        .withRetryAfter(Duration.ofSeconds(30))
                        .withSleep(Duration.ofMillis(5))
                        .withStopWhenEmpty(true)
                        .withMaxJobs(maxJobs));
        w.run();
        return w;
    }

    // ================= 测试任务 =================

    public static class Guarded implements ShouldQueue {
        private final String key;

        public Guarded(String key) {
            this.key = key;
        }

        @Override public int tries() { return 5; }

        @Override public Duration[] backoff() { return new Duration[] {Duration.ZERO}; }

        @Override public List<JobMiddleware> middleware() {
            return List.of(new WithoutOverlapping(CACHE, key)
                    .releaseAfter(Duration.ZERO));
        }

        public void handle() {
            LOG.add("guarded:" + key);
        }
    }

    public static class FailsUnderLock implements ShouldQueue {
        private final String key;

        public FailsUnderLock(String key) {
            this.key = key;
        }

        @Override public int tries() { return 1; }

        @Override public List<JobMiddleware> middleware() {
            return List.of(new WithoutOverlapping(CACHE, key));
        }

        public void handle() {
            throw new IllegalStateException("炸了");
        }
    }

    public static class SkipsWhenLocked implements ShouldQueue {
        private final String key;

        public SkipsWhenLocked(String key) {
            this.key = key;
        }

        @Override public List<JobMiddleware> middleware() {
            return List.of(new WithoutOverlapping(CACHE, key).dontRelease());
        }

        public void handle() {
            LOG.add("skipped-run:" + key);
        }
    }

    public static class Limited implements ShouldQueue {
        private final int n;

        public Limited(int n) {
            this.n = n;
        }

        @Override public int tries() { return 5; }

        @Override public Duration[] backoff() { return new Duration[] {Duration.ZERO}; }

        @Override public List<JobMiddleware> middleware() {
            return List.of(new RateLimited(LIMITER, "api", 2, Duration.ofMinutes(1))
                    .withJitter(Duration.ZERO));
        }

        public void handle() {
            LOG.add("limited:" + n);
        }
    }

    public static class Fragile implements ShouldQueue {
        private final int n;

        public Fragile(int n) {
            this.n = n;
        }

        @Override public int tries() { return 2; }

        @Override public Duration[] backoff() { return new Duration[] {Duration.ZERO}; }

        @Override public List<JobMiddleware> middleware() {
            return List.of(new ThrottlesExceptions(LIMITER, "downstream", 2,
                    Duration.ofMinutes(5)).releaseAfter(Duration.ofMinutes(1)));
        }

        public void handle() {
            LOG.add("tried:" + n);
            throw new IllegalStateException("下游挂了");
        }
    }

    /** 中间件既不放行也不决定结果 */
    public static class Broken implements ShouldQueue {
        public Broken() { }

        @Override public List<JobMiddleware> middleware() {
            return List.of(new JobMiddleware() {
                @Override public void handle(Context context, Runnable next) {
                    // 什么都不做
                }
            });
        }

        public void handle() {
            LOG.add("never");
        }
    }

    public static class OnlyOne implements ShouldQueue, UniqueJob {
        private final Long orderId;

        public OnlyOne(Long orderId) {
            this.orderId = orderId;
        }

        @Override public String uniqueId() { return String.valueOf(orderId); }

        @Override public Duration uniqueFor() { return Duration.ofMinutes(10); }

        public void handle() {
            LOG.add("once:" + orderId);
        }
    }

    public static class OnlyOneQueued implements ShouldQueue, UniqueJob {
        private final Long id;

        public OnlyOneQueued(Long id) {
            this.id = id;
        }

        @Override public String uniqueId() { return String.valueOf(id); }

        @Override public boolean uniqueUntilProcessing() { return true; }

        public void handle() {
            LOG.add("queued-once:" + id);
        }
    }

    // ================= WithoutOverlapping =================

    @Nested
    @DisplayName("WithoutOverlapping")
    class Overlapping {

        @Test
        @DisplayName("拿到锁就正常执行")
        void runsWhenFree() {
            Queue.dispatch(new Guarded("a"));
            drain(1);

            assertThat(LOG).containsExactly("guarded:a");
        }

        @Test
        @DisplayName("★ 锁被占时 release 回队列,不阻塞 worker")
        void releasesWhenLocked() {
            // 外部先占住锁
            assertThat(CACHE.lock("overlap:a", Duration.ofMinutes(5)).get()).isTrue();

            Queue.dispatch(new Guarded("a"));
            var w = drain(1);

            // 阻塞等待会占着一个并发额度干等,而那个额度本可以跑别的任务
            assertThat(LOG).isEmpty();
            assertThat(w.releasedCount()).isEqualTo(1);
            assertThat(Queue.size("default")).as("还在队列里等").isEqualTo(1);
        }

        @Test
        @DisplayName("★ 任务抛异常时锁也要释放")
        void releasesLockOnFailure() {
            Queue.dispatch(new FailsUnderLock("b"));
            drain(3);

            // 不释放的话这个 key 会被挡到锁 TTL 过期为止,
            // 而那期间的重试全部被 release 掉,看起来像「任务卡住了」
            assertThat(CACHE.lock("overlap:b", Duration.ofMinutes(5)).isHeld())
                    .isFalse();
        }

        @Test
        @DisplayName("dontRelease 时丢弃而不是重排")
        void discardsWhenConfigured() {
            CACHE.lock("overlap:c", Duration.ofMinutes(5)).get();

            Queue.dispatch(new SkipsWhenLocked("c"));
            var w = drain(1);

            assertThat(LOG).isEmpty();
            assertThat(w.releasedCount()).isZero();
            assertThat(Queue.size("default")).as("被丢弃了").isZero();
            assertThat(w.failedCount()).as("丢弃不算失败").isZero();
        }

        @Test
        @DisplayName("★ 锁的 TTL 不能是零或负 —— 没有 TTL 的锁会永久挡住 key")
        void ttlMustBePositive() {
            assertThatThrownBy(() ->
                    new WithoutOverlapping(CACHE, "x").expireAfter(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("永久");
        }
    }

    // ================= RateLimited =================

    @Nested
    @DisplayName("RateLimited")
    class Limiting {

        @Test
        @DisplayName("★ 限额内放行,超限后 release")
        void limitsThroughput() {
            for (int i = 1; i <= 4; i++) Queue.dispatch(new Limited(i));

            drain(10);

            // 限 2 次/分钟 —— 前两个跑掉,后两个被放回
            assertThat(LOG).hasSize(2);
            assertThat(Queue.size("default")).isEqualTo(2);
        }

        @Test
        @DisplayName("★ 超限不计入失败 —— 它们只是「来早了」")
        void overLimitIsNotFailure() {
            for (int i = 1; i <= 4; i++) Queue.dispatch(new Limited(i));

            var w = drain(10);

            // 被限流的任务进 failed_jobs 是错的:一次突发流量能把
            // 几百个任务推进失败表,而它们其实没有任何问题
            assertThat(w.failedCount()).isZero();
            assertThat(failedJobs.count()).isZero();
            assertThat(w.releasedCount()).isEqualTo(2);
        }
    }

    // ================= ThrottlesExceptions =================

    @Nested
    @DisplayName("ThrottlesExceptions")
    class Throttling {

        @Test
        @DisplayName("★ 连续失败到阈值后熔断,后续任务不再碰下游")
        void opensCircuit() {
            for (int i = 1; i <= 5; i++) Queue.dispatch(new Fragile(i));

            drain(20);

            // 阈值 2。只靠 tries 的话 5 个任务会打 10 次请求到一个
            // 已经挂掉的服务 —— 既拖慢自己的恢复,也妨碍对方恢复
            assertThat(LOG.size())
                    .as("实际执行次数应明显少于 5 个任务 × 2 次重试")
                    .isLessThan(10);
            assertThat(Queue.size("default")).as("剩下的在等熔断窗口").isPositive();
        }

        @Test
        @DisplayName("★ 只数异常,不数成功")
        void countsOnlyExceptions() {
            var throttle = new ThrottlesExceptions(LIMITER, "x", 3,
                    Duration.ofMinutes(1));

            // 成功的任务不该推高熔断计数 —— 否则它就变成了限流器
            JobMiddleware.run(List.of(throttle), fakeContext(), () -> { });
            assertThat(throttle.failures()).isZero();

            assertThatThrownBy(() -> JobMiddleware.run(List.of(throttle), fakeContext(),
                    () -> {
                        throw new IllegalStateException("x");
                    })).isInstanceOf(IllegalStateException.class);
            assertThat(throttle.failures()).isEqualTo(1);
        }

        @Test
        @DisplayName("reset 手工复位 —— 下游确认恢复后不必等窗口过期")
        void reset() {
            var throttle = new ThrottlesExceptions(LIMITER, "y", 1, Duration.ofMinutes(1));

            assertThatThrownBy(() -> JobMiddleware.run(List.of(throttle), fakeContext(),
                    () -> {
                        throw new IllegalStateException("x");
                    })).isInstanceOf(IllegalStateException.class);
            assertThat(throttle.failures()).isEqualTo(1);

            throttle.reset();
            assertThat(throttle.failures()).isZero();
        }

        private JobMiddleware.Context fakeContext() {
            return new JobMiddleware.Context() {
                @Override public ShouldQueue job() { return new Fragile(1); }

                @Override public QueuedJob envelope() {
                    return QueuedJob.of(job(), "default", java.util.Map.of(),
                            java.time.Instant.now(), null);
                }

                @Override public void release(Duration delay) { }

                @Override public void delete(String reason) { }

                @Override public boolean isResolved() { return false; }
            };
        }
    }

    // ================= 中间件的误用 =================

    @Nested
    @DisplayName("中间件的误用")
    class Misuse {

        @Test
        @DisplayName("★ 既不放行也不决定结果 → 报错,不让任务静默消失")
        void mustResolveOrPass() {
            Queue.dispatch(new Broken());
            var w = drain(3);

            // 任务已经被 pop 出来了,中间件什么都不做等于它静默消失 ——
            // 那是队列系统里最难查的一类问题
            assertThat(LOG).isEmpty();
            assertThat(w.failedCount()).isEqualTo(1);
            assertThat(failedJobs.all().get(0).exception())
                    .contains("既没有放行也没有决定结果");
        }
    }

    // ================= 唯一性 =================

    @Nested
    @DisplayName("唯一性任务")
    class Unique {

        @Test
        @DisplayName("★ 同 key 的第二次投递被丢弃")
        void secondDispatchIsDiscarded() {
            String first = Queue.dispatch(new OnlyOne(42L));
            String second = Queue.dispatch(new OnlyOne(42L));

            assertThat(first).isNotNull();
            assertThat(second).as("返回 null 表示被去重丢弃了").isNull();
            assertThat(Queue.size("default")).isEqualTo(1);
        }

        @Test
        @DisplayName("不同 key 互不影响")
        void differentKeys() {
            Queue.dispatch(new OnlyOne(1L));
            Queue.dispatch(new OnlyOne(2L));

            assertThat(Queue.size("default")).isEqualTo(2);
        }

        @Test
        @DisplayName("★ 任务执行完后锁释放,可以再投")
        void lockIsReleasedAfterRun() {
            Queue.dispatch(new OnlyOne(7L));
            drain(1);

            assertThat(LOG).containsExactly("once:7");

            // 不释放的话这个 key 要被挡 10 分钟(uniqueFor)
            assertThat(Queue.dispatch(new OnlyOne(7L))).isNotNull();
        }

        @Test
        @DisplayName("★ uniqueUntilProcessing:开始跑就放开排队位")
        void releasesWhenProcessingStarts() {
            Queue.dispatch(new OnlyOneQueued(1L));
            assertThat(Queue.dispatch(new OnlyOneQueued(1L)))
                    .as("排队中,挡住").isNull();

            drain(1);
            assertThat(LOG).containsExactly("queued-once:1");

            assertThat(Queue.dispatch(new OnlyOneQueued(1L)))
                    .as("已经开始跑了,新的可以排进来").isNotNull();
        }

        @Test
        @DisplayName("★ 没配锁存储时报错,不静默跳过去重")
        void requiresLockStore() {
            var serializer = new JobSerializer();
            var noLocks = new QueueManager(serializer)
                    .register("database", m -> new DatabaseQueue(connection, serializer))
                    .defaultConnection("database")
                    .afterCommitByDefault(false);
            Queue.use(noLocks);

            // 静默跳过的症状是「队列里有一堆重复任务」,
            // 很难联想到缓存没配
            assertThatThrownBy(() -> Queue.dispatch(new OnlyOne(1L)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("uniqueLocks");
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
