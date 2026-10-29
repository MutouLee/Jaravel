package io.jaravel.queue;

import java.time.Duration;

/**
 * 可入队的任务(docs/10 §1.1)。
 *
 * <pre>{@code
 * public class SendInvoice implements ShouldQueue {
 *
 *     private final Long orderId;          // 只存 id,不存整个模型
 *
 *     public SendInvoice(Long orderId) { this.orderId = orderId; }
 *
 *     @Override public String queue() { return "mail"; }
 *     @Override public int tries()    { return 3; }
 *
 *     // 参数从容器注入
 *     public void handle(Mailer mailer) {
 *         Order order = Order.findOrFail(orderId);
 *         mailer.to(order.email).send(new InvoiceMail(order));
 *     }
 *
 *     // 重试耗尽后调用
 *     public void failed(Throwable e) {
 *         Log.error("发票发送最终失败 orderId={}", orderId, e);
 *     }
 * }
 * }</pre>
 *
 * <h2>只存 id,不存模型</h2>
 * <p>字段会被序列化进 payload。放一个 Eloquent 模型进去有两个后果:
 * payload 膨胀,以及**数据过期** —— 任务可能在几分钟后才执行,
 * 那时模型快照已经不是库里的状态了,而基于过期数据做的写入会覆盖
 * 中间的修改。
 *
 * <h2>每个配置方法由谁执行</h2>
 * <p>这些方法里有一半是**给 Worker 看的**,sync 驱动不理它们。
 * 写清楚是必要的 —— 否则「我设了 tries=3 但没重试」会被当成 bug,
 * 而实际是用 sync 驱动跑的:
 *
 * <table>
 *   <caption>执行点</caption>
 *   <tr><th>方法</th><th>谁执行</th></tr>
 *   <tr><td>{@link #queue()}</td><td>投递时(决定进哪个队列)</td></tr>
 *   <tr><td>{@link #tries()} / {@link #backoff()}</td><td>Worker</td></tr>
 *   <tr><td>{@link #timeout()}</td><td>Worker</td></tr>
 *   <tr><td>{@link #retryUntil()}</td><td>Worker</td></tr>
 *   <tr><td>{@link #maxExceptions()}</td><td>Worker</td></tr>
 *   <tr><td>{@code handle(...)}</td><td>Worker 与 sync 驱动</td></tr>
 *   <tr><td>{@link #failed(Throwable)}</td><td>Worker(重试耗尽);sync 驱动立即调用</td></tr>
 * </table>
 *
 * <p>**sync 驱动不重试**,与 Laravel 一致:它的用途是开发期与测试,
 * 而「同步执行还要等 backoff」会让测试莫名慢上几十秒。
 *
 * <h2>handle 不在接口上</h2>
 * <p>因为它的参数是**容器注入**的,签名因任务而异。接口里声明一个
 * {@code handle()} 会迫使所有任务都用无参签名,然后在方法体里
 * 手写 {@code Application.make(Mailer.class)} —— 那就把依赖藏起来了。
 *
 * <p>代价:签名错误要到运行时才发现。{@link JobInvoker} 因此在
 * 找不到可调用的 handle 时给出带候选的错误。
 */
public interface ShouldQueue {

    /**
     * 进哪个队列。返回 null 表示用连接的默认队列。
     *
     * <p>队列名是**优先级的载体**:worker 用
     * {@code --queue=high,mail,default} 指定消费顺序。
     */
    default String queue() {
        return null;
    }

    /**
     * 最多尝试几次(含首次)。
     *
     * <p>默认 1 —— **不重试**。这个默认值是刻意的:自动重试对
     * 非幂等的任务是危险的(重复扣款、重复发邮件),而「是否幂等」
     * 只有写任务的人知道。让它默认开启等于替用户做了一个他没意识到的决定。
     */
    default int tries() {
        return 1;
    }

    /**
     * 每次重试前等多久。
     *
     * <p>数组长度小于重试次数时,**重复最后一个值**。
     * 返回空数组表示不等待。
     *
     * <p>默认是递增的 —— 固定间隔重试在「下游服务挂了」时会变成
     * 一轮同步的重试风暴,而那正是下游最需要喘息的时候。
     */
    default Duration[] backoff() {
        return new Duration[] {
                Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(300)};
    }

    /**
     * 单次执行的超时。
     *
     * <p>超时后任务被判定失败并按 {@link #tries()} 重试。
     * null 表示不限 —— **慎用**:一个卡住的任务会一直占着并发额度。
     */
    default Duration timeout() {
        return Duration.ofMinutes(2);
    }

    /**
     * 重试的绝对截止时间(相对入队时刻)。
     *
     * <p>与 {@link #tries()} 的区别:tries 限次数,这个限**总时长**。
     * 两者取先到的那个。
     *
     * <p>用途:「这封提醒邮件 24 小时之后发出去就没意义了」——
     * 这类任务不该因为重试次数没用完而在第二天凌晨发出。
     */
    default Duration retryUntil() {
        return null;
    }

    /**
     * 最多允许多少次**未捕获异常**。
     *
     * <p>与 tries 的区别:一个任务可能主动 release 自己(等锁、等限流),
     * 那不算异常。这个上限只数真正炸掉的次数,
     * 于是「等锁等了 50 次」不会耗尽重试配额。
     */
    default int maxExceptions() {
        return Integer.MAX_VALUE;
    }

    /**
     * 全部重试耗尽后调用。
     *
     * <p>默认什么都不做。**不默认记日志**:框架已经把失败写进
     * failed_jobs 表并记了日志,这里再记一条只会让同一个失败
     * 出现两次。覆盖它是为了做补偿动作(通知、回滚、标记订单)。
     */
    default void failed(Throwable cause) {
        // 由实现覆盖
    }

    /**
     * 任务的展示名,用于日志与失败列表。
     *
     * <p>默认是类的简单名。覆盖它可以带上关键参数 ——
     * failed_jobs 里一排 "SendInvoice" 和一排 "SendInvoice(order=42)"
     * 的排查成本差别很大。
     */
    default String displayName() {
        return getClass().getSimpleName();
    }
}
