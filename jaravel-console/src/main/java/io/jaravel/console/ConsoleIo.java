package io.jaravel.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 命令与终端之间的那一层(docs/13 §3.1)。
 *
 * <h2>★ 为什么要有这个接口,而不是直接用 System.out</h2>
 * <p>一条命令的**行为**很大一部分就是它的输出与交互:确认提示的默认值、
 * 表格的列宽、失败时 stderr 还是 stdout。直接写 {@code System.out} 的话
 * 这些只能靠肉眼验收,而「改一行输出顺手把 --force 的确认跳过了」
 * 这种事没有测试拦得住。
 *
 * <p>所以输出与读入都走这里,测试用 {@link Recording} 把它们变成断言。
 * 这不是为了「可替换」—— 终端只有一个,是为了**可断言**。
 */
public interface ConsoleIo {

    void write(String text);

    /** 错误流。分开是为了让 {@code jaravel route:list | grep x} 不被报错污染 */
    void writeError(String text);

    /**
     * 读一行。返回 null 表示**没有可交互的终端**(管道里、CI 里)——
     * 调用方必须据此走默认值,而不是无限等待一个永远不会来的回车。
     */
    String readLine(String prompt);

    /** 读一行但不回显(令牌、密码)。终端不支持时回落到 {@link #readLine} */
    String readSecret(String prompt);

    /** 支持 ANSI 颜色吗 —— 管道与 Windows 旧控制台不支持 */
    boolean supportsColor();

    // ================= 实现 =================

    /** 真实终端 */
    final class System implements ConsoleIo {

        private final PrintStream out;
        private final PrintStream err;
        private final BufferedReader in;
        private final java.io.Console console;
        private final boolean color;

        public System() {
            this(java.lang.System.out, java.lang.System.err,
                 new BufferedReader(new InputStreamReader(java.lang.System.in, StandardCharsets.UTF_8)),
                 java.lang.System.console(),
                 // 没有 Console 说明被重定向了(管道、CI)—— 那里 ANSI 是噪音
                 java.lang.System.console() != null && java.lang.System.getenv("NO_COLOR") == null);
        }

        System(PrintStream out, PrintStream err, BufferedReader in,
               java.io.Console console, boolean color) {
            this.out = out;
            this.err = err;
            this.in = in;
            this.console = console;
            this.color = color;
        }

        @Override public void write(String text)      { out.print(text); out.flush(); }
        @Override public void writeError(String text) { err.print(text); err.flush(); }
        @Override public boolean supportsColor()      { return color; }

        @Override
        public String readLine(String prompt) {
            if (console == null) return null;   // 不可交互 —— 让调用方走默认值
            write(prompt);
            try {
                return in.readLine();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public String readSecret(String prompt) {
            if (console == null) return null;
            char[] secret = console.readPassword("%s", prompt);
            return secret == null ? null : new String(secret);
        }
    }

    /**
     * 测试与 {@code Artisan.output()} 用的实现:输出攒在缓冲区里,
     * 读入来自一个预先排好的答案队列。
     *
     * <p>答案用完后 {@link #readLine} 返回 null —— 与「管道里没有终端」
     * 同一个信号。于是「忘了给答案」的测试会走默认值分支而不是卡住。
     */
    final class Recording implements ConsoleIo {

        private final StringBuilder out = new StringBuilder();
        private final StringBuilder err = new StringBuilder();
        private final Deque<String> answers = new ArrayDeque<>();
        private final List<String> prompts = new java.util.ArrayList<>();

        public Recording(String... scriptedAnswers) {
            answers.addAll(List.of(scriptedAnswers));
        }

        @Override public void write(String text)      { out.append(text); }
        @Override public void writeError(String text) { err.append(text); }
        @Override public boolean supportsColor()      { return false; }

        @Override
        public String readLine(String prompt) {
            prompts.add(prompt);
            return answers.poll();
        }

        @Override
        public String readSecret(String prompt) {
            return readLine(prompt);
        }

        public String output()  { return out.toString(); }
        public String errors()  { return err.toString(); }
        /** 问过的问题 —— 「确认提示的文案里带没带默认值」要能断言 */
        public List<String> prompts() { return List.copyOf(prompts); }
    }
}
