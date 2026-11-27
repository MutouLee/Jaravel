package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import app.http.controllers.IdempotencyDemoController;
import net.dobeen.jaravel.cache.Repository;
import net.dobeen.jaravel.testing.FeatureTest;

/**
 * 幂等性中间件的端到端(docs/12 §6.5)。
 *
 * <p>走真实 HTTP 管道与真实缓存:同一个 Idempotency-Key 的第二次请求
 * **不执行**控制器,直接返回第一次的响应;5xx 不缓存,允许重试。
 */
class IdempotencyFeatureTest extends FeatureTest {

    @Autowired Repository cache;

    @BeforeEach
    void reset() {
        IdempotencyDemoController.CALLS.set(0);
        IdempotencyDemoController.FAIL = false;
        cache.flush();
    }

    @Test
    @DisplayName("★ 同一个 key 的第二次请求直接重放响应,控制器只跑一次")
    void sameKeyReplaysTheFirstResponse() {
        var first = withHeader("Idempotency-Key", "pay-1")
                .postJson("/api/idempotency-demo", Map.of("amount", 100));
        var second = withHeader("Idempotency-Key", "pay-1")
                .postJson("/api/idempotency-demo", Map.of("amount", 100));

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.content())
                .as("重放的是第一次的响应,不是重新执行的结果")
                .isEqualTo(first.content());
        assertThat(IdempotencyDemoController.CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 key 各执行一次")
    void differentKeysExecuteSeparately() {
        withHeader("Idempotency-Key", "pay-a")
                .postJson("/api/idempotency-demo", Map.of("amount", 1));
        withHeader("Idempotency-Key", "pay-b")
                .postJson("/api/idempotency-demo", Map.of("amount", 2));

        assertThat(IdempotencyDemoController.CALLS.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("没有 key 不要求幂等 —— 每次照常执行")
    void withoutKeyEachRequestRuns() {
        postJson("/api/idempotency-demo", Map.of("amount", 1));
        postJson("/api/idempotency-demo", Map.of("amount", 1));

        assertThat(IdempotencyDemoController.CALLS.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 同 key 不同请求体 → 422,不执行也不重放")
    void sameKeyDifferentBodyIsRejected() {
        withHeader("Idempotency-Key", "pay-diff")
                .postJson("/api/idempotency-demo", Map.of("amount", 1));

        var second = withHeader("Idempotency-Key", "pay-diff")
                .postJson("/api/idempotency-demo", Map.of("amount", 2));

        assertThat(second.status()).isEqualTo(422);
        assertThat(IdempotencyDemoController.CALLS.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 5xx 不缓存 —— 允许用同一个 key 重试")
    void serverErrorsAreNotCached() {
        IdempotencyDemoController.FAIL = true;

        var failed = withHeader("Idempotency-Key", "pay-fail")
                .postJson("/api/idempotency-demo/fail", Map.of());
        assertThat(failed.status()).isEqualTo(500);

        IdempotencyDemoController.FAIL = false;
        var retried = withHeader("Idempotency-Key", "pay-fail")
                .postJson("/api/idempotency-demo/fail", Map.of());

        assertThat(retried.status()).isEqualTo(200);
        assertThat(IdempotencyDemoController.CALLS.get())
                .as("第一次 500 没有执行到计数;第二次成功执行")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("非法 key → 400,不执行")
    void invalidKeyIsRejected() {
        var response = withHeader("Idempotency-Key", "x".repeat(300))
                .postJson("/api/idempotency-demo", Map.of());

        assertThat(response.status()).isEqualTo(400);
        assertThat(IdempotencyDemoController.CALLS.get()).isZero();
    }
}
