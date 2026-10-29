package io.jaravel.queue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 信封级上下文的键,以及把它解析成 handle 注入参数的规则。
 *
 * <p>{@link QueuedJob#context()} 与 {@link QueuedJob#data()} 的分工:
 * <ul>
 *   <li>{@code data} —— 任务**自己的字段**,由 {@link JobSerializer} 往返;</li>
 *   <li>{@code context} —— 框架塞进来的东西:这条任务属于哪个批次、
 *       链断在哪。它不属于任务的字段,混进 data 会让
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} 把它当成未知字段而报错。</li>
 * </ul>
 *
 * <h2>为什么上下文随信封走</h2>
 * <p>一个批次的 {@code then} 回调可能由**另一个进程**的 worker 执行 ——
 * 投递它的那个 worker 的内存对那个进程不可见。
 * 放在信封里是唯一能跨进程成立的做法。
 *
 * <p>这件事在单进程测试里看不出来:worker 内存里的一个 map
 * 在那种场景下完全够用,于是「回调拿不到 Batch」会在部署成
 * web + worker 两个进程之后才出现。
 */
public final class JobContext {

    /** 这条回调属于哪个批次(不是「计入哪个批次」—— 见 Worker 里的注释) */
    public static final String BATCH_ID = "batchId";

    /** 链中断的上下文,值是一个 {@link ChainFailure} 的 map 形态 */
    public static final String CHAIN_FAILURE = "chainFailure";

    private JobContext() {
    }

    /**
     * 把信封上下文摊成「类型 → 实例」,供 {@link JobInvoker} 注入。
     *
     * <p>只解析**存在的**键:一个普通任务的 context 是空的,
     * 这里返回空 map,于是它的 handle 参数照常从容器解析。
     *
     * @param queues 用来查批次 —— {@link Batch} 是实时状态,
     *               不能在投递回调时就快照下来:那时批次还没完成,
     *               快照里的 {@code finishedAt} 会是 null,
     *               而回调里 {@code batch.isFinished()} 返回 false
     *               读起来就像个 bug
     */
    static Map<Class<?>, Object> resolve(QueuedJob envelope, QueueManager queues,
                                         JobSerializer serializer) {
        if (envelope.context().isEmpty()) return Map.of();

        var bindings = new LinkedHashMap<Class<?>, Object>();

        Object batchId = envelope.context().get(BATCH_ID);
        if (batchId != null && queues.hasBatches()) {
            queues.requireBatches().find(String.valueOf(batchId))
                    .ifPresent(batch -> bindings.put(Batch.class, batch));
        }

        Object failure = envelope.context().get(CHAIN_FAILURE);
        if (failure != null) {
            // 经过 JSON 往返之后它是一个 Map,不再是 ChainFailure ——
            // 用 serializer 的 mapper 转回去,而不是手工取字段:
            // 手工取的版本会在 ChainFailure 加字段时静默少一项
            bindings.put(ChainFailure.class,
                    serializer.convert(failure, ChainFailure.class));
        }

        return bindings;
    }
}
