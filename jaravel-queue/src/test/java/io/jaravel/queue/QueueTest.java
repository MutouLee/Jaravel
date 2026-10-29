package io.jaravel.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import io.jaravel.core.container.Container;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.queue.store.DatabaseQueue;
import io.jaravel.queue.store.NullQueue;
import io.jaravel.queue.store.SyncQueue;

/** 队列契约、JSON 信封、sync / null / database 驱动 */
class QueueTest {

    // ================= 测试用的任务 =================

    /** 记录每次执行 —— 静态是为了让反序列化出来的实例也能被观察到 */
    static final ConcurrentLinkedQueue<String> EXECUTED = new ConcurrentLinkedQueue<>();
    static final AtomicInteger FAILURES = new AtomicInteger();

    public static class SendInvoice implements ShouldQueue {

        private final Long orderId;
        private final String note;

        public SendInvoice(Long orderId, String note) {
            this.orderId = orderId;
            this.note = note;
        }

        @Override public String queue() { return "mail"; }
        @Override public int tries()    { return 3; }

        @Override public String displayName() {
            return "SendInvoice(order=" + orderId + ")";
        }

        public void handle() {
            EXECUTED.add("invoice:" + orderId + ":" + note);
        }

        public Long orderId() { return orderId; }
        public String note()  { return note; }
    }

    public static class AlwaysFails implements ShouldQueue {

        private final String reason;

        public AlwaysFails(String reason) {
            this.reason = reason;
        }

        public void handle() {
            throw new IllegalStateException("故意失败:" + reason);
        }

        @Override public void failed(Throwable cause) {
            FAILURES.incrementAndGet();
        }
    }

    /** 需要注入依赖的任务 */
    public static class NeedsMailer implements ShouldQueue {

        private final Long id;

        public NeedsMailer(Long id) {
            this.id = id;
        }

        public void handle(FakeMailer mailer) {
            mailer.sent.add(id);
        }
    }

    public static class FakeMailer {
        final List<Long> sent = new java.util.ArrayList<>();
    }

    /** 字段不可序列化 */
    public static class HoldsAStream implements ShouldQueue {

        private final java.io.InputStream stream;

        public HoldsAStream(java.io.InputStream stream) {
            this.stream = stream;
        }

        public void handle() { }
    }

    /** 两个 handle */
    public static class Overloaded implements ShouldQueue {

        public Overloaded() { }

        public void handle() { }

        public void handle(FakeMailer mailer) { }
    }

    public static class NoHandle implements ShouldQueue {
        public NoHandle() { }
    }

    // ================= 脚手架 =================

    private java.sql.Connection jdbc;
    private io.jaravel.database.Connection connection;
    private JobSerializer serializer;
    private FakeMailer mailer;

    @BeforeEach
    void setUp() throws SQLException {
        EXECUTED.clear();
        FAILURES.set(0);

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
        }
        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        serializer = new JobSerializer();
        mailer = new FakeMailer();

        // 容器里放一个假 Mailer,给注入测试用
        var container = new io.jaravel.core.container.SimpleContainer();
        container.instance(FakeMailer.class, mailer);
        container.instance(io.jaravel.database.Connection.class, connection);
        Application.setContainer(container);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Queue.reset();
        Application.reset();
        if (jdbc != null) jdbc.close();
    }

    private JobInvoker invoker() {
        return new JobInvoker(Application::make);
    }

    private QueueManager managerWith(String defaultConnection) {
        var queues = new QueueManager(serializer)
                .register("sync", m -> new SyncQueue(serializer, invoker()))
                .register("null", m -> new NullQueue())
                .register("database", m -> new DatabaseQueue(connection, serializer))
                .defaultQueue("default")
                .defaultConnection(defaultConnection);
        Queue.use(queues);
        return queues;
    }

    // ================= 序列化 =================

    @Nested
    @DisplayName("JSON 信封")
    class Envelope {

        @Test
        @DisplayName("字段往返")
        void roundTrip() {
            var job = new SendInvoice(42L, "备注");

            var data = serializer.toData(job);
            // Long 保持 Long,不降成 int —— 无损往返是 payload 的基本要求
            assertThat(data).containsEntry("orderId", 42L).containsEntry("note", "备注");

            var envelope = QueuedJob.of(job, "mail", data, Instant.now(), null);
            var restored = (SendInvoice) serializer.toJob(envelope);

            assertThat(restored.orderId()).isEqualTo(42L);
            assertThat(restored.note()).isEqualTo("备注");
        }

        @Test
        @DisplayName("信封编解码")
        void encodeDecode() {
            var job = new SendInvoice(7L, "x");
            var envelope = QueuedJob.of(job, "mail", serializer.toData(job),
                                        Instant.now(), Duration.ofMinutes(5));

            String json = serializer.encode(envelope);
            // 可读是 JSON 这个选择的主要理由之一
            assertThat(json).contains("\"orderId\":7").contains("SendInvoice");

            var decoded = serializer.decode(json);
            assertThat(decoded.uuid()).isEqualTo(envelope.uuid());
            assertThat(decoded.queue()).isEqualTo("mail");
            assertThat(decoded.tries()).isEqualTo(3);
            assertThat(decoded.displayName()).isEqualTo("SendInvoice(order=7)");
        }

        @Test
        @DisplayName("★ 行为配置在投递时快照,不是执行时读")
        void behaviourIsSnapshotted() {
            var job = new SendInvoice(1L, "x");
            var envelope = QueuedJob.of(job, "mail", serializer.toData(job),
                                        Instant.now(), null);

            // 发布时把 tries 从 3 改成 1,不该让队列里积压的任务
            // 突然少两次重试 —— 那种变化很难归因
            assertThat(envelope.tries()).isEqualTo(3);
            assertThat(envelope.timeout()).isEqualTo(Duration.ofMinutes(2));
        }

        @Test
        @DisplayName("★ 延迟体现在 availableAt 上")
        void delayShiftsAvailableAt() {
            var now = Instant.parse("2026-10-29T10:00:00Z");
            var job = new SendInvoice(1L, "x");

            var immediate = QueuedJob.of(job, "mail", Map.of(), now, null);
            var delayed = QueuedJob.of(job, "mail", Map.of(), now, Duration.ofMinutes(5));

            assertThat(immediate.availableAt()).isEqualTo(now);
            assertThat(delayed.availableAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
        }

        @Test
        @DisplayName("★ 不可序列化的字段在**投递时**就失败")
        void unserializableFailsEarly() {
            var job = new HoldsAStream(java.io.InputStream.nullInputStream());

            // 等 worker 取出来才发现的话,调用栈离原因很远
            assertThatThrownBy(() -> serializer.toData(job))
                    .isInstanceOf(JobSerializer.UnserializableJobException.class)
                    .hasMessageContaining("只存主键");
        }

        @Test
        @DisplayName("★ 未知字段报错,不静默忽略")
        void unknownFieldFails() {
            var envelope = new QueuedJob("u", SendInvoice.class.getName(),
                    Map.of("orderId", 1, "note", "x", "removedField", "y"),
                    "mail", 0, Instant.now(), Instant.now(), 1,
                    Duration.ofMinutes(1), null, 5, "x");

            // 静默忽略的后果:删掉一个 amount 字段之后,积压的旧任务
            // 会被用默认值执行 —— 变成「转账 0 元」
            assertThatThrownBy(() -> serializer.toJob(envelope))
                    .isInstanceOf(JobSerializer.UnserializableJobException.class)
                    .hasMessageContaining("未知字段");
        }

        @Test
        @DisplayName("找不到任务类时说清处理方式")
        void missingClass() {
            var envelope = new QueuedJob("u", "com.nope.Gone", Map.of(),
                    "default", 0, Instant.now(), Instant.now(), 1,
                    null, null, 5, "x");

            assertThatThrownBy(() -> serializer.toJob(envelope))
                    .hasMessageContaining("queue:forget");
        }

        @Test
        @DisplayName("jobClass 指向一个非任务类时拒绝")
        void notAJob() {
            var envelope = new QueuedJob("u", String.class.getName(), Map.of(),
                    "default", 0, Instant.now(), Instant.now(), 1,
                    null, null, 5, "x");

            assertThatThrownBy(() -> serializer.toJob(envelope))
                    .hasMessageContaining("不是 ShouldQueue");
        }
    }

    // ================= 重试判定 =================

    @Nested
    @DisplayName("重试判定")
    class Retrying {

        private QueuedJob jobWith(int attempts, Instant retryUntil) {
            return new QueuedJob("u", SendInvoice.class.getName(), Map.of(),
                    "mail", attempts, Instant.now(), Instant.now(), 3,
                    null, retryUntil, 5, "x");
        }

        @Test
        @DisplayName("次数没用完就能重试")
        void byAttempts() {
            var now = Instant.now();
            assertThat(jobWith(1, null).canRetry(now)).isTrue();
            assertThat(jobWith(3, null).canRetry(now)).isFalse();
        }

        @Test
        @DisplayName("★ retryUntil 与 tries 取先到的那个")
        void byDeadline() {
            var now = Instant.parse("2026-10-29T12:00:00Z");

            // 次数还有,但过了截止时间
            assertThat(jobWith(1, now.minusSeconds(1)).canRetry(now)).isFalse();
            assertThat(jobWith(1, now.plusSeconds(60)).canRetry(now)).isTrue();
        }

        @Test
        @DisplayName("耗尽原因要写得能看懂 —— 它会进 failed_jobs")
        void exhaustionReason() {
            var now = Instant.parse("2026-10-29T12:00:00Z");

            assertThat(jobWith(3, null).exhaustionReason(now))
                    .contains("已尝试 3 次").contains("上限 3");
            assertThat(jobWith(1, now.minusSeconds(1)).exhaustionReason(now))
                    .contains("超过重试截止时间");
        }

        @Test
        @DisplayName("nextAttempt 推进计数并推后可取时间")
        void nextAttempt() {
            var later = Instant.now().plusSeconds(60);
            var next = jobWith(1, null).nextAttempt(later);

            assertThat(next.attempts()).isEqualTo(2);
            assertThat(next.availableAt()).isEqualTo(later);
            assertThat(next.uuid()).as("uuid 贯穿重试,好让日志能串起来")
                    .isEqualTo(jobWith(1, null).uuid());
        }
    }

    // ================= handle 的解析 =================

    @Nested
    @DisplayName("handle 的解析与注入")
    class Handling {

        @Test
        @DisplayName("无参 handle")
        void noArgs() {
            invoker().invoke(new SendInvoice(1L, "a"));
            assertThat(EXECUTED).containsExactly("invoice:1:a");
        }

        @Test
        @DisplayName("参数从容器注入")
        void injectsDependencies() {
            invoker().invoke(new NeedsMailer(9L));
            assertThat(mailer.sent).containsExactly(9L);
        }

        @Test
        @DisplayName("★ 任务自己的异常原样抛出 —— Worker 要靠它判断可重试性")
        void propagatesJobException() {
            assertThatThrownBy(() -> invoker().invoke(new AlwaysFails("x")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("故意失败");
        }

        @Test
        @DisplayName("★ 没有 handle 时报错带该类的方法列表")
        void missingHandle() {
            assertThatThrownBy(() -> invoker().invoke(new NoHandle()))
                    .isInstanceOf(JobInvoker.JobHandlerException.class)
                    .hasMessageContaining("没有 handle 方法");
        }

        @Test
        @DisplayName("★ 重载的 handle 直接拒绝,不按参数个数猜")
        void overloadedHandleRejected() {
            // 按参数个数挑会让「加一个注入参数」悄悄改变被调用的那一个
            assertThatThrownBy(() -> invoker().invoke(new Overloaded()))
                    .hasMessageContaining("无从选择");
        }

        @Test
        @DisplayName("依赖解析不出来时提示加 @Component")
        void unresolvableDependency() {
            Application.reset();
            Application.setContainer(new io.jaravel.core.container.SimpleContainer());

            assertThatThrownBy(() -> invoker().invoke(new NeedsMailer(1L)))
                    .hasMessageContaining("@Component");
        }
    }

    // ================= sync 驱动 =================

    @Nested
    @DisplayName("sync 驱动")
    class Sync {

        @Test
        @DisplayName("立即执行")
        void executesImmediately() {
            managerWith("sync");

            Queue.dispatch(new SendInvoice(5L, "now"));

            assertThat(EXECUTED).containsExactly("invoice:5:now");
        }

        @Test
        @DisplayName("★ 仍然走一遍序列化 —— 让「字段不可序列化」在开发期暴露")
        void stillSerializes() {
            managerWith("sync");

            // 不走序列化的话,sync 驱动就成了「本地能跑、上线就炸」的发生器
            assertThatThrownBy(() -> Queue.dispatch(
                    new HoldsAStream(java.io.InputStream.nullInputStream())))
                    .isInstanceOf(JobSerializer.UnserializableJobException.class);
        }

        @Test
        @DisplayName("★ 失败时立即调 failed 并把异常抛出")
        void failsLoudly() {
            managerWith("sync");

            assertThatThrownBy(() -> Queue.dispatch(new AlwaysFails("boom")))
                    .isInstanceOf(IllegalStateException.class);

            // 吞掉异常会让「任务挂了但接口返回 200」
            assertThat(FAILURES.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 声明自己不是异步的 —— 让框架能提醒生产误用")
        void notAsynchronous() {
            var queues = managerWith("sync");
            assertThat(queues.isDefaultAsynchronous()).isFalse();
        }

        @Test
        @DisplayName("release 直接报错而不是静默丢弃")
        void releaseUnsupported() {
            var store = new SyncQueue(serializer, invoker());
            var job = QueuedJob.of(new SendInvoice(1L, "x"), "mail", Map.of(),
                                   Instant.now(), null);

            assertThatThrownBy(() -> store.release(job, Duration.ZERO))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("database");
        }
    }

    // ================= null 驱动 =================

    @Nested
    @DisplayName("null 驱动")
    class Discarding {

        @Test
        @DisplayName("丢弃但可计数")
        void discardsAndCounts() {
            var store = new NullQueue();
            managerWith("null");

            Queue.dispatch(new SendInvoice(1L, "x"));

            assertThat(EXECUTED).isEmpty();
            // 静默丢弃最怕「我以为它跑了」,所以至少要可观测
            assertThat(((NullQueue) Queue.manager().connection()).discarded())
                    .isEqualTo(1);
        }
    }

    // ================= database 驱动 =================

    @Nested
    @DisplayName("database 驱动")
    class Database {

        @Test
        @DisplayName("入队 → 取出 → 删除")
        void pushPopDelete() {
            managerWith("database");
            var store = Queue.manager().connection();

            Queue.dispatch(new SendInvoice(3L, "db"));
            assertThat(store.size("mail")).isEqualTo(1);

            var popped = store.pop("mail", Duration.ofSeconds(90)).orElseThrow();
            assertThat(popped.displayName()).isEqualTo("SendInvoice(order=3)");
            assertThat(popped.attempts()).as("取出即算一次尝试").isEqualTo(1);

            store.delete(popped);
            assertThat(store.size("mail")).isZero();
        }

        @Test
        @DisplayName("★ 已保留的任务不会被再取一次")
        void reservedIsNotPoppedAgain() {
            managerWith("database");
            var store = Queue.manager().connection();
            Queue.dispatch(new SendInvoice(1L, "x"));

            assertThat(store.pop("mail", Duration.ofSeconds(90))).isPresent();
            assertThat(store.pop("mail", Duration.ofSeconds(90)))
                    .as("同一条被取两次就是任务被执行两次 —— 对扣款是生产事故")
                    .isEmpty();
        }

        @Test
        @DisplayName("★ 保留超时后任务重新可见 —— worker 崩了要能捡起来")
        void staleReservationIsRecovered() {
            managerWith("database");
            var store = Queue.manager().connection();
            Queue.dispatch(new SendInvoice(1L, "x"));

            store.pop("mail", Duration.ofSeconds(90)).orElseThrow();

            // 用一个极短的 retryAfter 模拟「持有它的 worker 失联了」
            assertThat(store.pop("mail", Duration.ZERO))
                    .as("不恢复的话崩掉的 worker 持有的任务永久卡住")
                    .isPresent();
        }

        @Test
        @DisplayName("★ 延迟中的任务取不到")
        void delayedIsNotVisible() {
            managerWith("database");
            var store = Queue.manager().connection();

            Queue.job(new SendInvoice(1L, "x")).delay(Duration.ofMinutes(10)).dispatch();

            assertThat(store.size("mail")).as("它在队列里").isEqualTo(1);
            assertThat(store.pop("mail", Duration.ofSeconds(90)))
                    .as("但还不可取").isEmpty();
        }

        @Test
        @DisplayName("release 推后可取时间")
        void releaseDelays() {
            managerWith("database");
            var store = Queue.manager().connection();
            Queue.dispatch(new SendInvoice(1L, "x"));

            var popped = store.pop("mail", Duration.ofSeconds(90)).orElseThrow();
            store.release(popped, Duration.ofMinutes(5));

            assertThat(store.pop("mail", Duration.ofSeconds(90))).isEmpty();
            assertThat(store.size("mail")).isEqualTo(1);
        }

        @Test
        @DisplayName("★ release 一条已经不在表里的任务 —— 重新插入(queue:retry 走这条路)")
        void releaseReinserts() {
            managerWith("database");
            var store = Queue.manager().connection();
            Queue.dispatch(new SendInvoice(1L, "x"));

            var popped = store.pop("mail", Duration.ofSeconds(90)).orElseThrow();
            store.delete(popped);
            assertThat(store.size("mail")).isZero();

            store.release(popped, Duration.ZERO);
            assertThat(store.size("mail")).isEqualTo(1);
        }

        @Test
        @DisplayName("按队列隔离")
        void queuesAreIsolated() {
            managerWith("database");
            var store = Queue.manager().connection();

            Queue.job(new SendInvoice(1L, "a")).onQueue("high").dispatch();
            Queue.job(new SendInvoice(2L, "b")).onQueue("low").dispatch();

            assertThat(store.size("high")).isEqualTo(1);
            assertThat(store.pop("low", Duration.ofSeconds(90)).orElseThrow()
                    .displayName()).isEqualTo("SendInvoice(order=2)");
        }

        @Test
        @DisplayName("先进先出")
        void fifo() {
            managerWith("database");
            var store = Queue.manager().connection();

            for (long i = 1; i <= 3; i++) Queue.dispatch(new SendInvoice(i, "x"));

            for (long i = 1; i <= 3; i++) {
                assertThat(store.pop("mail", Duration.ofSeconds(90)).orElseThrow()
                        .displayName()).isEqualTo("SendInvoice(order=" + i + ")");
            }
        }

        @Test
        @DisplayName("★ 并发 pop 不会把同一条给两个消费者")
        void concurrentPopIsExclusive() throws Exception {
            managerWith("database");
            var store = Queue.manager().connection();

            int total = 20;
            for (long i = 1; i <= total; i++) Queue.dispatch(new SendInvoice(i, "x"));

            var seen = new ConcurrentLinkedQueue<String>();
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(4);

            for (int t = 0; t < 4; t++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        while (true) {
                            var job = store.pop("mail", Duration.ofSeconds(90));
                            if (job.isEmpty()) break;
                            seen.add(job.get().uuid());
                            store.delete(job.get());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

            // 乐观锁 UPDATE 的全部意义就在这一条:
            // 重复消费对非幂等任务(扣款、发邮件)是生产事故
            assertThat(seen).doesNotHaveDuplicates();
            assertThat(seen).hasSize(total);
        }

        @Test
        @DisplayName("clear 只清指定队列")
        void clearOneQueue() {
            managerWith("database");
            var store = Queue.manager().connection();

            Queue.job(new SendInvoice(1L, "a")).onQueue("a").dispatch();
            Queue.job(new SendInvoice(2L, "b")).onQueue("b").dispatch();

            store.clear("a");
            assertThat(store.size("a")).isZero();
            assertThat(store.size("b")).isEqualTo(1);
        }
    }

    // ================= 投递 API =================

    @Nested
    @DisplayName("投递 API")
    class Dispatching {

        @Test
        @DisplayName("任务自己声明的队列生效")
        void jobDeclaredQueue() {
            managerWith("database");
            Queue.dispatch(new SendInvoice(1L, "x"));

            // SendInvoice.queue() 返回 "mail"
            assertThat(Queue.size("mail")).isEqualTo(1);
            assertThat(Queue.size("default")).isZero();
        }

        @Test
        @DisplayName("onQueue 覆盖任务的声明")
        void onQueueOverrides() {
            managerWith("database");
            Queue.job(new SendInvoice(1L, "x")).onQueue("urgent").dispatch();

            assertThat(Queue.size("urgent")).isEqualTo(1);
            assertThat(Queue.size("mail")).isZero();
        }

        @Test
        @DisplayName("★ dispatch 幂等 —— 调两次不会入队两条")
        void dispatchIsIdempotent() {
            managerWith("database");
            var pending = Queue.job(new SendInvoice(1L, "x"));

            String first = pending.dispatch();
            String second = pending.dispatch();

            assertThat(first).isEqualTo(second);
            assertThat(Queue.size("mail")).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 投递之后再改配置直接报错")
        void configAfterDispatchFails() {
            managerWith("database");
            var pending = Queue.job(new SendInvoice(1L, "x"));
            pending.dispatch();

            assertThatThrownBy(() -> pending.onQueue("other"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("同一条链");
        }

        @Test
        @DisplayName("dispatchIf / dispatchUnless")
        void conditional() {
            managerWith("database");

            Queue.dispatchIf(true, new SendInvoice(1L, "yes"));
            Queue.dispatchIf(false, new SendInvoice(2L, "no"));
            Queue.dispatchUnless(false, new SendInvoice(3L, "yes"));

            assertThat(Queue.size("mail")).isEqualTo(2);
        }

        @Test
        @DisplayName("dispatchSync 强制走 sync 连接")
        void dispatchSyncUsesSync() {
            managerWith("database");

            Queue.dispatchSync(new SendInvoice(1L, "now"));

            assertThat(EXECUTED).containsExactly("invoice:1:now");
            assertThat(Queue.size("mail")).as("不该入队").isZero();
        }

        @Test
        @DisplayName("★ 没注册 sync 连接时 dispatchSync 报错,不静默退回默认连接")
        void dispatchSyncRequiresSyncConnection() {
            var queues = new QueueManager(serializer)
                    .register("database", m -> new DatabaseQueue(connection, serializer))
                    .defaultConnection("database");
            Queue.use(queues);

            // 静默退回会让 dispatchSync 变成异步投递,
            // 而那正好是调用方明确不想要的
            assertThatThrownBy(() -> Queue.dispatchSync(new SendInvoice(1L, "x")))
                    .hasMessageContaining("driver: sync");
        }

        @Test
        @DisplayName("未注册的连接名报错带候选")
        void unknownConnection() {
            managerWith("database");

            assertThatThrownBy(() ->
                    Queue.job(new SendInvoice(1L, "x")).onConnection("databse").dispatch())
                    .hasMessageContaining("是否想写 \"database\"");
        }
    }

    // ================= afterCommit =================

    @Nested
    @DisplayName("afterCommit")
    class AfterCommit {

        @Test
        @DisplayName("★ 事务回滚时任务不入队")
        void rollbackCancelsDispatch() {
            managerWith("database");

            assertThatThrownBy(() -> connection.transaction(() -> {
                Queue.dispatch(new SendInvoice(1L, "x"));
                throw new IllegalStateException("支付失败");
            })).hasMessageContaining("支付失败");

            // 不这样做的话:订单回滚了但任务在队列里,worker 取出来
            // findOrFail 找不到 → 重试三次后失败,而失败日志指向
            // 「订单不存在」,离真正的原因(支付失败)很远
            assertThat(Queue.size("mail")).isZero();
        }

        @Test
        @DisplayName("事务提交后才真正入队")
        void commitTriggersDispatch() {
            managerWith("database");

            connection.transaction(() -> {
                Queue.dispatch(new SendInvoice(1L, "x"));
                // 还在事务里 —— 此刻队列里应该什么都没有
                assertThat(Queue.size("mail")).isZero();
            });

            assertThat(Queue.size("mail")).isEqualTo(1);
        }

        @Test
        @DisplayName("beforeCommit 显式绕过")
        void beforeCommitBypasses() {
            managerWith("database");

            connection.transaction(() -> {
                Queue.job(new SendInvoice(1L, "x")).beforeCommit().dispatch();
                assertThat(Queue.size("mail")).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("不在事务里时直接入队")
        void outsideTransaction() {
            managerWith("database");
            Queue.dispatch(new SendInvoice(1L, "x"));
            assertThat(Queue.size("mail")).isEqualTo(1);
        }

        @Test
        @DisplayName("★ sync 驱动不等提交 —— 它的语义就是「立刻跑」")
        void syncIgnoresAfterCommit() {
            managerWith("sync");

            connection.transaction(() -> {
                Queue.dispatch(new SendInvoice(1L, "now"));
                // 等提交会让「事务里 dispatch 然后读结果」变成一种困惑:
                // 任务要到提交后才跑,而提交在那行代码之后
                assertThat(EXECUTED).containsExactly("invoice:1:now");
            });
        }

        @Test
        @DisplayName("全局关掉 after-commit 之后不等提交")
        void globalSwitch() {
            managerWith("database").afterCommitByDefault(false);

            connection.transaction(() -> {
                Queue.dispatch(new SendInvoice(1L, "x"));
                assertThat(Queue.size("mail")).isEqualTo(1);
            });
        }
    }

    // ================= 门面 =================

    @Nested
    @DisplayName("门面")
    class Facade {

        @Test
        @DisplayName("未装配时报错带装配示例")
        void notConfigured() {
            Queue.reset();

            assertThatThrownBy(() -> Queue.dispatch(new SendInvoice(1L, "x")))
                    .hasMessageContaining("Queue.use(");
        }

        @Test
        @DisplayName("连接可按名字取,且是单例")
        void connectionsAreSingletons() {
            managerWith("database");

            assertThat(Queue.manager().connection("database"))
                    .as("队列连接无状态,做成请求级毫无收益")
                    .isSameAs(Queue.manager().connection("database"));
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
