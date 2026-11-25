package io.jaravel.queue.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import io.jaravel.core.container.SimpleContainer;
import io.jaravel.core.Application;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.queue.JobInvoker;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.Queue;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueueWorker;
import io.jaravel.queue.ShouldQueue;
import io.jaravel.queue.WorkerOptions;

/**
 * 「broker 队列 + 库里的 failed_jobs / job_batches」能不能一起用(docs/10 §1.5)。
 *
 * <h2>★ 为什么需要这个测试</h2>
 * <p>KNOWN-ISSUES 里曾经有一条「RabbitMQ / SQS 的 failed_jobs / job_batches
 * 未实现」。那条**问错了问题**:归档与批次账本不是队列驱动的一部分 ——
 * 它们是独立的 {@code queue.failed.driver} / {@code queue.batching.driver}
 * (与 Laravel 一样),哪个队列驱动都能配。
 *
 * <p>但在此之前**没有任何测试证明这个组合真的能跑**:
 * worker 的归档路径走的是 {@code store.delete(envelope)} + 失败存储,
 * 而那两个在 broker 驱动上一个靠 ack、一个靠 SQL。这个类把它钉住。
 *
 * <p>给了 broker 连接串才跑(与 {@code RabbitQueueTest} / {@code SqsQueueTest}
 * 同一套开关):
 * <pre>
 * -Djaravel.test.rabbitmq.uri=amqp://guest:guest@127.0.0.1:5672/%2f
 * -Djaravel.test.sqs.endpoint=http://127.0.0.1:9324
 * </pre>
 */
class BrokerArchiveTest {

    static final List<String> LOG = new CopyOnWriteArrayList<>();
    static final AtomicInteger ATTEMPTS = new AtomicInteger();

    private java.sql.Connection jdbc;
    private io.jaravel.database.Connection connection;
    private JobSerializer serializer;
    private DatabaseFailedJobStore failedJobs;
    private DatabaseBatchRepository batches;
    private QueueManager queues;
    private final List<AutoCloseable> opened = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        LOG.clear();
        ATTEMPTS.set(0);

        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
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
        serializer = new JobSerializer();
        failedJobs = new DatabaseFailedJobStore(connection);
        batches = new DatabaseBatchRepository(connection, serializer);

        var container = new SimpleContainer();
        container.instance(io.jaravel.database.Connection.class, connection);
        Application.setContainer(container);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Queue.reset();
        Application.reset();
        for (AutoCloseable c : opened) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 测试结束,连接关不掉也无所谓
            }
        }
        opened.clear();
        if (jdbc != null) jdbc.close();
    }

    /** 每次 getConnection 都给同一条连接,且 close 是空操作(内存库一关就没了) */
    private static DataSource nonClosing(java.sql.Connection shared) {
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return java.lang.reflect.Proxy.newProxyInstance(
                                java.sql.Connection.class.getClassLoader(),
                                new Class<?>[]{java.sql.Connection.class},
                                (p2, m2, a2) -> m2.getName().equals("close")
                                        ? null
                                        : m2.invoke(shared, a2));
                    }
                    if (method.getName().equals("unwrap")) return shared;
                    return null;
                });
    }

    // ================= 两个 broker 的接入 =================

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private String uniqueQueue() {
        return "q" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void useRabbit() {
        String uri = firstNonBlank(
                System.getProperty("jaravel.test.rabbitmq.uri"),
                System.getenv("JARAVEL_TEST_RABBITMQ_URI"));
        Assumptions.assumeTrue(uri != null,
                "设置 -Djaravel.test.rabbitmq.uri 才跑 RabbitMQ 的归档测试");

        var store = new RabbitQueue(uri, serializer,
                "jaravel-archive-test:" + UUID.randomUUID() + ":");
        opened.add(store);
        use("rabbitmq", store);
    }

    private void useSqs() {
        String endpoint = firstNonBlank(
                System.getProperty("jaravel.test.sqs.endpoint"),
                System.getenv("JARAVEL_TEST_SQS_ENDPOINT"));
        Assumptions.assumeTrue(endpoint != null,
                "设置 -Djaravel.test.sqs.endpoint 才跑 SQS 的归档测试");

        var store = SqsQueue.connect(endpoint, "us-east-1", "test", "test", serializer,
                "jaravel-arch-" + UUID.randomUUID().toString().substring(0, 8) + "-");
        opened.add(store);
        use("sqs", store);
    }

    /** 队列驱动是 broker,failed_jobs / job_batches 在库里 —— 就是本测试的组合 */
    private void use(String name, QueueStore store) {
        queues = new QueueManager(serializer)
                .register(name, m -> store)
                .defaultConnection(name)
                .defaultQueue(queue)
                .afterCommitByDefault(false)
                .batches(batches);
        Queue.use(queues);
    }

    private String queue = "default";

    private QueueWorker drain(int maxJobs) {
        var w = new QueueWorker(queues, failedJobs, new JobInvoker(Application::make),
                WorkerOptions.onQueues(queue)
                        .withConcurrency(1)
                        // broker 侧的重投不该在这个测试里发生:
                        // 这里测的是 worker 自己的重试与归档
                        .withRetryAfter(Duration.ofSeconds(60))
                        .withSleep(Duration.ofMillis(20))
                        .withStopWhenEmpty(true)
                        .withMaxJobs(maxJobs));
        w.run();
        return w;
    }

    // ================= 任务 =================

    public static class AlwaysFails implements ShouldQueue {
        @Override public int tries() { return 2; }

        /** backoff=0:测试里不等退避(默认退避会让这个类慢上几十秒) */
        @Override public Duration[] backoff() {
            return new Duration[]{Duration.ZERO};
        }

        public void handle() {
            LOG.add("try:" + ATTEMPTS.incrementAndGet());
            throw new IllegalStateException("这个任务永远失败");
        }
    }

    public static class Ok implements ShouldQueue {
        private final String label;

        public Ok(String label) {
            this.label = label;
        }

        public void handle() {
            LOG.add("ok:" + label);
        }
    }

    // ================= 验收 =================

    @Test
    @DisplayName("★ rabbitmq 队列 + 库里的 failed_jobs:重试用尽后归档")
    void rabbitArchivesIntoTheDatabase() {
        queue = uniqueQueue();
        useRabbit();

        Queue.dispatch(new AlwaysFails());
        drain(5);

        assertThat(LOG).as("tries=2,所以执行两次").hasSize(2);
        var archived = failedJobs.all();
        assertThat(archived).hasSize(1);
        assertThat(archived.get(0).connection())
                .as("归档里记的是**哪个连接**失败的 —— 多 broker 的应用靠它定位")
                .isEqualTo("rabbitmq");
        assertThat(archived.get(0).queue()).isEqualTo(queue);
        assertThat(archived.get(0).reason()).contains("这个任务永远失败");

        assertThat(queues.connection("rabbitmq").size(queue))
                .as("归档之后那条消息必须从 broker 上消失(ack 过了),否则会被无限重投")
                .isZero();
    }

    @Test
    @DisplayName("★ sqs 队列 + 库里的 failed_jobs:重试用尽后归档")
    void sqsArchivesIntoTheDatabase() {
        queue = uniqueQueue();
        useSqs();

        Queue.dispatch(new AlwaysFails());
        drain(5);

        assertThat(LOG).hasSize(2);
        var archived = failedJobs.all();
        assertThat(archived).hasSize(1);
        assertThat(archived.get(0).connection()).isEqualTo("sqs");
        assertThat(archived.get(0).reason()).contains("这个任务永远失败");
    }

    @Test
    @DisplayName("★ rabbitmq 队列 + 库里的 job_batches:批次计数照常推进")
    void rabbitKeepsBatchBookkeepingInTheDatabase() {
        queue = uniqueQueue();
        useRabbit();

        var batch = Queue.batch(new Ok("1"), new Ok("2"), new Ok("3"))
                .name("broker-batch")
                .dispatch();
        drain(5);

        var reloaded = batches.find(batch.id()).orElseThrow();
        assertThat(reloaded.processedJobs()).isEqualTo(3);
        assertThat(reloaded.failedJobs()).isZero();
        assertThat(reloaded.isFinished())
                .as("批次账本与队列驱动无关 —— 它是 queue.batching.driver 的事")
                .isTrue();
        assertThat(LOG).containsExactlyInAnyOrder("ok:1", "ok:2", "ok:3");
    }
}
