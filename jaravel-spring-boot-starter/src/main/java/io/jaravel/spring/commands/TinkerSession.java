package io.jaravel.spring.commands;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis.Completeness;
import jdk.jshell.execution.LocalExecutionControlProvider;

/**
 * tinker 的求值引擎(docs/13 §5)。
 *
 * <p>与终端无关 —— 喂一行、拿一个结果,所以它**可以脱离终端测试**。
 * REPL 循环在 {@link TinkerCommand} 里。
 *
 * <h2>★ 同一个 JVM、同一个容器(实测结论)</h2>
 * <p>{@code LocalExecutionControlProvider} 让代码片段跑在**宿主进程**里 ——
 * 动手之前写了探针确认:片段里 {@code ProcessHandle.current().pid()}
 * 与宿主相同,读到的是容器里那个真对象,而且**片段对静态状态的修改宿主能看见**。
 * 这是整节能成立的前提;拿不到容器的话 tinker 只是个 Java 计算器。
 *
 * <h2>探针还查出三件必须处理的事</h2>
 * <ol>
 *   <li><b>声明语句要补分号</b>:{@code int x = 5} 会被拒
 *       (「进行语法分析时已到达文件结尾」),而 {@code x * 2} 不用。
 *       交给 {@code analyzeCompletion} 判断 —— 原生 jshell 也是这么做的。</li>
 *   <li><b>值只以字符串形式回来</b>,所以 {@code Post.find(1)} 是
 *       {@code app.models.Post@397fbdb}。于是表达式被包进
 *       {@link TinkerFormatter} 再求值 —— 格式化要发生在还有真对象的那一侧。</li>
 *   <li><b>默认包里的类访问不到</b>:片段活在自己的合成包里。
 *       框架与应用代码都在有名包里,所以这条只影响
 *       「把类写在默认包」的玩具项目。</li>
 * </ol>
 *
 * <h2>⚠ 片段里的 System.out 去的是**宿主的**终端</h2>
 * <p>本地执行引擎下 {@code System.out} 就是应用进程那个 —— JShell 构造器上的
 * {@code .out(...)} 管不着它(探针确认)。所以
 * {@code System.out.println(...)} 的输出不会经过这里返回。
 * 要截住它只能全局 {@code System.setOut},而那会把应用其它线程的日志
 * 一起截走 —— 在一个服务进程里不可接受。
 *
 * <p>后果:**表达式求值**(REPL 的常用形态)完全正常;
 * 而直接打印的片段,输出出现在应用自己的终端上。这也是
 * {@link TinkerCommand} 要求「终端与应用在同一个进程」的原因之一。
 */
public final class TinkerSession implements AutoCloseable {

    /**
     * 预置的 import。
     *
     * <p>docs/13 §5.1 说要扫描项目的模型包 —— 这里直接按 ADR-009 的约定
     * 布局写死({@code app.models} 等)。扫描能发现非约定布局的包,
     * 但代价是启动时要读一遍 classpath,而 tinker 的启动时间
     * 本来就是它最大的短板。
     *
     * <p>包不存在时那条 import 会被拒,**这是正常的**(一个纯 API 项目
     * 没有 app.views)。所以失败的 import 不报错、不打印。
     */
    private static final List<String> PRELUDE = List.of(
            "import java.util.*;",
            "import java.time.*;",
            "import static java.time.Duration.*;",
            "import io.jaravel.support.*;",
            "import io.jaravel.facades.*;",
            "import app.models.*;",
            "import app.jobs.*;",
            "import app.events.*;",
            "import app.mail.*;",
            "import app.notifications.*;");

    private final JShell shell;
    private final List<String> buffered = new ArrayList<>();
    private final List<String> failedImports = new ArrayList<>();

    public TinkerSession() {
        this(List.of());
    }

    /**
     * @param extraClasspath 额外的 classpath 条目
     *
     * <h2>★ 为什么需要这个参数:java.class.path 不总是全的</h2>
     * <p>默认从 {@code java.class.path} 取 —— 那覆盖「从 IDE 或
     * {@code mvn spring-boot:run} 启动」的常见情形。但两种情况下它不全:
     * <ul>
     *   <li><b>surefire</b> 用一个只有 manifest 的 booter jar 启动 JVM,
     *       于是 {@code java.class.path} 里只有那一个 jar ——
     *       这是写测试时当场撞到的;</li>
     *   <li><b>可执行 fat jar</b> 同理。</li>
     * </ul>
     *
     * <p>症状是「找不到符号 Post」,而那会让人以为 import 写错了。
     * 所以留一个显式入口,而不是去猜 ClassLoader 的内部结构 ——
     * 现代 Java 的应用加载器不是 URLClassLoader,猜不出来。
     */
    public TinkerSession(List<String> extraClasspath) {
        this.shell = JShell.builder()
                .executionEngine(new LocalExecutionControlProvider(), Map.of())
                .build();
        addHostClasspath();
        extraClasspath.forEach(entry ->
                shell.addToClasspath(new File(entry).getAbsolutePath()));
        loadPrelude();
    }

    /**
     * 宿主的 classpath 要**逐条**加,而且是绝对路径。
     *
     * <p>整串塞进 {@code addToClasspath} 不生效(探针确认)——
     * 症状是「找不到符号 Post」,而那会让人以为是 import 写错了。
     */
    private void addHostClasspath() {
        for (String entry : System.getProperty("java.class.path", "")
                .split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            shell.addToClasspath(new File(entry).getAbsolutePath());
        }
    }

    private void loadPrelude() {
        for (String statement : PRELUDE) {
            boolean ok = shell.eval(statement).stream()
                    .allMatch(e -> e.status() == Snippet.Status.VALID);
            if (!ok) failedImports.add(statement);
        }
    }

    /** 哪些预置 import 没加上(包不存在)—— {@code :help} 里说明一下 */
    public List<String> unavailableImports() {
        return List.copyOf(failedImports);
    }

    /** 一次求值的结果 */
    public record Outcome(Kind kind, String text) {
        public enum Kind {
            /** 有值,{@code text} 是格式化后的值 */
            VALUE,
            /** 跑了但没有值(赋值、打印、import) */
            DONE,
            /** 还没写完,等下一行 */
            INCOMPLETE,
            /** 编译错误或运行时异常,{@code text} 是说明 */
            ERROR
        }
    }

    /**
     * 喂一行。
     *
     * <p>多行输入由 {@link Completeness#DEFINITELY_INCOMPLETE} 驱动:
     * 一个 {@code if (x) {} 开头的行会攒起来等下一行。
     */
    public Outcome feed(String line) {
        buffered.add(line);
        String source = String.join("\n", buffered);
        Outcome last = new Outcome(Outcome.Kind.DONE, "");

        // ★ 一行里可能有**多段**:analyzeCompletion 只认到第一段为止,
        // 剩下的在 remaining() 里。不循环的话 "1+1; 2+2" 的后半句会被
        // **悄悄丢掉** —— 而「悄悄」是这里最糟的失败方式:
        // 用户以为自己敲的东西跑了。
        while (!source.isBlank()) {
            var info = shell.sourceCodeAnalysis().analyzeCompletion(source);

            if (info.completeness() == Completeness.DEFINITELY_INCOMPLETE
                    || info.completeness() == Completeness.CONSIDERED_INCOMPLETE) {
                // 没写完的那一截留着等下一行
                buffered.clear();
                buffered.add(source);
                return new Outcome(Outcome.Kind.INCOMPLETE, "");
            }
            if (info.completeness() == Completeness.EMPTY) break;
            if (info.completeness() == Completeness.UNKNOWN) {
                buffered.clear();
                return new Outcome(Outcome.Kind.ERROR, "这段代码看不懂(语法错误)");
            }

            // source() 在 COMPLETE_WITH_SEMI 时已经带上了分号
            last = evaluate(info.source());
            if (last.kind() == Outcome.Kind.ERROR) {
                buffered.clear();
                return last;   // 前一段就错了,后面几段不跑
            }
            source = info.remaining();
        }

        buffered.clear();
        return last;
    }

    /**
     * ★ 先按「表达式」试一次(包进格式化器),被拒了再按原样跑。
     *
     * <p>这样做是安全的:**REJECTED 意味着编译失败,片段根本没执行** ——
     * 所以第一次尝试不会产生副作用。而它换来的是
     * {@code Post.find(1)} 打印字段而不是 {@code Post@397fbdb}。
     *
     * <p>反过来(先原样跑、发现是表达式再格式化)做不到:
     * 值回来时已经是字符串了,真对象留在执行侧。
     */
    private Outcome evaluate(String source) {
        String wrapped = TinkerFormatter.class.getName() + ".of(" + stripSemicolon(source) + ")";
        var asExpression = shell.eval(wrapped);
        if (asExpression.stream().allMatch(e -> e.status() == Snippet.Status.VALID)) {
            return outcomeOf(asExpression, true);
        }
        return outcomeOf(shell.eval(source), false);
    }

    private static String stripSemicolon(String source) {
        String trimmed = source.strip();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private Outcome outcomeOf(List<SnippetEvent> events, boolean formatted) {
        for (SnippetEvent event : events) {
            if (event.exception() != null) {
                return new Outcome(Outcome.Kind.ERROR, describe(event));
            }
            if (event.status() != Snippet.Status.VALID) {
                return new Outcome(Outcome.Kind.ERROR, diagnosticsOf(event));
            }
        }

        String value = events.stream()
                .map(SnippetEvent::value)
                .filter(v -> v != null && !v.isBlank())
                .reduce((first, last) -> last)
                .orElse(null);

        if (value == null) return new Outcome(Outcome.Kind.DONE, "");
        // 包过格式化器的值是个 Java 字符串字面量("…"),去掉那层引号
        return new Outcome(Outcome.Kind.VALUE, formatted ? unquote(value) : value);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
        }
        return value;
    }

    /**
     * 运行时异常 → 异常类名 + 消息。
     *
     * <p>不打栈:tinker 里的栈几乎全是 JShell 的合成帧
     * ({@code $JShell$12.do_it$}),而用户要的是
     * 「哪个异常、说了什么」。
     */
    private String describe(SnippetEvent event) {
        if (event.exception() instanceof jdk.jshell.EvalException evaluated) {
            String message = evaluated.getMessage();
            return evaluated.getExceptionClassName()
                    + (message == null || message.isBlank() ? "" : ":" + message);
        }
        return String.valueOf(event.exception());
    }

    private String diagnosticsOf(SnippetEvent event) {
        if (event.snippet() == null) return "编译失败";
        var messages = shell.diagnostics(event.snippet())
                .map(d -> d.getMessage(null))
                .toList();
        return messages.isEmpty() ? "编译失败" : String.join("\n", messages);
    }

    /** 还在等后续输入吗 —— 提示符要变成 {@code …} */
    public boolean isMidStatement() {
        return !buffered.isEmpty();
    }

    /** 丢掉攒着的那半截(用户按了 Ctrl-C 或者输入了 {@code :clear}) */
    public void discardBuffer() {
        buffered.clear();
    }

    @Override
    public void close() {
        shell.close();
    }
}
