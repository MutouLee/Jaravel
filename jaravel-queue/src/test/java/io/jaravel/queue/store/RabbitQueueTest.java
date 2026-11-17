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
        }
    }
}
