package net.dobeen.jaravel.cache.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.cache.CacheStore;

/**
 * 两级缓存的 L1 跨实例失效(docs/09 §7)。
 *
 * <p>两个 tiered store 各有独立 L1、共用一个 Redis L2;A 的 forget 必须
 * 让 B 的 L1 失效。用真实 Redis pub/sub,给了
 * {@code -Djaravel.test.redis.uri} 才跑。
 */
class TieredStoreInvalidationTest {

    private static RedisStore l2;
    private static RedisCacheInvalidator invalidator;
    private static TieredStore a;
    private static TieredStore b;
    private static CacheStore l1a;
    private static CacheStore l1b;

    @BeforeAll
    static void connect() {
        String uri = firstNonBlank(
                System.getProperty("jaravel.test.redis.uri"),
                System.getenv("JARAVEL_TEST_REDIS_URI"));
        Assumptions.assumeTrue(uri != null,
                "设置 -Djaravel.test.redis.uri 才跑 L1 失效测试");

        l2 = new RedisStore(uri, "jaravel-tiered-test:" + UUID.randomUUID() + ":");
        invalidator = new RedisCacheInvalidator(uri,
                "jaravel-tiered-invalidate:" + UUID.randomUUID());

        l1a = new ArrayStore();
        l1b = new ArrayStore();
        a = new TieredStore(l1a, l2, Duration.ofSeconds(30), invalidator::publish);
        b = new TieredStore(l1b, l2, Duration.ofSeconds(30), invalidator::publish);
        invalidator.register(a);
        invalidator.register(b);
    }

    @AfterAll
    static void tearDown() {
        if (invalidator != null) invalidator.close();
        if (l2 != null) {
            l2.flush();
            l2.close();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    @Test
    @DisplayName("★ A 的 forget 让 B 的 L1 失效(不只是 L1 过期)")
    void forgetInvalidatesOtherInstancesL1() {
        a.put("k", "v", Duration.ofMinutes(1));

        assertThat(b.get("k")).contains("v");
        assertThat(l1b.get("k"))
                .as("B 的 L1 已经回填")
                .isPresent();

        a.forget("k");

        awaitEmpty(l1b, "k");
        assertThat(b.get("k"))
                .as("L2 也删了,所以这里是未命中")
                .isEmpty();
    }

    @Test
    @DisplayName("★ A 的 flush 让 B 清空整个 L1")
    void flushInvalidatesAllLocalEntries() {
        a.put("k2", "v2", Duration.ofMinutes(1));
        assertThat(b.get("k2")).contains("v2");
        assertThat(l1b.get("k2")).isPresent();

        a.flush();

        awaitEmpty(l1b, "k2");
    }

    private static void awaitEmpty(CacheStore store, String key) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (store.get(key).isEmpty()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("5 秒内没有收到 L1 失效:" + key);
    }
}
