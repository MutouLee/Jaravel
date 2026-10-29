package io.jaravel.spring;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.devtools.ChangeClassifier;
import io.jaravel.devtools.FileWatcher;
import io.jaravel.devtools.ReloadCoordinator;
import io.jaravel.devtools.RestartGate;
import io.jaravel.view.ViewEngine;

/**
 * 热重载的接线(docs/15 的 L1 与 L3 增强)。
 *
 * <h2>只在开发模式下装配</h2>
 * <p>条件是 {@code app.debug} 为真。生产环境里文件监听是纯负担:
 * 它占句柄、产生 IO,而部署后的文件不会变 —— 真变了也该走滚动发布
 * 而不是热重载。
 *
 * <h2>本模块交付的是 docs/15 §3.3 的两项增强,不是类加载器本身</h2>
 * <p>L3 的「双类加载器」由 {@code spring-boot-devtools} 提供 ——
 * 重新实现一遍它是性价比极低的自研(docs/15 明确说「复用其思路」)。
 * Jaravel 加的是 devtools **没有**的两件事:
 *
 * <ol>
 *   <li><b>请求挂起</b>:重启期间的请求等待而不是打到一个半死的容器上;</li>
 *   <li><b>按需重启</b>:存文件不立刻重启,下一个请求才触发 ——
 *       连续编辑 10 个文件只重启一次。</li>
 * </ol>
 *
 * <p>以及 L1:模板与配置的变更**完全不重启**。devtools 对
 * {@code resources/} 下的变更也走整体重启(几百毫秒到几秒),
 * 而 Jaravel 这里是毫秒级。
 *
 * <h2>没有 devtools 时会怎样</h2>
 * <p>L1 照常工作(它不依赖 devtools)。L3 退化为「打印一条提示,
 * 告诉用户改动需要重启才生效」—— 比静默无效要好。
 * {@code jaravel doctor}(v0.5)会建议加上 devtools。
 */
@AutoConfiguration(after = JaravelAutoConfiguration.class)
@ConditionalOnProperty(name = "app.debug", havingValue = "true", matchIfMissing = false)
public class JaravelDevToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestartGate jaravelRestartGate(ConfigRepository config) {
        return new RestartGate(config.duration("jaravel.hot-reload.max-wait",
                                               ReloadCoordinator.defaultMaxWait()));
    }

    /**
     * 协调器。
     *
     * <p>L3 的实际重启动作交给 devtools —— 它监听 classpath 变化并自己
     * 重启,所以这里的 {@code onRestartLoader} 只负责**等它做完**。
     * 没有 devtools 时打印提示。
     */
    @Bean
    @ConditionalOnMissingBean
    public ReloadCoordinator jaravelReloadCoordinator(
            RestartGate gate, ViewEngine views, ConfigRepository config) {

        boolean devtoolsPresent = org.springframework.util.ClassUtils.isPresent(
                "org.springframework.boot.devtools.restart.Restarter",
                getClass().getClassLoader());

        var coordinator = new ReloadCoordinator(gate, new ChangeClassifier(false))
                // L1:丢弃已编译的模板。配置的重读由 ConfigRepository 自己
                // 按文件 mtime 处理(开发期从文件系统读)
                .onResource(views::invalidate)
                .onRestartLoader(() -> {
                    if (devtoolsPresent) {
                        DevtoolsBridge.restart();
                    } else {
                        System.out.println("""

                                [jaravel] 检测到源码变更,但 spring-boot-devtools 不在 classpath 上
                                          —— 改动需要重启才会生效。
                                  加上它即可获得 < 2s 的类加载器重启:
                                    <dependency>
                                      <groupId>org.springframework.boot</groupId>
                                      <artifactId>spring-boot-devtools</artifactId>
                                      <optional>true</optional>
                                    </dependency>""");
                    }
                });

        startWatcher(coordinator, config);
        return coordinator;
    }

    private void startWatcher(ReloadCoordinator coordinator, ConfigRepository config) {
        if (!config.bool("jaravel.hot-reload.watch", true)) return;

        List<Path> roots = config.stringList("jaravel.hot-reload.roots").isEmpty()
                ? ReloadCoordinator.conventionalRoots()
                : config.stringList("jaravel.hot-reload.roots").stream().map(Path::of).toList();

        var watcher = new FileWatcher(roots, coordinator::handle);
        watcher.start();

        // 容器关闭时停掉监听线程。不停的话 devtools 重启会累积出
        // 一堆监听线程,每次文件变更被处理 N 次
        Runtime.getRuntime().addShutdownHook(
                new Thread(watcher::close, "jaravel-watcher-shutdown"));
    }

    /**
     * 请求闸门过滤器。
     *
     * <p>必须是**最外层**的过滤器:它要在任何 Jaravel 组件接触请求之前
     * 决定「要不要先重启」。重启会丢掉容器里的一切,半途重启等于
     * 让这个请求跑在两个不同的容器上。
     */
    @Bean
    @ConditionalOnMissingBean
    public HotReloadFilter jaravelHotReloadFilter(ReloadCoordinator coordinator) {
        return new HotReloadFilter(coordinator);
    }

    public static class HotReloadFilter extends OncePerRequestFilter implements Ordered {

        private final ReloadCoordinator coordinator;

        public HotReloadFilter(ReloadCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {

            if (!coordinator.enterRequest()) {
                // 等超时了。503 + 说明 —— 浏览器转圈之后至少要有个解释
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().write(coordinator.gate().timeoutMessage());
                return;
            }
            chain.doFilter(request, response);
        }

        /**
         * 静态资源与健康检查不走闸门。
         *
         * <p>浏览器在页面加载时会并发请求十几个静态文件。让它们每个都
         * 去抢重启的执行权没有意义,而且会让「谁触发重启」变得随机 ——
         * 调试热重载本身的时候那很恼人。
         */
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI();
            return path.startsWith("/actuator")
                || path.endsWith(".css") || path.endsWith(".js")
                || path.endsWith(".map") || path.endsWith(".ico")
                || path.endsWith(".png") || path.endsWith(".jpg")
                || path.endsWith(".svg") || path.endsWith(".woff2");
        }
    }

    /**
     * 对 devtools 的调用隔到一个单独的类里。
     *
     * <p>这样 {@code Restarter} 不在 classpath 上时,只有真的调用
     * {@link #restart()} 才会触发类加载失败 —— 而那条路径已经被
     * {@code devtoolsPresent} 守住了。写在上面的 lambda 里的话,
     * 外层类的验证阶段就可能去解析它。
     */
    static final class DevtoolsBridge {

        private DevtoolsBridge() {
        }

        static void restart() {
            // devtools 自己监听 classpath 变化并重启。这里显式触发是为了
            // 让「下一个请求触发重启」这个时序由 Jaravel 掌握 ——
            // 否则 devtools 会在文件一变时就重启(每保存一次一次)
            org.springframework.boot.devtools.restart.Restarter.getInstance().restart();
        }
    }

    /** 开发模式的判定与 Application.isDebug 保持一致,便于诊断输出 */
    public static boolean isDevMode() {
        return Application.isDebug();
    }
}
