package io.jaravel.queue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.support.Arr;

/**
 * 按名字管理队列连接。
 *
 * <pre>{@code
 * var queues = new QueueManager(serializer)
 *         .register("sync", m -> new SyncQueue(serializer, invoker))
 *         .register("database", m -> new DatabaseQueue(connection, serializer))
 *         .defaultConnection("database")
 *         .defaultQueue("default")
 *         .retryAfter(Duration.ofSeconds(90));
 * }</pre>
 *
 * <h2>连接是单例,与 guard 相反</h2>
 * <p>{@code AuthManager} 的 guard 是请求级的(它缓存当前用户),
 * 而队列连接**无状态** —— 它只是「往哪写、从哪读」。
 * 做成请求级会让 database 驱动每个请求重建一次,毫无收益。
 */
public final class QueueManager {

    private final Map<String, Function<QueueManager, QueueStore>> factories =
            new LinkedHashMap<>();
    private final Map<String, QueueStore> resolved = new LinkedHashMap<>();

    private final JobSerializer serializer;

    private String defaultConnection = "sync";
    private String defaultQueue = "default";
    private Duration retryAfter = Duration.ofSeconds(90);

    /**
     * 全局默认:是否等事务提交后才真正入队。
     *
     * <p>见 {@link PendingDispatch#afterCommit()} 的注释 ——
     * 这个开关默认**开**,因为关着的那个行为会产生一类很难查的 bug。
     */
    private boolean afterCommitByDefault = true;

    /**
     * 唯一性任务的锁存储。
     *
     * <p>没有它时 {@link UniqueJob} **不生效**,而那是个会被当成 bug 的
     * 静默行为 —— 所以 {@link #requireUniqueLocks} 在真的遇到
     * UniqueJob 时报错,而不是悄悄放过。
     */
    private io.jaravel.cache.Repository uniqueLocks;

    /**
     * 批次状态存储。
     *
     * <p>与 {@link #uniqueLocks} 同样的处理:没有它时 {@link #requireBatches}
     * 报错而不是静默退化。一个批处理在没有存储时「投成功了但回调永不触发」
     * 是最难查的那一类症状。
     */
    private BatchRepository batches;

    public QueueManager(JobSerializer serializer) {
        this.serializer = serializer;
    }

    /** 装上唯一性任务需要的锁存储(通常是默认 cache store) */
    public QueueManager uniqueLocks(io.jaravel.cache.Repository cache) {
        this.uniqueLocks = cache;
        return this;
    }

    /**
     * 取锁存储,没有就报错。
     *
     * <p>不静默跳过:一个声明了 {@code UniqueJob} 的任务却不去重,
     * 症状是「队列里有 5000 条重复任务」,而那很难联想到
     * 「缓存没配」。
     */
    io.jaravel.cache.Repository requireUniqueLocks(ShouldQueue job) {
        if (uniqueLocks != null) return uniqueLocks;

        throw new IllegalStateException("""
                任务 %s 声明了 UniqueJob,但队列没有配锁存储,唯一性无法生效。
                  修复:给 QueueManager 调 uniqueLocks(cache.store()),
                        或在 config/cache.yml 里配一个可用的 cache.default。
                  这里报错而不是静默跳过 —— 不去重的症状是
                  「队列里有一堆重复任务」,很难联想到缓存没配。"""
                .formatted(job.getClass().getName()));
    }

    boolean hasUniqueLocks() {
        return uniqueLocks != null;
    }

    public QueueManager batches(BatchRepository repository) {
        this.batches = repository;
        return this;
    }

    public boolean hasBatches() {
        return batches != null;
    }

    public BatchRepository requireBatches() {
        if (batches != null) return batches;

        throw new IllegalStateException("""
                队列没有配批次存储,Queue.batch(...) 无法使用。
                  修复:给 QueueManager 调 batches(new DatabaseBatchRepository(connection, serializer)),
                        并建好 job_batches 表(见 DatabaseBatchRepository 的类注释)。
                  这里报错而不是静默退化 —— 没有存储时批次会「投成功了但
                  then / catch / finally 回调永不触发」,而那很难联想到缺一张表。""");
    }

    // ================= 注册 =================

    public QueueManager register(String name, Function<QueueManager, QueueStore> factory) {
        factories.put(name, factory);
        resolved.remove(name);
        return this;
    }

    public QueueManager register(String name, QueueStore store) {
        return register(name, m -> store);
    }

    public QueueManager defaultConnection(String name) {
        requireKnown(name);
        this.defaultConnection = name;
        return this;
    }

    public QueueManager defaultQueue(String name) {
        this.defaultQueue = name;
        return this;
    }

    public QueueManager retryAfter(Duration duration) {
        this.retryAfter = duration;
        return this;
    }

    public QueueManager afterCommitByDefault(boolean enabled) {
        this.afterCommitByDefault = enabled;
        return this;
    }

    // ================= 解析 =================

    public QueueStore connection() {
        return connection(defaultConnection);
    }

    public QueueStore connection(String name) {
        requireKnown(name);
        return resolved.computeIfAbsent(name, n -> factories.get(n).apply(this));
    }

    private void requireKnown(String name) {
        if (factories.containsKey(name)) return;

        throw new IllegalStateException("""
                没有名为 "%s" 的队列连接。%s
                  已注册:%s
                  在 config/queue.yml 的 queue.connections 下登记它。"""
                .formatted(name,
                           Arr.closest(name, factories.keySet().stream().toList())
                              .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse(""),
                           factories.keySet()));
    }

    // ================= 访问器 =================

    public String defaultConnectionName() { return defaultConnection; }
    public String defaultQueueName()      { return defaultQueue; }
    public Duration retryAfterDuration()  { return retryAfter; }
    public boolean isAfterCommitByDefault() { return afterCommitByDefault; }
    public JobSerializer serializer()     { return serializer; }

    public java.util.Set<String> registeredConnections() {
        return java.util.Set.copyOf(factories.keySet());
    }

    /**
     * 当前默认连接是不是同步的。
     *
     * <p>框架在启动时用它提醒「生产环境配了 sync」——
     * 那意味着所有本该后台跑的任务都在请求线程里跑,
     * 症状是接口莫名很慢而功能看起来正常,非常难归因。
     */
    public boolean isDefaultAsynchronous() {
        return connection().isAsynchronous();
    }
}
