package io.jaravel.queue.middleware;

import java.time.Duration;

import io.jaravel.cache.Lock;
import io.jaravel.cache.Repository;
import io.jaravel.queue.JobMiddleware;

/**
 * 同一个 key 的任务不并发(docs/10 §1.3)。
 *
 * <pre>{@code
 * @Override public List<JobMiddleware> middleware() {
 *     return List.of(new WithoutOverlapping("invoice:" + orderId)
 *             .releaseAfter(Duration.ofSeconds(60)));
 * }
 * }</pre>
 *
 * <h2>它解决的是什么</h2>
 * <p>「同一个订单的发票生成任务同时跑两个」会产出两份发票。
 * 这类问题在队列里很常见:用户连点两次、或上游系统重发了一个事件。
 *
 * <p>**注意它不是幂等性的替代品**。它只保证「同一时刻只有一个」,
 * 不保证「只执行一次」—— 第二个任务会稍后再跑。要「只执行一次」
 * 需要任务自己判断状态(或用 {@link UniqueJob})。
 *
 * <h2>拿不到锁时 release 而不是阻塞等待</h2>
 * <p>阻塞会占着一个 worker 的并发额度干等,而那个额度本可以去跑别的任务。
 * release 把任务放回队列,worker 立刻去处理下一个 —— 这是任务中间件
 * 比 HTTP 中间件多出来的那个动作的主要用途。
 *
 * <h2>锁必须有 TTL</h2>
 * <p>持锁的 worker 崩掉之后,没有 TTL 的锁会**永久**挡住这个 key 的
 * 所有任务 —— 而那个症状(某个订单的任务永远不执行)极难归因。
 * 所以 {@link #expireAfter} 有一个默认值,且不允许设为无限。
 */
public class WithoutOverlapping implements JobMiddleware {

    private final Repository cache;
    private final String key;

    private Duration expireAfter = Duration.ofMinutes(5);
    private Duration releaseAfter = Duration.ofSeconds(10);
    private boolean skipWhenLocked;

    public WithoutOverlapping(Repository cache, String key) {
        this.cache = cache;
        this.key = key;
    }

    /**
     * 锁的 TTL —— 也就是「持锁者崩掉后多久自动解锁」。
     *
     * <p>要比任务的最长执行时间长,否则任务还在跑锁就没了。
     */
    public WithoutOverlapping expireAfter(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("""
                    锁的 TTL 必须是正数。
                      没有 TTL 的锁在持锁 worker 崩掉后会**永久**挡住这个 key,
                      而症状(某个 key 的任务永远不执行)极难归因。""");
        }
        this.expireAfter = duration;
        return this;
    }

    /** 拿不到锁时,过多久再试 */
    public WithoutOverlapping releaseAfter(Duration duration) {
        this.releaseAfter = duration;
        return this;
    }

    /**
     * 拿不到锁时**丢弃**而不是稍后重试。
     *
     * <p>适合「这件事做一次就够了」的任务:缓存预热、重算统计。
     * 另一个任务正在做同一件事,再排一次没有意义。
     *
     * <p>默认不开 —— 丢弃是不可逆的,而「稍后重试」在语义上更保守。
     */
    public WithoutOverlapping dontRelease() {
        this.skipWhenLocked = true;
        return this;
    }

    @Override
    public String name() {
        return "WithoutOverlapping(" + key + ")";
    }

    @Override
    public void handle(Context context, Runnable next) {
        Lock lock = cache.lock("overlap:" + key, expireAfter);

        if (!lock.get()) {
            if (skipWhenLocked) {
                context.delete("同 key 的任务正在执行(" + key + "),本次丢弃");
            } else {
                context.release(releaseAfter);
            }
            return;
        }

        try {
            next.run();
        } finally {
            // **必须在 finally 里释放**:任务抛异常时如果不释放,
            // 这个 key 会被挡到锁 TTL 过期为止 —— 而那期间的重试
            // 全部会被 release 掉,看起来像「任务卡住了」
            lock.release();
        }
    }

    public String key() {
        return key;
    }
}
