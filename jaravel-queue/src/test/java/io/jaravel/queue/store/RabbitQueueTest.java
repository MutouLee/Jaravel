package io.jaravel.queue.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;

/**
 * RabbitMQ 队列驱动(docs/10 §1.5)的真实 broker 测试。
 *
 * <p>给了 {@code -Djaravel.test.rabbitmq.uri} 或环境变量
 * {@code JARAVEL_TEST_RABBITMQ_URI} 才跑:
 * <pre>
 * docker run -d --name jaravel-rabbitmq -p 5672:5672 -p 15672:15672 \
 *   rabbitmq:3-management
 * </pre>
 * 用唯一前缀;队列不删除(临时 broker),测试内 clear。
 */
class RabbitQueueTest {

    private static final JobSerializer SERIALIZER = new JobSerializer();
    private static String uri;
    private static String prefix;

    @BeforeAll
    static void connect() {
        uri = firstNonBlank(
                System.getProperty("jaravel.test.rabbitmq.uri"),
                System.getenv("JARAVEL_TEST_RABBITMQ_URI"));
        Assumptions.assumeTrue(uri != null,
                "设置 -Djaravel.test.rabbitmq.uri 才跑 RabbitMQ 队列测试");
        prefix = "jaravel-rabbit-test:" + UUID.randomUUID() + ":";
    }

    @AfterAll
    static void tearDown() {
        // 队列留在临时 broker 上;每个测试用唯一 queue 名,不会互相污染
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static RabbitQueue queue() {
        return new RabbitQueue(uri, SERIALIZER, prefix);
    }

    private static String q() {
        return "q" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static QueuedJob job(String uuid, String queue) {
        return QueuedJob.forTest(uuid, "app.jobs.TestJob", Map.of("x", 1), queue, 0, 3);
    }

    private static QueuedJob delayed(QueuedJob base, Duration delay) {
        return new QueuedJob(base.uuid(), base.jobClass(), base.data(), base.queue(),
                base.attempts(), base.pushedAt(), Instant.now().plus(delay),
                base.tries(), base.timeout(), base.retryUntil(), base.maxExceptions(),
                base.displayName(), base.exceptions(), base.batchId(),
                base.chain(), base.chainCatch(), base.context());
    }

    @Test
    @DisplayName("push → pop(attempts=1)→ delete")
    void pushPopDelete() {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("u1", q));
            assertThat(queue.size(q)).isEqualTo(1);

            var popped = queue.pop(q, Duration.ofSeconds(10)).orElseThrow();
            assertThat(popped.uuid()).isEqualTo("u1");
            assertThat(popped.attempts()).isEqualTo(1);

            queue.delete(popped);
            assertThat(queue.size(q)).isZero();
            assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();
        }
    }

    @Test
    @DisplayName("FIFO")
    void fifo() {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("a", q));
            queue.push(job("b", q));

            assertThat(queue.pop(q, Duration.ofSeconds(10)).orElseThrow().uuid()).isEqualTo("a");
            assertThat(queue.pop(q, Duration.ofSeconds(10)).orElseThrow().uuid()).isEqualTo("b");
        }
    }

    @Test
    @DisplayName("延迟投递:到点之前取不到,之后取得到")
    void delayedPush() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(delayed(job("d1", q), Duration.ofMillis(300)));

            assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();
            Thread.sleep(800);
            assertThat(queue.pop(q, Duration.ofSeconds(10)).orElseThrow().uuid())
                    .isEqualTo("d1");
        }
    }

    @Test
    @DisplayName("release 带延迟,attempts 保留到下一次")
    void releaseKeepsAttempts() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("r1", q));
            QueuedJob first = queue.pop(q, Duration.ofSeconds(10)).orElseThrow();
            assertThat(first.attempts()).isEqualTo(1);

            queue.release(first.withException(), Duration.ofMillis(300));

            assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();
            Thread.sleep(800);

            QueuedJob second = queue.pop(q, Duration.ofSeconds(10)).orElseThrow();
            assertThat(second.uuid()).isEqualTo("r1");
            assertThat(second.attempts()).isEqualTo(2);
            assertThat(second.exceptions()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("touch 在 unacked 时 true,delete 后 false")
    void touch() {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("t1", q));
            QueuedJob popped = queue.pop(q, Duration.ofSeconds(10)).orElseThrow();

            assertThat(queue.touch(popped, Duration.ofSeconds(10))).isTrue();
            queue.delete(popped);
            assertThat(queue.touch(popped, Duration.ofSeconds(10))).isFalse();
        }
    }

    @Test
    @DisplayName("size 不含 unacked(AMQP 的语义)")
    void sizeExcludesUnacked() {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("s1", q));
            queue.pop(q, Duration.ofSeconds(10)).orElseThrow();

            assertThat(queue.size(q))
                    .as("basicGet 之后消息 unacked,ready 计数为 0")
                    .isZero();
        }
    }

    @Test
    @DisplayName("clear 清空 ready 与 delayed")
    void clear() {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("c1", q));
            queue.push(delayed(job("c2", q), Duration.ofSeconds(30)));

            queue.clear(q);

            assertThat(queue.size(q)).isZero();
            assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();
        }
    }

    @Test
    @DisplayName("★ 连接断开时 unacked 消息被 broker 重投")
    void unackedIsRedeliveredAfterConnectionLoss() {
        String q = q();

        var first = queue();
        first.push(job("redeliver", q));
        QueuedJob popped = first.pop(q, Duration.ofSeconds(10)).orElseThrow();
        assertThat(popped.uuid()).isEqualTo("redeliver");

        // 不 ack 直接关闭 —— 模拟 worker 崩溃
        first.close();

        try (var second = queue()) {
            var redelivered = second.pop(q, Duration.ofSeconds(10));
            assertThat(redelivered).isPresent();
            assertThat(redelivered.orElseThrow().uuid()).isEqualTo("redeliver");
            assertThat(redelivered.orElseThrow().attempts())
                    .as("""
                            classic 队列没有 x-delivery-count —— 重投的 payload
                            是旧的,所以这里仍然是 1。这条断言钉住的是**限制本身**:
                            要让崩溃重投算一次尝试,得用 quorum 队列(见下一组测试)。""")
                    .isEqualTo(1);
        }
    }

    // ================= quorum 队列:崩溃重投也算一次尝试 =================

    private static RabbitQueue quorumQueue() {
        return new RabbitQueue(uri, SERIALIZER, prefix + "quorum:", true);
    }

    @Test
    @DisplayName("★ quorum 队列:崩溃重投让 attempts 递增,重试上限才成立")
    void quorumCountsCrashRedeliveries() {
        String q = q();

        var first = quorumQueue();
        first.push(job("qa", q));
        assertThat(first.pop(q, Duration.ofSeconds(10)).orElseThrow().attempts())
                .isEqualTo(1);
        first.close();                       // 不 ack 直接关闭 = worker 崩了

        var second = quorumQueue();
        QueuedJob redelivered = second.pop(q, Duration.ofSeconds(10)).orElseThrow();
        assertThat(redelivered.attempts())
                .as("""
                        权威值来自 broker 的 x-delivery-count。只看 payload 的话
                        这里是 1,于是一个每次都把 worker 弄死的任务永远重投。""")
                .isEqualTo(2);
        second.close();

        try (var third = quorumQueue()) {
            QueuedJob last = third.pop(q, Duration.ofSeconds(10)).orElseThrow();
            assertThat(last.attempts()).isEqualTo(3);
            assertThat(last.canRetry(Instant.now()))
                    .as("tries=3,第三次就该进 failed_jobs")
                    .isFalse();

            third.delete(last);
        }
    }

    @Test
    @DisplayName("quorum 队列上 push/pop/release/延迟 与 classic 行为一致")
    void quorumBehavesLikeClassicOtherwise() {
        String q = q();
        try (var queue = quorumQueue()) {
            queue.push(job("q1", q));
            QueuedJob popped = queue.pop(q, Duration.ofSeconds(10)).orElseThrow();
            assertThat(popped.attempts()).isEqualTo(1);

            // 延迟队列仍然是 classic(per-message TTL + 死信回 quorum 主队列)
            queue.release(popped.withException(), Duration.ofMillis(300));
            assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();

            QueuedJob second = awaitPop(queue, q);
            assertThat(second.attempts()).isEqualTo(2);
            assertThat(second.exceptions()).isEqualTo(1);

            queue.delete(second);
            assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();
        }
    }

    @Test
    @DisplayName("★ 队列类型改不了:报错要说清「删掉它或配回去」")
    void switchingQueueTypeFailsWithAnActionableMessage() {
        String q = q();
        try (var classic = new RabbitQueue(uri, SERIALIZER, prefix + "switch:")) {
            classic.push(job("s1", q));
        }

        // 同一个队列名,改声明成 quorum —— broker 给的是
        // 「PRECONDITION_FAILED - inequivalent arg 'x-queue-type'」,
        // 那句话不会告诉你要怎么办
        try (var quorum = new RabbitQueue(uri, SERIALIZER, prefix + "switch:", true)) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> quorum.push(job("s2", q)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("队列类型不能原地改")
                    .hasMessageContaining("queue-type");
        }
    }

    private static QueuedJob awaitPop(RabbitQueue queue, String q) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var found = queue.pop(q, Duration.ofSeconds(10));
            if (found.isPresent()) return found.get();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待延迟消息时被中断", e);
            }
        }
        throw new AssertionError("10 秒内没有取到延迟后的消息");
    }
}
