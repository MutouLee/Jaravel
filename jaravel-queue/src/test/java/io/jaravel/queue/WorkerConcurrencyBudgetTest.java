package io.jaravel.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.core.Application;
import io.jaravel.core.container.SimpleContainer;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.queue.store.DatabaseFailedJobStore;
import io.jaravel.queue.store.DatabaseQueue;

/**
 * ★ v0.4 验收第 2 条:**高并发下 worker 的内存增长**。
 *
 * <p>docs/17 的原文是「{@code --concurrency=200} 下 worker 内存增长
 * < 100MB(验证虚拟线程优势)」。
 *
 * <h2>为什么要有这个测试</h2>
 * <p>在它之前,这条验收**没有任何东西证明**。最深的并发测试是
 * 50 个任务、并发 10,而「虚拟线程下 N 个并发几乎零成本」这句话
 * 只写在一句注释里。
 *
 * <p>一句没被测过的性能断言与一句错的性能断言在代码里长得一模一样,
 * 而这一条恰好是 Jaravel 相对 PHP 的核心卖点 ——
 * 它要么被证实,要么被推翻,不该停在注释阶段。
 *
 * <h2>⚠ 这个测试测的不是「200 个平台线程要多少内存」</h2>
 * <p>它测的是:**200 个并发槽位 + 400 个任务,其中每个任务都在阻塞**,
 * 整个过程的堆增长。这是虚拟线程真正要解决的形状 ——
 * 队列任务绝大多数时间卡在 IO(发邮件、调支付网关)上。
 *
 * <p>平台线程的成本主要在**栈**(默认 1 MB 虚拟内存预留,
 * 实际触碰的物理页少得多),那部分不在 Java 堆里,
 * {@code Runtime.totalMemory - freeMemory} 量不到。
 * 所以这个测试**不是**「虚拟线程 vs 平台线程」的对照实验 ——
 * 那需要 OS 级的 RSS 测量,而那在一个单元测试里做不可靠。
 *
 * <p>它能证明的是文档那条验收字面上的东西:
 * **并发开到 200 时,worker 自己不会把堆吃掉**
 * (比如为每个槽位缓存一份任务状态、或者把整个队列读进内存)。
 * 这个限定写在这里,免得这条「通过」被当成比它实际更强的结论。
 */
class WorkerConcurrencyBudgetTest {

    /** docs/17 验收第 2 条里的那个数字 */
    private static final int CONCURRENCY = 200;

    /** 任务数要明显多于并发数,否则测不到「槽位复用」那段 */
    private static final int JOB_COUNT = 400;

    /** 验收标准:100 MB */
    private static final long MEMORY_BUDGET_BYTES = 100L * 1024 * 1024;

    static final AtomicInteger RAN = new AtomicInteger();
    static CountDownLatch gate;

    /**
     * 一个**阻塞**的任务。
     *
     * <p>必须阻塞 —— 一个瞬间返回的任务会让 200 个槽位里
     * 永远只有几个同时活着,于是这个测试什么都没测。
     *
     * <p>阻塞在一个 latch 上而不是 sleep:sleep 的时长要么太短
     * (测不到并发峰值)要么太长(测试变慢),而 latch 能让
     * **所有** 200 个槽位都真的同时挂着。
     */
    public static class Blocks implements ShouldQueue {

        public void handle() {
            RAN.incrementAndGet();
            try {
                // 等到主线程确认峰值已经形成
                if (!gate.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("闸门没开 —— 测试本身卡住了");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ================= 装配 =================

    private static java.sql.Connection sqlite;
    private QueueManager queues;
    private DatabaseFailedJobStore failedJobs;

    @BeforeEach
    void setUp() throws SQLException {
        RAN.set(0);
        gate = new CountDownLatch(1);

        sqlite = DriverManager.getConnection(
                "jdbc:sqlite:file:concbudget?mode=memory&cache=shared");
        try (Statement st = sqlite.createStatement()) {
            st.execute("drop table if exists jobs");
            st.execute("drop table if exists failed_jobs");
            st.execute("""
                    create table jobs (
                      id integer primary key autoincrement,
                      uuid varchar(36) not null,
                      queue varchar(255) not null,
                      payload text not null,
                      attempts integer not null default 0,
                      reserved_at bigint,
                      available_at bigint not null,
                      created_at bigint not null,
                      version integer not null default 0
                    )""");
            st.execute("""
                    create table failed_jobs (
                      id integer primary key autoincrement,
                      uuid varchar(36) not null,
                      connection varchar(255) not null,
                      queue varchar(255) not null,
                      payload text not null,
                      exception text not null,
                      failed_at bigint not null
                    )""");
        }

        var connection = new JdbcConnection(nonClosing(), new SqliteGrammar());
        var serializer = new JobSerializer();
        failedJobs = new DatabaseFailedJobStore(connection);

        var container = new SimpleContainer();
        container.instance(io.jaravel.database.Connection.class, connection);
        Application.setContainer(container);

        queues = new QueueManager(serializer)
                .register("database", m -> new DatabaseQueue(connection, serializer))
                .defaultConnection("database")
                .defaultQueue("default")
                .afterCommitByDefault(false);
        Queue.use(queues);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (gate != null) gate.countDown();
        Queue.reset();
        if (sqlite != null && !sqlite.isClosed()) sqlite.close();
    }

    // ================= 验收 =================

    @Test
    @DisplayName("★ concurrency=200、400 个阻塞任务:堆增长在预算内且全部跑完")
    void memoryStaysWithinBudget() throws Exception {
        for (int i = 0; i < JOB_COUNT; i++) Queue.dispatch(new Blocks());

        long baseline = usedHeapAfterGc();

        var options = WorkerOptions.onQueues("default")
                .withConcurrency(CONCURRENCY)
                .withRetryAfter(Duration.ofMinutes(5))
                .withShutdownGrace(Duration.ofSeconds(30))
                .withStopWhenEmpty(true)
                .withSleep(Duration.ofMillis(5));

        var worker = new QueueWorker(queues, failedJobs,
                new JobInvoker(Application::make), options);

        var workerThread = Thread.ofPlatform().name("budget-worker").start(worker::run);

        // 等到并发峰值真的形成 —— 否则后面量的是一个空闸门
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (RAN.get() < CONCURRENCY && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        int peak = RAN.get();
        long atPeak = usedHeapAfterGc();

        // 放闸,让所有任务跑完
        gate.countDown();
        workerThread.join(TimeUnit.SECONDS.toMillis(120));

        long growth = atPeak - baseline;

        assertThat(peak)
                .as("""
                        并发峰值没达到 %d —— 这个测试就没测到它要测的东西。
                          实际同时在跑:%d
                          (可能原因:槽位没真的并发、或者任务没阻塞住)""",
                        CONCURRENCY, peak)
                .isGreaterThanOrEqualTo(CONCURRENCY);

        assertThat(growth)
                .as("""
                        ★ v0.4 验收第 2 条:并发 %d 时堆增长应 < 100 MB。
                          实测增长:%.1f MB(基线 %.1f MB → 峰值 %.1f MB)

                          ⚠ 量的是 Java 堆,不是 RSS —— 平台线程的栈不在堆里。
                          所以这一条证明的是「worker 自己不会把堆吃掉」,
                          不是「虚拟线程比平台线程省内存」。见类注释。""",
                        CONCURRENCY,
                        growth / 1048576.0,
                        baseline / 1048576.0,
                        atPeak / 1048576.0)
                .isLessThan(MEMORY_BUDGET_BYTES);

        assertThat(RAN.get())
                .as("400 个任务都要跑到 —— 内存达标但丢任务不算通过")
                .isEqualTo(JOB_COUNT);
        assertThat(worker.succeededCount()).isEqualTo(JOB_COUNT);
        assertThat(Queue.size("default")).isZero();

        System.out.printf(
                "[验收 2] concurrency=%d, jobs=%d, 峰值并发=%d, 堆增长=%.1f MB%n",
                CONCURRENCY, JOB_COUNT, peak, growth / 1048576.0);
    }

    /**
     * 测堆用量。
     *
     * <p>{@code System.gc()} 不保证执行,所以连着来两轮并给一点时间 ——
     * 一个不稳的基线会让这个测试时而通过时而失败,而
     * **flaky 的性能测试比没有更糟**:它会训练人去忽略红色。
     *
     * <p>这也是为什么预算定在 100 MB 而不是贴着实测值:
     * 这个数字要容得下 GC 的抖动与不同 JVM 的差异。
     * 它来自 docs/17,不是反过来按实测值凑的。
     */
    private static long usedHeapAfterGc() {
        var runtime = Runtime.getRuntime();
        for (int i = 0; i < 2; i++) {
            System.gc();
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static DataSource nonClosing() {
        return new DataSource() {
            @Override
            public java.sql.Connection getConnection() {
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                        WorkerConcurrencyBudgetTest.class.getClassLoader(),
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
