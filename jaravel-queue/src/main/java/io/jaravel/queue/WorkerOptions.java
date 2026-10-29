package io.jaravel.queue;

import java.time.Duration;
import java.util.List;

/**
 * Worker 的运行参数(docs/10 §1.4 的命令行开关)。
 *
 * <pre>{@code
 * var options = WorkerOptions.onQueues("high", "mail", "default")
 *         .withConcurrency(50)
 *         .withMaxJobs(1000);
 * }</pre>
 *
 * <h2>队列顺序就是优先级</h2>
 * <p>{@code onQueues("high", "mail", "default")} 的语义是**严格优先**:
 * 只要 high 还有任务就不去看 mail。
 *
 * <p>这会让低优先级队列在高优先级持续有任务时饿死 —— 那是刻意的,
 * 也是 Laravel 的语义。「加权轮询」听起来更好,但它让
 * 「紧急任务到底多久能被处理」变得说不清,而那是优先级队列
 * 唯一要回答的问题。需要公平性就起两组 worker。
 */
public record WorkerOptions(
        List<String> queues,
        int concurrency,
        Duration sleep,
        Duration retryAfter,
        int maxJobs,
        Duration maxTime,
        Duration heartbeat,
        Duration shutdownGrace,
        boolean stopWhenEmpty
) {

    public WorkerOptions {
        queues = queues == null || queues.isEmpty() ? List.of("default") : List.copyOf(queues);
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency 至少 1,当前 " + concurrency);
        }
    }

    /**
     * 默认参数。
     *
     * <p>{@code concurrency = 10}:虚拟线程下这个数字几乎没有成本
     * (10 个虚拟线程 ≈ 几 KB),但它仍然是个**有意义的上限** ——
     * 并发度最终受限于数据库连接池,而不是线程。设成无限会让
     * 一次队列积压把连接池打满,连 HTTP 请求都拿不到连接。
     *
     * <p>{@code heartbeat = retryAfter / 3}:续约频率要明显快于
     * 失联判定,否则一次 GC 停顿就可能让任务被判定失联。
     */
    public static WorkerOptions defaults() {
        Duration retryAfter = Duration.ofSeconds(90);
        return new WorkerOptions(
                List.of("default"),
                10,
                Duration.ofSeconds(1),
                retryAfter,
                0,
                Duration.ZERO,
                retryAfter.dividedBy(3),
                Duration.ofSeconds(30),
                false);
    }

    public static WorkerOptions onQueues(String... queues) {
        return defaults().withQueues(List.of(queues));
    }

    // ================= 链式 =================

    public WorkerOptions withQueues(List<String> value) {
        return new WorkerOptions(value, concurrency, sleep, retryAfter,
                maxJobs, maxTime, heartbeat, shutdownGrace, stopWhenEmpty);
    }

    /** 并发执行的任务数上限 */
    public WorkerOptions withConcurrency(int value) {
        return new WorkerOptions(queues, value, sleep, retryAfter,
                maxJobs, maxTime, heartbeat, shutdownGrace, stopWhenEmpty);
    }

    /** 队列空时轮询的间隔 */
    public WorkerOptions withSleep(Duration value) {
        return new WorkerOptions(queues, concurrency, value, retryAfter,
                maxJobs, maxTime, heartbeat, shutdownGrace, stopWhenEmpty);
    }

    /**
     * 保留多久之后视为「worker 失联」。
     *
     * <p>有心跳续约之后这个值不再需要「大于最长任务」——
     * 它变成了「worker 挂掉之后任务多久能被别人捡起来」。
     * 见 {@link QueueStore#touch}。
     */
    public WorkerOptions withRetryAfter(Duration value) {
        return new WorkerOptions(queues, concurrency, sleep, value,
                maxJobs, maxTime, value.dividedBy(3), shutdownGrace, stopWhenEmpty);
    }

    /** 处理多少个任务后退出。0 = 不限。用于「定期重启以释放内存」 */
    public WorkerOptions withMaxJobs(int value) {
        return new WorkerOptions(queues, concurrency, sleep, retryAfter,
                value, maxTime, heartbeat, shutdownGrace, stopWhenEmpty);
    }

    /** 运行多久后退出。ZERO = 不限。 */
    public WorkerOptions withMaxTime(Duration value) {
        return new WorkerOptions(queues, concurrency, sleep, retryAfter,
                maxJobs, value, heartbeat, shutdownGrace, stopWhenEmpty);
    }

    /** 停机时等在跑的任务最多多久 */
    public WorkerOptions withShutdownGrace(Duration value) {
        return new WorkerOptions(queues, concurrency, sleep, retryAfter,
                maxJobs, maxTime, heartbeat, value, stopWhenEmpty);
    }

    /**
     * 队列空了就退出。
     *
     * <p>用途是**一次性清空队列**(部署脚本、测试)。
     * 常驻 worker 不该开它 —— 队列短暂为空是正常的。
     */
    public WorkerOptions withStopWhenEmpty(boolean value) {
        return new WorkerOptions(queues, concurrency, sleep, retryAfter,
                maxJobs, maxTime, heartbeat, shutdownGrace, value);
    }

    public boolean hasJobLimit()  { return maxJobs > 0; }
    public boolean hasTimeLimit() { return maxTime != null && !maxTime.isZero(); }
}
