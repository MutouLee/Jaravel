package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;

import io.jaravel.console.Artisan;
import io.jaravel.console.Command;
import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.Option;
import io.jaravel.console.generators.GeneratorCommand;

/**
 * 控制台的装配(docs/13)。
 *
 * <p>不起 Spring 上下文 —— 用一个真的 {@code DefaultListableBeanFactory}
 * 就够:这里要验的是「命令怎么从容器里拿」,而那一段与上下文的生命周期无关。
 * (仓库里 starter 的测试都是这个路子。)
 */
class ConsoleAutoConfigurationTest {

    @AfterEach
    void clearStaticState() {
        Artisan.reset();
    }

    /** 应用自己的命令:带一个容器注入的依赖 */
    @Command(name = "inventory:sync", description = "同步库存")
    static class SyncInventory extends ConsoleCommand {

        private final String supplier;

        @Option(names = {"-f", "--force"})
        boolean force;

        SyncInventory(String supplier) {
            this.supplier = supplier;
        }

        @Override public int handle() {
            line("sync " + supplier + " force=" + force);
            return 0;
        }
    }

    @Command(name = "always:fails")
    static class AlwaysFails extends ConsoleCommand {
        @Override public int handle() {
            error("没成");
            return 3;
        }
    }

    private DefaultListableBeanFactory beansWith(Class<?> commandType, String scope) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("supplierName", "acme");

        var definition = new GenericBeanDefinition();
        definition.setBeanClass(commandType);
        definition.setScope(scope);
        definition.setAutowireMode(GenericBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        beans.registerBeanDefinition("syncInventory", definition);
        return beans;
    }

    private CommandRegistry registryFrom(DefaultListableBeanFactory beans, Path projectRoot) {
        var registry = new JaravelConsoleAutoConfiguration().jaravelCommandRegistry(
                beans, beans.getBeanProvider(ConsoleCommand.class));
        // 测试不能让 make:* 往真实仓库里写文件 —— 后注册的钩子覆盖前面的
        registry.configure(command -> {
            if (command instanceof GeneratorCommand generator) generator.inProject(projectRoot);
        });
        return registry;
    }

    @Test
    @DisplayName("★ 框架自带的 make:* 与应用自己的命令都登记上了,Artisan 门面可用")
    void registersBuiltInAndApplicationCommands(@TempDir Path tmp) {
        var registry = registryFrom(beansWith(SyncInventory.class, BeanDefinition.SCOPE_PROTOTYPE), tmp);

        assertThat(registry.names())
                .contains("make:model", "make:migration", "make:controller", "inventory:sync");
        assertThat(registry.descriptionOf("inventory:sync")).isEqualTo("同步库存");

        assertThat(Artisan.call("make:model", "Post")).isZero();
        assertThat(tmp.resolve("src/main/java/app/models/Post.java")).exists();
    }

    @Test
    @DisplayName("★ 应用命令的依赖由容器注入 —— 注册表存工厂,正好填进这个接缝")
    void applicationCommandGetsItsDependencies(@TempDir Path tmp) {
        var registry = registryFrom(beansWith(SyncInventory.class, BeanDefinition.SCOPE_PROTOTYPE), tmp);
        Artisan.use(registry);

        assertThat(Artisan.call("inventory:sync", "-f")).isZero();
        assertThat(Artisan.output()).contains("sync acme force=true");
    }

    @Test
    @DisplayName("★ prototype 作用域下,每次运行都是干净的实例")
    void prototypeCommandsDoNotLeakState(@TempDir Path tmp) {
        var registry = registryFrom(beansWith(SyncInventory.class, BeanDefinition.SCOPE_PROTOTYPE), tmp);
        Artisan.use(registry);

        Artisan.call("inventory:sync", "-f");
        assertThat(Artisan.output()).contains("force=true");

        Artisan.call("inventory:sync");
        assertThat(Artisan.output())
                .as("命令对象带状态:解析出来的参数就写在它的字段上")
                .contains("force=false");
    }

    @Test
    @DisplayName("★ 单例命令要警告 —— 症状(第二条命令带上了第一条的选项)离原因太远")
    void warnsAboutSingletonCommands(@TempDir Path tmp) {
        var err = new java.io.ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            registryFrom(beansWith(SyncInventory.class, BeanDefinition.SCOPE_SINGLETON), tmp);
        } finally {
            System.setErr(original);
        }

        assertThat(err.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("是单例 bean")
                .as("不去改用户 bean 的作用域(那会很意外),而是把话说出来")
                .contains("prototype");
    }

    @Test
    @DisplayName("★ 容器里没有数据库/路由时,装配照样成功 —— 依赖在真要跑的时候才取")
    void registrationDoesNotResolveDependencies(@TempDir Path tmp) {
        // 这个 BeanFactory 里只有一个 String 和那个命令 bean:
        // 没有 Connection、没有 RouteCollection、没有 ConfigRepository
        var registry = registryFrom(beansWith(SyncInventory.class,
                BeanDefinition.SCOPE_PROTOTYPE), tmp);

        assertThat(registry.names())
                .as("""
                        名字从 @Command 注解读,不建实例 ——
                        「注册时就建实例」会让一个没有数据库的应用
                        在注册 migrate 时就炸,而那条命令它根本不会用。""")
                .contains("migrate", "route:list", "about");

        // 真去跑才会要那些 bean,那时报的是「没有这个 bean」,
        // 而不是在启动期把整个控制台装配带下去。
        //
        // 用 route:list 而不是 migrate:后者第一步是生产护栏
        // (这个测试里 Application.environment() 是默认的 production),
        // 会在碰数据库之前就返回 —— 那证明不了惰性
        Artisan.use(registry);
        assertThatThrownBy(() -> Artisan.call("route:list"))
                .hasMessageContaining("RouteCollection");
    }

    // ================= 调度器的命令通道 =================

    @Test
    @DisplayName("★ s.command(\"…\") 终于有通道了(docs/10 §3.3 挂了很久的那条)")
    void schedulerCanRunCommands(@TempDir Path tmp) {
        var beans = beansWith(SyncInventory.class, BeanDefinition.SCOPE_PROTOTYPE);
        var registry = registryFrom(beans, tmp);
        Artisan.use(registry);

        var runner = new JaravelConsoleAutoConfiguration()
                .jaravelScheduledCommandRunner(registry);

        var schedule = new io.jaravel.scheduler.Schedule(null, runner);
        schedule.command("inventory:sync --force").everyMinute();

        assertThat(schedule.tasks()).hasSize(1);
        assertThatCode(() -> schedule.tasks().get(0).action().run())
                .as("这条命令本身是成功的,不该抛")
                .doesNotThrowAnyException();
        assertThat(Artisan.output()).contains("force=true");
    }

    @Test
    @DisplayName("★ 命令返回非 0 时抛异常 —— 不然失败的计划任务在日志里是成功的")
    void nonZeroExitCodeFails(@TempDir Path tmp) {
        var registry = registryFrom(beansWith(SyncInventory.class, BeanDefinition.SCOPE_PROTOTYPE), tmp);
        registry.register(AlwaysFails.class);
        Artisan.use(registry);

        var runner = new JaravelConsoleAutoConfiguration()
                .jaravelScheduledCommandRunner(registry);

        assertThatThrownBy(() -> runner.run("always:fails"))
                .as("onFailure 与心跳都靠这个异常")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("退出码 3")
                .as("把命令自己的输出带上 —— 否则日志里只有一个数字")
                .hasMessageContaining("没成");
    }

    @Test
    @DisplayName("没装控制台时 s.command 仍然报那条「没有命令通道」的错,而不是静默")
    void withoutRunnerCommandStillFailsLoudly() {
        var schedule = new io.jaravel.scheduler.Schedule(null, null);

        assertThatThrownBy(() -> schedule.command("cache:clear"))
                .hasMessageContaining("命令通道");
    }

    @Test
    @DisplayName("命令行字符串按空白切分成参数")
    void commandStringIsSplitIntoArguments(@TempDir Path tmp) {
        var registry = registryFrom(beansWith(SyncInventory.class, BeanDefinition.SCOPE_PROTOTYPE), tmp);
        Artisan.use(registry);

        var runner = new JaravelConsoleAutoConfiguration()
                .jaravelScheduledCommandRunner(registry);

        assertThat(List.of("make:model Post --force".split("\\s+")))
                .containsExactly("make:model", "Post", "--force");

        assertThatCode(() -> runner.run("make:model Post --force"))
                .doesNotThrowAnyException();
        assertThat(tmp.resolve("src/main/java/app/models/Post.java")).exists();
    }
}
