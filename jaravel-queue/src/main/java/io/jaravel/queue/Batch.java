package io.jaravel.queue;

import java.time.Instant;
import java.util.List;

/**
 * 一个批次的状态快照(docs/10 §1.2)。
 *
 * <pre>{@code
 * Batch batch = Queue.batch(reportJobs).name("月度报表").dispatch();
 *
 * batch.id();          // 存起来,之后用 Queue.findBatch(id) 查进度
 * batch.progress();    // 0.0 ~ 1.0
 * batch.cancel();      // 取消:未执行的任务被跳过
 * }</pre>
 *
 * <h2>这是快照,不是活对象</h2>
 * <p>{@link #progress()} 返回的是**取出这个对象那一刻**的进度。
 * 批次由多个 worker 并发推进,所以读到的值天生是过去的。
 * 要最新的就 {@link #fresh()}。
 *
 * <p>刻意不做成「每次读属性都查库」:那会让
 * {@code log(b.processedJobs() + "/" + b.totalJobs())} 这一行里的两个数
 * 来自不同时刻,于是日志里出现 {@code 101/100} 这种读起来像 bug 的东西。
 *
 * @param id            批次 id(uuid)
 * @param name          展示名,可为 null
 * @param totalJobs     投递时的任务总数
 * @param pendingJobs   还没有最终结果的数量(失败但还能重试的算 pending)
 * @param failedJobs    最终失败的数量
 * @param failedJobIds  最终失败的那些任务的 uuid,用来在 failed_jobs 里查
 * @param allowsFailures 有任务失败时是否继续跑其余的
 * @param createdAt     投递时刻
 * @param cancelledAt   取消时刻,没取消则为 null
 * @param finishedAt    全部有结果的时刻,未完成则为 null
 */
public record Batch(
        String id,
        String name,
        int totalJobs,
        int pendingJobs,
        int failedJobs,
        List<String> failedJobIds,
        boolean allowsFailures,
        Instant createdAt,
        Instant cancelledAt,
        Instant finishedAt
) {

    public Batch {
        failedJobIds = failedJobIds == null ? List.of() : List.copyOf(failedJobIds);
    }

    /** 已经有最终结果的数量(成功 + 最终失败) */
    public int processedJobs() {
        return totalJobs - pendingJobs;
    }

    /** 成功的数量 */
    public int succeededJobs() {
        return processedJobs() - failedJobs;
    }

    /**
     * 进度,0.0 ~ 1.0。
     *
     * <p>totalJobs 为 0 时返回 1.0 —— 一个空批次是「已完成」。
     * 返回 0.0 会让进度条永远停在起点。
     */
    public double progress() {
        if (totalJobs == 0) return 1.0;
        return (double) processedJobs() / totalJobs;
    }

    public boolean isFinished() {
        return finishedAt != null;
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    public boolean hasFailures() {
        return failedJobs > 0;
    }

    /**
     * 取消这个批次。
     *
     * <p>**已经在执行的任务不会被打断** —— 取消的作用是让还没出队的
     * 任务在出队时被跳过。打断正在执行的任务需要中断线程,
     * 而那会让「任务执行到一半」变成一种常态,远比多跑几个任务危险。
     */
    public void cancel() {
        Queue.batches().cancel(id);
    }

    /** 重新查一遍 —— 批次是并发推进的,这个对象是快照 */
    public Batch fresh() {
        return Queue.batches().find(id).orElse(this);
    }

    @Override
    public String toString() {
        return "Batch[%s]%s %d/%d%s%s".formatted(
                id.substring(0, Math.min(8, id.length())),
                name == null ? "" : " " + name,
                processedJobs(), totalJobs,
                failedJobs > 0 ? " 失败 " + failedJobs : "",
                isCancelled() ? " (已取消)" : "");
    }
}
