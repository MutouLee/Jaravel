package io.jaravel.console.generators;

import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.support.Str;

/**
 * {@code jaravel make:command SyncInventory} —— 自定义命令(docs/13 §3)。
 *
 * <p>命令名默认由类名推出来并带 {@code app:} 前缀:
 * {@code SyncInventory} → {@code app:sync-inventory}(与 Laravel 一致)。
 * 前缀的意义是**不与框架的命令撞名** —— 应用里一条叫 {@code migrate}
 * 的命令会悄悄遮住框架的那条,而那种冲突在运行时表现为「migrate 的行为变了」。
 * 要别的名字用 {@code --command=inventory:sync}。
 *
 * <p>stub 里带 {@code @Component} 与 {@code @Scope("prototype")}:
 * 命令对象带状态(解析出来的参数就写在字段上),单例会让连着跑的
 * 第二条命令看到第一条留下的选项。装配时会警告,但生成时就写对更好。
 */
@Command(name = "make:command", description = "新建一个 Artisan 命令", offline = true)
public final class MakeConsoleCommand extends SimpleGenerator {

    @Option(names = "--command", description = "命令名,默认 app:kebab-类名")
    String command;

    @Override protected String targetPackage() { return "app.console"; }

    @Override protected String stubName() { return "command"; }

    @Override protected String label() { return "命令"; }

    @Override
    protected void customise(Stub stub, String className) {
        stub.with("commandName", command != null && !command.isBlank()
                ? command.trim()
                : "app:" + Str.kebab(className));
    }
}
