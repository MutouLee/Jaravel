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

    /**
     * 这条命令**不需要应用上下文**吗(docs/13 §1 的离线/在线分流)。
     *
     * <h2>★ 默认是 false(需要容器),而且必须是</h2>
     * <p>标错方向的代价不对称:
     * <ul>
     *   <li>把离线命令标成在线 —— 只是慢,它仍然跑得对;</li>
     *   <li>把需要容器的命令标成离线 —— {@code jaravel-cli} 会在一个
     *       **没有容器**的进程里直接跑它,于是门面全是未装配状态,
     *       报错是「数据库尚未装配」之类,与「这条命令标错了」毫无关系。</li>
     * </ul>
     *
     * <p>所以默认值取那个**错了也不致命**的方向。{@code make:*} 显式声明
     * {@code offline = true} —— 它们只读写文件,这是它们能在 100ms 内
     * 跑完、并且终将跑在 native 二进制里的前提。
     */
    boolean offline() default false;
}
