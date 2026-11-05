package io.jaravel.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.jaravel.console.BuiltInCommands;
import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.generators.GeneratorCommand;

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

    /**
     * 这个进程里能跑的全部命令。
     *
     * <p>表本身在 {@link BuiltInCommands} —— 此前 CLI 与 starter 各写了
     * 一遍,而漏掉一处的症状是「这条命令在应用里有、在 CLI 上没有」。
     *
     * <p>⚠ 这里列的每一条都必须带 {@code @Command(offline = true)} ——
     * 有一条测试核对这件事。漏标的后果是 {@code jaravel-cli} 在一个
     * 没有容器的进程里跑一条需要容器的命令。
     */
    static final List<Class<? extends ConsoleCommand>> ALL =
            java.util.stream.Stream.concat(
                            BuiltInCommands.DISCOVERY.stream(),
                            BuiltInCommands.GENERATORS.stream())
                    .toList();

    static CommandRegistry registry() {
        return registry(Path.of("").toAbsolutePath());
    }

    static CommandRegistry registry(Path projectRoot) {
        var registry = new CommandRegistry();
        ALL.forEach(registry::register);
        registry.configure(command -> {
            // list 要说清「这张清单不全」—— 这个进程里没有应用,
            // 所以在线命令与应用自己的命令都不在表里
            if (command instanceof io.jaravel.console.ListCommands list) {
                list.withoutApplication();
            }
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
