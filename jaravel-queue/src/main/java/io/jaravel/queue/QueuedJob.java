package io.jaravel.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 队列里的一条记录 —— 任务的 JSON 信封(docs/10 §1.6)。
 *
 * <pre>{@code
 * {
 *   "uuid": "9c1a…",
 *   "jobClass": "com.app.jobs.SendInvoice",
 *   "data": { "orderId": 42 },
 *   "attempts": 0,
 *   "queue": "mail",
 *   "pushedAt": "2026-10-28T06:12:00Z"
 * }
 * }</pre>
 *
 * <h2>为什么是 JSON 而不是 Java 序列化</h2>
 * <ul>
 *   <li><b>可读</b> —— 能在 {@code redis-cli} 或 SQL 里直接看到 payload。
 *       排查「这个任务带的参数对不对」不需要写一段反序列化代码;</li>
 *   <li><b>跨版本兼容</b> —— 加一个字段不会让队列里积压的旧任务全部反序列化失败。
 *       Java 序列化的 serialVersionUID 冲突会让一次发布把队列报废;</li>
 *   <li><b>无反序列化漏洞</b> —— Java 原生反序列化是一类长期的 RCE 来源,
 *       而队列 payload 在某些部署里是半可信的(多个服务共写一个队列);</li>
 *   <li><b>可被其它语言消费</b> —— 一个 Go worker 能读同一个队列。</li>
 * </ul>
 *
 * @param uuid        任务的唯一标识。**由投递方生成**,贯穿重试与失败记录 ——
 *                    这样「同一个任务重试了 3 次」在日志里能串起来
 * @param jobClass    完全限定类名
 * @param data        任务字段
 * @param queue       队列名
 * @param attempts    已尝试次数(**含**当前这次)
 * @param pushedAt    首次入队时刻。{@link ShouldQueue#retryUntil()} 以它为基准
 * @param availableAt 何时可被取出。延迟投递与重试 backoff 都靠它
 * @param tries       从任务上快照下来的重试上限
 */
public record QueuedJob(
        String uuid,
        String jobClass,
        Map<String, Object> data,
        String queue,
        int attempts,
        Instant pushedAt,
        Instant availableAt,
        int tries,
        Duration timeout,
        Instant retryUntil,
        int maxExceptions,
        String displayName
) {

    public QueuedJob {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /**
     * 新建一条信封。
     *
     * <p>**行为配置在投递时快照**,不是执行时读。理由:worker 执行时
     * 可能已经是新版本的代码了,而一个任务的重试策略应该与它入队时
     * 看到的一致 —— 否则「发布时把 tries 从 3 改成 1」会让队列里
     * 积压的任务突然少两次重试,而那很难归因。
     */
    static QueuedJob of(ShouldQueue job, String queueName, Map<String, Object> data,
                        Instant now, Duration delay) {
        Instant availableAt = delay == null || delay.isZero() || delay.isNegative()
                ? now
                : now.plus(delay);

        Duration retryWindow = job.retryUntil();

        return new QueuedJob(
                UUID.randomUUID().toString(),
                job.getClass().getName(),
                data,
                queueName,
                0,
                now,
                availableAt,
                Math.max(1, job.tries()),
                job.timeout(),
                retryWindow == null ? null : now.plus(retryWindow),
                job.maxExceptions(),
                job.displayName());
    }

    /** 下一次尝试 —— attempts 加一,可取时间推后 */
    public QueuedJob nextAttempt(Instant availableAt) {
        return new QueuedJob(uuid, jobClass, data, queue, attempts + 1,
                pushedAt, availableAt, tries, timeout, retryUntil,
                maxExceptions, displayName);
    }

    /** 标记为正在执行的这一次 */
    public QueuedJob reserved() {
        return new QueuedJob(uuid, jobClass, data, queue, attempts + 1,
                pushedAt, availableAt, tries, timeout, retryUntil,
                maxExceptions, displayName);
    }

    /**
     * 还能不能再试。
     *
     * <p>两个条件取先到的那个:次数用完,或超过了绝对截止时间。
     */
    public boolean canRetry(Instant now) {
        if (attempts >= tries) return false;
        return retryUntil == null || now.isBefore(retryUntil);
    }

    /** 为什么不能再试 —— 写进 failed_jobs 的原因里 */
    public String exhaustionReason(Instant now) {
        if (retryUntil != null && !now.isBefore(retryUntil)) {
            return "超过重试截止时间(retryUntil=" + retryUntil + ")";
        }
        return "已尝试 " + attempts + " 次,达到上限 " + tries;
    }

    @Override
    public String toString() {
        return "%s[%s] queue=%s attempts=%d/%d"
                .formatted(displayName, uuid.substring(0, 8), queue, attempts, tries);
    }
}
