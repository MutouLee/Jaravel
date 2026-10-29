package io.jaravel.queue;

import java.time.Duration;
import java.util.Optional;

/**
 * 队列驱动 SPI。
 *
 * <p>只有六个方法。队列的复杂度(重试策略、超时、失败归档、链式、批处理)
 * 全部在 Worker 与上层,驱动只负责「存一条、取一条、放回去、删掉」——
 * 这样新增一个驱动(RabbitMQ / SQS)不需要重新实现一遍重试语义。
 *
 * <h2>pop 必须是原子的</h2>
 * <p>两个 worker 同时 pop 不能拿到同一条。这是驱动唯一必须自己保证的事,
 * 也是实现一个驱动时最容易做错的地方 —— 做错的症状是**任务被执行两次**,
 * 而对非幂等任务(扣款、发邮件)那是生产事故。
 *
 * <p>{@link io.jaravel.queue.store.DatabaseQueue} 用「乐观锁 UPDATE」
 * 实现它,见那个类的注释。
 */
public interface QueueStore {

    String name();

    /** 入队。返回信封里的 uuid。 */
    String push(QueuedJob job);

    /**
     * 取一条可执行的任务,并把它标记为**已保留**。
     *
     * <p>「可执行」= {@code availableAt <= now} 且没有被别的 worker 保留。
     *
     * <p>保留是有时效的:worker 崩了之后那条任务必须能被别人捡起来,
     * 所以驱动要在 {@code retryAfter} 之后让它重新可见。
     *
     * @param queue      队列名
     * @param retryAfter 保留多久之后视为「worker 失联」,让任务重新可见
     */
    Optional<QueuedJob> pop(String queue, Duration retryAfter);

    /**
     * 执行成功 —— 删掉它。
     *
     * <p>**必须在任务逻辑成功之后**。先删再执行的话,worker 崩在中间
     * 就会静默丢任务。
     */
    void delete(QueuedJob job);

    /**
     * 放回队列,延迟一段时间后可取。
     *
     * <p>用于重试与任务主动让出(等锁、等限流)。
     */
    void release(QueuedJob job, Duration delay);

    /** 队列里还有多少条(含延迟中的) */
    long size(String queue);

    /** 清空。测试与 {@code queue:flush} 用。 */
    void clear(String queue);

    /**
     * 这个驱动是否真的会异步执行。
     *
     * <p>{@code sync} 与 {@code null} 返回 false。用途是让框架在
     * 「配置了 sync 却在生产环境」时能提醒 —— 那意味着所有本该
     * 后台跑的任务都在请求线程里同步跑,症状是接口莫名很慢。
     */
    default boolean isAsynchronous() {
        return true;
    }
}
