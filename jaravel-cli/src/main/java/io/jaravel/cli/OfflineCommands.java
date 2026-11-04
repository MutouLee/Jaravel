package io.jaravel.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.HelpCommand;
import io.jaravel.console.ListCommands;
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
 * CLI 能**不启动容器**就跑完的那些命令(docs/13 §1)。
 *
 * <p>与 starter 那边的 {@code BUILT_IN} 是两张表,而且应该是两张:
 * 应用进程里**所有**命令都能跑(它有容器),CLI 这边只有离线的那些。
 * 两张表共享的是命令类本身。
 *
 * <p>⚠ 这里列的每一条都必须带 {@code @Command(offline = true)} ——
 * 有一条测试会核对这件事。漏标的后果是 {@code jaravel-cli} 在一个
 * 没有容器的进程里跑一条需要容器的命令。
 */
final class OfflineCommands {

    private OfflineCommands() {
    }

    static final List<Class<? extends ConsoleCommand>> ALL = List.of(
            ListCommands.class, HelpCommand.class,
            MakeModel.class, MakeController.class, MakeMigration.class,
            MakeRequest.class, MakeJob.class, MakeMiddleware.class,
            MakeEvent.class, MakeListener.class, MakePolicy.class,
            MakeSeeder.class, MakeMail.class, MakeNotification.class,
            MakeConsoleCommand.class, MakeTest.class);

    static CommandRegistry registry() {
        return registry(Path.of("").toAbsolutePath());
    }

    static CommandRegistry registry(Path projectRoot) {
        var registry = new CommandRegistry();
        ALL.forEach(registry::register);
        registry.configure(command -> {
            if (command instanceof GeneratorCommand generator) generator.inProject(projectRoot);
        });
        return registry;
    }

    /**
     * 当前目录像不像一个 Jaravel 项目。
     *
     * <p>CLI 用它给一句提示而**不是拒绝执行**:在一个空目录里
     * {@code jaravel make:model Post} 的意图很可能是「我忘了 cd」,
     * 但也可能是故意的(在一个还没有 pom 的新目录里起骨架)。
     * 拒绝执行会挡住后者,而一句提示对前者就够了。
     */
    static boolean looksLikeProject(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml"))
            || Files.isDirectory(directory.resolve(GeneratorCommand.SOURCE_ROOT));
    }
}
