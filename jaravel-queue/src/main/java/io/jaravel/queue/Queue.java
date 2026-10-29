package io.jaravel.queue;

import java.time.Duration;

/**
 * 队列的静态门面(docs/10 §1.2)。
 *
 * <pre>{@code
 * Queue.dispatch(new SendInvoice(orderId));                     // 直接投
 * Queue.job(new SendInvoice(id)).onQueue("mail").dispatch();    // 配置后投
 * Queue.dispatchSync(new SendInvoice(id));                      // 同步执行
 * Queue.dispatchIf(order.needsInvoice(), new SendInvoice(id));
 * }</pre>
 *
 * <p>两个入口的分工与理由见 {@link PendingDispatch} 的类注释 ——
 * 简短版:Laravel 的 {@code dispatch(job)->onQueue(...)} 依赖 PHP 的
 * 确定性析构,Java 没有这个机制。
 */
public final class Queue {

    private static volatile QueueManager manager;

    private Queue() {
    }

    public static void use(QueueManager configured) {
        manager = configured;
    }

    public static QueueManager manager() {
        QueueManager current = manager;
        if (current == null) {
            throw new IllegalStateException("""
                    队列尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调
                        Queue.use(new QueueManager(new JobSerializer())
                                .register("sync", m -> new SyncQueue(...))
                                .defaultConnection("sync"));""");
        }
        return current;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        manager = null;
    }

    // ================= 投递 =================

    /**
     * 用任务自己的配置投递。
     *
     * @return 任务的 uuid,可用于日志关联与失败查询
     */
    public static String dispatch(ShouldQueue job) {
        return new PendingDispatch(manager(), job).dispatch();
    }

    /** 要改连接 / 队列 / 延迟时用。**必须以 {@code dispatch()} 结束。** */
    public static PendingDispatch job(ShouldQueue job) {
        return new PendingDispatch(manager(), job);
    }

    public static String dispatch(ShouldQueue job, Duration delay) {
        return job(job).delay(delay).dispatch();
    }

    /**
     * 条件投递。
     *
     * <p>存在的理由不是省一个 if,而是让「条件」与「投递」读起来是一件事:
     * {@code Queue.dispatchIf(order.isPaid(), new SendInvoice(id))}。
     * 包在 if 里的版本容易在重构时被拆散。
     */
    public static void dispatchIf(boolean condition, ShouldQueue job) {
        if (condition) dispatch(job);
    }

    public static void dispatchUnless(boolean condition, ShouldQueue job) {
        if (!condition) dispatch(job);
    }

    /**
     * 同步执行,不入队。
     *
     * <p>用途:测试、以及「这个任务其实该同步跑但复用了任务的代码」。
     *
     * <p>**异常原样抛出** —— 同步执行的调用方就在当前调用栈上,
     * 它有权知道失败了。吞掉会让「任务挂了但接口返回 200」。
     */
    public static void dispatchSync(ShouldQueue job) {
        job(job).onConnection(syncConnectionName()).beforeCommit().dispatch();
    }

    /**
     * sync 连接叫什么。
     *
     * <p>约定是 "sync"。没注册时报错而不是静默退回默认连接 ——
     * 后者会让 {@code dispatchSync} 变成异步投递,
     * 而那正好是调用方明确不想要的。
     */
    private static String syncConnectionName() {
        var queues = manager();
        if (queues.registeredConnections().contains("sync")) return "sync";

        throw new IllegalStateException("""
                dispatchSync 需要一个名为 "sync" 的连接,但它没有注册。
                  已注册:%s
                  在 config/queue.yml 里加:
                    connections:
                      sync:
                        driver: sync"""
                .formatted(queues.registeredConnections()));
    }

    // ================= 查询 =================

    /** 默认连接的默认队列里有多少条 */
    public static long size() {
        return manager().connection().size(manager().defaultQueueName());
    }

    public static long size(String queue) {
        return manager().connection().size(queue);
    }

    public static void clear(String queue) {
        manager().connection().clear(queue);
    }
}
