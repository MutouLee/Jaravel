package io.jaravel.queue.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;

/**
 * SQS 队列驱动(docs/10 §1.5)的真实 SQS 兼容服务测试。
 *
 * <p>给了 {@code -Djaravel.test.sqs.endpoint} 或环境变量
 * {@code JARAVEL_TEST_SQS_ENDPOINT} 才跑。用 ElasticMQ(轻量 SQS 兼容):
 *
 * <pre>
 * docker run -d --name jaravel-elasticmq -p 9324:9324 \
 *   softwaremill/elasticmq-native
 * </pre>
 *
 * <p>SQS 的 size 是近似值、purge 有延迟,所以断言用轮询。
 */
class SqsQueueTest {

    private static final JobSerializer SERIALIZER = new JobSerializer();
    private static String endpoint;
    private static String prefix;

    @BeforeAll
    static void connect() {
        endpoint = firstNonBlank(
                System.getProperty("jaravel.test.sqs.endpoint"),
                System.getenv("JARAVEL_TEST_SQS_ENDPOINT"));
        Assumptions.assumeTrue(endpoint != null,
                "设置 -Djaravel.test.sqs.endpoint 才跑 SQS 队列测试");
        prefix = "jaravel-test-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    }

    @AfterAll
    static void tearDown() {
        // 队列留在临时 ElasticMQ 上;每次运行前缀唯一
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static SqsQueue queue() {
        return SqsQueue.connect(endpoint, "us-east-1", "test", "test",
                SERIALIZER, prefix);
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

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("10 秒内没有满足:" + what);
    }

    @Test
    @DisplayName("push → pop(attempts=1)→ delete")
    void pushPopDelete() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("u1", q));
            await(() -> queue.size(q) == 1, "size = 1");

            var popped = queue.pop(q, Duration.ofSeconds(30)).orElseThrow();
            assertThat(popped.uuid()).isEqualTo("u1");
            assertThat(popped.attempts()).isEqualTo(1);

            queue.delete(popped);
            await(() -> queue.pop(q, Duration.ofSeconds(30)).isEmpty(), "队列为空");
        }
    }

    @Test
    @DisplayName("延迟投递:到点之前取不到,之后取得到")
    void delayedPush() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(delayed(job("d1", q), Duration.ofSeconds(2)));

            assertThat(queue.pop(q, Duration.ofSeconds(30))).isEmpty();
            Thread.sleep(3000);
            assertThat(queue.pop(q, Duration.ofSeconds(30)).orElseThrow().uuid())
                    .isEqualTo("d1");
        }
    }

    @Test
    @DisplayName("release 带延迟,attempts 保留到下一次")
    void releaseKeepsAttempts() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("r1", q));
            QueuedJob first = queue.pop(q, Duration.ofSeconds(30)).orElseThrow();
            assertThat(first.attempts()).isEqualTo(1);

            queue.release(first.withException(), Duration.ofSeconds(2));

            assertThat(queue.pop(q, Duration.ofSeconds(30))).isEmpty();
            Thread.sleep(3000);

            QueuedJob second = queue.pop(q, Duration.ofSeconds(30)).orElseThrow();
            assertThat(second.uuid()).isEqualTo("r1");
            assertThat(second.attempts()).isEqualTo(2);
            assertThat(second.exceptions()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("★ touch 延长可见性超时;未删除的消息不会在这期间重投")
    void touchExtendsVisibility() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("t1", q));
            QueuedJob popped = queue.pop(q, Duration.ofSeconds(1)).orElseThrow();

            assertThat(queue.touch(popped, Duration.ofSeconds(30))).isTrue();
            Thread.sleep(2500);

            assertThat(queue.pop(q, Duration.ofSeconds(30)))
                    .as("touch 之后 30 秒内不可见")
                    .isEmpty();

            queue.delete(popped);
        }
    }

    @Test
    @DisplayName("★ 可见性超时后未 ack 的消息自动重投")
    void visibilityTimeoutRedelivers() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("v1", q));
            var first = queue.pop(q, Duration.ofSeconds(1)).orElseThrow();
            assertThat(first.uuid()).isEqualTo("v1");

            Thread.sleep(2500);
            var second = queue.pop(q, Duration.ofSeconds(30));
            assertThat(second).isPresent();
            assertThat(second.orElseThrow().uuid()).isEqualTo("v1");

            queue.delete(second.orElseThrow());
        }
    }

    @Test
    @DisplayName("★ 崩溃重投也算一次尝试 —— 否则重试上限失效")
    void crashRedeliveryCountsAsAnAttempt() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("a1", q));

            // 取出来就「崩掉」:既不 delete 也不 release,
            // payload 里的 attempts 一个字都没改
            assertThat(queue.pop(q, Duration.ofSeconds(1)).orElseThrow().attempts())
                    .isEqualTo(1);
            Thread.sleep(2500);

            var second = queue.pop(q, Duration.ofSeconds(1)).orElseThrow();
            assertThat(second.attempts())
                    .as("""
                            只看 payload 的实现这里会一直是 1 —— 于是一个每次都把
                            worker 弄死的任务永远重投,而症状是「有一条任务一直在跑」。
                            权威值来自 broker 的 ApproximateReceiveCount。""")
                    .isEqualTo(2);
            Thread.sleep(2500);

            var third = queue.pop(q, Duration.ofSeconds(30)).orElseThrow();
            assertThat(third.attempts()).isEqualTo(3);
            assertThat(third.canRetry(Instant.now()))
                    .as("tries=3,第三次就该进 failed_jobs 而不是再放回去")
                    .isFalse();

            queue.delete(third);
        }
    }

    @Test
    @DisplayName("release 之后 attempts 接着 payload 走 —— 新消息的投递计数会重置")
    void releaseKeepsCountingFromThePayload() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("a2", q));
            var first = queue.pop(q, Duration.ofSeconds(30)).orElseThrow();

            // release 发的是一条**新消息**,它的 ApproximateReceiveCount 从 1 开始;
            // 取 max 的另一半意义就在这里 —— payload 里的计数更大
            queue.release(first.withException(), Duration.ZERO);
            await(() -> queue.size(q) >= 1, "release 后消息回到队列");

            var second = queue.pop(q, Duration.ofSeconds(30)).orElseThrow();
            assertThat(second.attempts()).isEqualTo(2);

            queue.delete(second);
        }
    }

    @Test
    @DisplayName("clear 清空队列")
    void clear() throws InterruptedException {
        String q = q();
        try (var queue = queue()) {
            queue.push(job("c1", q));
            queue.push(job("c2", q));
            await(() -> queue.size(q) == 2, "size = 2");

            queue.clear(q);

            await(() -> queue.size(q) == 0, "size = 0");
            assertThat(queue.pop(q, Duration.ofSeconds(30))).isEmpty();
        }
    }
}
