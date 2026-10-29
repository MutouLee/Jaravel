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

    public QueueManager(JobSerializer serializer) {
        this.serializer = serializer;
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
