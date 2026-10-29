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
 * @param exceptions  真正抛过异常的次数。与 {@code attempts} 分开记 ——
 *                    被中间件 release 的那些不算,见
 *                    {@link ShouldQueue#maxExceptions()}
 * @param batchId     所属批次,没有则为 null
 * @param chain       链上**后续**的任务。前一个成功后由 Worker 投下一个,
 *                    见 {@link PendingChain}
 * @param chainCatch  链中断时要投的任务,没有则为 null
 * @param context     **信封级**的上下文,与 {@code data} 分开。
 *                    data 是任务自己的字段;context 是框架塞进来的东西
 *                    (这条任务属于哪个批次、链断在哪)。
 *                    <p>它必须随信封走而不是放在 worker 的内存里 ——
 *                    一个批次的 then 回调可能由**另一个进程**的 worker
 *                    执行,那个进程对本地 map 一无所知
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
        String displayName,
        int exceptions,
        String batchId,
        java.util.List<QueuedJob> chain,
        QueuedJob chainCatch,
        Map<String, Object> context
) {

    public QueuedJob {
        data = data == null ? Map.of() : Map.copyOf(data);
        chain = chain == null ? java.util.List.of() : java.util.List.copyOf(chain);
        context = context == null ? Map.of() : Map.copyOf(context);
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
                job.displayName(),
                0,
                null,
                java.util.List.of(),
                null,
                Map.of());
    }

    /**
     * 手工拼一条信封 —— **测试用**。
     *
     * <p>存在的理由:这个 record 有 16 个组件,而测试通常只关心其中三四个。
     * 让测试写全 16 个参数的后果是,以后每加一个字段都要改掉几十处
     * 与那个字段无关的测试,于是「加字段」这件事变得很贵,
     * 而代价会体现在设计上(大家开始把东西塞进 data 里)。
     */
    public static QueuedJob forTest(String uuid, Class<?> jobType,
                                    Map<String, Object> data, String queue,
                                    int attempts, int tries) {
        Instant now = Instant.now();
        return new QueuedJob(uuid, jobType.getName(), data, queue, attempts,
                now, now, tries, Duration.ofMinutes(1), null,
                Integer.MAX_VALUE, jobType.getSimpleName(), 0, null,
                java.util.List.of(), null, Map.of());
    }

    /** 同上,但 jobClass 是一个任意字符串(用于「类不存在」这类测试) */
    public static QueuedJob forTest(String uuid, String jobClass,
                                    Map<String, Object> data, String queue,
                                    int attempts, int tries) {
        Instant now = Instant.now();
        return new QueuedJob(uuid, jobClass, data, queue, attempts,
                now, now, tries, Duration.ofMinutes(1), null,
                Integer.MAX_VALUE, "test", 0, null, java.util.List.of(), null, Map.of());
    }

    /** 下一次尝试 —— attempts 加一,可取时间推后 */
    public QueuedJob nextAttempt(Instant availableAt) {
        return new QueuedJob(uuid, jobClass, data, queue, attempts + 1,
                pushedAt, availableAt, tries, timeout, retryUntil,
                maxExceptions, displayName, exceptions, batchId, chain, chainCatch, context);
    }

    /** 标记为正在执行的这一次 */
    public QueuedJob reserved() {
        return new QueuedJob(uuid, jobClass, data, queue, attempts + 1,
                pushedAt, availableAt, tries, timeout, retryUntil,
                maxExceptions, displayName, exceptions, batchId, chain, chainCatch, context);
    }

    /**
     * 记一次真正的异常。
     *
     * <p>只在任务**抛出**时调用 —— 中间件主动 release 不走这里。
     * 这是 {@code maxExceptions} 与 {@code tries} 得以区分的地方:
     * 「等锁等了 50 次」不该耗尽异常配额。
     */
    public QueuedJob withException() {
        return new QueuedJob(uuid, jobClass, data, queue, attempts,
                pushedAt, availableAt, tries, timeout, retryUntil,
                maxExceptions, displayName, exceptions + 1, batchId, chain, chainCatch, context);
    }

    public QueuedJob inBatch(String id) {
        return new QueuedJob(uuid, jobClass, data, queue, attempts,
                pushedAt, availableAt, tries, timeout, retryUntil,
                maxExceptions, displayName, exceptions, id, chain, chainCatch, context);
    }

    /** 挂上链上后续任务与中断回调 */
    public QueuedJob withChain(java.util.List<QueuedJob> rest, QueuedJob onFailure) {
        return new QueuedJob(uuid, jobClass, data, queue, attempts,
                pushedAt, availableAt, tries, timeout, retryUntil,
                maxExceptions, displayName, exceptions, batchId, rest, onFailure, context);
    }

    /**
     * 链上的下一个任务,已经挂好它自己的后续。
     *
     * <p>可取时间重置为 {@code now} —— 链的延迟是**整条链**的开始延迟,
     * 不是每一环都再等一遍。把原始 availableAt 传下去会让一条
     * 「延迟 1 小时」的 5 环链变成「每环都等 1 小时」。
     */
    public java.util.Optional<QueuedJob> nextInChain(Instant now) {
        if (chain.isEmpty()) return java.util.Optional.empty();

        QueuedJob head = chain.get(0);
        var rest = chain.subList(1, chain.size());
        return java.util.Optional.of(
                new QueuedJob(head.uuid(), head.jobClass(), head.data(), head.queue(),
                        0, now, now, head.tries(), head.timeout(),
                        head.retryUntil(), head.maxExceptions(), head.displayName(),
                        0, head.batchId(), rest, chainCatch, head.context()));
    }

    public boolean hasChain() {
        return !chain.isEmpty();
    }

    /** 往信封级上下文里塞一项(覆盖同名) */
    public QueuedJob withContext(String key, Object value) {
        var merged = new java.util.LinkedHashMap<>(context);
        merged.put(key, value);
        return new QueuedJob(uuid, jobClass, data, queue, attempts,
                pushedAt, availableAt, tries, timeout, retryUntil,
                maxExceptions, displayName, exceptions, batchId, chain, chainCatch,
                merged);
    }

    /**
     * 还能不能再试。
     *
     * <p>两个条件取先到的那个:次数用完,或超过了绝对截止时间。
     */
    public boolean canRetry(Instant now) {
        if (attempts >= tries) return false;
        if (exceptions >= maxExceptions) return false;
        return retryUntil == null || now.isBefore(retryUntil);
    }

    /** 为什么不能再试 —— 写进 failed_jobs 的原因里 */
    public String exhaustionReason(Instant now) {
        if (retryUntil != null && !now.isBefore(retryUntil)) {
            return "超过重试截止时间(retryUntil=" + retryUntil + ")";
        }
        if (exceptions >= maxExceptions) {
            return "抛出异常 " + exceptions + " 次,达到 maxExceptions 上限 " + maxExceptions;
        }
        return "已尝试 " + attempts + " 次,达到上限 " + tries;
    }

    @Override
    public String toString() {
        return "%s[%s] queue=%s attempts=%d/%d"
                .formatted(displayName, uuid.substring(0, 8), queue, attempts, tries);
    }
}
