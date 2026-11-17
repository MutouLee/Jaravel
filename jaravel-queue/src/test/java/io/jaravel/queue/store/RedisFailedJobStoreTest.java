package io.jaravel.queue.store;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueuedJob;

/**
 * 失败任务的 redis 存储(docs/10 §1.7)。
 *
 * <p>给了 {@code -Djaravel.test.redis.uri} 才跑。独立库/前缀,清理在
 * {@code @BeforeEach} 与 {@code @AfterAll}。
 */
class RedisFailedJobStoreTest {

    private static final JobSerializer SERIALIZER = new JobSerializer();
    private static RedisStore connection;
    private static String prefix;
    private static RedisFailedJobStore store;

    @BeforeAll
    static void connect() {
        String uri = firstNonBlank(
                System.getProperty("jaravel.test.redis.uri"),
                System.getenv("JARAVEL_TEST_REDIS_URI"));
        Assumptions.assumeTrue(uri != null,
                "设置 -Djaravel.test.redis.uri 才跑 Redis 失败任务测试");

        prefix = "jaravel-failed-test:" + UUID.randomUUID() + ":";
        connection = new RedisStore(uri, prefix);
        store = new RedisFailedJobStore(connection, SERIALIZER, prefix);
    }

    @BeforeEach
    void clear() {
        store.flush();
    }

    @AfterAll
    static void tearDown() {
        if (store != null) store.flush();
        if (connection != null) connection.close();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static QueuedJob job(String uuid) {
        return QueuedJob.forTest(uuid, "app.jobs.TestJob", Map.of("x", 1), "default", 3, 3);
    }

    @Test
    @DisplayName("record → find / all / count,字段完整")
    void recordAndFind() {
        long id = store.record(job("u1"), "redis", new IllegalStateException("boom"));

        assertThat(id).isPositive();
        assertThat(store.count()).isEqualTo(1);

        var found = store.find(id).orElseThrow();
        assertThat(found.uuid()).isEqualTo("u1");
        assertThat(found.connection()).isEqualTo("redis");
        assertThat(found.queue()).isEqualTo("default");
        assertThat(found.displayName()).isEqualTo("test");
        assertThat(found.exception()).contains("boom").contains("IllegalStateException");
        assertThat(found.failedAt()).isNotNull();
        assertThat(found.reason()).contains("boom");

        assertThat(store.all()).extracting(FailedJobStore.FailedJob::uuid)
                .containsExactly("u1");
    }

    @Test
    @DisplayName("★ 同一个 uuid 再失败:覆盖,不追加")
    void sameUuidOverwrites() {
        long first = store.record(job("dup"), "redis", new IllegalStateException("first"));
        long second = store.record(job("dup"), "database", new IllegalStateException("second"));

        assertThat(second).isEqualTo(first);
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.find(first).orElseThrow().exception()).contains("second");
        assertThat(store.find(first).orElseThrow().connection()).isEqualTo("database");
    }

    @Test
    @DisplayName("forget / flush")
    void forgetAndFlush() {
        long id = store.record(job("f1"), "redis", new IllegalStateException("x"));

        assertThat(store.forget(id)).isTrue();
        assertThat(store.forget(id)).isFalse();
        assertThat(store.count()).isZero();

        store.record(job("f2"), "redis", new IllegalStateException("x"));
        assertThat(store.flush()).isEqualTo(1);
        assertThat(store.count()).isZero();
    }

    @Test
    @DisplayName("prune 只删超过时长的")
    void prune() throws InterruptedException {
        store.record(job("old"), "redis", new IllegalStateException("old"));
        Thread.sleep(10);

        assertThat(store.prune(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(store.count()).isZero();

        store.record(job("new"), "redis", new IllegalStateException("new"));
        assertThat(store.prune(Duration.ofHours(1))).isZero();
        assertThat(store.count()).isEqualTo(1);
    }
}
