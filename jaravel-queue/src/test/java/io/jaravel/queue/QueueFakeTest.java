package io.jaravel.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.core.Application;
import io.jaravel.core.container.SimpleContainer;
import io.jaravel.queue.store.SyncQueue;

/**
 * {@code Queue.fake()} 与 {@link QueueFake} 的断言(docs/14 §4)。
 *
 * <p>这里**不接数据库**:fake 的全部意义就是在序列化与入队之前拦下,
 * 所以验证它需要的只是「一个异步驱动」与「一个能观察到真实入队的驱动」。
 * 真实入队用一个记录型的 {@link RecordingQueue} —— 它存在的理由是
 * 让「部分 fake 下没被假装的那条**真的**入队了」这件事可断言,
 * 而那正是部分 fake 最容易做错的地方。
 */
class QueueFakeTest {

    // ================= 任务 =================

    static final ConcurrentLinkedQueue<String> EXECUTED = new ConcurrentLinkedQueue<>();

    public static class SendInvoice implements ShouldQueue {

        private final Long orderId;

        public SendInvoice(Long orderId) {
            this.orderId = orderId;
        }

        @Override public String queue() { return "mail"; }

        public void handle() {
            EXECUTED.add("invoice:" + orderId);
        }

        public Long orderId() { return orderId; }
    }

    public static class RefundJob implements ShouldQueue {

        public void handle() {
            EXECUTED.add("refund");
        }
    }

    public static class ProcessPayment implements ShouldQueue {

        public void handle() {
            EXECUTED.add("payment");
        }
    }

    public static class NotifyBroken implements ShouldQueue {

        public void handle() {
            EXECUTED.add("broken");
        }
    }

    /** 真的入队到内存里 —— 用来证明「没被假装的那条确实入队了」 */
    static final class RecordingQueue implements QueueStore {

        final List<QueuedJob> pushed = new CopyOnWriteArrayList<>();

        @Override public String name() { return "memory"; }

        @Override
        public String push(QueuedJob job) {
            pushed.add(job);
            return job.uuid();
        }

        @Override public Optional<QueuedJob> pop(String queue, Duration retryAfter) {
            return Optional.empty();
        }

        @Override public void delete(QueuedJob job) { }

        @Override public void release(QueuedJob job, Duration delay) { }

        @Override public long size(String queue) { return pushed.size(); }

        @Override public void clear(String queue) { pushed.clear(); }
    }

    // ================= 脚手架 =================

    private RecordingQueue memory;
    private QueueManager queues;

    @BeforeEach
    void setUp() {
        EXECUTED.clear();
        Application.setContainer(new SimpleContainer());

        var serializer = new JobSerializer();
        memory = new RecordingQueue();
        queues = new QueueManager(serializer)
                .register("memory", m -> memory)
                .register("sync", m -> new SyncQueue(serializer,
                        new JobInvoker(Application::make)))
                .defaultQueue("default")
                .defaultConnection("memory");
        Queue.use(queues);
    }

    @AfterEach
    void tearDown() {
        Queue.reset();
        Application.reset();
    }

    // ================= 基本 =================

    @Nested
    @DisplayName("fake 之后不入队")
    class Interception {

        @Test
        @DisplayName("★ 投递被记下来,但驱动一条也没收到")
        void recordsWithoutPushing() {
            var fake = Queue.fake();

            Queue.dispatch(new SendInvoice(42L));

            assertThat(memory.pushed)
                    .as("fake 的全部意义:真的驱动一条也不该收到")
                    .isEmpty();
            fake.assertPushed(SendInvoice.class);
            assertThat(fake.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("dispatch 仍然返回一个 uuid —— 调用方拿它存关联")
        void stillReturnsUuid() {
            Queue.fake();

            assertThat(Queue.dispatch(new SendInvoice(1L))).isNotBlank();
        }

        @Test
        @DisplayName("stopFaking 之后真的入队")
        void stopFakingRestoresRealQueue() {
            Queue.fake();
            Queue.dispatch(new SendInvoice(1L));
            Queue.stopFaking();

            Queue.dispatch(new SendInvoice(2L));

            assertThat(Queue.isFaking()).isFalse();
            assertThat(memory.pushed).hasSize(1);
        }

        @Test
        @DisplayName("★ dispatchSync 不被拦下 —— 它就在调用栈上执行")
        void syncDispatchIsNotFaked() {
            var fake = Queue.fake();

            Queue.dispatchSync(new SendInvoice(7L));

            assertThat(EXECUTED)
                    .as("拦下它的结果是被测代码什么都没做而测试绿了")
                    .containsExactly("invoice:7");
            assertThatThrownBy(() -> fake.assertPushed(SendInvoice.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("dispatchSync");
        }

        @Test
        @DisplayName("不在 fake 模式时取记录器会报错并说清原因")
        void recorderWithoutFakeExplains() {
            assertThatThrownBy(Queue::recorder)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不在 fake 模式")
                    .hasMessageContaining("在被测代码**之前**调 Queue.fake()");
        }
    }

    // ================= 断言 =================

    @Nested
    @DisplayName("投递断言")
    class PushAssertions {

        @Test
        @DisplayName("assertPushed 的三个重载")
        void pushedOverloads() {
            var fake = Queue.fake();

            Queue.dispatch(new SendInvoice(42L));
            Queue.dispatch(new SendInvoice(43L));

            fake.assertPushed(SendInvoice.class);
            fake.assertPushed(SendInvoice.class, 2);
            fake.assertPushed(SendInvoice.class, j -> j.orderId().equals(42L));

            assertThatThrownBy(() -> fake.assertPushed(SendInvoice.class, 3))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("期望投递 3 次,实际 2 次");
        }

        @Test
        @DisplayName("★ 条件不满足时,消息里带上实际记录了什么")
        void failureListsWhatWasRecorded() {
            var fake = Queue.fake();
            Queue.dispatch(new SendInvoice(42L));

            assertThatThrownBy(() ->
                    fake.assertPushed(SendInvoice.class, j -> j.orderId().equals(99L)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("没有一个满足条件")
                    .hasMessageContaining("SendInvoice → mail@memory");
        }

        @Test
        @DisplayName("assertPushedOnQueue:队列名来自 onQueue / 任务的 queue()")
        void pushedOnQueue() {
            var fake = Queue.fake();

            Queue.dispatch(new SendInvoice(1L));                      // queue() = mail
            Queue.job(new RefundJob()).onQueue("refunds").dispatch();

            fake.assertPushedOnQueue("mail", SendInvoice.class);
            fake.assertPushedOnQueue("refunds", RefundJob.class);

            assertThatThrownBy(() -> fake.assertPushedOnQueue("default", SendInvoice.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("它去了 [mail]");
        }

        @Test
        @DisplayName("assertNotPushed / assertNothingPushed / assertPushedCount")
        void negativeAssertions() {
            var fake = Queue.fake();
            fake.assertNothingPushed();

            Queue.dispatch(new SendInvoice(1L));

            fake.assertNotPushed(RefundJob.class);
            fake.assertPushedCount(1);

            assertThatThrownBy(() -> fake.assertNotPushed(SendInvoice.class))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("不该投递 SendInvoice");
            assertThatThrownBy(fake::assertNothingPushed)
                    .isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("延迟投递也被记下延迟")
        void recordsDelay() {
            var fake = Queue.fake();

            Queue.dispatch(new SendInvoice(1L), Duration.ofMinutes(5));

            assertThat(fake.pushed().get(0).delay()).isEqualTo(Duration.ofMinutes(5));
        }
    }

    // ================= 链与批 =================

    @Nested
    @DisplayName("链与批")
    class ChainsAndBatches {

        @Test
        @DisplayName("★ assertChained 要求顺序一致 —— 链的意义就在顺序")
        void chainOrderMatters() {
            var fake = Queue.fake();

            Queue.chain(List.of(new ProcessPayment(), new SendInvoice(1L)))
                 .catchWith(new NotifyBroken())
                 .dispatch();

            assertThat(memory.pushed).isEmpty();
            fake.assertChained(List.of(ProcessPayment.class, SendInvoice.class));

            assertThatThrownBy(() ->
                    fake.assertChained(List.of(SendInvoice.class, ProcessPayment.class)))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("没有这样一条链");

            assertThat(fake.chains().get(0).onFailure()).isInstanceOf(NotifyBroken.class);
        }

        @Test
        @DisplayName("★ 批次在 fake 下不要求 job_batches 表")
        void batchNeedsNoRepository() {
            var fake = Queue.fake();

            // 没有调 queues.batches(...) —— 真实路径会在 requireBatches() 报错
            Batch batch = Queue.batch(List.of(
                    new ProcessPayment(), new SendInvoice(1L), new RefundJob())).dispatch();

            assertThat(batch.totalJobs()).isEqualTo(3);
            assertThat(memory.pushed).isEmpty();
            fake.assertBatched(b -> b.jobs().size() == 3);

            assertThatThrownBy(() -> fake.assertBatched(b -> b.jobs().isEmpty()))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("没有满足条件的批次");
        }

        @Test
        @DisplayName("链与批不计入 assertPushedCount")
        void chainsAndBatchesAreNotPushes() {
            var fake = Queue.fake();

            Queue.chain(List.of(new ProcessPayment())).dispatch();
            Queue.dispatch(new SendInvoice(1L));

            fake.assertPushedCount(1);
        }
    }

    // ================= 部分 fake =================

    @Nested
    @DisplayName("部分 fake(docs/14 §4.1)")
    class PartialFake {

        @Test
        @DisplayName("★ 只假装列出的类型,其余真的入队")
        void onlyListedTypesAreFaked() {
            var fake = Queue.fake(SendInvoice.class);

            Queue.dispatch(new SendInvoice(1L));
            Queue.dispatch(new RefundJob());

            fake.assertPushed(SendInvoice.class);
            fake.assertNotPushed(RefundJob.class);
            assertThat(memory.pushed)
                    .as("没被假装的那条必须真的入队 —— 否则它被静默吞了")
                    .hasSize(1);
        }

        @Test
        @DisplayName("空集合等同于全部假装")
        void emptyMeansEverything() {
            var fake = Queue.fake(List.of());

            Queue.dispatch(new RefundJob());

            fake.assertPushed(RefundJob.class);
            assertThat(memory.pushed).isEmpty();
        }
    }
}
