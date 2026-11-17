package io.jaravel.queue.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.cache.store.RedisStore;
import io.jaravel.queue.Batch;
import io.jaravel.queue.BatchRepository;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;

/**
 * 批次状态的 redis 存储(docs/10 §1.5)。
 *
 * <p>给了 {@code -Djaravel.test.redis.uri} 才跑。重点钉住与 database
 * 实现相同的语义:原子计数、markFinished 只成功一次、prune 不碰在跑的批次。
 */
class RedisBatchRepositoryTest {

    private static final JobSerializer SERIALIZER = new JobSerializer();
    private static RedisStore connection;
    private static String prefix;
    private static RedisBatchRepository store;

    @BeforeAll
    static void connect() {
        String uri = firstNonBlank(
                System.getProperty("jaravel.test.redis.uri"),
                System.getenv("JARAVEL_TEST_REDIS_URI"));
        Assumptions.assumeTrue(uri != null,
                "设置 -Djaravel.test.redis.uri 才跑 Redis 批次测试");

        prefix = "jaravel-batch-test:" + UUID.randomUUID() + ":";
        connection = new RedisStore(uri, prefix);
        store = new RedisBatchRepository(connection, SERIALIZER, prefix);
    }

    @BeforeEach
    void clear() {
        connection.flush();
    }

    @AfterAll
    static void tearDown() {
        if (connection != null) {
            connection.flush();
            connection.close();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static QueuedJob callback(String name) {
        return QueuedJob.forTest("cb-" + name, "app.jobs." + name,
                Map.of(), "default", 0, 1);
    }

    private static String create() {
        String id = UUID.randomUUID().toString();
        store.create(id, "月度报表", 3, true, callback("Then"), callback("Catch"),
                callback("Finally"));
        return id;
    }

    @Test
    @DisplayName("create → find,字段与回调完整")
    void createAndFind() {
        String id = create();

        Batch batch = store.find(id).orElseThrow();
        assertThat(batch.name()).isEqualTo("月度报表");
        assertThat(batch.totalJobs()).isEqualTo(3);
        assertThat(batch.pendingJobs()).isEqualTo(3);
        assertThat(batch.failedJobs()).isZero();
        assertThat(batch.allowsFailures()).isTrue();
        assertThat(batch.createdAt()).isNotNull();
        assertThat(batch.finishedAt()).isNull();

        assertThat(store.callback(id, BatchRepository.Kind.THEN)).isPresent();
        assertThat(store.callback(id, BatchRepository.Kind.CATCH)).isPresent();
        assertThat(store.callback(id, BatchRepository.Kind.FINALLY)).isPresent();
    }

    @Test
    @DisplayName("★ markFinished 只在第一次返回 true —— 回调只跑一次")
    void markFinishedOnlyOnce() {
        String id = create();

        assertThat(store.markFinished(id)).isTrue();
        assertThat(store.markFinished(id)).isFalse();
        assertThat(store.find(id).orElseThrow().isFinished()).isTrue();
    }

    @Test
    @DisplayName("recordSuccess / recordFailure 原子计数")
    void counters() {
        String id = create();

        Batch afterSuccess = store.recordSuccess(id);
        assertThat(afterSuccess.pendingJobs()).isEqualTo(2);

        Batch afterFailure = store.recordFailure(id, "u-fail-1");
        assertThat(afterFailure.pendingJobs()).isEqualTo(1);
        assertThat(afterFailure.failedJobs()).isEqualTo(1);
        assertThat(afterFailure.failedJobIds()).containsExactly("u-fail-1");

        Batch another = store.recordFailure(id, "u-fail-2");
        assertThat(another.failedJobIds())
                .containsExactly("u-fail-1", "u-fail-2");

        // 计数不会减成负数
        store.recordSuccess(id);
        store.recordSuccess(id);
        assertThat(store.find(id).orElseThrow().pendingJobs()).isZero();
    }

    @Test
    @DisplayName("cancel 幂等 —— 取消时间不会被后一次调用推后")
    void cancelIsIdempotent() throws InterruptedException {
        String id = create();

        store.cancel(id);
        var first = store.find(id).orElseThrow().cancelledAt();
        Thread.sleep(5);
        store.cancel(id);
        var second = store.find(id).orElseThrow().cancelledAt();

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("recent 按创建时间倒序")
    void recent() throws InterruptedException {
        String firstId = create();
        Thread.sleep(5);
        String secondId = create();

        assertThat(store.recent(1)).extracting(Batch::id).containsExactly(secondId);
        assertThat(store.recent(10)).extracting(Batch::id)
                .containsExactly(secondId, firstId);
    }

    @Test
    @DisplayName("★ prune 只清已完成的,在跑的批次不动")
    void pruneOnlyFinished() throws InterruptedException {
        String finishedId = create();
        String runningId = create();
        store.markFinished(finishedId);
        Thread.sleep(10);

        assertThat(store.prune(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(store.find(finishedId)).isEmpty();
        assertThat(store.find(runningId)).isPresent();
    }

    @Test
    @DisplayName("未知批次:recordSuccess 报错,markFinished 返回 false")
    void unknownBatch() {
        assertThat(store.markFinished("no-such-batch")).isFalse();
        assertThatThrownBy(() -> store.recordSuccess("no-such-batch"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("查不到");
    }
}
