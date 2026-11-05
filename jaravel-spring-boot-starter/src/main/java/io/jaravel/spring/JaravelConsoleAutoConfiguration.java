package io.jaravel.spring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigRepository;

import io.jaravel.console.Artisan;
import io.jaravel.console.BuiltInCommands;
import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.channel.ChannelServer;
import io.jaravel.console.generators.GeneratorCommand;
import io.jaravel.database.Connection;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Seeder;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobInvoker;
import io.jaravel.queue.JobSerializer;
import io.jaravel.queue.QueueManager;
import io.jaravel.routing.RouteCollection;
import io.jaravel.scheduler.Scheduler;
import io.jaravel.spring.commands.AboutCommand;
import io.jaravel.spring.commands.DbSeedCommand;
import io.jaravel.spring.commands.DbTableCommand;
import io.jaravel.spring.commands.DbShowCommand;
import io.jaravel.spring.commands.ConfigShowCommand;
import io.jaravel.spring.commands.DoctorCheck;
import io.jaravel.spring.commands.DoctorCommand;
import io.jaravel.spring.commands.FrameworkChecks;
import io.jaravel.spring.commands.MigrateCommand;
import io.jaravel.spring.commands.MigrateFreshCommand;
import io.jaravel.spring.commands.MigrateRollbackCommand;
import io.jaravel.spring.commands.MigrateStatusCommand;
import io.jaravel.spring.commands.QueueFailedCommand;
import io.jaravel.spring.commands.QueueFlushCommand;
import io.jaravel.spring.commands.QueueRetryCommand;
import io.jaravel.spring.commands.QueueWorkCommand;
import io.jaravel.spring.commands.ScheduleClearMutexesCommand;
import io.jaravel.spring.commands.ScheduleListCommand;
import io.jaravel.spring.commands.ScheduleRunCommand;
import io.jaravel.spring.commands.ScheduleTestCommand;
import io.jaravel.spring.commands.TinkerCommand;
import io.jaravel.spring.commands.RouteListCommand;

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
    /**
     * 框架自带的离线命令 + list / help。
     *
     * <p>表在 {@link BuiltInCommands} —— 与 {@code jaravel-cli} 共用一份。
     * 此前两边各写了一遍,而且已经漂了:CLI 侧有 list / help,
     * 这边一条都没有,于是 {@code jaravel list} 看不到任何在线命令。
     */
    static final List<Class<? extends ConsoleCommand>> BUILT_IN =
            java.util.stream.Stream.concat(
                            BuiltInCommands.DISCOVERY.stream(),
                            BuiltInCommands.GENERATORS.stream())
                    .toList();

    @Bean
    @ConditionalOnMissingBean
    public CommandRegistry jaravelCommandRegistry(
            org.springframework.beans.factory.BeanFactory beans,
            ObjectProvider<ConsoleCommand> applicationCommands) {

        var registry = new CommandRegistry();

        // 框架自带的 make:*。无参构造,直接注册
        BUILT_IN.forEach(registry::register);

        registerRuntimeCommands(registry, beans);

        // 应用自己的命令 —— 每次跑都是一个**新实例**,见 freshInstance
        applicationCommands.stream().forEach(command -> {
            Class<?> type = command.getClass();
            @SuppressWarnings("unchecked")
            var commandType = (Class<? extends ConsoleCommand>) type;
            registry.register(commandType, () -> freshInstance(beans, commandType));
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
     * 在线命令(docs/13 §2.3 / §2.5)。
     *
     * <h2>★ 依赖全部**惰性**取</h2>
     * <p>每条命令拿到的是 {@code Supplier},而不是 {@code Connection} /
     * {@code RouteCollection} 本身。两个理由:
     *
     * <ol>
     *   <li><b>避免启动期的循环</b>:注册表在很早就建好(门面要它),
     *       而 {@code RouteCollection} 要等全部 {@code RouteDefinition}
     *       定义完。在这里直接注入会把注册表拖到路由之后,
     *       而那条链上还有控制器 bean。</li>
     *   <li><b>缺了也不致命</b>:一个纯队列应用可能没有路由定义。
     *       惰性取的话,{@code route:list} 在它被调用时才报
     *       「没有路由」—— 而不是让整个控制台装配失败。</li>
     * </ol>
     *
     * <p>命令类住在 {@code io.jaravel.spring.commands} 而不是 jaravel-console,
     * 理由写在 {@code RuntimeCommand} 的类注释上(发现机制是 Spring 的扫描器,
     * 而 console 要保持羽量级给 CLI 用)。
     */
    private void registerRuntimeCommands(CommandRegistry registry,
                                         org.springframework.beans.factory.BeanFactory beans) {

        Supplier<Connection> connection = () -> beans.getBean(Connection.class);
        Supplier<RouteCollection> routes = () -> beans.getBean(RouteCollection.class);
        Supplier<List<Migration>> migrations =
                () -> beans.getBean(MigrationRunner.class).discover();
        Supplier<List<Seeder>> seeders =
                () -> beans.getBean(MigrationRunner.class).discoverSeeders();

        // 两个参数的 register:名字从**类的注解**读,实例留到真要跑的时候才建。
        // 只给工厂的那个重载会立刻建一个实例探名字 —— 那会在注册时就解析
        // 全部依赖,于是一个没有数据库的应用在注册 migrate 时就炸了
        registry.register(MigrateCommand.class,
                        () -> new MigrateCommand(connection, migrations))
                .register(MigrateStatusCommand.class,
                        () -> new MigrateStatusCommand(connection, migrations))
                .register(MigrateRollbackCommand.class,
                        () -> new MigrateRollbackCommand(connection, migrations))
                .register(MigrateFreshCommand.class,
                        () -> new MigrateFreshCommand(connection, migrations))
                .register(DbSeedCommand.class, () -> new DbSeedCommand(seeders))
                .register(RouteListCommand.class, () -> new RouteListCommand(routes))
                .register(AboutCommand.class,
                        () -> new AboutCommand(beans.getBean(ConfigRepository.class), routes))
                .register(ConfigShowCommand.class,
                        () -> new ConfigShowCommand(beans.getBean(ConfigRepository.class)));

        // db:show / db:table 要的是 DataSource,而不是 Connection ——
        // 它们读 JDBC 的元数据(DatabaseMetaData),那样就只有一条代码路径,
        // 而不是「SQLite 查 sqlite_master、MySQL 用 SHOW TABLES」三份方言
        // (其中两份在这台机器上永远不会被执行,于是会悄悄腐坏)。
        // Connection 那个契约故意只有 SQL,不暴露底层连接
        Supplier<javax.sql.DataSource> dataSource = () -> beans.getBean(javax.sql.DataSource.class);
        registry.register(DbShowCommand.class,
                        () -> new DbShowCommand(dataSource.get(), connection.get(),
                                beans.getBean(ConfigRepository.class)))
                .register(DbTableCommand.class,
                        () -> new DbTableCommand(dataSource.get(), connection.get()));

        // 队列与调度:能力早就在模块里了,这里只是接上 CLI 入口。
        // 同样惰性 —— 一个没有队列驱动的应用不该在注册时就炸
        Supplier<QueueManager> queues = () -> beans.getBean(QueueManager.class);
        Supplier<FailedJobStore> failedJobs = () -> beans.getBean(FailedJobStore.class);
        Supplier<JobInvoker> invoker = () -> beans.getBean(JobInvoker.class);
        Supplier<JobSerializer> serializer = () -> beans.getBean(JobSerializer.class);
        Supplier<Scheduler> scheduler = () -> beans.getBean(Scheduler.class);

        registry.register(QueueWorkCommand.class,
                        () -> new QueueWorkCommand(queues, failedJobs, invoker))
                .register(QueueFailedCommand.class, () -> new QueueFailedCommand(failedJobs))
                .register(QueueRetryCommand.class,
                        () -> new QueueRetryCommand(queues, failedJobs, serializer))
                .register(QueueFlushCommand.class, () -> new QueueFlushCommand(failedJobs))
                .register(ScheduleListCommand.class, () -> new ScheduleListCommand(scheduler))
                .register(ScheduleRunCommand.class, () -> new ScheduleRunCommand(scheduler))
                .register(ScheduleTestCommand.class, () -> new ScheduleTestCommand(scheduler))
                .register(ScheduleClearMutexesCommand.class,
                        () -> new ScheduleClearMutexesCommand(scheduler));

        registry.register(TinkerCommand.class, TinkerCommand::new);

        // doctor:框架自带的检查项 + 应用自己加的(DoctorCheck bean)
        registry.register(DoctorCommand.class, () -> new DoctorCommand(
                doctorChecks(beans.getBean(ConfigRepository.class),
                        connection, migrations, scheduler,
                        beans.getBeanProvider(DoctorCheck.class))));
    }

    /**
     * 框架的检查项 + 应用自己的。
     *
     * <p>应用的排在后面:框架的那些是「这个应用能不能正常工作」,
     * 而应用的是「我们这套部署的额外前提」。先看前者 ——
     * 一个连不上数据库的应用,它的 S3 桶配得怎么样不重要。
     */
    private List<DoctorCheck> doctorChecks(
            ConfigRepository config,
            Supplier<Connection> connection,
            Supplier<List<Migration>> migrations,
            Supplier<Scheduler> scheduler,
            ObjectProvider<DoctorCheck> applicationChecks) {

        var checks = new java.util.ArrayList<DoctorCheck>(
                FrameworkChecks.all(config, connection, migrations, scheduler));
        applicationChecks.orderedStream().forEach(checks::add);
        return List.copyOf(checks);
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

    /**
     * 每跑一次,新造一个命令实例。
     *
     * <h2>★ 为什么不是 {@code beans.getBean(type)}</h2>
     * <p>命令对象**带状态**:解析出来的参数就写在它的字段上。
     * 单例 bean 上连着跑两条命令时,第二条会看到第一条留下的选项 ——
     * 而那个症状(「我没加 --force 但它覆盖了」)与原因离得太远。
     *
     * <p>此前的做法是:照样 {@code getBean},但在装配时**警告**
     * 「这条命令是单例,请加 @Scope("prototype")」。那不够好 ——
     * 一条警告要求每个用户在每条命令上重复一个仪式,而忘了的代价是
     * 一个极难查的 bug。{@code createBean} 让容器按类型造一个新实例
     * (依赖照样注入、BeanPostProcessor 照样跑),于是**正确成了默认**,
     * {@code @Scope("prototype")} 不再是必须的。
     *
     * <p>这件事是写 parity 切片 05 时发现的:那个仪式在每条命令上
     * 要花两行(注解 + import),而它本可以是零行。
     */
    private ConsoleCommand freshInstance(
            org.springframework.beans.factory.BeanFactory beans,
            Class<? extends ConsoleCommand> type) {
        if (beans instanceof org.springframework.beans.factory.config.AutowireCapableBeanFactory factory) {
            return factory.createBean(type);
        }
        // 兜底:拿不到 AutowireCapableBeanFactory 时回到 getBean ——
        // 那时 @Scope("prototype") 又变成必须的了,所以说一句
        System.err.printf("""
                [jaravel] 这个容器不支持新造实例,命令 %s 会复用同一个对象。
                          请给它加 @Scope("prototype") —— 否则连着跑两条命令时,
                          第二条会看到第一条留下的选项。%n""", type.getName());
        return (ConsoleCommand) beans.getBean(type);
    }

    /**
     * {@code -Djaravel.tinker=true} —— 应用启动完就进 REPL。
     *
     * <h2>★ 这是今天进 tinker 的唯一入口</h2>
     * <p>求值要在应用进程里跑,而 REPL 要读键盘 —— 两件事必须在同一个
     * 进程、同一个终端上。本地命令通道是单向的(读不到客户端的键盘),
     * 而 CLI 冷启动应用 JVM 的那条路还没实现。
     * 所以:{@code mvn spring-boot:run -Djaravel.tinker=true}。
     *
     * <p>⚠ 它**占着启动线程**不返回,直到用户 {@code :exit} ——
     * 那正是想要的(这个进程此刻就是一个 REPL),但也意味着
     * 这个开关绝不该出现在生产配置里。所以只读系统属性、不读配置文件:
     * 一个写在 application.yml 里的开关会被打包带走,
     * 而一个 {@code -D} 参数不会。
     */
    @Bean
    @Conditional(OnSystemProperty.Tinker.class)
    public TinkerStarter jaravelTinkerStarter(CommandRegistry registry) {
        return new TinkerStarter(registry);
    }

    /**
     * 「只读系统属性」得自己写 —— {@code @ConditionalOnProperty} 做不到。
     *
     * <h2>★ 这是对账查出来的一处脱节</h2>
     * <p>上面那段注释一直写着「只读系统属性、不读配置文件」,理由也是对的
     * (一个写进 {@code application.yml} 的开关会被打包带走)。但
     * {@code @ConditionalOnProperty} 读的是整个 {@code Environment},
     * 里面**包含** application.yml —— 也就是说
     * {@code jaravel.tinker: true} 写在 yml 里是生效的,
     * 而那正是注释声称防住了的那件事。
     *
     * <p>后果不是理论上的:那个开关会**占着启动线程**不返回。
     * 一个带着这行 yml 打包上线的应用,表现是「启动卡住、端口不开」,
     * 而日志里只有一行 banner —— 从那里查到「有人在 yml 里开了 REPL」
     * 要很久。所以条件必须真的只看 {@code -D}。
     */
    public abstract static class OnSystemProperty
            implements org.springframework.context.annotation.Condition {

        private final String name;

        protected OnSystemProperty(String name) {
            this.name = name;
        }

        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                               org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String value = java.lang.System.getProperty(name);
            return value != null && !value.isBlank() && accepts(value);
        }

        /** 默认只要「有值」;tinker 要求值是 true */
        protected boolean accepts(String value) {
            return true;
        }

        /** {@code -Djaravel.tinker=true} */
        public static final class Tinker extends OnSystemProperty {
            public Tinker() {
                super("jaravel.tinker");
            }

            @Override
            protected boolean accepts(String value) {
                return value.equals("true");
            }
        }

        /** {@code -Djaravel.command="migrate --force"} */
        public static final class CommandMode extends OnSystemProperty {
            public CommandMode() {
                super(COMMAND_PROPERTY);
            }
        }
    }

    /**
     * {@code -Djaravel.command="migrate --force"} —— 启动完跑这一条命令然后退出。
     *
     * <h2>★ docs/13 §1 的第二条路径:冷启动一个应用 JVM</h2>
     * <p>在线命令({@code migrate} / {@code db:seed} / {@code queue:work})要容器。
     * 应用跑着时它们走命令通道(0 开销);应用**没跑**时 —— CI 里、
     * 部署脚本里、刚 clone 下来的机器上 —— 就得真的起一个 JVM。
     * 这个开关就是那个 JVM 的入口,{@code jaravel-cli} 的启动器拉它。
     *
     * <p>手敲也完全可用,而且这是今天唯一不需要启动器的那条路:
     * <pre>
     * mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Djaravel.command=migrate"
     * </pre>
     *
     * <h2>为什么没有生产环境护栏</h2>
     * <p>通道那边有(它能执行任意命令,是个后门),这边**不该有**:
     * 「在生产机器上 migrate」正是这条路径存在的主要理由之一。
     * 危险的是具体命令,所以护栏在命令里({@code confirmInProduction} ——
     * 没有 TTY 时它直接拒绝,除非 {@code --force})。
     *
     * <p>同理这里也不检查 {@code app.env}:一个跑在生产环境、
     * 只读的检视命令({@code about} / {@code migrate:status})
     * 没有任何理由被挡。
     */
    @Bean
    @Conditional(OnSystemProperty.CommandMode.class)
    public OneShotCommand jaravelOneShotCommand(
            CommandRegistry registry,
            org.springframework.context.ConfigurableApplicationContext context) {
        return new OneShotCommand(registry, context);
    }

    /**
     * 这个系统属性也是「别开通道」的信号 —— 见 {@link ChannelHolder#start}。
     *
     * <p>名字定义在 {@code jaravel-console} 里:CLI 那边拼这个属性、
     * 这边拆它,所以约定只能有一份(理由写在那个常量上)。
     */
    public static final String COMMAND_PROPERTY =
            io.jaravel.console.CommandLine.COMMAND_PROPERTY;

    /**
     * 跑一条命令,然后把退出码交给 shell。
     *
     * <p>{@code ApplicationRunner} 而不是 {@code @PostConstruct}:
     * 后者在**自己这个 bean** 造好时就跑,那时别的 bean 可能还没好。
     * 一条 {@code migrate} 要的是「容器完全就绪」。
     *
     * <p>{@code LOWEST_PRECEDENCE}:应用自己的 runner 先跑完 ——
     * 它们是应用启动的一部分,而我们是**启动之后**那件事。
     */
    @org.springframework.core.annotation.Order(
            org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    public static class OneShotCommand
            implements org.springframework.boot.ApplicationRunner {

        private final CommandRegistry registry;
        private final org.springframework.context.ConfigurableApplicationContext context;

        OneShotCommand(CommandRegistry registry,
                       org.springframework.context.ConfigurableApplicationContext context) {
            this.registry = registry;
            this.context = context;
        }

        @Override
        public void run(org.springframework.boot.ApplicationArguments args) {
            List<String> argv = io.jaravel.console.CommandLine.split(
                    java.lang.System.getProperty(COMMAND_PROPERTY));
            if (argv.isEmpty()) {
                java.lang.System.err.println(
                        "[jaravel] -D" + COMMAND_PROPERTY + " 是空的 —— 没有命令可跑。");
                exit(1);
                return;
            }

            int code = registry.run(argv.get(0), argv.subList(1, argv.size()),
                    new io.jaravel.console.ConsoleIo.System());
            exit(code);
        }

        /**
         * ★ 为什么是 {@code System.exit} 而不是让启动流程自然走完。
         *
         * <p>两个理由:
         * <ul>
         *   <li><b>退出码是 CLI 与 shell 唯一的契约</b>。让 {@code run()}
         *       正常返回的话,{@code main} 结束、JVM 退出码是 0 ——
         *       一条失败的 {@code migrate} 在 CI 里会被当成成功,
         *       而那是这整条路径最不能出的错。</li>
         *   <li><b>这个进程的任务已经完成了</b>。它是为一条命令起的,
         *       不该接着去开端口、起定时任务、连队列。
         *       (启动器还会传 {@code web-application-type=none},
         *       但那只管 web 那一半。)</li>
         * </ul>
         *
         * <p>容器照样关得干净:Spring Boot 注册了关闭钩子,
         * 所以 {@code @PreDestroy} 都会跑到 —— 连接池、通道、
         * 队列的 worker 都会收到关闭信号。
         */
        private void exit(int code) {
            java.lang.System.out.flush();
            java.lang.System.exit(
                    org.springframework.boot.SpringApplication.exit(context, () -> code));
        }
    }

    /** 启动后把终端交给 tinker */
    public static class TinkerStarter {

        private final CommandRegistry registry;

        TinkerStarter(CommandRegistry registry) {
            this.registry = registry;
        }

        @PostConstruct
        void start() {
            if (Application.isProduction()) {
                System.err.println("[jaravel] 生产环境下不开 tinker。");
                return;
            }
            registry.run("tinker", List.of(), new io.jaravel.console.ConsoleIo.System());
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
            // ★ 命令模式(-Djaravel.command=…)下**绝不**开通道。
            // 两个进程的 socket 路径是同一个,所以这个短命的 JVM 会先抢占
            // 那个文件、再在退出时把它删掉 —— 结果是「jaravel migrate 跑完之后,
            // 旁边那个 dev server 的通道莫名其妙没了」。
            // 症状与原因离得极远,而这个进程本来也不需要通道:
            // 它自己就是来跑一条命令的。
            if (java.lang.System.getProperty(COMMAND_PROPERTY) != null) return;
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
