package net.dobeen.jaravel.spring.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.cache.Repository;
import net.dobeen.jaravel.cache.store.ArrayStore;
import net.dobeen.jaravel.core.config.MapConfigRepository;
import net.dobeen.jaravel.http.BasicRequest;
import net.dobeen.jaravel.http.Request;
import net.dobeen.jaravel.http.Response;

/**
 * 幂等性中间件的核心语义(docs/12 §6.5)。
 *
 * <p>真实 HTTP 的端到端在 examples/queue 的 {@code IdempotencyFeatureTest};
 * 这里直接驱动中间件,覆盖「没有 key / 不同 key / 5xx 不缓存 / 非法 key」
 * 这些分支 —— 它们不值得各起一个 Spring 上下文。
 */
class IdempotentTest {

    private Repository cache;
    private Idempotent middleware;

    @BeforeEach
    void setUp() {
        // 标成共享,避免每个用例打一次「多实例会重复执行」的警告。
        // 测试要的是一致性语义,不是警告本身。
        cache = new Repository(new ArrayStore() {
            @Override public boolean isShared() {
                return true;
            }
        });
        middleware = new Idempotent(cache, new MapConfigRepository(Map.of(
                "api", Map.of("idempotency", Map.of("ttl", "1h")))));
    }

    private static Request request(String method, String key) {
        var request = BasicRequest.of(method, "/payments");
        if (key != null) request.header("Idempotency-Key", key);
        return request;
    }

    private Response call(Request request, AtomicInteger calls) {
        Function<Request, Response> next = req ->
                Response.json(Map.of("calls", calls.incrementAndGet()));
        return middleware.handle(request, next);
    }

    @Test
    @DisplayName("同 key 第二次直接重放,next 只跑一次")
    void sameKeyReplays() {
        var calls = new AtomicInteger();

        var first = call(request("POST", "k1"), calls);
        var second = call(request("POST", "k1"), calls);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    @DisplayName("没带 key → 每次照常执行")
    void withoutKeyRunsEachTime() {
        var calls = new AtomicInteger();

        call(request("POST", null), calls);
        call(request("POST", null), calls);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET 不参与幂等")
    void getIsIgnored() {
        var calls = new AtomicInteger();

        middleware.handle(request("GET", "k2"), req -> {
            calls.incrementAndGet();
            return Response.json(Map.of());
        });
        middleware.handle(request("GET", "k2"), req -> {
            calls.incrementAndGet();
            return Response.json(Map.of());
        });

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 5xx 不缓存 —— 同一个 key 可以重试")
    void serverErrorIsNotCached() {
        var calls = new AtomicInteger();

        var failed = middleware.handle(request("POST", "k3"),
                req -> Response.text("boom").status(500));
        var retried = call(request("POST", "k3"), calls);

        assertThat(failed.status()).isEqualTo(500);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("非法 key → 400,next 不执行")
    void invalidKeyIsRejected() {
        var calls = new AtomicInteger();

        var response = call(request("POST", "x".repeat(300)), calls);

        assertThat(response.status()).isEqualTo(400);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("不同 key 各执行一次")
    void differentKeysRunSeparately() {
        var calls = new AtomicInteger();

        call(request("POST", "a"), calls);
        call(request("POST", "b"), calls);

        assertThat(calls.get()).isEqualTo(2);
    }
}
