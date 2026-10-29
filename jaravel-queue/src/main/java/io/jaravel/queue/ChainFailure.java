package io.jaravel.queue;

/**
 * 链中断的上下文,注入给 {@link PendingChain#catchWith} 指定的任务。
 *
 * <pre>{@code
 * public class NotifyChainBroken implements ShouldQueue {
 *
 *     private final Long orderId;
 *
 *     public NotifyChainBroken(Long orderId) { this.orderId = orderId; }
 *
 *     public void handle(ChainFailure failure, Mailer mailer) {
 *         Log.error("订单 {} 的链断在 {}:{}",
 *                   orderId, failure.jobName(), failure.message());
 *     }
 * }
 * }</pre>
 *
 * <h2>为什么不直接传 Throwable</h2>
 * <p>回调任务是**另一次**出队执行,很可能在另一个进程、甚至另一台机器上。
 * 那时原始异常对象早已不存在 —— 它没法穿过队列。
 *
 * <p>所以传的是异常的**可序列化摘要**:类名、消息、以及断在哪一环。
 * 这三样是排查时真正会看的东西。想要完整堆栈的话它在
 * {@code failed_jobs} 里,用 {@link #jobUuid()} 能查到那一条。
 *
 * @param jobClass  断掉的那一环的类名
 * @param jobName   它的 {@code displayName()} —— 带参数的那个版本更好认
 * @param jobUuid   它的 uuid,用来在 failed_jobs 里定位完整堆栈
 * @param attempts  它试了几次
 * @param exceptionClass 异常类名
 * @param message   异常消息
 */
public record ChainFailure(
        String jobClass,
        String jobName,
        String jobUuid,
        int attempts,
        String exceptionClass,
        String message
) {

    static ChainFailure of(QueuedJob envelope, Throwable cause) {
        Throwable root = rootOf(cause);
        return new ChainFailure(
                envelope.jobClass(),
                envelope.displayName(),
                envelope.uuid(),
                envelope.attempts(),
                root.getClass().getName(),
                root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage());
    }

    /**
     * 剥到最内层的原因。
     *
     * <p>Worker 会把失败包进 {@code JobFailedException},而那一层的消息是
     * 「已尝试 3 次,达到上限 3」—— 对「为什么失败」毫无帮助。
     * 用户要看的是最里面那个。
     */
    private static Throwable rootOf(Throwable cause) {
        Throwable current = cause;
        // 防环:自引用的 cause 链会让这里死循环
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 16) {
            current = current.getCause();
        }
        return current;
    }
}
