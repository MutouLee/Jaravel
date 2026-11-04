package io.jaravel.console;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一条命令的名字与说明(docs/13 §3)。
 *
 * <pre>{@code
 * @Command(name = "inventory:sync", description = "从供应商 API 同步库存")
 * public class SyncInventory extends ConsoleCommand { … }
 * }</pre>
 *
 * <p>名字里的冒号是**分组**:{@code make:model} 与 {@code make:controller}
 * 在 {@code jaravel list} 里会排在一起。这不是装饰 ——
 * artisan 的命令有上百条,没有分组就没法找。
 *
 * <p>docs/13 §3.2 说注册由 APT 生成索引。那一步还没做:
 * 现在要显式 {@link CommandRegistry#register},而 Kernel 持有那张表。
 * 先有运行时注册表、再有编译期索引,顺序上后者只是省掉手写注册的一行。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Command {

    String name();

    String description() default "";
}
