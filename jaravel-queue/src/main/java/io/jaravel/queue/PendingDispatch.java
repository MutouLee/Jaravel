package io.jaravel.queue;

import java.lang.ref.Cleaner;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import io.jaravel.database.Connection;

/**
 * 一次待投递的配置。由 {@link Queue#job(ShouldQueue)} 创建,
 * 必须以 {@link #dispatch()} 结束。
 *
 * <pre>{@code
 * Queue.job(new SendInvoice(id))
 *      .onQueue("mail")
 *      .delay(Duration.ofMinutes(5))
 *      .dispatch();
 * }</pre>
 *
 * <h2>为什么与 docs/10 §1.2 写的形式不同</h2>
 * <p>设计文档里写的是 Laravel 的形式:
 *
 * <pre>{@code
 * Queue.dispatch(new SendInvoice(id));                      // 直接投
 * Queue.dispatch(new SendInvoice(id)).onQueue("mail");      // 配置后投
 * }</pre>
 *
 * <p>第二行在 Laravel 里能工作,靠的是 PHP 的**确定性析构** ——
 * {@code PendingDispatch} 的 {@code __destruct} 在表达式结束时触发投递。
 * Java 没有这个机制(GC 的时机不确定,finalize 已废弃),
 * 所以这个形式在 Java 里**无法原样实现**。
 *
 * <p>硬做的两条路都更糟:
 * <ul>
 *   <li>让 {@code Queue.dispatch(job)} 立即投递、链式方法事后修改 ——
 *       那时任务已经在队列里了,{@code onQueue} 改不动它;</li>
 *   <li>让它延迟到请求作用域结束再投 —— 投递时机变得不可预期,
 *       而 CLI 与队列任务里根本没有请求作用域。</li>
 * </ul>
 *
 * <p>所以拆成两个入口,各自的语义都是确定的:
 * <ul>
 *   <li>{@code Queue.dispatch(job)} —— 用任务自己的配置立即投递;</li>
 *   <li>{@code Queue.job(job)…dispatch()} —— 要改配置时用,
 *       末尾的 {@code dispatch()} 是必须的。</li>
 * </ul>
 *
 * <p>「忘了写 dispatch()」这个风险用 {@link #WARN_ON_LEAK} 兜住:
 * 开发模式下一个从未投递的 PendingDispatch 被回收时会打印警告。
 * 静默什么都不做是这里唯一不可接受的结果。
 */
public final class PendingDispatch {

    /**
     * 开发模式下,未投递就被回收的 PendingDispatch 会打印警告。
     *
     * <p>生产环境关掉:注册 Cleaner 有开销,而生产里这个错误
     * 早在开发期就该被发现。
     */
    private static final boolean WARN_ON_LEAK = io.jaravel.core.Application.isDebug();

    private static final Cleaner CLEANER = Cleaner.create();

    private final QueueManager queues;
    private final ShouldQueue job;

    /** 共享给 Cleaner 的标记 —— 不能让它持有 PendingDispatch 本身 */
    private final AtomicBoolean dispatched = new AtomicBoolean();

    private String connectionName;
    private String queueName;
    private Duration delay = Duration.ZERO;
    private Boolean afterCommit;
    private String uuid;

    PendingDispatch(QueueManager queues, ShouldQueue job) {
        this.queues = queues;
        this.job = job;

        if (WARN_ON_LEAK) registerLeakWarning(job.getClass().getName(), dispatched);
    }

    /**
     * 注册回收时的检查。
     *
     * <p>**不能捕获 this** —— 那会让这个对象永远可达,Cleaner 永不触发。
     * 只传类名(字符串)与那个共享标记。
     */
    private void registerLeakWarning(String jobClass, AtomicBoolean flag) {
        CLEANER.register(this, () -> {
            if (flag.get()) return;

            System.err.printf("""

                    [jaravel] 警告:一个 %s 的投递配置被丢弃了,任务**没有入队**。
                              Queue.job(...) 必须以 .dispatch() 结束:
                                Queue.job(job).onQueue("mail").dispatch();
                              不需要改配置时直接用 Queue.dispatch(job)。
                    %n""", jobClass);
        });
    }

    // ================= 配置 =================

    public PendingDispatch onConnection(String name) {
        requireNotDispatched("onConnection");
        this.connectionName = name;
        return this;
    }

    public PendingDispatch onQueue(String name) {
        requireNotDispatched("onQueue");
        this.queueName = name;
        return this;
    }

    public PendingDispatch delay(Duration duration) {
        requireNotDispatched("delay");
        this.delay = duration == null ? Duration.ZERO : duration;
        return this;
    }

    /**
     * 等当前事务提交后才真正入队。
     *
     * <p>**这是默认行为**(可用 {@code queue.after-commit: false} 关掉)。
     *
     * <p>不这样做会产生一类很难查的 bug:
     *
     * <pre>{@code
     * DB.transaction(() -> {
     *     var order = Order.create(...);
     *     Queue.dispatch(new SendInvoice(order.id));   // 立即入队
     *     throw new PaymentFailed();                   // 事务回滚
     * });
     * }</pre>
     *
     * <p>订单回滚了,但任务已经在队列里。worker 取出来执行
     * {@code Order.findOrFail(orderId)} → 找不到 → 重试三次后失败。
     * 失败日志指向「订单不存在」,而真正的原因是支付失败 ——
     * 两者在日志里相距很远。
     *
     * <p>更糟的变体:worker 比事务提交**更快**。那时订单还没可见,
     * 任务同样找不到它 —— 而这个版本只在高负载下偶发。
     */
    public PendingDispatch afterCommit() {
        requireNotDispatched("afterCommit");
        this.afterCommit = true;
        return this;
    }

    /** 不等事务 —— 立即入队 */
    public PendingDispatch beforeCommit() {
        requireNotDispatched("beforeCommit");
        this.afterCommit = false;
        return this;
    }

    private void requireNotDispatched(String method) {
        if (!dispatched.get()) return;

        throw new IllegalStateException("""
                %s 必须在 dispatch() 之前调用,而这个任务已经投递了。
                  配置要写在同一条链上:
                    Queue.job(job).onQueue("mail").delay(ofMinutes(5)).dispatch();"""
                .formatted(method));
    }

    // ================= 投递 =================

    /**
     * 投递。
     *
     * <p>重复调用是**幂等的** —— 返回同一个 uuid,不会入队两次。
     * 「不小心调了两次」不该变成两条任务。
     */
    public String dispatch() {
        if (!dispatched.compareAndSet(false, true)) return uuid;

        QueueStore store = connectionName == null
                ? queues.connection()
                : queues.connection(connectionName);

        String target = queueName != null ? queueName
                : (job.queue() != null ? job.queue() : queues.defaultQueueName());

        var envelope = QueuedJob.of(job, target,
                queues.serializer().toData(job), Instant.now(), delay);
        this.uuid = envelope.uuid();

        // 唯一性在**投递时**判定。执行时判定意味着重复任务已经占了
        // 队列空间与一次 pop,而「队列里有 5000 条重复任务」本身就是问题
        if (job instanceof UniqueJob unique && !acquireUniqueLock(unique)) {
            this.uuid = null;       // 没有入队,就没有 uuid 可返回
            return null;
        }

        if (shouldWaitForCommit(store)) {
            currentConnection().orElseThrow().afterCommit(() -> store.push(envelope));
        } else {
            store.push(envelope);
        }
        return uuid;
    }

    /**
     * 抢唯一性锁。
     *
     * <p>**锁的 TTL 是兜底**,不是唯一性窗口:正常情况下 Worker 在
     * 任务结束时释放它。TTL 只在 worker 崩掉、没机会释放时起作用。
     *
     * @return 抢到了吗。false = 已有同 key 的任务,本次丢弃
     */
    private boolean acquireUniqueLock(UniqueJob unique) {
        var cache = queues.requireUniqueLocks((ShouldQueue) unique);
        return cache.lock(unique.uniqueLockKey(), unique.uniqueFor()).get();
    }

    /**
     * 要不要等提交。
     *
     * <p>三个条件都得满足:开关开着、当前真的在事务里、驱动是异步的。
     *
     * <p>最后一条很重要:sync 驱动等提交会让「事务里 dispatch 一个任务
     * 然后读它的结果」变成一种困惑 —— 任务要到事务提交后才跑,
     * 而提交在那行代码之后。sync 的语义就是「立刻跑」,
     * 保持它比形式上统一更有用。
     */
    private boolean shouldWaitForCommit(QueueStore store) {
        boolean wanted = afterCommit != null ? afterCommit : queues.isAfterCommitByDefault();
        if (!wanted || !store.isAsynchronous()) return false;

        return currentConnection().map(Connection::inTransaction).orElse(false);
    }

    /**
     * 当前数据库连接。
     *
     * <p>队列模块不持有它 —— 从 {@code Application} 取,取不到就当作
     * 「不在事务里」。无库应用用 redis 队列是完全合理的,
     * 为此要求一个 DataSource 是错的。
     */
    private Optional<Connection> currentConnection() {
        return io.jaravel.core.Application.tryMake(Connection.class);
    }

    public ShouldQueue job() {
        return job;
    }

    public boolean isDispatched() {
        return dispatched.get();
    }
}
