package io.jaravel.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一个待投递的批次(docs/10 §1.2)。并行执行 + 汇总回调。
 *
 * <pre>{@code
 * Batch batch = Queue.batch(orderIds.stream().map(GenerateReport::new).toList())
 *         .name("月度报表")
 *         .allowFailures()
 *         .then(new NotifyBatchDone(adminId))
 *         .catchWith(new AlertFirstFailure(adminId))
 *         .finallyRun(new CleanUpTempFiles())
 *         .dispatch();
 * }</pre>
 *
 * <h2>批处理与链的区别</h2>
 * <table>
 *   <caption>选哪个</caption>
 *   <tr><th></th><th>链</th><th>批</th></tr>
 *   <tr><td>执行</td><td>串行</td><td>并行</td></tr>
 *   <tr><td>一个失败后</td><td>后面的**不执行**</td><td>其余的继续(除非 allowFailures 关着)</td></tr>
 *   <tr><td>状态存在</td><td>信封里</td><td>job_batches 表</td></tr>
 *   <tr><td>适合</td><td>有先后依赖的步骤</td><td>同一件事的 N 份</td></tr>
 * </table>
 *
 * <h2>三个回调由谁触发</h2>
 * <ul>
 *   <li>{@code then} —— 全部完成**且无最终失败**;</li>
 *   <li>{@code catchWith} —— **第一个**最终失败时(不是每个);</li>
 *   <li>{@code finallyRun} —— 全部有结果时,无论成败。</li>
 * </ul>
 *
 * <p>「全部完成」的判定由 {@link BatchRepository#markFinished} 的条件
 * UPDATE 收敛成一次 —— 见那里的注释,计数可以近似但回调不能重复。
 *
 * <h2>为什么回调是任务而不是 lambda</h2>
 * <p>见 {@link PendingChain} 的类注释:Java 的 lambda 没法可靠地
 * 穿过队列。回调任务能通过 handle 的注入参数拿到 {@link Batch}:
 *
 * <pre>{@code
 * public void handle(Batch batch, Mailer mailer) {
 *     mailer.send(new BatchSummary(batch.succeededJobs(), batch.failedJobs()));
 * }
 * }</pre>
 *
 * <h2>allowFailures 默认是关的</h2>
 * <p>默认行为:**第一个最终失败就取消整个批次**,剩下没出队的任务被跳过。
 *
 * <p>这个默认值与 Laravel 一致,理由也相同:一个批次通常是「同一件事的
 * N 份」,第一份就失败往往说明是共因(配置错了、下游挂了),
 * 继续跑剩下的 999 份只是把同一个错误重复 999 次,并把
 * failed_jobs 表灌满。
 *
 * <p>确实互相独立的批次(比如「给每个用户生成报表」)应显式
 * {@link #allowFailures()}。
 */
public final class PendingBatch {

    private final QueueManager queues;
    private final List<ShouldQueue> jobs;

    private String name;
    private String connectionName;
    private String queueName;
    private Duration delay = Duration.ZERO;
    private boolean allowFailures;
    private ShouldQueue thenCallback;
    private ShouldQueue catchCallback;
    private ShouldQueue finallyCallback;

    PendingBatch(QueueManager queues, List<ShouldQueue> jobs) {
        if (jobs == null) throw new IllegalArgumentException("批次的任务列表不能为 null");
        this.queues = queues;
        this.jobs = List.copyOf(jobs);
    }

    public PendingBatch name(String batchName) {
        this.name = batchName;
        return this;
    }

    public PendingBatch onConnection(String connection) {
        this.connectionName = connection;
        return this;
    }

    public PendingBatch onQueue(String queue) {
        this.queueName = queue;
        return this;
    }

    public PendingBatch delay(Duration duration) {
        this.delay = duration == null ? Duration.ZERO : duration;
        return this;
    }

    /** 有任务失败时继续跑其余的。默认不继续 —— 理由见类注释 */
    public PendingBatch allowFailures() {
        this.allowFailures = true;
        return this;
    }

    /** 全部成功后投这个任务 */
    public PendingBatch then(ShouldQueue callback) {
        this.thenCallback = callback;
        return this;
    }

    /** **第一个**最终失败时投这个任务 */
    public PendingBatch catchWith(ShouldQueue callback) {
        this.catchCallback = callback;
        return this;
    }

    /** 全部有结果后投这个任务,无论成败 */
    public PendingBatch finallyRun(ShouldQueue callback) {
        this.finallyCallback = callback;
        return this;
    }

    /**
     * 投递。
     *
     * <p>**先建批次记录,再推任务**。顺序是必须的:反过来的话
     * 一个 worker 可能在批次行写入之前就取到任务,然后
     * {@code recordSuccess} 查不到那一行 —— 而那个竞态只在
     * worker 已经在跑的时候出现,也就是生产环境。
     */
    public Batch dispatch() {
        QueueStore store = connectionName == null
                ? queues.connection()
                : queues.connection(connectionName);

        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();

        var repository = queues.requireBatches();

        Batch batch = repository.create(id, name, jobs.size(), allowFailures,
                envelopeFor(thenCallback, now),
                envelopeFor(catchCallback, now),
                envelopeFor(finallyCallback, now));

        // 空批次:没有任务可投,它当场就该是「已完成」。
        // 不特殊处理的话 pending_jobs 从 0 开始,而没有任何
        // recordSuccess 会去触发完成判定 —— 回调永不执行,
        // 批次永远停在「已完成 0/0」而 finished_at 为 null
        if (jobs.isEmpty()) {
            if (repository.markFinished(id)) {
                dispatchCallback(store, repository, id, BatchRepository.Kind.THEN);
                dispatchCallback(store, repository, id, BatchRepository.Kind.FINALLY);
            }
            return repository.find(id).orElse(batch);
        }

        // 延迟作用于**每个任务**(它们并行,没有先后),
        // 与链的「整条链延迟一次」不同
        for (ShouldQueue job : jobs) {
            var envelope = QueuedJob.of(job, resolveQueue(job),
                            queues.serializer().toData(job), now, delay)
                    .inBatch(id);
            store.push(envelope);
        }

        return batch;
    }

    private void dispatchCallback(QueueStore store, BatchRepository repository,
                                  String id, BatchRepository.Kind kind) {
        // 必须挂上 context —— 否则回调的 handle(Batch batch) 拿不到批次,
        // 任务会以「无法为参数解析依赖」失败。
        // 这条与 Worker 里投回调的那条必须一致,所以两处都走 JobContext.BATCH_ID
        repository.callback(id, kind)
                .map(callback -> callback.withContext(JobContext.BATCH_ID, id))
                .ifPresent(store::push);
    }

    private QueuedJob envelopeFor(ShouldQueue callback, Instant now) {
        if (callback == null) return null;

        return QueuedJob.of(callback, resolveQueue(callback),
                        queues.serializer().toData(callback), now, Duration.ZERO)
                // 回调本身也属于这个批次吗?**不属于**。
                // 让它带上 batchId 会让它的完成又去减一次 pending_jobs,
                // 而那个计数里本来没有它 —— 结果是计数变负、进度超过 100%
                .inBatch(null);
    }

    private String resolveQueue(ShouldQueue job) {
        if (queueName != null) return queueName;
        if (job.queue() != null) return job.queue();
        return queues.defaultQueueName();
    }
}
