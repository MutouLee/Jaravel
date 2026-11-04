package io.jaravel.spring.commands;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobInvoker;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.QueueWorker;
import io.jaravel.queue.QueuedJob;
import io.jaravel.queue.WorkerOptions;

/**
 * {@code jaravel queue:work}(docs/13 §2.4)。
 *
 * <pre>
 * jaravel queue:work --queue=high,mail,default   优先级:从左到右
 * jaravel queue:work --once                      只处理一个任务
 * jaravel queue:work --max-jobs=1000 --max-time=3600
 * </pre>
 *
 * <h2>★ 走命令通道时,worker 跑在**应用进程里**</h2>
 * <p>这与 Laravel 的 {@code php artisan queue:work}(独立进程)不同,
 * 值得知道:开发期这正是要的(不用再起一个 JVM),
 * 但**生产环境应当让 worker 独立部署** —— web 与 worker 要能分开伸缩
 * 与重启,一个耗内存的任务不该影响接口的 P99。
 * (那条理由写在 {@code JaravelQueueAutoConfiguration} 里,
 * 也是进程内 worker 默认不开的原因。)
 *
 * <h2>⚠ 不带任何限制时它不会自己停</h2>
 * <p>那正是 worker 该有的行为。通道上的终止条件有四种:
 * {@code --once} / {@code --max-jobs} / {@code --max-time} /
 * {@code --stop-when-empty},或者**客户端断开**(Ctrl-C)——
 * 后者由通道检测,见 {@code ChannelServer} 里关于「客户端走了」的注释。
 * 没有那个检测的话,一次 Ctrl-C 会在 dev server 里留下一个**永远跑着的
 * worker**,而下一次 {@code queue:work} 会再留一个。
 */
@Command(name = "queue:work", description = "处理队列里的任务")
public final class QueueWorkCommand extends RuntimeCommand {

    @Option(names = "--queue", description = "队列名,逗号分隔。顺序即优先级")
    String queue;

    @Option(names = "--once", description = "只处理一个任务就退出")
    boolean once;

    @Option(names = "--max-jobs", description = "处理这么多个任务后退出")
    int maxJobs;

    @Option(names = "--max-time", description = "跑这么多秒后退出")
    int maxTime;

    @Option(names = "--sleep", description = "队列空时歇几秒")
    int sleep;

    @Option(names = "--concurrency", description = "并发处理几个(虚拟线程)")
    int concurrency;

    @Option(names = "--stop-when-empty", description = "队列空了就退出")
    boolean stopWhenEmpty;

    @Option(names = "--connection", description = "用哪个队列连接")
    String connection;

    private final Supplier<QueueManager> queues;
    private final Supplier<FailedJobStore> failedJobs;
    private final Supplier<JobInvoker> invoker;

    public QueueWorkCommand(Supplier<QueueManager> queues,
                            Supplier<FailedJobStore> failedJobs,
                            Supplier<JobInvoker> invoker) {
        this.queues = queues;
        this.failedJobs = failedJobs;
        this.invoker = invoker;
    }

    @Override
    public int handle() {
        QueueManager manager = queues.get();
        String connectionName = connection == null || connection.isBlank()
                ? manager.defaultConnectionName()
                : connection.trim();

        var worker = new QueueWorker(manager, failedJobs.get(), invoker.get(),
                                     options(manager), connectionName);
        worker.addListener(new QueueWorker.Listener() {
            @Override public void onSuccess(QueuedJob job) {
                success(job.displayName());
            }
            @Override public void onFailed(QueuedJob job, Throwable cause) {
                error(job.displayName() + " —— " + cause);
            }
            @Override public void onRetry(QueuedJob job, Throwable cause, Duration delay) {
                warn(job.displayName() + " 失败,将在 " + delay.toSeconds() + "s 后重试");
            }
        });

        info("worker 启动:连接 " + connectionName + ",队列 "
                + worker.options().queues());
        worker.run();

        newLine();
        line("处理 %d 个:成功 %d,失败 %d,放回 %d".formatted(
                worker.processedCount(), worker.succeededCount(),
                worker.failedCount(), worker.releasedCount()));

        // ★ 有失败就返回非 0。
        //
        // 「跑完了」与「跑完了但有任务失败」对 CI 与监控是两件事,
        // 而失败任务已经进了 failed 表 —— 退出码 0 会让那张表没人去看。
        return worker.failedCount() > 0 ? 1 : 0;
    }

    private WorkerOptions options(QueueManager manager) {
        WorkerOptions options = WorkerOptions.defaults()
                .withRetryAfter(manager.retryAfterDuration());

        if (queue != null && !queue.isBlank()) {
            options = options.withQueues(List.of(queue.trim().split("\\s*,\\s*")));
        }
        if (maxJobs > 0)     options = options.withMaxJobs(maxJobs);
        if (maxTime > 0)     options = options.withMaxTime(Duration.ofSeconds(maxTime));
        if (sleep > 0)       options = options.withSleep(Duration.ofSeconds(sleep));
        if (concurrency > 0) options = options.withConcurrency(concurrency);

        // --once 就是「一个任务 + 队列空了就走」:只给 maxJobs 的话,
        // 一个空队列会让它一直歇着等那一个任务
        if (once) options = options.withMaxJobs(1).withStopWhenEmpty(true);
        if (stopWhenEmpty) options = options.withStopWhenEmpty(true);
        return options;
    }
}
