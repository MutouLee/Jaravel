package io.jaravel.console;

/**
 * {@code jaravel help make:model} —— 一条命令的用法。
 *
 * <p>用法从 {@code @Argument} / {@code @Option} 生成({@link Usage}),
 * 不是手写的 —— 手写的用法与真实参数表之间没有关联,
 * 而一条说错了用法的帮助比没有帮助更费时间。
 */
@Command(name = "help", description = "某条命令的用法", offline = true)
public final class HelpCommand extends ConsoleCommand {

    @Argument(description = "命令名", required = false)
    String command;

    @Override
    public int handle() {
        if (command == null || command.isBlank()) {
            // 不给命令名时等于 list ——「jaravel help」想看的就是「有什么」
            return call("list");
        }

        ConsoleCommand target = commands().create(command);
        if (target == null) {
            // 「没有这条命令」连带「是否想写」的建议,交给注册表统一出 ——
            // 两处各写一遍的话,其中一处迟早不带建议。
            // (走到这里说明命令不存在,run 会在解析参数之前就返回,所以参数给空的)
            return commands().run(command, java.util.List.of(), io());
        }

        newLine();
        line(Usage.of(target));
        return 0;
    }
}
