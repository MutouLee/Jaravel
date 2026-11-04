package io.jaravel.spring.commands;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.core.Application;

/**
 * {@code jaravel tinker}(docs/13 §5)—— 容器里的 REPL。
 *
 * <pre>
 * jaravel&gt; Post.query().count()
 * 12
 * jaravel&gt; var p = Post.find(1)
 * jaravel&gt; p
 * Post{id=1, title=第一篇, published=true}
 * jaravel&gt; :exit
 * </pre>
 *
 * <h2>★ 它要一个**与应用同进程**的终端</h2>
 * <p>求值引擎跑在应用进程里(那是整件事的价值所在,见 {@link TinkerSession}),
 * 而 REPL 要读键盘、要立刻回显。两个限制把可用的入口收窄到一个:
 *
 * <ul>
 *   <li><b>本地命令通道是单向的</b>:服务端能往回写,但读不到客户端的键盘
 *       (见 {@code ChannelServer} 的注释)。所以 {@code jaravel tinker}
 *       走通道时**没法交互** —— 这里直接拒绝,而不是启动一个读不到输入、
 *       看起来挂住了的 REPL。</li>
 *   <li><b>片段里的 {@code System.out} 去的是应用自己的终端</b>
 *       (本地执行引擎的固有性质,探针确认过)。即便把键盘转发过来,
 *       打印类片段的输出也会出现在另一个终端上。</li>
 * </ul>
 *
 * <p>所以今天可用的入口是:**让应用带着终端启动**
 * ({@code mvn spring-boot:run -Djaravel.tinker=true},或 IDE 里加那个属性)。
 * docs/13 §1 说的另一条路是 CLI 冷启动一个应用 JVM 并把终端交给它 ——
 * 那个启动器还没实现,它一到位这条命令就能从 {@code jaravel tinker} 直接进。
 *
 * <p>不假装能跑的理由与别处一致:一个读不到输入的 REPL 与「卡住了」
 * 无法区分,而用户会去查网络、查数据库,查不到自己的键盘上。
 */
@Command(name = "tinker", description = "在应用容器里开一个 REPL")
public class TinkerCommand extends ConsoleCommand {

    @Override
    public int handle() {
        // 读得到一行吗 —— 这同时覆盖了命令通道、管道、CI 与 Artisan.call
        if (!interactive()) {
            failure("tinker 需要一个能读键盘的终端,而当前这条路读不到输入。");
            newLine();
            line("""
                    原因:求值要在**应用进程里**跑(那是 tinker 的价值所在),
                      而本地命令通道是单向的 —— 服务端能往回写,读不到你的键盘。

                    今天可用的入口:让应用带着终端启动
                      mvn spring-boot:run -Djaravel.tinker=true
                      (IDE 里就在运行配置上加那个 -D 参数)

                    「CLI 冷启动一个应用 JVM 并把终端交给它」那条路还没实现 ——
                    它一到位,jaravel tinker 就能直接进。""");
            return 1;
        }

        try (var session = new TinkerSession()) {
            banner(session);
            return loop(session);
        }
    }

    /**
     * 这条 IO 读得到输入吗。
     *
     * <p>用「试着读一行」来判断不行 —— 那会吃掉用户的第一行输入。
     * {@code ConsoleIo} 的约定是不可交互时 {@code readLine} 返回 null,
     * 所以探一次提示符为空的读取:真终端上它会等输入,
     * 而那正是我们**不想**在探测阶段做的事。
     *
     * <p>于是改为问系统:有没有 {@code System.console()}。
     * 它与 {@code ConsoleIo.System} 判断可交互的依据是同一个,
     * 而通道/管道/测试用的 Recording IO 都不是它。
     *
     * <p>protected:这是 REPL 唯一的测试接缝。护栏让这条命令在管道里
     * 跑不起来(那正是它的用处),于是测试覆盖它、从真正的
     * {@link #handle} 进 —— 而不是在生产代码里加一个「测试模式」开关。
     */
    protected boolean interactive() {
        return io() instanceof io.jaravel.console.ConsoleIo.System
                && java.lang.System.console() != null;
    }

    private void banner(TinkerSession session) {
        info("Jaravel tinker —— 容器已就绪,门面已导入(" + Application.environment() + ")");
        line("  :exit 退出 · :clear 丢掉没写完的那半截 · :help 帮助");
        if (!session.unavailableImports().isEmpty()) {
            comment("  (这些包不存在,已跳过:" + session.unavailableImports().size() + " 条 import)");
        }
        newLine();
    }

    private int loop(TinkerSession session) {
        while (true) {
            String line = io().readLine(session.isMidStatement() ? "     … " : "jaravel> ");
            if (line == null) return 0;        // Ctrl-D

            String trimmed = line.trim();
            if (trimmed.equals(":exit") || trimmed.equals(":quit")) return 0;
            if (trimmed.equals(":clear")) {
                session.discardBuffer();
                continue;
            }
            if (trimmed.equals(":help")) {
                help(session);
                continue;
            }
            if (trimmed.equals(":reload")) {
                // 如实说:重载应用类需要换一个 ClassLoader 重建容器,
                // 而那会让 shell 里已有的变量全部失效(它们引用旧类)。
                // docs/13 §5.2 把它列为「JShell 的现实限制」之一
                warn(":reload 还没实现 —— 改了代码请重启应用。");
                line("  原因:重载应用类要换 ClassLoader 重建容器,"
                        + "而 shell 里已有的变量会引用旧类。");
                continue;
            }
            if (trimmed.isEmpty() && !session.isMidStatement()) continue;

            print(session.feed(line));
        }
    }

    private void print(TinkerSession.Outcome outcome) {
        switch (outcome.kind()) {
            case VALUE -> line(outcome.text());
            case ERROR -> failure(outcome.text());
            case DONE, INCOMPLETE -> { }
        }
    }

    private void help(TinkerSession session) {
        line("""
                直接写 Java 表达式或语句:
                  Post.query().count()          表达式 —— 打印格式化后的值
                  var p = Post.find(1)          声明 —— 之后可以用 p
                  if (x > 1) {                  多行 —— 提示符变成 …

                命令:
                  :exit / :quit   退出
                  :clear          丢掉没写完的那半截
                  :reload         (还没实现)

                ⚠ System.out.println(...) 的输出会出现在**应用自己的终端**上 ——
                  本地执行引擎下片段与应用共用一个 System.out。
                  要看值就直接写表达式,不要 println。""");
        if (!session.unavailableImports().isEmpty()) {
            newLine();
            line("跳过的 import(包不存在,正常):");
            session.unavailableImports().forEach(statement -> line("  " + statement));
        }
    }
}
