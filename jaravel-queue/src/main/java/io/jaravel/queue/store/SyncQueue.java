package io.jaravel.queue.store;

import java.time.Duration;
import java.util.Optional;

import io.jaravel.queue.JobInvoker;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueuedJob;

/**
 * 立即同步执行。开发与测试的默认驱动。
 *
 * <h2>它故意不重试</h2>
 * <p>与 Laravel 一致。理由:sync 的用途是「我想看这个任务跑起来对不对」,
 * 而等 10 秒 backoff 再试一次只会让测试莫名慢几十秒。
 *
 * <p>失败时**立即**调 {@code job.failed(e)} 并把异常原样抛出 ——
 * 抛出是必要的:同步投递的调用方就在当前调用栈上,它有权知道失败了。
 * 吞掉异常会让「任务挂了但接口返回 200」。
 *
 * <h2>它会让「忘了配队列」变成一个性能问题而不是功能问题</h2>
 * <p>生产环境误用 sync 的后果是所有后台任务在请求线程里跑 ——
 * 接口莫名很慢,但功能看起来正常,于是很难归因。
 * {@link #isAsynchronous()} 返回 false 就是为了让框架能在启动时提醒。
 */
public class SyncQueue implements QueueStore {

    private final JobSerializer serializer;
    private final JobInvoker invoker;

    public SyncQueue(JobSerializer serializer, JobInvoker invoker) {
        this.serializer = serializer;
        this.invoker = invoker;
    }

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public boolean isAsynchronous() {
        return false;
    }

    /**
     * 「入队」= 立刻执行。
     *
     * <p>**仍然走一遍序列化**,哪怕马上就要反序列化回来。
     * 这是刻意的:让「任务字段不可序列化」这个错误在开发期
     * 就暴露出来,而不是等切到 database/redis 驱动的那天。
     *
     * <p>换句话说:sync 驱动的行为要尽量接近真驱动,
     * 否则它就成了一个「本地能跑、上线就炸」的发生器。
     */
    @Override
    public String push(QueuedJob job) {
        var instance = serializer.toJob(job);

        try {
            invoker.invoke(instance);
        } catch (RuntimeException | Error e) {
            // 立即调 failed:sync 没有重试,这一次就是最后一次
            try {
                instance.failed(e);
            } catch (RuntimeException ignored) {
                // failed 自己炸了不该掩盖原始异常
            }
            throw e;
        }
        return job.uuid();
    }

    /** sync 队列里永远没有待取的任务 —— 它们已经执行完了 */
    @Override
    public Optional<QueuedJob> pop(String queue, Duration retryAfter) {
        return Optional.empty();
    }

    @Override public void delete(QueuedJob job) { }

    @Override
    public void release(QueuedJob job, Duration delay) {
        throw new UnsupportedOperationException("""
                sync 驱动不能 release 任务 —— 它没有队列可放回。
                  任务主动让出(等锁、等限流)需要一个真的异步驱动。
                  开发期想试这类行为:把 queue.default 改成 database。""");
    }

    @Override public long size(String queue) { return 0; }

    @Override public void clear(String queue) { }
}
