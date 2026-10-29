package io.jaravel.auth.middleware;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.cache.RateLimiter;
import io.jaravel.http.HttpException;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * 限流:{@code middleware("throttle:60,1")} —— 每分钟 60 次。
 *
 * <h2>按什么计数</h2>
 * <p>已登录按用户 id,未登录按 IP。按 IP 的问题是 NAT 后面的一群人共享配额
 * (公司网络、学校、手机运营商),所以一旦能识别用户就优先用 id ——
 * 否则「同事把额度用光了」会变成一个无法解释的故障。
 *
 * <h2>响应头</h2>
 * <p>给出 {@code X-RateLimit-Limit} / {@code Remaining} / {@code Retry-After}。
 * 客户端据此退避;没有这些头,SDK 只能盲目重试,把限流变成一场互相伤害。
 */
public class ThrottleRequests implements Middleware.Parameterized {

    private final RateLimiter limiter;

    private int maxAttempts = 60;
    private Duration window = Duration.ofMinutes(1);
    private String prefix = "";

    public ThrottleRequests(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /**
     * {@code throttle:次数,分钟[,前缀]}
     *
     * <p>第三个参数是可选的命名前缀 —— 让不同接口各自计数
     * ({@code throttle:5,1,login} 与 {@code throttle:60,1,api} 互不影响)。
     */
    @Override
    public void configure(String... params) {
        if (params.length > 0) maxAttempts = parsePositive(params[0], "次数");
        if (params.length > 1) window = Duration.ofMinutes(parsePositive(params[1], "分钟"));
        if (params.length > 2) prefix = params[2];
    }

    private static int parsePositive(String raw, String what) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "throttle 的" + what + "必须为正,当前是 " + value
                      + "。写 0 会让这条路由完全不可访问。");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "throttle 的" + what + " \"" + raw + "\" 不是整数。"
                  + "\n  写法:middleware(\"throttle:60,1\")—— 每分钟 60 次", e);
        }
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        String key = resolveKey(request);

        if (limiter.tooManyAttempts(key, maxAttempts)) {
            throw tooManyRequests(key);
        }
        limiter.hit(key, window);

        Response response = next.apply(request);
        return withHeaders(response, key);
    }

    /**
     * 计数的键。
     *
     * <p>包含**路由**:否则 {@code throttle:60,1} 挂在十条路由上时,
     * 它们共享一个 60 次的额度 —— 用户在一个接口上用光后其余九个也被封,
     * 而配置看起来像是「每个接口 60 次」。
     */
    private String resolveKey(Request request) {
        String subject = currentUserId(request)
                .map(id -> "u:" + id)
                .orElseGet(() -> "ip:" + request.ip());

        String scope = prefix.isEmpty()
                ? request.method() + " " + request.path()
                : prefix;

        return scope + "|" + subject;
    }

    /**
     * 当前用户 id。
     *
     * <p>**不能让这里的失败影响限流** —— 没有认证上下文(限流中间件可能
     * 排在认证之前)时退回 IP,而不是抛异常。限流是防护措施,
     * 它自己崩掉等于防护消失。
     */
    private java.util.Optional<Object> currentUserId(Request request) {
        try {
            return io.jaravel.auth.Auth.id();
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    private Response withHeaders(Response response, String key) {
        return response
                .header("X-RateLimit-Limit", String.valueOf(maxAttempts))
                .header("X-RateLimit-Remaining",
                        String.valueOf(limiter.remaining(key, maxAttempts)));
    }

    private HttpException tooManyRequests(String key) {
        long retryAfter = limiter.availableInSeconds(key);
        return new HttpException(429,
                "请求过于频繁,请在 " + retryAfter + " 秒后重试。",
                Map.of("Retry-After", String.valueOf(retryAfter),
                       "X-RateLimit-Limit", String.valueOf(maxAttempts),
                       "X-RateLimit-Remaining", "0"));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration window() {
        return window;
    }
}
