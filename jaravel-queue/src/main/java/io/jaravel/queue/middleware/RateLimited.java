package io.jaravel.queue.middleware;

import java.time.Duration;

import io.jaravel.cache.RateLimiter;
import io.jaravel.queue.JobMiddleware;

/**
 * 限流 —— 单位时间内最多跑几个(docs/10 §1.3)。
 *
 * <pre>{@code
 * new RateLimited(limiter, "mail-api", 100, Duration.ofMinutes(1))
 * }</pre>
 *
 * <h2>用途是保护**下游**,不是保护自己</h2>
 * <p>典型场景:第三方邮件 API 限 100 次/分钟。超了之后对方返回 429,
 * 而那个 429 会被当成任务失败并消耗重试配额 —— 于是一次突发流量
 * 能把几百个任务全部推进 failed_jobs,而它们其实只是「来早了」。
 *
 * <p>在队列侧限流把这个问题移到了正确的位置:任务被 release 回队列,
 * 不计入异常次数,下一分钟自然就跑了。
 *
 * <h2>超限时的等待时间来自限流器本身</h2>
 * <p>不是一个固定值 —— 用 {@code availableIn} 问「还要等多久」,
 * 于是一批被限流的任务会在窗口刷新的那一刻集中重试,而不是
 * 各自按固定间隔乱试。固定间隔会让它们在窗口刷新前反复撞墙,
 * 每次都白跑一遍 pop。
 */
public class RateLimited implements JobMiddleware {

    private final RateLimiter limiter;
    private final String key;
    private final int maxAttempts;
    private final Duration window;

    /** 超限时额外多等一点,避免所有任务在同一毫秒涌入 */
    private Duration jitterCeiling = Duration.ofSeconds(3);

    public RateLimited(RateLimiter limiter, String key, int maxAttempts, Duration window) {
        this.limiter = limiter;
        this.key = key;
        this.maxAttempts = maxAttempts;
        this.window = window;
    }

    /**
     * 抖动上限。
     *
     * <p>设成 ZERO 会让一批被限流的任务在窗口刷新的同一毫秒全部涌入,
     * 于是立刻又超限 —— 限流变成了同步化器。
     */
    public RateLimited withJitter(Duration ceiling) {
        this.jitterCeiling = ceiling == null ? Duration.ZERO : ceiling;
        return this;
    }

    @Override
    public String name() {
        return "RateLimited(" + key + " " + maxAttempts + "/" + window + ")";
    }

    @Override
    public void handle(Context context, Runnable next) {
        if (limiter.tooManyAttempts(key, maxAttempts)) {
            context.release(waitTime());
            return;
        }

        limiter.hit(key, window);
        next.run();
    }

    /**
     * 等到窗口刷新,再加一点抖动。
     *
     * <p>{@code availableInSeconds} 返回 0 时(窗口刚好过期)仍要等
     * 至少一点时间 —— 否则 release(0) 会让这个任务立刻被 pop 回来,
     * 在限流器刷新之前空转好几圈。
     */
    private Duration waitTime() {
        long seconds = Math.max(1, limiter.availableInSeconds(key));
        long jitterMillis = jitterCeiling.isZero()
                ? 0
                : (long) (Math.random() * jitterCeiling.toMillis());

        return Duration.ofSeconds(seconds).plusMillis(jitterMillis);
    }
}
