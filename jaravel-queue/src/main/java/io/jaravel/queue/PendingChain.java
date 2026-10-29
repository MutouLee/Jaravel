package io.jaravel.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条待投递的任务链(docs/10 §1.2)。前一个成功后才执行下一个。
 *
 * <pre>{@code
 * Queue.chain(List.of(
 *          new ProcessPayment(orderId),
 *          new SendInvoice(orderId),
 *          new UpdateInventory(orderId)))
 *      .onQueue("orders")
 *      .catchWith(new NotifyChainBroken(orderId))
 *      .dispatch();
 * }</pre>
 *
 * <h2>链在信封里,不在数据库里</h2>
 * <p>整条链的剩余部分放在**队首任务的信封**里。前一个成功时 Worker
 * 把下一个连带剩余部分推进队列。
 *
 * <p>另一种做法是建一张 {@code job_chains} 表记录进度。没选它的理由:
 * 链的状态与「队列里有哪条任务」必须一致,分成两处存就有了两个
 * 事实来源 —— worker 崩在「删掉任务」与「更新链进度」之间时,
 * 链会永久卡住,而那需要一个额外的清理任务去发现。
 * 放在信封里的话,链的状态**就是**队列的状态,不存在不一致。
 *
 * <p>代价:链很长时 payload 会变大(每一环都带着后面所有环的数据)。
 * 10 环以内可以忽略;上百环的链本身就该换成批处理。
 *
 * <h2>为什么 catch 是一个任务而不是 lambda</h2>
 * <p>设计文档里写的是 {@code .catch_(e -> Log.error("链中断", e))}。
 * 这在 Laravel 里能工作,靠的是 {@code SerializableClosure} ——
 * PHP 能把闭包连同它捕获的变量序列化进 payload。
 *
 * <p>Java 做不到这件事。Java 的 lambda 能序列化的前提是目标接口
 * 继承 {@code Serializable},而反序列化它需要**同一个类的同一次编译**
 * 产生的合成方法名($deserializeLambda$ 里按序号匹配)。改一行代码、
 * 调一下编译顺序,队列里积压的回调就全部失效 —— 而失效的表现是
 * 反序列化异常,发生在那条链已经跑了一半的时候。
 *
 * <p>所以回调是一个**普通任务**。这样做反而多拿到三样东西:
 * <ul>
 *   <li>它能带参数({@code new NotifyChainBroken(orderId)}),
 *       而 lambda 捕获的变量在 Java 里恰恰是最难序列化的部分;</li>
 *   <li>它的 handle 能被容器注入依赖;</li>
 *   <li>它自己也有重试与失败归档 —— 一个「发通知」的回调本身
 *       也可能因为下游挂了而失败,lambda 版本里那次失败无处可去。</li>
 * </ul>
 *
 * <p>失败的上下文(哪一环、什么异常)通过 handle 的注入参数拿:
 * {@code public void handle(ChainFailure failure, Mailer mailer)}。
 */
public final class PendingChain {

    private final QueueManager queues;
    private final List<ShouldQueue> jobs;

    private String connectionName;
    private String queueName;
    private Duration delay = Duration.ZERO;
    private ShouldQueue onFailure;

    PendingChain(QueueManager queues, List<ShouldQueue> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            throw new IllegalArgumentException("""
                    任务链是空的,没有东西可投。
                      如果链的内容来自一个可能为空的集合,在投之前判一下 ——
                      这里报错是刻意的:静默什么都不做会让调用方以为投成功了。""");
        }
        this.queues = queues;
        this.jobs = List.copyOf(jobs);
    }

    public PendingChain onConnection(String name) {
        this.connectionName = name;
        return this;
    }

    public PendingChain onQueue(String name) {
        this.queueName = name;
        return this;
    }

    /**
     * 整条链的开始延迟。
     *
     * <p>**不是每一环都延迟这么久** —— 后续各环在前一环成功后立即可取。
     * 见 {@link QueuedJob#nextInChain(Instant)}。
     */
    public PendingChain delay(Duration duration) {
        this.delay = duration == null ? Duration.ZERO : duration;
        return this;
    }

    /**
     * 链中断时投这个任务。
     *
     * <p>「中断」= 链上某一环**重试耗尽后仍失败**。中途的重试不算 ——
     * 那时链还有机会继续。
     *
     * <p>断点之后的环**不会**执行:链的语义就是「前一个成功才继续」。
     * 这一点与批处理相反,是两者的主要区别。
     */
    public PendingChain catchWith(ShouldQueue job) {
        this.onFailure = job;
        return this;
    }

    /** 投递。@return 队首任务的 uuid */
    public String dispatch() {
        QueueStore store = connectionName == null
                ? queues.connection()
                : queues.connection(connectionName);

        Instant now = Instant.now();

        List<QueuedJob> envelopes = new ArrayList<>(jobs.size());
        for (ShouldQueue job : jobs) {
            envelopes.add(QueuedJob.of(job, resolveQueue(job),
                    queues.serializer().toData(job), now, Duration.ZERO));
        }

        QueuedJob catchEnvelope = onFailure == null ? null
                : QueuedJob.of(onFailure, resolveQueue(onFailure),
                               queues.serializer().toData(onFailure), now, Duration.ZERO);

        QueuedJob head = envelopes.get(0);
        if (!delay.isZero() && !delay.isNegative()) {
            head = head.nextAttempt(now.plus(delay));
            // nextAttempt 把 attempts 加了一 —— 这里只是想推后可取时间。
            // 用它是为了不再加一个 wither,代价是要把 attempts 还原
            head = resetAttempts(head);
        }

        store.push(head.withChain(envelopes.subList(1, envelopes.size()), catchEnvelope));
        return head.uuid();
    }

    /**
     * 把 attempts 还原成 0。
     *
     * <p>这个辅助存在只是因为 {@code nextAttempt} 同时做了两件事
     * (推后时间 + 加次数),而这里只要前一件。
     */
    private static QueuedJob resetAttempts(QueuedJob envelope) {
        return new QueuedJob(envelope.uuid(), envelope.jobClass(), envelope.data(),
                envelope.queue(), 0, envelope.pushedAt(), envelope.availableAt(),
                envelope.tries(), envelope.timeout(), envelope.retryUntil(),
                envelope.maxExceptions(), envelope.displayName(),
                0, envelope.batchId(), envelope.chain(), envelope.chainCatch(),
                envelope.context());
    }

    /** 链上的 onQueue 覆盖任务自己的 queue();都没有就用连接默认 */
    private String resolveQueue(ShouldQueue job) {
        if (queueName != null) return queueName;
        if (job.queue() != null) return job.queue();
        return queues.defaultQueueName();
    }
}
