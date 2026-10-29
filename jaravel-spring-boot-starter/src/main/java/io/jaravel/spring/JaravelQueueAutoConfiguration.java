package io.jaravel.spring;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.container.Container;
import io.jaravel.database.Connection;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobInvoker;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.Queue;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.QueueWorker;
import io.jaravel.queue.WorkerOptions;
import io.jaravel.cache.CacheManager;
import io.jaravel.queue.store.DatabaseBatchRepository;
import io.jaravel.queue.store.DatabaseFailedJobStore;
import io.jaravel.queue.store.DatabaseQueue;
import io.jaravel.queue.store.NullQueue;
import io.jaravel.queue.store.SyncQueue;
import io.jaravel.support.Arr;

/**
 * 队列的自动装配(docs/10 §1.5 的 config/queue.yml)。
 *
 * <pre>{@code
 * queue:
 *   default: ${QUEUE_CONNECTION:database}
 *   after-commit: true
 *   connections:
 *     database:
 *       driver: database
 *       table: jobs
 *       queue: default
 *       retry-after: PT90S
 *     sync:
 *       driver: sync
 *   failed:
 *     table: failed_jobs
 *   batching:
 *     table: job_batches
 *   worker:
 *     enabled: false          # 进程内起 worker
 *     queues: high,default
 *     concurrency: 10
 * }</pre>
 *
 * <h2>默认不在应用进程里起 worker</h2>
 * <p>{@code queue.worker.enabled} 默认 **false**。理由:web 进程与 worker
 * 进程应该分开伸缩与重启 —— 一个耗内存的任务不该影响接口的 P99,
 * 而重启 worker 不该断掉正在处理的 HTTP 请求。
 *
 * <p>但**允许**它为 true,因为对小项目「一个进程全搞定」是真实需求,
 * 而强制分进程意味着他们得先学会部署两个东西。这是个取舍,
 * 默认值选了「生产正确」那一边。
 */
@AutoConfiguration(after = JaravelAutoConfiguration.class)
public class JaravelQueueAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JobSerializer jaravelJobSerializer() {
        return new JobSerializer();
    }

    /**
     * handle 参数的注入来源。
     *
     * <p>直接用容器 —— 于是任务的 handle 能注入任何 bean,
     * 与控制器一致。
     */
    @Bean
    @ConditionalOnMissingBean
    public JobInvoker jaravelJobInvoker(Container container) {
        return new JobInvoker(container::make);
    }

    @Bean
    @ConditionalOnMissingBean
    public FailedJobStore jaravelFailedJobStore(ConfigRepository config,
                                                ObjectProvider<Connection> connection) {
        Connection conn = connection.getIfAvailable();
        if (conn == null) {
            // 无库应用(redis 队列)也要有一个失败归档。
            // 返回一个只打日志的实现比让启动失败好 ——
            // 但必须说清楚「失败任务不会被保留」
            return new LoggingFailedJobStore();
        }
        return new DatabaseFailedJobStore(conn,
                config.string("queue.failed.table", "failed_jobs"));
    }

    @Bean
    @ConditionalOnMissingBean
    public QueueManager jaravelQueueManager(
            ConfigRepository config, JobSerializer serializer, JobInvoker invoker,
            ObjectProvider<Connection> connection,
            ObjectProvider<CacheManager> cache,
            JaravelAutoConfiguration.JaravelStaticWiring wiring) {

        var queues = new QueueManager(serializer);

        var connections = config.section("queue.connections");
        if (connections.isEmpty()) {
            // 没配就给一套能用的默认值 —— 「不写 config/queue.yml 也能跑」
            registerBuiltin(queues, "sync", "sync", config, serializer, invoker, connection);
            registerBuiltin(queues, "database", "database", config,
                            serializer, invoker, connection);
            registerBuiltin(queues, "null", "null", config, serializer, invoker, connection);
        } else {
            connections.keySet().forEach(name -> registerBuiltin(queues, name,
                    config.string("queue.connections." + name + ".driver", "sync"),
                    config, serializer, invoker, connection));

            // sync 没在配置里出现时也注册它 —— Queue.dispatchSync 需要它,
            // 而那是个随处可用的 API,不该依赖用户记得配一条连接
            if (!queues.registeredConnections().contains("sync")) {
                registerBuiltin(queues, "sync", "sync", config,
                                serializer, invoker, connection);
            }
        }

        String defaultConnection = config.string("queue.default",
                queues.registeredConnections().contains("database") ? "database" : "sync");
        queues.defaultConnection(defaultConnection)
              .defaultQueue(config.string("queue.connections." + defaultConnection + ".queue",
                                          "default"))
              .retryAfter(config.duration(
                      "queue.connections." + defaultConnection + ".retry-after",
                      Duration.ofSeconds(90)))
              .afterCommitByDefault(config.bool("queue.after-commit", true));

        wireUniqueLocks(queues, cache);
        wireBatches(queues, serializer, connection, config);

        warnIfSynchronousInProduction(queues, config);

        Queue.use(queues);
        return queues;
    }

    /**
     * 唯一性任务要的锁存储。
     *
     * <p>没有缓存时**不装** —— 于是真遇到 {@code UniqueJob} 时
     * {@code requireUniqueLocks} 会报一条说得清的错。
     * 在这里塞一个空实现才是坏的:那会让去重静默失效。
     */
    private void wireUniqueLocks(QueueManager queues, ObjectProvider<CacheManager> cache) {
        CacheManager manager = cache.getIfAvailable();
        if (manager == null) return;

        try {
            queues.uniqueLocks(manager.store());
        } catch (RuntimeException e) {
            // 缓存配错了不该让整个应用起不来 —— 它只影响 UniqueJob,
            // 而那时 requireUniqueLocks 的报错比这里的启动失败更有指向性
            System.err.printf(
                    "[jaravel] 队列的唯一性锁没能装上(%s)。UniqueJob 在投递时会报错。%n",
                    e.getMessage());
        }
    }

    /**
     * 批次存储。
     *
     * <p>同样是「有库才装」:无库应用用不了 {@code Queue.batch(...)},
     * 而那时的报错会指向缺的那张表,比在这里编一个内存实现好 ——
     * 内存实现会让批次状态在重启后消失,于是「月度报表跑完了但没收到
     * 汇总邮件」而 job_batches 里什么都查不到。
     */
    private void wireBatches(QueueManager queues, JobSerializer serializer,
                             ObjectProvider<Connection> connection,
                             ConfigRepository config) {
        Connection db = connection.getIfAvailable();
        if (db == null) return;

        queues.batches(new DatabaseBatchRepository(db, serializer,
                config.string("queue.batching.table", "job_batches")));
    }

    private void registerBuiltin(QueueManager queues, String name, String driver,
                                 ConfigRepository config, JobSerializer serializer,
                                 JobInvoker invoker, ObjectProvider<Connection> connection) {
        String path = "queue.connections." + name;

        switch (driver) {
            case "sync" -> queues.register(name, m -> new SyncQueue(serializer, invoker));

            case "null" -> queues.register(name, m -> new NullQueue());

            // 懒注册:只有真正用到时才要求有连接 ——
            // 否则无库应用一启动就报错
            case "database" -> queues.register(name, m -> new DatabaseQueue(
                    requireConnection(connection, name),
                    serializer,
                    config.string(path + ".table", "jobs")));

            default -> throw new IllegalStateException("""
                    队列连接 "%s" 的 driver "%s" 不支持。%s
                      v0.4 内置:database / sync / null
                      redis / sqs / rabbitmq 列入 v0.5 —— 它们需要各自的客户端依赖。
                      自定义驱动:实现 QueueStore 并提供一个 QueueManager @Bean。"""
                    .formatted(name, driver,
                               Arr.closest(driver, List.of("database", "sync", "null"))
                                  .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse("")));
        }
    }

    private static Connection requireConnection(ObjectProvider<Connection> connection,
                                                String name) {
        Connection conn = connection.getIfAvailable();
        if (conn == null) {
            throw new IllegalStateException("""
                    队列连接 "%s" 用的是 database 驱动,但没有可用的 DataSource。
                      配置 spring.datasource.url,或把 queue.default 改成 sync。"""
                    .formatted(name));
        }
        return conn;
    }

    /**
     * 生产环境用 sync 驱动时提醒。
     *
     * <p>这个误用的症状很隐蔽:**功能看起来完全正常**,只是所有本该
     * 后台跑的任务都在请求线程里同步跑 —— 接口莫名很慢。
     * 一个发邮件的任务会让下单接口多等两秒,而那很难归因到队列配置上。
     *
     * <p>只警告不报错:有些部署确实故意这样(单机小站、或 worker 还没上)。
     */
    private void warnIfSynchronousInProduction(QueueManager queues,
                                               ConfigRepository config) {
        if (config.bool("app.debug", false)) return;
        if (queues.isDefaultAsynchronous()) return;

        System.err.printf("""

                [jaravel] 警告:queue.default = "%s" 是**同步**驱动,而当前不是开发环境。
                          所有任务都会在请求线程里立即执行 —— 功能正常,但接口会变慢,
                          而且这个因果很难从现象上看出来。
                          生产环境应配 database 或 redis,并单独起 queue:work。
                %n""", queues.defaultConnectionName());
    }

    // ================= 进程内 worker =================

    /**
     * 在应用进程里起一个 worker。
     *
     * <p>默认关闭,见类注释。开启时它随容器启动,随容器关闭优雅停机。
     */
    @Bean
    @ConditionalOnProperty(name = "queue.worker.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public InProcessWorker jaravelInProcessWorker(
            QueueManager queues, FailedJobStore failedJobs, JobInvoker invoker,
            ConfigRepository config) {

        var options = WorkerOptions
                .onQueues(queueNames(config))
                .withConcurrency(config.integer("queue.worker.concurrency", 10))
                .withSleep(config.duration("queue.worker.sleep", Duration.ofSeconds(1)))
                .withRetryAfter(queues.retryAfterDuration())
                .withMaxJobs(config.integer("queue.worker.max-jobs", 0))
                .withMaxTime(config.duration("queue.worker.max-time", Duration.ZERO))
                .withShutdownGrace(config.duration("queue.worker.shutdown-grace",
                                                   Duration.ofSeconds(30)));

        return new InProcessWorker(
                new QueueWorker(queues, failedJobs, invoker, options));
    }

    private static String[] queueNames(ConfigRepository config) {
        var configured = config.stringList("queue.worker.queues");
        return configured.isEmpty() ? new String[] {"default"}
                                    : configured.toArray(String[]::new);
    }

    /**
     * 把 worker 的生命周期绑到容器上。
     *
     * <p>{@code @PreDestroy} 里停机 —— 容器关闭时要等在跑的任务完成,
     * 而不是让 JVM 直接退出。后者会让那些任务停在半途,
     * 然后在保留超时后被重新执行:对非幂等任务就是重复副作用。
     */
    public static class InProcessWorker implements AutoCloseable {

        private final QueueWorker worker;
        private Thread thread;

        public InProcessWorker(QueueWorker worker) {
            this.worker = worker;
        }

        @jakarta.annotation.PostConstruct
        public void start() {
            thread = worker.runInBackground();
            System.out.printf("[jaravel] 进程内 worker 已启动:队列 %s,并发 %d%n",
                    worker.options().queues(), worker.options().concurrency());
        }

        @jakarta.annotation.PreDestroy
        @Override
        public void close() {
            worker.stop();
            if (thread == null) return;
            try {
                thread.join(worker.options().shutdownGrace().toMillis() + 2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public QueueWorker worker() {
            return worker;
        }
    }

    /**
     * 没有数据库时的失败归档。
     *
     * <p>**只打日志,不保留** —— 所以消息里必须说清这一点。
     * 静默丢弃失败任务是最糟的结果:用户会以为 failed_jobs 里有记录。
     */
    static class LoggingFailedJobStore implements FailedJobStore {

        private final java.util.concurrent.atomic.AtomicLong counter =
                new java.util.concurrent.atomic.AtomicLong();

        @Override
        public long record(io.jaravel.queue.QueuedJob job, String connection,
                           Throwable cause) {
            long id = counter.incrementAndGet();
            System.err.printf("""
                    [jaravel] 任务最终失败:%s(%s)
                              %s
                              ⚠ 没有配置数据库,这条记录**不会被保留** ——
                                queue:failed / queue:retry 看不到它。
                                要保留失败任务请配置 spring.datasource.url。
                    %n""", job.displayName(), job.uuid(), cause);
            return id;
        }

        @Override public java.util.Optional<FailedJob> find(long id) {
            return java.util.Optional.empty();
        }

        @Override public List<FailedJob> all()        { return List.of(); }
        @Override public boolean forget(long id)      { return false; }
        @Override public int flush()                  { return 0; }
        @Override public int prune(Duration olderThan) { return 0; }
        @Override public long count()                 { return 0; }
    }
}
