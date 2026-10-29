package io.jaravel.queue.middleware;

import java.time.Duration;

import io.jaravel.cache.RateLimiter;
import io.jaravel.queue.JobMiddleware;

/**
 * 熔断 —— 连续失败到一定次数后暂停一段时间(docs/10 §1.3)。
 *
 * <pre>{@code
 * new ThrottlesExceptions(limiter, "payment-api", 5, Duration.ofMinutes(10))
 * }</pre>
 *
 * <h2>它与 tries / backoff 的区别</h2>
 * <p>{@code tries} 是**单个任务**的重试上限,而这个是**一类任务**的
 * 共同闸门。区别在下游挂掉的时候:
 *
 * <p>假设支付网关宕机,队列里有 5000 个支付任务。只靠 tries + backoff 的话,
 * 这 5000 个任务会各自重试 3 次 —— 一万五千次请求打向一个已经挂掉的服务。
 * 那既拖慢自己的恢复,也妨碍对方恢复。
 *
 * <p>熔断让前 5 个失败之后的所有任务直接 release,不再碰下游。
 * 它们在窗口结束后自然重试,而那时下游大概已经好了。
 *
 * <h2>计数器按 key 共享,所以 key 要选对</h2>
 * <p>key 应该标识**下游**({@code "payment-api"}),不是任务
 * ({@code "charge:" + orderId})。按任务计数等于每个任务一个独立熔断器,
 * 那就退化成了 tries。
 */
public class ThrottlesExceptions implements JobMiddleware {

    private final RateLimiter limiter;
    private final String key;
    private final int maxExceptions;
    private final Duration decayWindow;

    private Duration releaseAfter = Duration.ofSeconds(60);

    public ThrottlesExceptions(RateLimiter limiter, String key,
                               int maxExceptions, Duration decayWindow) {
        this.limiter = limiter;
        this.key = key;
        this.maxExceptions = maxExceptions;
        this.decayWindow = decayWindow;
    }

    /** 熔断打开时,任务过多久再试 */
    public ThrottlesExceptions releaseAfter(Duration duration) {
        this.releaseAfter = duration;
        return this;
    }

    @Override
    public String name() {
        return "ThrottlesExceptions(" + key + ")";
    }

    @Override
    public void handle(Context context, Runnable next) {
        if (limiter.tooManyAttempts(throttleKey(), maxExceptions)) {
            context.release(releaseAfter);
            return;
        }

        try {
            next.run();
        } catch (RuntimeException | Error failure) {
            // 只数**异常**,不数正常完成 —— 否则它就变成了限流器。
            // 成功的任务不该推高熔断计数
            limiter.hit(throttleKey(), decayWindow);
            throw failure;
        }
    }

    private String throttleKey() {
        return "circuit:" + key;
    }

    /** 当前累计了多少次失败 —— 诊断与测试用 */
    public long failures() {
        return limiter.attempts(throttleKey());
    }

    /** 手工复位。下游确认恢复后可以调它,不必等窗口自然过期。 */
    public void reset() {
        limiter.clear(throttleKey());
    }
}
