package io.jaravel.cli;

import java.util.List;

import io.jaravel.console.Artisan;
import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleIo;

/**
 * {@code jaravel} 的入口(docs/13 §1)。
 *
 * <pre>
 * jaravel make:model Post -m      离线命令:就在这个进程里跑完
 * jaravel migrate                 在线命令:需要应用上下文
 * jaravel list · help · --version
 * </pre>
 *
 * <h2>★ 分流:离线命令**不启动任何容器**</h2>
 * <p>这是整个模块存在的理由。{@code make:model} 只读写文件,
 * 为它等 Spring 启动三秒是 docs/13 开头就点明的那个不可接受的 DX。
 * 所以它在这里直接跑 —— 一个只有 jaravel-console + jaravel-support
 * 的 classpath,没有数据库、没有模板引擎、没有容器。
 *
 * <p>离线与否由 {@code @Command(offline = true)} 声明,默认 false ——
 * 标错方向的代价不对称,理由写在那个注解上。
 *
 * <h2>在线命令:先试通道,没有通道就说清为什么跑不了</h2>
 * <p>{@code migrate}、{@code tinker}、{@code queue:work} 要容器。
 * 应用跑着时它们走**本地命令通道**(docs/13 §1.2)——
 * 在那个已经热起来的进程里执行,0 启动开销。
 *
 * <p>通道不存在(或者是上一次崩溃残留的 socket 文件)时,回落到
 * **冷启动一个应用 JVM**({@link AppLauncher})—— 慢(要付一次完整启动),
 * 但 CI 里、部署脚本里、刚 clone 下来的机器上根本没有一个跑着的应用,
 * 而「请先把应用跑起来」在那些地方是最没用的建议。
 *
 * <p>连那条也走不通时(目录里没有 pom.xml)才打说明。不假装成功
 * (退出码 0 而什么都没做 —— 脚本里它是成功的),也不在这个没有容器的
 * 进程里硬跑它(那会得到「数据库尚未装配」之类与真正原因无关的报错)。
 */
public final class Jaravel {

    private Jaravel() {
    }

    public static void main(String[] args) {
        System.exit(run(args, new ConsoleIo.System()));
    }

    /**
     * 可测的入口:不碰 {@code System.exit}。
     *
     * <p>{@code main} 里那一行 exit 是 CLI 必须有的(退出码是它与 shell
     * 唯一的契约),但它在测试里会把 JVM 直接带走。
     */
    public static int run(String[] args, ConsoleIo io) {
        return run(args, io, java.nio.file.Path.of("").toAbsolutePath());
    }

    /** 测试与 {@code jaravelw} 用:指定项目根 */
    public static int run(String[] args, ConsoleIo io, java.nio.file.Path projectRoot) {
        try {
            return dispatch(args, io, projectRoot);
        } catch (RuntimeException | Error e) {
            io.writeError(failure(e));
            return 1;
        }
    }

    /**
     * 命令抛异常时,给用户看**消息**而不是一屏栈帧。
     *
     * <h2>★ 为什么不直接让它冒出去</h2>
     * <p>冒到 {@code main} 之外的话,JVM 打的是完整栈 —— 而对
     * {@code jaravel migrate} 的用户来说,有用的那句话是
     * {@code Migrator} 写在消息里的「本次已回滚,数据库结构未变。
     * 下一步:修好上面的错误,再次 migrate 会从这个迁移继续」。
     * 那句话会被埋在三十行框架帧的最上面一行。
     *
     * <p>栈不是没用,是**给框架开发者的**:所以它在
     * {@code JARAVEL_DEBUG=1} 下才打。
     */
    private static String failure(Throwable e) {
        var out = new StringBuilder();
        String message = e.getMessage();
        out.append(message == null || message.isBlank()
                ? "命令失败:" + e.getClass().getName()
                : message);
        out.append(System.lineSeparator());

        if (debugEnabled()) {
            var stack = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(stack));
            out.append(stack);
        } else {
            out.append("  (").append(e.getClass().getSimpleName())
               .append(";完整堆栈:JARAVEL_DEBUG=1)")
               .append(System.lineSeparator());
        }
        return out.toString();
    }

    private static boolean debugEnabled() {
        String flag = System.getenv("JARAVEL_DEBUG");
        return flag != null && !flag.isBlank() && !flag.equals("0");
    }

    private static int dispatch(String[] args, ConsoleIo io, java.nio.file.Path projectRoot) {
        CommandRegistry registry = OfflineCommands.registry(projectRoot);
        Artisan.use(registry);

        List<String> argv = List.of(args);

        if (argv.isEmpty()) return registry.run("list", List.of(), io);

        String first = argv.get(0);
        List<String> rest = argv.subList(1, argv.size());

        switch (first) {
            case "--version", "-V" -> {
                io.write(version() + "\n");
                return 0;
            }
            case "--help", "-h" -> {
                return registry.run("help", rest, io);
            }
            default -> { }
        }

        if (registry.has(first)) {
            if (first.startsWith("make:") && !OfflineCommands.looksLikeProject(projectRoot)) {
                // 提示而不是拒绝:在空目录里 make:model 很可能是「忘了 cd」,
                // 但也可能是故意的(还没有 pom 的新目录)。拒绝会挡住后者
                io.writeError(("[提示] %s 看起来不像一个 Jaravel 项目"
                        + "(没有 pom.xml,也没有 src/main/java)——%n"
                        + "       生成的文件仍然会写到这里。%n").formatted(projectRoot));
            }
            return registry.run(first, rest, io);
        }
        if (ONLINE_COMMANDS.stream().anyMatch(first::startsWith)) {
            // 先试通道:dev server 开着的话,这条命令就在那个**已经热起来的**
            // 进程里跑 —— docs/13 §1.2 称它是本模块最重要的 DX 设计
            var throughChannel = ChannelClient.tryRun(projectRoot, first, rest, io);
            if (throughChannel.isPresent()) return throughChannel.get();

            // 通道没通 —— 冷启动一个应用 JVM(docs/13 §1 的第二条路径)。
            // 慢(要付一次完整启动),但 CI 与部署脚本里根本没有
            // 一个跑着的应用,而那正是这条路存在的理由
            var coldStart = AppLauncher.tryRun(projectRoot, first, rest, io);
            if (coldStart.isPresent()) return coldStart.get();

            io.writeError(needsApplication(first) + "\n");
            return 1;
        }
        // 不认识的名字交给注册表 ——「是否想写」的建议在那里
        return registry.run(first, rest, io);
    }

    /**
     * docs/13 §2 里**需要容器**的那些命令。
     *
     * <p>这张表的用途只有一个:把「这条命令还没实现」与
     * 「这条命令要容器而通道还没接」区分开。前者该给
     * 「是否想写 …?」的拼写建议,后者该说清为什么跑不了 ——
     * 对 {@code migrate} 给出「是否想写 make:migration ?」
     * 是一条会把人带到错误方向的建议。
     *
     * <p>用前缀匹配:{@code queue:} 下面有七八条,而它们的状态是一样的。
     *
     * <p>⚠ {@code key:generate} **不在**这张表里,尽管 docs/13 §2.5 把它
     * 列在检视命令那一组:没有 APP_KEY 的应用起不来,所以一条
     * 「要先把应用跑起来」的路径在那里是死循环。它是离线命令。
     * (有一条测试核对「两张表不重叠」—— 这个冲突就是它抓出来的。)
     */
    static final List<String> ONLINE_COMMANDS = List.of(
            "migrate", "db:", "db", "model:", "schema:",
            "tinker", "serve", "queue:", "schedule:",
            "route:", "config:", "view:", "event:",
            "optimize", "about", "down", "up",
            "storage:link", "lang:check", "openapi:",
            "doctor", "analyze:", "inspire", "stub:publish");

    private static String needsApplication(String command) {
        return """
                "%s" 需要应用上下文,而 jaravel 还没有连上应用的通道。

                  这条命令要容器 / 数据库 / 队列(docs/13 §1 的「在线命令」)。

                  出路:**先把应用跑起来**(IDE 里 run,或 mvn spring-boot:run)。
                  它会开一个命令通道(%s),
                  这条命令就会在那个已经热起来的进程里执行。
                    · 通道只在非 production 环境开 —— 它能执行任意命令;
                    · 应用明明跑着却看到这条消息的话,先确认是不是
                      在另一个项目目录里敲的。

                  「冷启动一个 JVM 执行」那条回落也没走通 ——
                  当前目录下没有 pom.xml,所以不知道要启动什么:
                    · 要在项目根目录里敲(pom.xml 旁边);
                    · Gradle 项目暂不支持那条路。
                  此外也可以在应用里调 Artisan.call("%s")。

                  不在这里硬跑它的理由:这个进程没有容器,
                  硬跑会得到「数据库尚未装配」之类与真正原因无关的报错。""".formatted(
                command,
                io.jaravel.console.channel.CommandChannel.DIRECTORY + "/"
                        + io.jaravel.console.channel.CommandChannel.SOCKET_FILE,
                command);
    }

    /**
     * 版本号从 jar 的 manifest 读。
     *
     * <p>不硬编码一个字符串:那个数字会漂 —— 发布流程改版本号时
     * 没人会记得同步代码里的常量,而一个**报错版本号的 --version**
     * 在排查「我装的是哪个版本」时是负资产。
     */
    static String version() {
        String implementation = Jaravel.class.getPackage().getImplementationVersion();
        return "Jaravel " + (implementation == null ? "开发版(未打包)" : implementation)
                + " (Java " + Runtime.version().feature() + ")";
    }
}
