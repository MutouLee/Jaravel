package io.jaravel.queue;

import java.time.Duration;

/**
 * 唯一性约束:同一个 key 的任务不重复入队(docs/10 §1.1)。
 *
 * <pre>{@code
 * public class SendInvoice implements ShouldQueue, UniqueJob {
 *     @Override public String uniqueId()    { return String.valueOf(orderId); }
 *     @Override public Duration uniqueFor() { return Duration.ofMinutes(10); }
 * }
 * }</pre>
 *
 * <h2>为什么是独立接口而不是 ShouldQueue 的默认方法</h2>
 * <p>它需要一个锁存储({@code Repository})才能生效。做成
 * {@code ShouldQueue} 的默认方法意味着「所有任务都声明了这个能力,
 * 但只在配了缓存时才真的工作」—— 而那正是我在 v0.3 反复修的那类问题:
 * **声明了却不生效的 API**。
 *
 * <p>独立接口让「我需要唯一性」是一个显式选择,而框架可以在装配时
 * 检查缓存是否可用。
 *
 * <h2>唯一性在**投递时**判定,不是执行时</h2>
 * <p>重复的 dispatch 直接被丢弃,不入队。这与 Laravel 一致,
 * 理由是:执行时判定意味着重复任务已经占了队列空间与一次 pop,
 * 而「队列里有 5000 条重复任务」本身就是个问题。
 *
 * <h2>锁什么时候释放</h2>
 * <p>默认在任务**执行完成后**释放(成功或最终失败)。
 * 于是 {@link #uniqueFor()} 的含义是「兜底 TTL」——
 * 它只在 worker 崩掉、没机会释放时起作用。
 *
 * <p>{@link #uniqueUntilProcessing()} 改成「开始执行时就释放」:
 * 适合「同一时刻只排一个,但正在跑的不影响新的排队」的场景,
 * 比如定时触发的报表生成。
 */
public interface UniqueJob {

    /**
     * 唯一性的 key。
     *
     * <p>**不含类名** —— 框架会自动加上。手工拼类名的话,
     * 两个不同任务用同一个 id 会互相挡住,而那很难看出来。
     */
    String uniqueId();

    /**
     * 兜底 TTL。
     *
     * <p>锁正常会在任务结束时释放,这个值只在 worker 崩掉时起作用。
     * 设得太短会让重复任务在任务还在跑时就能入队;太长会让
     * 一次 worker 崩溃挡住这个 key 很久。
     */
    default Duration uniqueFor() {
        return Duration.ofHours(1);
    }

    /**
     * 开始执行时就释放锁,而不是执行完成后。
     *
     * <p>语义差别:
     * <ul>
     *   <li>默认(false)—— 「同一个 key 同时只有一个任务存在」,
     *       排队中与执行中都算;</li>
     *   <li>true —— 「同一个 key 同时只有一个在**排队**」,
     *       已经开始跑的那个不挡新的入队。</li>
     * </ul>
     */
    default boolean uniqueUntilProcessing() {
        return false;
    }

    /** 完整的锁 key。框架用,包含类名以避免不同任务互相挡。 */
    default String uniqueLockKey() {
        return "unique:" + getClass().getName() + ":" + uniqueId();
    }
}
