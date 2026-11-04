package io.jaravel.console.generators;

import io.jaravel.console.Argument;
import io.jaravel.console.ConsoleException;

/**
 * 「一个名字 → 一个文件」的那一类 {@code make:*}。
 *
 * <p>绝大多数生成命令就是这件事:把类名填进一个 stub,写到约定的包下。
 * 真正有自己逻辑的只有 {@code make:model}(连带生成)、
 * {@code make:migration}(时间戳与表名推断)与 {@code make:controller}
 * (三个变体)—— 它们各自是独立的类。
 *
 * <h2>为什么不做成一张表</h2>
 * <p>命令名来自 {@code @Command} 注解,而注解只能写在类上;
 * 而且像 {@code --event}、{@code --model} 这样的选项各命令不同。
 * 一张数据表会把这些挤成一个带可选字段的记录,读的时候更难看出
 * 「这条命令到底生成什么」。十来个十行的子类反而更直白。
 */
public abstract class SimpleGenerator extends GeneratorCommand {

    @Argument(description = "类名")
    protected String name;

    /** 生成到哪个包。约定来自 docs/16 §5 的应用骨架 */
    protected abstract String targetPackage();

    /**
     * 用哪个 stub。默认由命令名推出来:{@code make:request} → {@code request}。
     *
     * <p>这个默认值让「加一条命令」= 写一个十行的子类 + 一个 stub 文件,
     * 中间没有第三处要记得同步的地方。
     */
    protected String stubName() {
        String command = name();
        int colon = command.indexOf(':');
        if (colon < 0) {
            throw new ConsoleException(command + " 不是 make:xxx 形式,请覆盖 stubName()");
        }
        return command.substring(colon + 1);
    }

    /** 子类往 stub 里补自己的变量({@code --model}、{@code --event} 之类) */
    protected void customise(Stub stub, String className) {
    }

    /** 输出里那个词:「请求类:src/main/java/…」。默认用 stub 名 */
    protected String label() {
        return stubName();
    }

    @Override
    public int handle() {
        String className = classNameOf(name);
        String pkg = targetPackage();

        Stub stub = stub(stubName()).with("pkg", pkg).with("class", className);
        customise(stub, className);

        success(label() + ":" + write(fileFor(pkg, className), stub.render()));
        return 0;
    }
}
