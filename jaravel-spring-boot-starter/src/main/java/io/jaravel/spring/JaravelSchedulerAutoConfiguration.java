package io.jaravel.spring;

import java.time.Duration;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.jaravel.cache.CacheManager;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.container.Container;
import io.jaravel.scheduler.Schedule;
import io.jaravel.scheduler.Scheduler;
import io.jaravel.scheduler.SchedulerConfigCheck;
import io.jaravel.scheduler.TaskAction;

/**
 * 调度的自动装配(docs/10 §3.3)。
 *
 * <pre>{@code
 * schedule:
 *   enabled: true
 *   in-process: true        # 默认:应用内置调度器,无需外部 cron
 *   instances: 0            # 0 = 未声明;声明 1 才允许 onOneServer + 本地缓存
 *   timezone: Asia/Shanghai
 * }</pre>
 *
 * <h2>默认开启,而队列 worker 默认关闭</h2>
 * <p>这两个默认值相反,值得解释。
 *
 * <p>队列 worker 默认关,因为 web 与 worker 应该分开伸缩与重启 ——
 * 一个耗内存的任务不该影响接口的 P99。
 *
 * <p>调度器默认开,因为**它几乎不消耗资源**(一个每分钟醒一次的
 * 虚拟线程),而关着的后果是「我写了计划任务但它不跑」——
 * 那是一个会让人以为框架坏了的体验。真正重的活儿由
 * {@code s.job(...)} 投进队列,那条路径仍然受 worker 开关控制。
 *
 * <p>没有任何任务时**不启动线程** —— 一个空的调度器没必要存在。
 */
@AutoConfiguration(after = {JaravelAutoConfiguration.class,
        JaravelQueueAutoConfiguration.class, JaravelEventsAutoConfiguration.class})
public class JaravelSchedulerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Schedule jaravelSchedule(
            ConfigRepository config,
            Container container,
            ObjectProvider<Schedule.ScheduleDefinition> definitions) {

        var schedule = new Schedule(queueDispatcher(container), null);

        String zone = config.string("schedule.timezone", null);
        if (zone != null && !zone.isBlank()) {
            // 在登记任务**之前**设,否则对已登记的不生效 ——
            // 见 Schedule.timezone 的注释
            schedule.timezone(zone);
        }

        definitions.orderedStream().forEach(definition -> definition.schedule(schedule));
        return schedule;
    }

    /**
     * 投队列任务的通道。
     *
     * <p>{@code jaravel-queue} 不在 classpath 上时返回 null ——
     * 那时 {@code s.job(...)} 会报一条说清后果的错(见
     * {@link Schedule#job}),而不是静默同步执行。
     *
     * <p>用反射判断而不是直接 import:starter 对 queue 是可选依赖。
     */
    private TaskAction.JobDispatcher queueDispatcher(Container container) {
        if (!classPresent("io.jaravel.queue.Queue")) return null;

        return jobType -> {
            // 任务实例由容器建 —— 一个计划任务通常是无参的,
            // 但它可能需要注入依赖(与控制器一致)
            Object job = container.make(jobType);
            if (!(job instanceof io.jaravel.queue.ShouldQueue queueable)) {
                throw new IllegalStateException("""
                        s.job(%s) 要求它实现 ShouldQueue,但它没有。
                          计划任务走的是队列,所以必须是一个队列任务。
                          只想定时跑一段代码的话用 s.call("名字", () -> ...)。"""
                        .formatted(jobType.getName()));
            }
            io.jaravel.queue.Queue.dispatch(queueable);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public Scheduler jaravelScheduler(Schedule schedule, ConfigRepository config,
                                      ObjectProvider<CacheManager> caches) {

        // ★ docs/10 §3.4 要求的启动校验。在**建 Scheduler 之前**做 ——
        // 建完再校验意味着一个不安全的配置已经有了一个可用的调度器对象,
        // 而那个对象可能被别的 bean 拿去 start()
        verifyConfiguration(schedule, config, caches);

        var scheduler = new Scheduler(schedule)
                .locks(taskLocks(caches))
                .pinger(httpPinger());

        SchedulerConfigCheck.suspiciousOverlapTtls(schedule.tasks())
                .forEach(warning -> System.err.printf("[jaravel] %s%n", warning));

        return scheduler;
    }

    private void verifyConfiguration(Schedule schedule, ConfigRepository config,
                                     ObjectProvider<CacheManager> caches) {
        CacheManager manager = caches.getIfAvailable();
        String driver = manager == null
                ? null
                : config.string("cache.default", "array");

        SchedulerConfigCheck.verify(schedule.tasks(), driver,
                config.integer("schedule.instances", 0));
    }

    /**
     * {@code onOneServer} 用的锁。
     *
     * <p>没有缓存时**不装** —— 那时 {@code Scheduler.TaskLocks.NONE} 放行,
     * 而「多实例 + 没有共享锁」的组合已经被上面的启动校验拦下了。
     */
    private Scheduler.TaskLocks taskLocks(ObjectProvider<CacheManager> caches) {
        CacheManager manager = caches.getIfAvailable();
        if (manager == null) return Scheduler.TaskLocks.NONE;

        var cache = manager.store();
        return new Scheduler.TaskLocks() {
            @Override
            public Handle acquire(String key, Duration ttl) {
                var lock = cache.lock(key, ttl);
                if (!lock.get()) return null;

                // forceRelease 而不是 release:锁的 owner 是**这一次**
                // 调用,而释放发生在任务跑完之后(可能在另一个虚拟线程里)。
                // owner 检查在那里会失败 —— 那个检查对缓存锁是对的
                // (防止误释放别人的锁),但这里持有者与释放者天生不同
                return lock::forceRelease;
            }

            @Override
            public void forceRelease(String key) {
                cache.restoreLock(key, "scheduler").forceRelease();
            }
        };
    }

    /**
     * 心跳 ping —— 走 {@code Http} 门面。
     *
     * <p>**超时必须短**(5 秒):ping 是在任务结束后同步做的,
     * 一个挂住的监控端点会让调度线程卡在那里,而后果是
     * **其它所有任务都延迟** —— 比 ping 失败严重得多。
     *
     * <p>只发 GET、不看响应体:Healthchecks.io 这类服务只关心请求到达。
     *
     * <p>**不重试**(retry(1))。这一点与通知的 slack/webhook 渠道
     * 相反,理由是 ping 的语义:它报告「这次任务跑完了」,
     * 而重试会让调度线程等得更久 —— 而下一次任务运行时会再 ping 一次。
     * 漏一次心跳的代价(监控可能误报一次)小于拖住调度线程。
     *
     * <p>原先这里自己 new 了一个 HttpClient。换成门面是因为
     * 通知的 slack/webhook 渠道也做了同样的一遍 ——
     * 两处的超时值与错误判定注定会分叉。
     */
    private Scheduler.Pinger httpPinger() {
        return url -> {
            var response = io.jaravel.http.client.Http
                    .timeout(Duration.ofSeconds(5))
                    .connectTimeout(Duration.ofSeconds(5))
                    .retry(1, Duration.ZERO)
                    .get(url);

            if (response.failed()) {
                throw new IllegalStateException(
                        "ping 失败 %s:%s".formatted(url,
                                response.connectionFailed()
                                        ? String.valueOf(response.failure())
                                        : "HTTP " + response.status()));
            }
        };
    }

    /**
     * 进程内调度器的生命周期。
     *
     * <p>{@code in-process: false} 时不启动 —— 那是「外部触发」模式
     * (Kubernetes CronJob 调 {@code jaravel schedule:run}),
     * 适合「调度不能依赖应用实例是否存活」的场景。
     */
    @Bean
    @ConditionalOnMissingBean
    public InProcessScheduler jaravelInProcessScheduler(Scheduler scheduler,
                                                        ConfigRepository config) {
        return new InProcessScheduler(scheduler, config);
    }

    static class InProcessScheduler {

        private final Scheduler scheduler;
        private final ConfigRepository config;

        InProcessScheduler(Scheduler scheduler, ConfigRepository config) {
            this.scheduler = scheduler;
            this.config = config;
        }

        @PostConstruct
        void start() {
            if (!config.bool("schedule.enabled", true)) {
                return;
            }
            if (!config.bool("schedule.in-process", true)) {
                System.err.println("[jaravel] 调度器为外部触发模式"
                        + "(schedule.in-process=false)—— 进程内不启动。");
                return;
            }

            // 没有任务就不起线程。一个空的调度器没必要存在,
            // 而「启动日志里有一行调度器启动但什么都没登记」会让人
            // 以为任务登记成功了
            if (scheduler.schedule().isEmpty()) {
                return;
            }

            scheduler.start();
            System.err.printf("[jaravel] 调度器已启动,%d 个任务。%n",
                    scheduler.schedule().tasks().size());
        }

        @PreDestroy
        void stop() {
            if (scheduler.isRunning()) scheduler.stop();
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false,
                    JaravelSchedulerAutoConfiguration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
