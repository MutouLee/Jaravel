package app.http.controllers;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.jaravel.http.Request;

/**
 * 幂等性示例(docs/12 §6.5)。**只用于示例与测试**。
 *
 * <p>{@link #CALLS} 是测试观察点:同一个 Idempotency-Key 的第二次请求
 * 不应该把它再加一。
 */
@Component
public class IdempotencyDemoController {

    public static final AtomicInteger CALLS = new AtomicInteger();

    /** 测试「5xx 不缓存」用:为 true 时抛异常 */
    public static volatile boolean FAIL;

    public Map<String, Object> create(Request request) {
        int calls = CALLS.incrementAndGet();
        return Map.of(
                "calls", calls,
                "amount", request.input("amount", "0"),
                "idempotencyKey", request.header("Idempotency-Key").orElse(""));
    }

    public Map<String, Object> failOnceThenSucceed(Request request) {
        if (FAIL) throw new IllegalStateException("第一次故意失败");
        int calls = CALLS.incrementAndGet();
        return Map.of("calls", calls, "ok", true);
    }
}
