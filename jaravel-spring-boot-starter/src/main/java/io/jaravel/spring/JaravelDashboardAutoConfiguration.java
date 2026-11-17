package io.jaravel.spring;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.dashboard.DashboardService;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.QueueWorker;
import io.jaravel.spring.dashboard.DashboardController;
import io.jaravel.spring.dashboard.DashboardRoutes;

/**
 * 队列监控面板的自动装配(docs/10 §1.8)。
 *
 * <pre>{@code
 * dashboard:
 *   enabled: true                  # 默认 = app.debug
 *   path: /_jaravel/dashboard
 *   middleware: web                # 默认 web(会话 + CSRF);生产建议 web,auth
 *   queues: default,mail           # 默认取 queue.worker.queues,再退到默认队列
 * }</pre>
 *
 * <h2>默认只在 debug / local 下开</h2>
 * <p>它会展示失败任务的异常消息与队列积压 —— 生产环境不该默认公开。
 * 要开就显式 {@code dashboard.enabled: true},并自己加认证中间件。
 *
 * <h2>worker 状态只覆盖进程内 worker</h2>
 * <p>独立 worker 进程(v0.7 之前推荐的部署方式)没有心跳注册,
 * 面板看不到它们。这是已知限制;心跳归 {@code jaravel-dashboard}
 * 的后续版本。
 */
@AutoConfiguration(after = JaravelQueueAutoConfiguration.class)
@ConditionalOnBean({QueueManager.class, FailedJobStore.class})
public class JaravelDashboardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DashboardService jaravelDashboardService(
            ConfigRepository config,
            QueueManager queues,
            FailedJobStore failedJobs,
            ObjectProvider<JaravelQueueAutoConfiguration.InProcessWorker> worker) {

        List<String> show = config.stringList("dashboard.queues");
        if (show.isEmpty()) show = config.stringList("queue.worker.queues");
        if (show.isEmpty()) show = List.of(queues.defaultQueueName());

        Supplier<DashboardService.WorkerStat> workerStat = null;
        var inProcess = worker.getIfAvailable();
        if (inProcess != null) {
            QueueWorker w = inProcess.worker();
            workerStat = () -> new DashboardService.WorkerStat(
                    queues.defaultConnectionName(),
                    w.options().queues(),
                    w.options().concurrency(),
                    w.processedCount(),
                    w.succeededCount(),
                    w.failedCount(),
                    w.releasedCount(),
                    w.inFlightCount());
        }

        return new DashboardService(queues, failedJobs,
                queues.hasBatches() ? queues.requireBatches() : null,
                show, workerStat);
    }

    @Bean
    @ConditionalOnMissingBean
    public DashboardController jaravelDashboardController(DashboardService dashboard,
                                                          ConfigRepository config) {
        return new DashboardController(dashboard,
                normalizeBase(config.string("dashboard.path", "/_jaravel/dashboard")));
    }

    @Bean
    @ConditionalOnMissingBean(DashboardRoutes.class)
    public FrameworkRoutes jaravelDashboardRoutes(ConfigRepository config) {
        boolean enabled = config.bool("dashboard.enabled",
                config.bool("app.debug", false));
        String base = normalizeBase(config.string("dashboard.path",
                "/_jaravel/dashboard"));

        List<String> middleware = config.stringList("dashboard.middleware");
        if (middleware.isEmpty()) middleware = List.of("web");

        if (enabled && !middleware.contains("auth") && !config.bool("app.debug", false)) {
            System.err.println("""
                    [jaravel] 警告:dashboard 已开启,但中间件里没有 auth。
                              面板会展示失败任务的异常消息与队列积压 —— 生产环境
                              请把 dashboard.middleware 改成 web,auth,或关掉它。""");
        }

        return new DashboardRoutes(enabled, base, middleware);
    }

    /** {@code /x/} 与 {@code x} 都规整成 {@code /x} */
    public static String normalizeBase(String path) {
        String p = path == null || path.isBlank() ? "/_jaravel/dashboard" : path.trim();
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
