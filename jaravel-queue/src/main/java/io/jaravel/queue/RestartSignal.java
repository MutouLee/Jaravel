package io.jaravel.queue;

import java.time.Instant;
import java.util.Optional;

import io.jaravel.cache.Repository;
import io.jaravel.support.Clock;

/**
 * {@code jaravel queue:restart} 的那个信号(docs/10 §2、docs/13 §2.4)。
 *
 * <p>一个时间戳写在缓存里。worker 每处理完一个任务回到主循环时看一眼:
 * 时间戳比自己启动的时刻新,就**优雅退出** —— 等在跑的任务做完,不再取新的。
 *
 * <pre>{@code
 * // 部署脚本里
 * jaravel queue:restart          // 所有 worker 做完手上的任务后退出
 * }</pre>
 *
 * <h2>★ 为什么是「信号 + 自己退出」而不是去杀进程</h2>
 * <p>要换代码就得让 worker 重启,而直接 kill 会把**正在跑的那个任务**打断:
 * 它已经从队列里取走了,保留超时(retryAfter)到了才会被别人重新取到 ——
 * 中间那段时间里,用户看到的是「提交了但什么都没发生」。更糟的情况是任务
 * 做了一半的副作用(扣了款没发货)。
 *
 * <p>所以退出必须由 worker 自己在**任务之间**那个安全点做。Laravel 用同一个
 * 做法({@code illuminate:queue:restart}),理由也一样。
 *
 * <h2>⚠ 它只负责「退出」,不负责「再起来」</h2>
 * <p>把 worker 重新拉起来是进程管理器的事(systemd 的 {@code Restart=always}、
 * k8s 的重启策略、supervisor)。没有它的话 {@code queue:restart} 的效果是
 * 「所有 worker 都停了」—— 这件事必须写在命令的输出里,否则一次部署会把
 * 队列彻底停掉,而监控上看到的只是「积压在涨」。
 *
 * <h2>⚠ 必须是共享缓存</h2>
 * <p>caffeine / array 驱动在进程内,信号传不到别的进程 —— 那恰好是
 * worker 所在的地方。多实例部署要把 {@code cache.default} 配成
 * redis / database。命令在发现驱动不共享时会警告。
 */
public final class RestartSignal {

    /** 缓存键。带 {@code jaravel:} 前缀,与应用自己的键分开 */
    public static final String KEY = "jaravel:queue:restart";

    private final Repository cache;

    public RestartSignal(Repository cache) {
        this.cache = cache;
    }

    /**
     * 广播一次重启请求。
     *
     * <p>用 {@code forever}:信号没有过期的概念 —— 一个两天后才启动的
     * worker 不需要被两天前的请求重启,那由「比启动时刻新」这个比较决定,
     * 不由 TTL 决定。
     *
     * @return 写进去的时刻
     */
    public Instant broadcast() {
        Instant now = Clock.realNow();
        cache.forever(KEY, now.toEpochMilli());
        return now;
    }

    /** 上一次广播的时刻 */
    public Optional<Instant> lastBroadcastAt() {
        return cache.get(KEY, Long.class).map(Instant::ofEpochMilli);
    }

    /**
     * 在 {@code since} 之后有人请求过重启吗。
     *
     * <p>读不到就是没有:缓存挂了不该让 worker 退出 —— 那会把一次缓存
     * 故障放大成「队列停了」。
     */
    public boolean requestedAfter(Instant since) {
        try {
            return lastBroadcastAt().filter(at -> at.isAfter(since)).isPresent();
        } catch (RuntimeException cacheFailure) {
            return false;
        }
    }
}
