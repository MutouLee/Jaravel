package io.jaravel.cache;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 限流器。{@code throttle} 中间件与登录节流都用它。
 *
 * <pre>{@code
 * if (limiter.tooManyAttempts("login:" + email, 5)) {
 *     throw new TooManyRequestsException(limiter.availableIn("login:" + email));
 * }
 * limiter.hit("login:" + email, Duration.ofMinutes(1));
 * }</pre>
 *
 * <h2>算法:固定窗口,不是滑动窗口</h2>
 * <p>固定窗口在窗口边界允许突发(第 59 秒和第 61 秒各打满一次,
 * 两秒内通过了两倍配额)。滑动窗口没这个问题,但需要存每次命中的时间戳 ——
 * 对缓存来说是一个会随流量增长的列表。
 *
 * <p>选固定窗口,因为限流的目的是**防滥用**而不是精确配额:边界突发是两倍,
 * 不是两个数量级。Laravel 同样是固定窗口。需要精确的场景应该用专门的
 * 网关限流,而不是应用层缓存。
 */
public class RateLimiter {

    private final Repository cache;

    public RateLimiter(Repository cache) {
        this.cache = cache;
    }

    // ================= 查询 =================

    public boolean tooManyAttempts(String key, int maxAttempts) {
        return attempts(key) >= maxAttempts;
    }

    public long attempts(String key) {
        return cache.get(counterKey(key), Long.class, 0L);
    }

    public long remaining(String key, int maxAttempts) {
        return Math.max(0, maxAttempts - attempts(key));
    }

    /**
     * 还要等多少秒才能再试。
     *
     * <p>来自一个与计数器同 TTL 的「窗口结束时间」条目。
     * 不能用计数器自己的剩余 TTL —— 缓存 SPI 没有「查剩余 TTL」这个原语,
     * 而给每个驱动加一个只为限流服务的方法不值得。
     */
    public long availableInSeconds(String key) {
        long resetAt = cache.get(timerKey(key), Long.class, 0L);
        if (resetAt == 0) return 0;
        long remaining = resetAt - System.currentTimeMillis();
        return remaining > 0 ? (remaining + 999) / 1000 : 0;
    }

    // ================= 记一次 =================

    /**
     * 计一次命中。
     *
     * @return 当前窗口内的累计次数
     */
    public long hit(String key, Duration window) {
        // 先用 add 建窗口结束时间:只有第一次命中会成功,
        // 后续命中不会把窗口往后推 —— 否则持续请求会让窗口永不结束
        cache.add(timerKey(key), System.currentTimeMillis() + window.toMillis(), window);

        // 计数器也用 add 初始化,确保它带上 TTL。
        // 直接 increment 时,若键不存在,多数驱动会建一个**永久**条目 ——
        // 那样限流窗口永不重置,用户被永久封锁。
        if (cache.add(counterKey(key), 1L, window)) {
            return 1;
        }
        return cache.increment(counterKey(key));
    }

    /**
     * 未超限则执行并计数;超限返回空。
     *
     * @return 执行结果;超限时为空
     */
    public <T> java.util.Optional<T> attempt(String key, int maxAttempts,
                                             Duration window, Supplier<T> body) {
        if (tooManyAttempts(key, maxAttempts)) return java.util.Optional.empty();
        hit(key, window);
        return java.util.Optional.ofNullable(body.get());
    }

    // ================= 清除 =================

    /** 登录成功后清掉该账号的失败计数 */
    public void clear(String key) {
        cache.forget(counterKey(key));
        cache.forget(timerKey(key));
    }

    private static String counterKey(String key) {
        return "throttle:" + key;
    }

    private static String timerKey(String key) {
        return "throttle:" + key + ":timer";
    }
}
