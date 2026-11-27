package net.dobeen.jaravel.queue.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.cache.store.RedisStore;
import net.dobeen.jaravel.queue.JobSerializer;
import net.dobeen.jaravel.queue.QueuedJob;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * 真实 Redis 上的队列语义(docs/10 §3)。
 *
 * <p>没有配置 Redis 时整类跳过 —— 队列的其余驱动(database / sync /
 * null)仍有自己的测试。配置方式:
 * {@code -Djaravel.test.redis.uri=redis://127.0.0.1:6379/15}
 * 或环境变量 {@code JARAVEL_TEST_REDIS_URI}。
 * 用独立的库/前缀,清理由 {@code @AfterAll} 完成。
 *
 * <p>这里钉住的都是「换驱动后行为不能变」的契约:
 * 原子 pop、attempts 跨崩溃、可见性超时、延迟、续约、删除。
 */
class RedisQueueTest {

    private static final JobSerializer SERIALIZER = new JobSerializer();

    private static RedisStore connection;
    private static RedisCommands<String, String> commands;
    private static String prefix;
    private static RedisQueue queue;

    @BeforeAll
    static void connect() {
        String uri = firstNonBlank(
                System.getProperty("jaravel.test.redis.uri"),
                System.getenv("JARAVEL_TEST_REDIS_URI"));
        Assumptions.assumeTrue(uri != null,
                "设置 -Djaravel.test.redis.uri 才跑 Redis 队列测试");

        connection = new RedisStore(uri);
        prefix = "jaravel-queue-test:" + UUID.randomUUID() + ":";
        queue = new RedisQueue(connection, SERIALIZER, prefix);
        commands = connection.raw();
    }

    @AfterAll
    static void cleanUp() {
        if (commands == null) return;
        try {
            var keys = commands.keys(prefix + "*");
            if (!keys.isEmpty()) commands.del(keys.toArray(String[]::new));
        } finally {
            connection.close();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    /** 每个用例一个独立队列名,互不干扰 */
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
        queue.push(job("u1", q));

        assertThat(queue.size(q)).isEqualTo(1);

        var popped = queue.pop(q, Duration.ofSeconds(10));
        assertThat(popped).isPresent();
        assertThat(popped.get().uuid()).isEqualTo("u1");
        assertThat(popped.get().attempts()).isEqualTo(1);

        queue.delete(popped.get());
        assertThat(queue.size(q)).isZero();
        assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    @DisplayName("FIFO:先入先出")
    void fifo() {
        String q = q();
        queue.push(job("a", q));
        queue.push(job("b", q));

        assertThat(queue.pop(q, Duration.ofSeconds(10))).get().extracting(QueuedJob::uuid)
                .isEqualTo("a");
        assertThat(queue.pop(q, Duration.ofSeconds(10))).get().extracting(QueuedJob::uuid)
                .isEqualTo("b");
    }

    @Test
    @DisplayName("延迟任务到点之前取不到,到点之后取得到")
    void delayedJob() throws InterruptedException {
        String q = q();
        queue.push(delayed(job("d1", q), Duration.ofMillis(250)));

        assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();

        Thread.sleep(400);
        assertThat(queue.pop(q, Duration.ofSeconds(10))).get().extracting(QueuedJob::uuid)
                .isEqualTo("d1");
    }

    @Test
    @DisplayName("release 保留 attempts,下一次 pop 是第 2 次")
    void releaseKeepsAttempts() throws InterruptedException {
        String q = q();
        queue.push(job("r1", q));

        QueuedJob first = queue.pop(q, Duration.ofSeconds(10)).orElseThrow();
        assertThat(first.attempts()).isEqualTo(1);

        queue.release(first.withException(), Duration.ofMillis(200));

        assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();
        Thread.sleep(350);

        QueuedJob second = queue.pop(q, Duration.ofSeconds(10)).orElseThrow();
        assertThat(second.uuid()).isEqualTo("r1");
        assertThat(second.attempts())
                .as("attempts 必须跨 release 保留")
                .isEqualTo(2);
        assertThat(second.exceptions())
                .as("release 会重写 payload,异常次数不能丢")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("worker 崩了(不 touch):保留超时后能被重新取到")
    void staleReservationIsRecovered() throws InterruptedException {
        String q = q();
        queue.push(job("s1", q));

        QueuedJob first = queue.pop(q, Duration.ofMillis(100)).orElseThrow();
        assertThat(first.attempts()).isEqualTo(1);

        // 不 touch,模拟 worker 在处理中途崩溃
        Thread.sleep(250);

        QueuedJob second = queue.pop(q, Duration.ofMillis(100)).orElseThrow();
        assertThat(second.uuid()).isEqualTo("s1");
        assertThat(second.attempts())
                .as("崩溃一次也必须计入 attempts")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("touch:仍在保留中返回 true,删除后返回 false")
    void touchOnlyWhileReserved() {
        String q = q();
        queue.push(job("t1", q));
        QueuedJob popped = queue.pop(q, Duration.ofSeconds(10)).orElseThrow();

        assertThat(queue.touch(popped, Duration.ofSeconds(10))).isTrue();

        queue.delete(popped);
        assertThat(queue.touch(popped, Duration.ofSeconds(10))).isFalse();
    }

    @Test
    @DisplayName("size 含 pending / delayed / reserved")
    void sizeCountsAll() {
        String q = q();
        queue.push(job("p1", q));
        queue.push(delayed(job("d1", q), Duration.ofSeconds(30)));

        queue.pop(q, Duration.ofSeconds(10)).orElseThrow();   // p1 → reserved

        // delayed 1 + reserved 1
        assertThat(queue.size(q)).isEqualTo(2);
    }

    @Test
    @DisplayName("clear 清掉这个队列的所有键")
    void clear() {
        String q = q();
        queue.push(job("c1", q));
        queue.push(delayed(job("c2", q), Duration.ofSeconds(30)));

        queue.clear(q);

        assertThat(queue.size(q)).isZero();
        assertThat(queue.pop(q, Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    @DisplayName("★ 并发 pop 不重复取到同一条")
    void concurrentPopNeverDuplicates() throws Exception {
        String q = q();
        int jobs = 30;
        for (int i = 0; i < jobs; i++) queue.push(job("j" + i, q));

        var seen = ConcurrentHashMap.<String>newKeySet();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            Thread t = Thread.ofVirtual().start(() -> {
                while (true) {
                    var next = queue.pop(q, Duration.ofSeconds(30));
                    if (next.isEmpty()) break;
                    assertThat(seen.add(next.get().uuid())).isTrue();
                    queue.delete(next.get());
                }
            });
            threads.add(t);
        }
        for (Thread t : threads) t.join();

        assertThat(seen).hasSize(jobs);
        assertThat(queue.size(q)).isZero();
    }
}
