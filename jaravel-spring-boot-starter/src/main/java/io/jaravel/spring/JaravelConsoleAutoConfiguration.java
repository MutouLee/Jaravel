package io.jaravel.spring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigRepository;

import io.jaravel.console.Artisan;
import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.channel.ChannelServer;
import io.jaravel.console.generators.GeneratorCommand;
import io.jaravel.console.generators.MakeConsoleCommand;
import io.jaravel.console.generators.MakeController;
import io.jaravel.console.generators.MakeEvent;
import io.jaravel.console.generators.MakeJob;
import io.jaravel.console.generators.MakeListener;
import io.jaravel.console.generators.MakeMail;
import io.jaravel.console.generators.MakeMiddleware;
import io.jaravel.console.generators.MakeMigration;
import io.jaravel.console.generators.MakeModel;
import io.jaravel.console.generators.MakeNotification;
import io.jaravel.console.generators.MakePolicy;
import io.jaravel.console.generators.MakeRequest;
import io.jaravel.console.generators.MakeSeeder;
import io.jaravel.console.generators.MakeTest;

/**
 * 控制台的自动装配(docs/13)。
 *
 * <p>做三件事:把框架自带的命令登记进注册表、把应用自己的
 * {@link ConsoleCommand} bean 也登记进去、装上 {@code Artisan} 门面。
 *
 * <h2>★ 应用的命令是 Spring bean,所以依赖直接注入</h2>
 * <pre>{@code
 * @Component
 * @Command(name = "inventory:sync")
 * public class SyncInventory extends ConsoleCommand {
 *     private final SupplierClient client;
 *     public SyncInventory(SupplierClient client) { this.client = client; }
 * }
 * }</pre>
 *
 * <p>{@code CommandRegistry} 存的是**工厂**而不是实例,这里正好把
 * 「向容器要一个新实例」填进那个接缝 —— 于是命令每次运行都是干净的
 * 对象(参数绑定写在字段上,复用实例会让上一次的 {@code --force} 漏过来),
 * 而依赖仍然由 Spring 注入。
 *
 * <p>⚠ 所以应用的命令 bean 应当是 {@code prototype} 作用域。
 * 单例的话每次拿到的是同一个对象,又回到了「上一次的参数还在」。
 * 这里不去改用户 bean 的作用域(那会很意外),而是**在发现单例命令时
 * 警告一次** —— 症状(连着跑两条命令,第二条带上了第一条的选项)
 * 与原因离得太远,不说出来基本不可能自己找到。
 */
@AutoConfiguration(after = JaravelAutoConfiguration.class)
public class JaravelConsoleAutoConfiguration {

    /**
     * 框架自带的命令。
     *
     * <p>一张显式的表而不是扫包:docs/13 §3.2 说注册由 APT 生成索引,
     * 那一步还没做 —— 而运行时扫包在可执行 jar 与 native 镜像里都不可靠,
     * 恰好是这个模块最终要跑的两个环境(同 {@code Stub.bundled()} 的理由)。
     *
     * <p>加一条命令 = 写类 + 写 stub + 在这里加一行。第三行是暂时的:
     * APT 索引做完之后它会消失。
     */
    static final List<Class<? extends ConsoleCommand>> BUILT_IN = List.of(
            MakeModel.class, MakeController.class, MakeMigration.class,
            MakeRequest.class, MakeJob.class, MakeMiddleware.class,
            MakeEvent.class, MakeListener.class, MakePolicy.class,
            MakeSeeder.class, MakeMail.class, MakeNotification.class,
            MakeConsoleCommand.class, MakeTest.class);

    @Bean
    @ConditionalOnMissingBean
    public CommandRegistry jaravelCommandRegistry(
            org.springframework.beans.factory.BeanFactory beans,
            ObjectProvider<ConsoleCommand> applicationCommands) {

        var registry = new CommandRegistry();

        // 框架自带的 make:*。无参构造,直接注册
        BUILT_IN.forEach(registry::register);

        // 应用自己的命令:每次向容器要一个新实例
        applicationCommands.stream().forEach(command -> {
            Class<?> type = command.getClass();
            warnIfSingleton(beans, type);
            registry.register(() -> (ConsoleCommand) beans.getBean(type));
        });

        // 生成类命令要知道项目根在哪。**不是 classpath,是源码目录** ——
        // make:model 要写的是 src/main/java/app/models/Post.java,
        // 而那只在「从项目目录启动」时才有意义(IDE / mvn spring-boot:run)。
        Path projectRoot = projectRoot();
        registry.configure(command -> {
            if (command instanceof GeneratorCommand generator) generator.inProject(projectRoot);
        });

        Artisan.use(registry);
        return registry;
    }

    /**
     * 项目根 = 当前工作目录。
     *
     * <p>⚠ 这个假设在打包运行时**不成立**:一个 jar 可能从任何目录启动,
     * 那时 {@code make:model} 会往那个目录写 {@code src/main/java/...}。
     *
     * <p>不在这里报错,因为 {@code make:*} 在生产环境里本来就不该被调用 ——
     * 它们是离线命令,终将由 {@code jaravel-cli} 在项目目录里执行
     * (docs/13 §1)。这里只在**看不到 src/main/java** 时提醒一句,
     * 让「生成的文件去哪了」这个问题有线索。
     */
    private Path projectRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(cwd.resolve(GeneratorCommand.SOURCE_ROOT))) {
            System.err.printf("[jaravel] 当前目录 %s 下没有 %s —— "
                            + "make:* 生成的文件会落在这里。%n"
                            + "          生成类命令应当在项目目录里运行。%n",
                    cwd, GeneratorCommand.SOURCE_ROOT);
        }
        return cwd;
    }

    private void warnIfSingleton(org.springframework.beans.factory.BeanFactory beans,
                                 Class<?> type) {
        String[] names = beans instanceof org.springframework.beans.factory.ListableBeanFactory listable
                ? listable.getBeanNamesForType(type)
                : new String[0];
        for (String name : names) {
            if (!beans.isSingleton(name)) continue;
            System.err.printf("""
                    [jaravel] 命令 %s 是单例 bean。
                              命令对象带状态(解析出来的参数就写在它的字段上),
                              所以连着跑两条命令时,第二条会看到第一条留下的选项。
                              加 @Scope("prototype")。%n""", type.getName());
        }
    }

    /**
     * 本地命令通道(docs/13 §1.2)——「本模块最重要的 DX 设计」。
     *
     * <p>应用跑着的时候,{@code jaravel migrate} 会连上这个 socket,
     * 在**这个已经热起来的进程**里执行,而不是冷启动一个 JVM。
     *
     * <h2>★ 只在非 production 环境开</h2>
     * <p>这个通道按名字执行注册表里的**任何**命令 —— 在开发机上那正是要的,
     * 在生产环境里是个后门。所以:
     * <ul>
     *   <li>{@code app.env=production} 时根本不创建;</li>
     *   <li>socket 放在**项目目录**下(受项目目录的权限保护),
     *       而不是 {@code /tmp} 那种全局可写的地方;</li>
     *   <li>POSIX 上再收紧到 0600。</li>
     * </ul>
     *
     * <p>也可以用 {@code console.channel.enabled=false} 显式关掉。
     *
     * <p>开不起来时**只警告不中断启动**:一个开发期的便利设施
     * 不该让应用起不来(比如项目目录只读、或者文件系统不支持
     * unix socket)。那时 {@code jaravel migrate} 会回落到它的说明。
     */
    @Bean
    @ConditionalOnMissingBean
    public ChannelHolder jaravelCommandChannel(CommandRegistry registry,
                                               ConfigRepository config) {
        return new ChannelHolder(registry, config);
    }

    /** 通道的生命周期。随上下文关闭而关 —— 否则 socket 文件会留下来 */
    public static class ChannelHolder implements AutoCloseable {

        private final CommandRegistry registry;
        private final ConfigRepository config;
        private ChannelServer server;

        ChannelHolder(CommandRegistry registry, ConfigRepository config) {
            this.registry = registry;
            this.config = config;
        }

        public ChannelServer server() {
            return server;
        }

        @PostConstruct
        void start() {
            if (!config.bool("console.channel.enabled", true)) return;
            if (Application.isProduction()) {
                // 不打日志:生产环境里这是**正常**状态,
                // 一条「通道未启动」的警告只会训练人忽略警告
                return;
            }

            try {
                server = new ChannelServer(registry, Path.of("").toAbsolutePath()).start();
                System.err.printf("[jaravel] 命令通道已开:%s%n"
                        + "          另一个终端里可以直接 jaravel migrate 等命令。%n",
                        server.socketPath());
            } catch (Exception e) {
                System.err.printf("[jaravel] 命令通道没开起来(%s)—— 不影响应用运行,%n"
                        + "          只是 jaravel 的在线命令要回落到说明。%n", e.getMessage());
            }
        }

        @PreDestroy
        @Override
        public void close() {
            if (server != null) server.close();
        }
    }

    /**
     * 调度器的命令通道 —— docs/10 §3.3 的 {@code s.command("cache:clear")}。
     *
     * <p>这个 SPI({@code TaskAction.CommandRunner})早就在 scheduler 里了,
     * 缺的只是一个实现:在控制台装配好之前,{@code s.command(...)} 会报
     * 「需要 CLI,但调度器没有命令通道」。
     *
     * <p>按模块依赖方向,这条线只能由 starter 接:scheduler 不认识 console,
     * console 也不认识 scheduler。
     */
    @Bean
    @ConditionalOnMissingBean
    public io.jaravel.scheduler.TaskAction.CommandRunner jaravelScheduledCommandRunner(
            CommandRegistry registry) {

        return command -> {
            List<String> parts = List.of(command.trim().split("\\s+"));
            int code = Artisan.call(parts.get(0), parts.subList(1, parts.size()));
            // 退出码非 0 要抛 —— 不然失败的计划任务在调度日志里是成功的,
            // 而 onFailure / 心跳都靠这个异常
            if (code != 0) {
                throw new IllegalStateException("计划任务里的命令 \"" + command
                        + "\" 返回退出码 " + code + "\n" + Artisan.output());
            }
        };
    }
}
