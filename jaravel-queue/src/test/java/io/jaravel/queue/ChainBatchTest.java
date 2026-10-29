package io.jaravel.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

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
import io.jaravel.queue.store.DatabaseBatchRepository;
import io.jaravel.queue.store.DatabaseFailedJobStore;
import io.jaravel.queue.store.DatabaseQueue;

/**
 * 链式任务与批量任务。
 *
 * <p>这些测试刻意**跑真的 Worker + 真的 SQLite**,不 mock 存储。
 * 理由:链与批的全部难点都在「Worker 与存储之间的接线」上 ——
 * 计数是否原子、回调是否只投一次、取消是否真的跳过任务。
 * 对 BatchRepository 打桩的测试会把这些全部测成绿色。
 */
class ChainBatchTest {

    static final ConcurrentLinkedQueue<String> LOG = new ConcurrentLinkedQueue<>();

    private java.sql.Connection jdbc;
    private io.jaravel.database.Connection connection;
    private QueueManager queues;
    private DatabaseFailedJobStore failedJobs;
    private DatabaseBatchRepository batches;

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
            st.execute("""
                    create table job_batches (
                        id text primary key, name text null,
                        total_jobs integer not null, pending_jobs integer not null,
                        failed_jobs integer not null default 0,
                        failed_job_ids text not null default '',
                        allows_failures integer not null default 0,
                        then_callback text null, catch_callback text null,
                        finally_callback text null,
                        created_at text not null, cancelled_at text null,
                        finished_at text null)
                    """);
        }

        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        failedJobs = new DatabaseFailedJobStore(connection);

        var container = new SimpleContainer();
        container.instance(io.jaravel.database.Connection.class, connection);
        Application.setContainer(container);

        var serializer = new JobSerializer();
        batches = new DatabaseBatchRepository(connection, serializer);
        queues = new QueueManager(serializer)
                .register("database", m -> new DatabaseQueue(connection, serializer))
                .defaultConnection("database")
                .defaultQueue("default")
                .afterCommitByDefault(false)
                .batches(batches);
        Queue.use(queues);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Queue.reset();
        Application.reset();
        if (jdbc != null) jdbc.close();
    }

    /** 跑到队列空为止 */
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

    public static class Step implements ShouldQueue {
        private final String label;

        public Step(String label) {
            this.label = label;
        }

        public void handle() {
            LOG.add("step:" + label);
        }
    }

    public static class FailingStep implements ShouldQueue {
        private final String label;

        public FailingStep(String label) {
            this.label = label;
        }

        @Override public int tries() { return 1; }

        public void handle() {
            LOG.add("fail:" + label);
            throw new IllegalStateException("第 " + label + " 环炸了");
        }
    }

    /** 链中断回调 —— 通过注入参数拿到失败上下文 */
    public static class OnBroken implements ShouldQueue {
        private final String tag;

        public OnBroken(String tag) {
            this.tag = tag;
        }

        public void handle(ChainFailure failure) {
            LOG.add("broken:" + tag + ":" + failure.jobName() + ":" + failure.message());
        }
    }

    /** 批次回调 —— 通过注入参数拿到批次 */
    public static class OnBatchDone implements ShouldQueue {
        private final String tag;

        public OnBatchDone(String tag) {
            this.tag = tag;
        }

        public void handle(Batch batch) {
            LOG.add("%s:%d/%d:失败%d:完成%s".formatted(
                    tag, batch.processedJobs(), batch.totalJobs(),
                    batch.failedJobs(), batch.isFinished()));
        }
    }

    // ================= 链 =================

    @Nested
    @DisplayName("链式任务")
    class Chaining {

        @Test
        @DisplayName("★ 按顺序执行,前一个成功才投下一个")
        void runsInOrder() {
            Queue.chain(new Step("a"), new Step("b"), new Step("c")).dispatch();

            // 投递后队列里**只有一条** —— 后面两环在信封里
            assertThat(Queue.size("default"))
                    .as("整条链入队时只占一条记录")
                    .isEqualTo(1);

            drain(10);

            assertThat(LOG).containsExactly("step:a", "step:b", "step:c");
            assertThat(Queue.size("default")).isZero();
        }

        @Test
        @DisplayName("★ 某一环失败后,后续各环不执行")
        void stopsAtFailure() {
            Queue.chain(new Step("a"), new FailingStep("b"), new Step("c")).dispatch();

            drain(10);

            assertThat(LOG)
                    .as("c 不该执行 —— 链的语义是前一个成功才继续")
                    .containsExactly("step:a", "fail:b")
                    .doesNotContain("step:c");
        }

        @Test
        @DisplayName("★ 链断时投 catch 回调,并带上失败上下文")
        void dispatchesCatchCallback() {
            Queue.chain(new Step("a"), new FailingStep("b"), new Step("c"))
                    .catchWith(new OnBroken("x"))
                    .dispatch();

            drain(10);

            assertThat(LOG).contains("step:a", "fail:b");
            assertThat(LOG)
                    .as("回调要能拿到断在哪一环、什么异常")
                    .anyMatch(line -> line.startsWith("broken:x:FailingStep:第 b 环炸了"));
        }

        @Test
        @DisplayName("失败的那一环进 failed_jobs")
        void brokenLinkIsArchived() {
            Queue.chain(new Step("a"), new FailingStep("b")).dispatch();

            drain(10);

            assertThat(connection.table("failed_jobs").count())
                    .as("断掉的那一环要能在 failed_jobs 里查到完整堆栈")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("没有 catch 回调时链安静地断掉,不报错")
        void noCatchIsFine() {
            Queue.chain(new Step("a"), new FailingStep("b")).dispatch();

            drain(10);

            assertThat(LOG).containsExactly("step:a", "fail:b");
            assertThat(Queue.size("default")).isZero();
        }

        @Test
        @DisplayName("★ 整条链只延迟一次,不是每环都延迟")
        void delayAppliesOnceToTheHead() {
            Queue.chain(new Step("a"), new Step("b"))
                    .delay(Duration.ofSeconds(30))
                    .dispatch();

            // 队首被推后了 —— 现在取不到
            drain(10);
            assertThat(LOG).as("队首还没到可取时间").isEmpty();

            // 把队首的可取时间拨到过去,模拟 30 秒后
            connection.update("update jobs set available_at = ?",
                    List.of(java.time.Instant.now().minusSeconds(1)));

            drain(10);
            assertThat(LOG)
                    .as("第二环不该再等 30 秒 —— 延迟是整条链的,不是每环的")
                    .containsExactly("step:a", "step:b");
        }

        @Test
        @DisplayName("空链报错,不静默什么都不做")
        void emptyChainFails() {
            assertThatThrownBy(() -> Queue.chain(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("空");
        }

        @Test
        @DisplayName("onQueue 覆盖链上每一环的队列")
        void onQueueAppliesToWholeChain() {
            Queue.chain(new Step("a"), new Step("b")).onQueue("orders").dispatch();

            assertThat(Queue.size("orders")).isEqualTo(1);
            assertThat(Queue.size("default")).isZero();

            var w = new QueueWorker(queues, failedJobs, new JobInvoker(Application::make),
                    WorkerOptions.onQueues("orders")
                            .withConcurrency(1).withSleep(Duration.ofMillis(5))
                            .withStopWhenEmpty(true).withMaxJobs(10));
            w.run();

            assertThat(LOG).containsExactly("step:a", "step:b");
        }

        @Test
        @DisplayName("链上某环重试后成功,链继续")
        void retryThenContinue() {
            Queue.chain(new Step("a"), new FlakyOnce("b"), new Step("c")).dispatch();

            drain(20);

            assertThat(LOG)
                    .as("重试中的失败不算链中断 —— 那时链还有机会继续")
                    .containsExactly("step:a", "flaky-fail:b", "flaky-ok:b", "step:c");
        }
    }

    /** 第一次失败、第二次成功 */
    public static class FlakyOnce implements ShouldQueue {
        private static final java.util.Set<String> SEEN =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        private final String label;

        public FlakyOnce(String label) {
            this.label = label;
        }

        @Override public int tries() { return 3; }

        @Override public Duration[] backoff() { return new Duration[] {Duration.ZERO}; }

        public void handle() {
            if (SEEN.add(label)) {
                LOG.add("flaky-fail:" + label);
                throw new IllegalStateException("第一次总是失败");
            }
            LOG.add("flaky-ok:" + label);
        }

        static void reset() {
            SEEN.clear();
        }
    }

    // ================= 批 =================

    @Nested
    @DisplayName("批量任务")
    class Batching {

        @Test
        @DisplayName("★ 全部完成后投 then 回调,且回调能拿到批次")
        void thenCallbackReceivesBatch() {
            Batch batch = Queue.batch(new Step("1"), new Step("2"), new Step("3"))
                    .name("报表")
                    .then(new OnBatchDone("then"))
                    .dispatch();

            assertThat(batch.totalJobs()).isEqualTo(3);
            assertThat(batch.pendingJobs()).isEqualTo(3);
            assertThat(batch.progress()).isZero();

            drain(20);

            assertThat(LOG).contains("step:1", "step:2", "step:3");
            assertThat(LOG)
                    .as("回调要能看到一个**已完成**的批次 —— "
                            + "isFinished 为 false 读起来像 bug")
                    .contains("then:3/3:失败0:完成true");

            Batch done = Queue.findBatch(batch.id()).orElseThrow();
            assertThat(done.isFinished()).isTrue();
            assertThat(done.progress()).isEqualTo(1.0);
            assertThat(done.succeededJobs()).isEqualTo(3);
        }

        @Test
        @DisplayName("★ 回调不计入批次计数 —— 否则进度会超过 100%")
        void callbackIsNotCounted() {
            Batch batch = Queue.batch(new Step("1"))
                    .then(new OnBatchDone("then"))
                    .finallyRun(new OnBatchDone("finally"))
                    .dispatch();

            drain(20);

            Batch done = Queue.findBatch(batch.id()).orElseThrow();
            assertThat(done.totalJobs()).isEqualTo(1);
            assertThat(done.pendingJobs())
                    .as("两个回调执行后 pending 不该被减成负数")
                    .isZero();
            assertThat(done.progress()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("★ then 与 finally 都只投一次")
        void callbacksRunExactlyOnce() {
            Queue.batch(new Step("1"), new Step("2"))
                    .then(new OnBatchDone("then"))
                    .finallyRun(new OnBatchDone("finally"))
                    .dispatch();

            drain(20);

            assertThat(LOG.stream().filter(l -> l.startsWith("then:")).count())
                    .as("「给所有人发汇总邮件」的回调跑两遍是用户可见的事故")
                    .isEqualTo(1);
            assertThat(LOG.stream().filter(l -> l.startsWith("finally:")).count())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ 第一个失败就取消剩余的(allowFailures 默认关)")
        void firstFailureCancelsTheRest() {
            Batch batch = Queue.batch(
                            new FailingStep("1"), new Step("2"), new Step("3"))
                    .dispatch();

            drain(20);

            assertThat(LOG).contains("fail:1");
            assertThat(LOG)
                    .as("默认行为是「第一个失败说明是共因,别把同一个错重复 N 次」")
                    .doesNotContain("step:2", "step:3");

            Batch done = Queue.findBatch(batch.id()).orElseThrow();
            assertThat(done.isCancelled()).isTrue();
            assertThat(done.failedJobs()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ allowFailures 后其余任务照常跑完")
        void allowFailuresKeepsGoing() {
            Batch batch = Queue.batch(
                            new FailingStep("1"), new Step("2"), new Step("3"))
                    .allowFailures()
                    .finallyRun(new OnBatchDone("finally"))
                    .dispatch();

            drain(20);

            assertThat(LOG).contains("fail:1", "step:2", "step:3");

            Batch done = Queue.findBatch(batch.id()).orElseThrow();
            assertThat(done.isCancelled()).isFalse();
            assertThat(done.failedJobs()).isEqualTo(1);
            assertThat(done.succeededJobs()).isEqualTo(2);
            assertThat(done.isFinished()).isTrue();
        }

        @Test
        @DisplayName("★ 有失败时不投 then,但 finally 照投")
        void thenIsSkippedOnFailure() {
            Queue.batch(new FailingStep("1"), new Step("2"))
                    .allowFailures()
                    .then(new OnBatchDone("then"))
                    .finallyRun(new OnBatchDone("finally"))
                    .dispatch();

            drain(20);

            assertThat(LOG.stream().anyMatch(l -> l.startsWith("then:")))
                    .as("then 的语义是「全部成功」")
                    .isFalse();
            assertThat(LOG.stream().anyMatch(l -> l.startsWith("finally:")))
                    .as("finally 的语义是「全部有结果」,无论成败")
                    .isTrue();
        }

        @Test
        @DisplayName("★ catch 只在第一个失败时投,不是每个")
        void catchFiresOnceOnFirstFailure() {
            Queue.batch(new FailingStep("1"), new FailingStep("2"), new FailingStep("3"))
                    .allowFailures()
                    .catchWith(new OnBatchDone("catch"))
                    .dispatch();

            drain(20);

            assertThat(LOG.stream().filter(l -> l.startsWith("catch:")).count())
                    .as("三个都失败,但 catch 只该响一次")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ 取消后未出队的任务被跳过")
        void cancelSkipsPendingJobs() {
            Batch batch = Queue.batch(new Step("1"), new Step("2"), new Step("3"))
                    .dispatch();

            batch.cancel();

            drain(20);

            assertThat(LOG)
                    .as("取消的作用点是出队时,不打断正在跑的")
                    .isEmpty();
            assertThat(Queue.size("default"))
                    .as("跳过的任务要被删掉,不然会反复出队反复跳过")
                    .isZero();
        }

        @Test
        @DisplayName("取消是幂等的 —— 重复取消不改变取消时间")
        void cancelIsIdempotent() {
            Batch batch = Queue.batch(new Step("1")).dispatch();

            batch.cancel();
            var first = Queue.findBatch(batch.id()).orElseThrow().cancelledAt();

            batch.cancel();
            var second = Queue.findBatch(batch.id()).orElseThrow().cancelledAt();

            assertThat(second)
                    .as("取消时间是排查时会看的一个时间点,不该随误调后移")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("★ 空批次当场就是「已完成」,回调照投")
        void emptyBatchFinishesImmediately() {
            Batch batch = Queue.batch(List.of())
                    .then(new OnBatchDone("then"))
                    .dispatch();

            assertThat(batch.progress())
                    .as("空批次的进度是 1.0 —— 返回 0 会让进度条永远停在起点")
                    .isEqualTo(1.0);

            drain(20);

            assertThat(LOG)
                    .as("不特殊处理的话没有任何 recordSuccess 去触发完成判定,"
                            + "回调永不执行")
                    .contains("then:0/0:失败0:完成true");
        }

        @Test
        @DisplayName("失败的 uuid 记在批次上,能定位到 failed_jobs")
        void failedIdsAreRecorded() {
            Batch batch = Queue.batch(new FailingStep("1"), new FailingStep("2"))
                    .allowFailures()
                    .dispatch();

            drain(20);

            Batch done = Queue.findBatch(batch.id()).orElseThrow();
            assertThat(done.failedJobIds())
                    .as("第一个条目前不该多一个空字符串")
                    .hasSize(2)
                    .allSatisfy(id -> assertThat(id).isNotBlank());

            for (String uuid : done.failedJobIds()) {
                assertThat(connection.table("failed_jobs").where("uuid", uuid).count())
                        .as("批次记下的 uuid 要真的能查到那条失败记录")
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("计数不会被减成负数")
        void countsNeverGoNegative() {
            Batch batch = Queue.batch(new Step("1")).dispatch();

            // 模拟一次重复的成功上报(任务被判失联后两个 worker 都报了成功)
            batches.recordSuccess(batch.id());
            batches.recordSuccess(batch.id());
            batches.recordSuccess(batch.id());

            Batch after = Queue.findBatch(batch.id()).orElseThrow();
            assertThat(after.pendingJobs()).isZero();
            assertThat(after.progress())
                    .as("减成负数会让进度超过 100%")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("prune 只清已完成的批次")
        void pruneSpareUnfinishedBatches() {
            Batch running = Queue.batch(new Step("1")).dispatch();
            Batch done = Queue.batch(new Step("2")).dispatch();

            drain(1);      // 只跑一个,让 running 还留着

            // 把 done 标成很久以前完成
            batches.markFinished(done.id());
            connection.update("update job_batches set finished_at = ? where id = ?",
                    List.of(java.time.Instant.now().minusSeconds(86_400), done.id()));

            int pruned = batches.prune(Duration.ofHours(1));

            assertThat(pruned).isEqualTo(1);
            assertThat(Queue.findBatch(done.id())).isEmpty();
            assertThat(Queue.findBatch(running.id()))
                    .as("清掉在跑的批次会让它的回调永不触发")
                    .isPresent();
        }

        @Test
        @DisplayName("没配批次存储时报错,不静默退化")
        void missingRepositoryFails() {
            var bare = new QueueManager(new JobSerializer())
                    .register("database", m -> new DatabaseQueue(connection,
                            new JobSerializer()))
                    .defaultConnection("database");
            Queue.use(bare);

            assertThatThrownBy(() -> Queue.batch(new Step("1")).dispatch())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("job_batches");
        }

        @Test
        @DisplayName("并发完成时 then 回调仍然只投一次")
        void concurrentCompletionStillFiresOnce() throws Exception {
            Batch batch = Queue.batch(new Step("1"), new Step("2"), new Step("3"),
                            new Step("4"), new Step("5"), new Step("6"))
                    .then(new OnBatchDone("then"))
                    .dispatch();

            // 并发度 6:最后几个任务几乎同时完成,
            // 「我是不是最后一个」的判定会被同时问到
            var w = new QueueWorker(queues, failedJobs, new JobInvoker(Application::make),
                    WorkerOptions.onQueues("default")
                            .withConcurrency(6)
                            .withRetryAfter(Duration.ofSeconds(30))
                            .withSleep(Duration.ofMillis(2))
                            .withStopWhenEmpty(true)
                            .withMaxJobs(30));
            w.run();

            assertThat(LOG.stream().filter(l -> l.startsWith("then:")).count())
                    .as("markFinished 的条件 UPDATE 是这件事的唯一保证")
                    .isEqualTo(1);
            assertThat(Queue.findBatch(batch.id()).orElseThrow().succeededJobs())
                    .isEqualTo(6);
        }
    }

    // ================= maxExceptions =================

    @Nested
    @DisplayName("异常计数与 tries 的区分")
    class ExceptionCounting {

        @Test
        @DisplayName("★ maxExceptions 比 tries 小时,按 maxExceptions 停")
        void maxExceptionsStopsEarlier() {
            Queue.dispatch(new PickyFailure());

            drain(30);

            assertThat(LOG)
                    .as("tries=10 但 maxExceptions=2 —— 只该执行两次")
                    .hasSize(2);

            var exception = connection.table("failed_jobs").first().orElseThrow()
                    .string("exception");
            assertThat(exception)
                    .as("归档原因要说清是哪个上限触发的,否则无从调参")
                    .contains("maxExceptions");
        }
    }

    public static class PickyFailure implements ShouldQueue {
        @Override public int tries() { return 10; }

        @Override public int maxExceptions() { return 2; }

        @Override public Duration[] backoff() { return new Duration[] {Duration.ZERO}; }

        public void handle() {
            LOG.add("picky");
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

    @BeforeEach
    void resetFlaky() {
        FlakyOnce.reset();
    }
}
