package io.jaravel.spring.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.console.Artisan;
import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleIo;

/**
 * tinker 的求值引擎(docs/13 §5)。
 *
 * <p>引擎与 REPL 循环分开,正是为了这些测试能**脱离终端**跑 ——
 * 喂一行、拿一个结果。
 *
 * <p>{@code TinkerSession} 启动一次要编译一遍预置 import,所以这里
 * 共用一个实例({@code @BeforeAll})—— 而那恰好也更接近真实用法:
 * 一个会话里连着敲很多行,后面的行能看见前面的声明。
 */
public class TinkerTest {

    /** 片段要能读到的宿主静态状态 —— 模拟容器 */
    public static final Map<String, Object> HOST_STATE = new ConcurrentHashMap<>();

    /** 片段调它,用来数「跑了几次」 */
    public static final java.util.concurrent.atomic.AtomicInteger SIDE_EFFECTS =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void bumpSideEffect() {
        SIDE_EFFECTS.incrementAndGet();
    }

    /** 公共字段的 POJO —— 与 ADR-010 的模型形状一致 */
    public static final class Post {
        public Long id;
        public String title;
        public boolean published;

        public Post(Long id, String title, boolean published) {
            this.id = id;
            this.title = title;
            this.published = published;
        }
    }

    private static TinkerSession session;

    @BeforeAll
    static void open() {
        HOST_STATE.put("database", "一个真的连接对象");
        // surefire 用只有 manifest 的 booter jar 启动,java.class.path 里
        // 没有测试类 —— 显式加上(那也是 TinkerSession 留那个参数的理由)
        session = new TinkerSession(List.of(
                TinkerTest.class.getProtectionDomain().getCodeSource()
                        .getLocation().getPath()));
    }

    @AfterAll
    static void close() {
        session.close();
        HOST_STATE.clear();
    }

    private TinkerSession.Outcome feed(String line) {
        return session.feed(line);
    }

    private String value(String line) {
        var outcome = feed(line);
        assertThat(outcome.kind())
                .as("期望是个有值的表达式,实际 %s:%s", outcome.kind(), outcome.text())
                .isEqualTo(TinkerSession.Outcome.Kind.VALUE);
        return outcome.text();
    }

    // ================= 同进程、同容器 =================

    @Test
    @DisplayName("★ 片段跑在**同一个 JVM** 里 —— 这是整节能成立的前提")
    void sameJvm() {
        String pid = value("ProcessHandle.current().pid()");

        assertThat(pid)
                .as("""
                        拿不到宿主进程的话,tinker 只是个 Java 计算器 ——
                        而它的全部用途是「在真的容器里探索数据」。""")
                .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
    }

    @Test
    @DisplayName("★ 读到的是宿主里那个真对象")
    void readsHostState() {
        String seen = value("io.jaravel.spring.commands.TinkerTest.HOST_STATE.get(\"database\")");

        assertThat(seen).isEqualTo("\"一个真的连接对象\"");
    }

    @Test
    @DisplayName("★ 片段对宿主状态的修改,宿主看得见")
    void mutatesHostState() {
        feed("io.jaravel.spring.commands.TinkerTest.HOST_STATE.put(\"从 shell 塞进去的\", 42);");

        assertThat(HOST_STATE)
                .as("「改一下再看看」是 REPL 的常用动作 —— 它必须作用在真东西上")
                .containsEntry("从 shell 塞进去的", 42);
    }

    // ================= 求值 =================

    @Nested
    @DisplayName("求值")
    class Evaluation {

        @Test
        @DisplayName("★ 声明语句自动补分号 —— int x = 5 不该被拒")
        void declarationsNeedNoSemicolon() {
            assertThat(feed("int tinkerX = 5").kind())
                    .as("""
                            原生 JShell 对 "int x = 5" 报「进行语法分析时已到达文件结尾」,
                            而表达式不用分号 —— 靠 analyzeCompletion 区分。""")
                    .isNotEqualTo(TinkerSession.Outcome.Kind.ERROR);

            assertThat(value("tinkerX * 2")).isEqualTo("10");
        }

        @Test
        @DisplayName("后面的行看得见前面的声明")
        void stateCarriesAcrossLines() {
            feed("var greeting = \"你好\"");

            assertThat(value("greeting + \",tinker\"")).isEqualTo("\"你好,tinker\"");
        }

        @Test
        @DisplayName("★ 多行:没写完的语句攒起来等下一行")
        void multiLine() {
            feed("int total = 0");
            assertThat(feed("for (int i = 0; i < 3; i++) {").kind())
                    .isEqualTo(TinkerSession.Outcome.Kind.INCOMPLETE);
            assertThat(session.isMidStatement())
                    .as("提示符要变成 … ,否则用户不知道它在等自己")
                    .isTrue();

            feed("  total += i;");
            feed("}");

            assertThat(session.isMidStatement()).isFalse();
            assertThat(value("total")).isEqualTo("3");
        }

        @Test
        @DisplayName(":clear 丢掉攒着的那半截")
        void discardBuffer() {
            feed("if (true) {");
            assertThat(session.isMidStatement()).isTrue();

            session.discardBuffer();

            assertThat(session.isMidStatement()).isFalse();
            assertThat(value("1 + 1")).isEqualTo("2");
        }

        @Test
        @DisplayName("★ 运行时异常 → 异常类名 + 消息,不打 JShell 的合成帧")
        void runtimeException() {
            var outcome = feed("1 / 0");

            assertThat(outcome.kind()).isEqualTo(TinkerSession.Outcome.Kind.ERROR);
            assertThat(outcome.text())
                    .as("tinker 里的栈几乎全是 $JShell$12.do_it$ 这种合成帧")
                    .contains("ArithmeticException").contains("by zero")
                    .doesNotContain("JShell");
        }

        @Test
        @DisplayName("编译错误带上编译器的诊断")
        void compileError() {
            var outcome = feed("没有这个变量 + 1");

            assertThat(outcome.kind()).isEqualTo(TinkerSession.Outcome.Kind.ERROR);
            assertThat(outcome.text()).isNotBlank();
        }

        @Test
        @DisplayName("★ 先按表达式试的那一次失败了,不会产生副作用")
        void failedFirstAttemptHasNoSideEffects() {
            SIDE_EFFECTS.set(0);

            // if 是**语句**,所以「包进格式化器」那次会编译失败 ——
            // 而编译失败意味着片段根本没执行,于是计数只会 +1 一次
            var outcome = feed("if (true) io.jaravel.spring.commands.TinkerTest.bumpSideEffect();");

            assertThat(outcome.kind())
                    .as("语句跑完了没有值 —— 回落那条路成功了:%s", outcome.text())
                    .isEqualTo(TinkerSession.Outcome.Kind.DONE);

            assertThat(SIDE_EFFECTS.get())
                    .as("""
                            REJECTED 意味着编译失败、片段没跑 ——
                            那正是「先按表达式试一次」安全的理由。
                            要是它执行了,这里会是 2。""")
                    .isEqualTo(1);
        }
    }

    // ================= 格式化 =================

    @Nested
    @DisplayName("值的格式化")
    class Formatting {

        @Test
        @DisplayName("★ 模型打印字段,而不是 Post@397fbdb")
        void objectsPrintTheirFields() {
            String shown = value("new io.jaravel.spring.commands.TinkerTest.Post(1L, \"第一篇\", true)");

            assertThat(shown)
                    .as("""
                            JShell 把值交回来时只给一个字符串(执行侧 toString 的结果),
                            所以原生 jshell 里这是 Post@397fbdb —— 对探索数据毫无用处,
                            而那是 tinker 的全部用途。""")
                    .isEqualTo("Post{id=1, title=\"第一篇\", published=true}");
        }

        @Test
        @DisplayName("集合打印元素")
        void collections() {
            assertThat(value("List.of(1, 2, 3)")).isEqualTo("[1, 2, 3]");
            assertThat(value("Map.of(\"a\", 1)")).isEqualTo("{\"a\"=1}");
        }

        @Test
        @DisplayName("★ 大集合截断 —— 一个一万行的查询不该把终端刷满")
        void largeCollectionsAreTruncated() {
            String shown = value("java.util.stream.IntStream.range(0, 100).boxed().toList()");

            assertThat(shown).contains("…共 100 个");
            assertThat(shown.length()).isLessThan(200);
        }

        @Test
        @DisplayName("null 与字符串各自清楚")
        void nullAndStrings() {
            assertThat(value("(String) null")).isEqualTo("null");
            assertThat(value("\"文本\"")).isEqualTo("\"文本\"");
        }
    }

    // ================= 入口 =================

    @Nested
    @DisplayName("命令入口")
    class Entry {

        /**
         * 驱动真正的 {@code handle()} —— 只把「有没有终端」那道护栏换掉。
         *
         * <p>管道里 {@code System.console()} 是 null,所以护栏会拦住
         * (那正是它的用处,另有一条测试)。覆盖它之后走的是完整路径:
         * banner、提示符、命令、求值、退出。
         */
        private ConsoleIo.Recording replay(String... lines) {
            var io = new ConsoleIo.Recording(lines);
            new CommandRegistry()
                    .register(TinkerCommand.class, () -> new TinkerCommand() {
                        @Override protected boolean interactive() { return true; }
                    })
                    .run("tinker", List.of(), io);
            return io;
        }

        @Test
        @DisplayName("★ REPL 循环:表达式回显值,声明不回显,:exit 退出")
        void replLoop() {
            var io = replay("1 + 1", "var replVar = \"值\"", "replVar", ":exit", "不该跑到这里");

            assertThat(io.output()).contains("2").contains("\"值\"");
            assertThat(io.output())
                    .as(":exit 之后不该再读下一行")
                    .doesNotContain("不该跑到这里");
        }

        @Test
        @DisplayName("★ 没写完时提示符变成 … —— 否则用户不知道它在等自己")
        void continuationPrompt() {
            var io = replay("if (true) {", "  int ignored = 1;", "}", ":exit");

            assertThat(io.prompts()).contains("jaravel> ");
            assertThat(io.prompts())
                    .as("提示符不变的话,用户会以为上一行没被接受,然后再敲一遍")
                    .contains("     … ");
        }

        @Test
        @DisplayName(":reload 如实说它没实现,而不是假装重载了")
        void reloadIsHonest() {
            var io = replay(":reload", ":exit");

            assertThat(io.output()).contains("还没实现").contains("ClassLoader");
        }

        @Test
        @DisplayName("读到 null(Ctrl-D)也干净退出")
        void ctrlD() {
            var io = replay();   // 一行都没有 = 立刻 EOF

            assertThat(io.output())
                    .as("banner 还是会打,但不该挂住或报错")
                    .contains("容器已就绪");
        }

        @Test
        @DisplayName("★ 不可交互时拒绝,并说清今天怎么进")
        void refusesWithoutATerminal() {
            var io = new ConsoleIo.Recording();
            int code = new CommandRegistry()
                    .register(TinkerCommand.class, TinkerCommand::new)
                    .run("tinker", List.of(), io);

            assertThat(code).isEqualTo(1);
            assertThat(io.output())
                    .as("""
                            一个读不到输入的 REPL 与「卡住了」无法区分,
                            而用户会去查网络、查数据库,查不到自己的键盘上。""")
                    .contains("需要一个能读键盘的终端")
                    .contains("jaravel.tinker=true")
                    .as("也要说清那条还没实现的路")
                    .contains("冷启动一个应用 JVM");
            Artisan.reset();
        }
    }
}
