package io.jaravel.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import io.jaravel.queue.Batch;
import io.jaravel.queue.BatchRepository;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.QueueStore;
import io.jaravel.queue.QueuedJob;
import io.jaravel.support.Clock;

/**
 * 队列监控面板的数据聚合(docs/10 §1.8)。
 *
 * <p>**不依赖 Spring / HTTP** —— 它只回答「现在有什么」:
 * 每个连接×队列的积压、失败任务、批次进度、进程内 worker 的计数。
 * HTML 与 JSON 适配在 starter 里,测试可以直接对这一层断言。
 *
 * <h2>数据源的失败不许把整个面板打 500</h2>
 * <p>队列连不上、failed_jobs 表没建、批次存储不可用 —— 这些正是
 * 打开监控面板时最想知道的情况。它们被收进 {@link Overview#warnings()},
 * 其余部分照常展示。反过来做(任何一处异常就整页 500)会让面板
 * 在最需要它的时候不可用。
 */
public final class DashboardService {

    private static final int FAILED_LIMIT = 50;
    private static final int BATCH_LIMIT = 20;

    /** 一个连接×队列的积压。{@code pending = -1} 表示查不到,原因在 error */
    public record QueueStat(String connection, String queue, long pending,
                            long failed, String error) {
    }

    /** 失败任务的一行(不含完整堆栈) */
    public record FailedJobView(long id, String uuid, String queue, String displayName,
                                String reason, Instant failedAt) {
    }

    /** 进程内 worker 的计数。独立 worker 进程没有心跳,这里看不到 */
    public record WorkerStat(String connection, List<String> queues, int concurrency,
                             long processed, long succeeded, long failed, long released,
                             int inFlight) {
    }

    public record Overview(Instant generatedAt,
                           List<QueueStat> queues,
                           long failedTotal,
                           List<FailedJobView> failed,
                           List<Batch> batches,
                           WorkerStat worker,
                           List<String> warnings) {
    }

    private final QueueManager queues;
    private final FailedJobStore failedJobs;
    private final BatchRepository batches;
    private final List<String> queueNames;
    private final Supplier<WorkerStat> worker;

    public DashboardService(QueueManager queues, FailedJobStore failedJobs,
                            BatchRepository batches, List<String> queueNames,
                            Supplier<WorkerStat> worker) {
        this.queues = queues;
        this.failedJobs = failedJobs;
        this.batches = batches;
        this.queueNames = queueNames == null || queueNames.isEmpty()
                ? List.of("default")
                : List.copyOf(queueNames);
        this.worker = worker;
    }

    // ================= 快照 =================

    public Overview overview() {
        var warnings = new ArrayList<String>();

        List<FailedJobStore.FailedJob> all = List.of();
        try {
            all = failedJobs.all();
        } catch (RuntimeException e) {
            warnings.add("读不到失败任务:" + shortMessage(e));
        }

        Map<QueueKey, Long> failedByQueue = new HashMap<>();
        for (var failed : all) {
            failedByQueue.merge(new QueueKey(failed.connection(), failed.queue()), 1L, Long::sum);
        }

        var stats = new ArrayList<QueueStat>();
        Set<String> connections;
        try {
            connections = new TreeSet<>(queues.registeredConnections());
        } catch (RuntimeException e) {
            warnings.add("读不到队列连接:" + shortMessage(e));
            connections = Set.of();
        }

        for (String connection : connections) {
            QueueStore store;
            try {
                store = queues.connection(connection);
            } catch (RuntimeException e) {
                stats.add(new QueueStat(connection, "*", -1, 0, shortMessage(e)));
                continue;
            }

            for (String queue : queueNames) {
                long failed = failedByQueue.getOrDefault(new QueueKey(connection, queue), 0L);
                try {
                    stats.add(new QueueStat(connection, queue, store.size(queue), failed, null));
                } catch (RuntimeException e) {
                    stats.add(new QueueStat(connection, queue, -1, failed, shortMessage(e)));
                }
            }
        }

        var failedViews = all.stream()
                .limit(FAILED_LIMIT)
                .map(f -> new FailedJobView(f.id(), f.uuid(), f.queue(),
                        f.displayName(), f.reason(), f.failedAt()))
                .toList();

        List<Batch> recentBatches = List.of();
        if (batches != null) {
            try {
                recentBatches = batches.recent(BATCH_LIMIT);
            } catch (RuntimeException e) {
                warnings.add("读不到批次:" + shortMessage(e));
            }
        }

        WorkerStat workerStat = null;
        if (worker != null) {
            try {
                workerStat = worker.get();
            } catch (RuntimeException e) {
                warnings.add("读不到 worker 状态:" + shortMessage(e));
            }
        }

        return new Overview(Clock.now(), List.copyOf(stats), all.size(),
                failedViews, recentBatches, workerStat, List.copyOf(warnings));
    }

    // ================= 操作 =================

    /**
     * 把一条失败任务放回它原来的连接 —— 不是默认连接。
     *
     * <p>与 {@code queue:retry} 同一语义:放回后删除失败记录,
     * 否则下一次 retry all 会让同一个任务跑两遍。
     *
     * @return false = 没有这个 id
     */
    public boolean retry(long id) {
        var failed = failedJobs.find(id);
        if (failed.isEmpty()) return false;

        QueuedJob envelope = queues.serializer().decode(failed.get().payload());
        queues.connection(failed.get().connection()).push(envelope);
        failedJobs.forget(id);
        return true;
    }

    public boolean forget(long id) {
        return failedJobs.forget(id);
    }

    public int flushFailed() {
        return failedJobs.flush();
    }

    // ================= 内部 =================

    private record QueueKey(String connection, String queue) {
    }

    private static String shortMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) message = e.getClass().getSimpleName();
        return message.length() > 140 ? message.substring(0, 137) + "…" : message;
    }
}
