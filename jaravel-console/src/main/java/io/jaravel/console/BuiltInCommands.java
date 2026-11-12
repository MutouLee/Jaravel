package io.jaravel.console;

import java.util.List;

import io.jaravel.console.generators.KeyGenerateCommand;
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
import io.jaravel.console.generators.WrapperInstallCommand;
import io.jaravel.console.openapi.OpenApiDiffCommand;

/**
 * 框架自带的离线命令 —— **这张表只有一份**。
 *
 * <h2>★ 为什么把它提出来</h2>
 * <p>此前它在两个地方各写了一遍:{@code jaravel-cli} 的
 * {@code OfflineCommands.ALL} 和 starter 的 {@code BUILT_IN}。
 * 加一条命令要改两处 —— 而**漏掉一处的症状是「这条命令在应用里有、
 * 在 CLI 上没有」(或者反过来)**,而那看起来像装配出了问题,
 * 不像少写了一行。
 *
 * <p>这不是假想:做 {@code wrapper:install} 和那三条检视命令时,
 * 每次都要记着改两处。两张表当时已经不一致了 ——
 * CLI 侧多了 {@code list} / {@code help},而 starter 侧**一条都没有**
 * (那条差异自己也是个 bug,见下)。
 *
 * <h2>docs/13 §3.2 说的 APT 索引呢</h2>
 * <p>文档的方案是用注解处理器生成一份索引,自动注册。对**框架自带的**
 * 命令来说那是在解决一个不存在的问题:它们本来就全在一个模块里,
 * 一个 {@code List.of(...)} 常量就够,而 APT 要一个新的 Maven 模块、
 * 一个处理器、一份索引解析器。
 *
 * <p>APT 真正不可替代的地方是**应用自己的**命令和 **native 镜像**:
 * 后者需要一份穷尽的类名清单来生成 reflect-config
 * ({@code ArgumentBinder} 靠反射读字段)。那件事排在 v0.9,
 * 到时候这张表正好是它的一半输入。
 */
public final class BuiltInCommands {

    private BuiltInCommands() {
    }

    /**
     * 离线命令:生成类 + {@code key:generate} + {@code wrapper:install}
     * + {@code openapi:diff}。
     *
     * <p>全部是 {@code offline = true}:它们只读写文件,不需要容器
     * (有一条测试核对这件事 —— 标错方向的代价不对称,
     * 理由写在 {@code @Command} 的 {@code offline} 上)。
     *
     * <p>⚠ 不含 {@code list} / {@code help}:它们要列出**所在注册表**里的
     * 命令,所以两侧各自注册 —— 而两侧的注册表内容本来就不同
     * (CLI 只有离线命令,应用里还有在线命令和应用自己的命令)。
     * 见 {@link #DISCOVERY}。
     */
    public static final List<Class<? extends ConsoleCommand>> GENERATORS = List.of(
            MakeModel.class, MakeController.class, MakeMigration.class,
            MakeRequest.class, MakeJob.class, MakeMiddleware.class,
            MakeEvent.class, MakeListener.class, MakePolicy.class,
            MakeSeeder.class, MakeMail.class, MakeNotification.class,
            MakeConsoleCommand.class, MakeTest.class,
            KeyGenerateCommand.class, WrapperInstallCommand.class,
            OpenApiDiffCommand.class);

    /**
     * {@code list} 与 {@code help} —— **两侧都要注册**。
     *
     * <h2>★ starter 此前一条都没注册,而那是个 bug</h2>
     * <p>后果:{@code jaravel list} 跑在 CLI 自己的注册表上,
     * 那里只有离线命令 —— 于是 {@code migrate}、{@code queue:work}、
     * 以及**应用自己定义的所有命令**在命令清单里一条都看不见。
     *
     * <p>而 {@code list} 正是「有哪些命令」的那个入口。Laravel 的
     * {@code artisan list} 列的是全部。一个列不全的清单比没有清单更糟:
     * 用户会得出「这个框架没有 migrate」的结论。
     *
     * <p>应用这边注册上之后,{@code jaravel list} 走通道时拿到的是
     * **应用那张完整的表**(框架在线命令 + 应用自己的命令)。
     */
    public static final List<Class<? extends ConsoleCommand>> DISCOVERY =
            List.of(ListCommands.class, HelpCommand.class);
}
