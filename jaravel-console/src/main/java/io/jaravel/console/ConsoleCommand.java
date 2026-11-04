package io.jaravel.console;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * 所有命令的基类(docs/13 §3.1 的输出与交互 API)。
 *
 * <pre>{@code
 * @Command(name = "inventory:sync", description = "同步库存")
 * public class SyncInventory extends ConsoleCommand {
 *
 *     @Argument(description = "供应商代码,留空则全部", required = false)
 *     String supplier;
 *
 *     @Option(names = {"-f", "--force"}) boolean force;
 *
 *     @Override public int handle() {
 *         info("开始同步");
 *         withProgressBar(items, item -> inventory.upsert(item));
 *         success("完成");
 *         return 0;
 *     }
 * }
 * }</pre>
 */
public abstract class ConsoleCommand {

    private ConsoleIo io = new ConsoleIo.System();
    private CommandRegistry registry;

    /** 命令要做的事。返回**退出码** —— 0 成功,非 0 失败 */
    public abstract int handle();

    public String name() {
        Command annotation = getClass().getAnnotation(Command.class);
        if (annotation == null) {
            throw new ConsoleException(getClass().getSimpleName()
                    + " 没有 @Command 注解 —— 命令要有名字才能被调用");
        }
        return annotation.name();
    }

    public String description() {
        Command annotation = getClass().getAnnotation(Command.class);
        return annotation == null ? "" : annotation.description();
    }

    // ================= 装配(由 CommandRegistry 调用)=================

    void bind(ConsoleIo console, CommandRegistry commands) {
        this.io = console;
        this.registry = commands;
    }

    protected ConsoleIo io() {
        return io;
    }

    /** 注册表 —— list / help 这类「关于命令的命令」要它 */
    protected CommandRegistry commands() {
        if (registry == null) {
            throw new ConsoleException(name()
                    + " 需要注册表,但它不是通过 CommandRegistry 运行的");
        }
        return registry;
    }

    // ================= 输出 =================

    /** 普通一行 */
    public void line(String text)    { io.write(text + "\n"); }

    public void info(String text)    { io.write(color("36", text) + "\n"); }
    public void comment(String text) { io.write(color("33", text) + "\n"); }
    public void question(String text){ io.write(color("35", text) + "\n"); }
    public void warn(String text)    { io.write(color("33", "⚠ " + text) + "\n"); }
    public void success(String text) { io.write(color("32", "✓ " + text) + "\n"); }

    /**
     * 错误走 **stderr**。
     *
     * <p>不然 {@code jaravel route:list --json | jq} 会把报错也喂给 jq ——
     * 得到的是「JSON 解析失败」,而真正的原因在那串输出的最上面。
     */
    public void error(String text) { io.writeError(color("31", "✗ " + text) + "\n"); }

    /**
     * 报告里的一条失败 —— 红色,但走 **stdout**。
     *
     * <h2>★ 与 {@link #error} 的区别:谁失败了</h2>
     * <ul>
     *   <li>{@code error(...)}:**这条命令**失败了。走 stderr,
     *       于是 {@code route:list --json | jq} 的管道不被污染。</li>
     *   <li>{@code failure(...)}:命令正常跑完了,而它**报告**的内容里有
     *       一条坏消息({@code doctor} 的 ✗ 项、{@code migrate:status}
     *       里没跑的那些)。它属于报告,必须和报告的其余部分在同一条流上。</li>
     * </ul>
     *
     * <p>这个区分不是洁癖。doctor 的每个 ✗ 后面跟着几行 {@code →} 说明,
     * 而那些说明走 stdout —— 用 {@code error} 的话,
     * 在日志里(或者任何把两条流分开收集的地方)**结论与解释就分家了**:
     * 一串无主的 {@code → 修复:…} 在上面,一串无因的 {@code ✗} 在下面。
     * 终端上两条流交错输出,所以这个缺陷在人眼前是看不出来的。
     *
     * <p>命令是否失败由**退出码**表达 —— 那才是机器要读的那一位。
     */
    public void failure(String text) { io.write(color("31", "✗ " + text) + "\n"); }

    public void newLine()          { newLine(1); }

    public void newLine(int count) { io.write("\n".repeat(Math.max(0, count))); }

    /** ANSI 的转义字符。写成码点而不是字面量 —— 源文件里不留控制字符 */
    private static final String ESC = String.valueOf((char) 27);

    private String color(String code, String text) {
        return io.supportsColor() ? ESC + "[" + code + "m" + text + ESC + "[0m" : text;
    }

    /**
     * 表格。列宽按**内容**算,不是固定宽度 —— 固定宽度在中文下必然错位。
     *
     * <p>⚠ 宽度按 {@link #displayWidth} 算:CJK 字符在等宽终端里占两格,
     * 按 {@code String.length()} 排的表在中文内容上每一行都对不齐,
     * 而那是这个框架的主要使用语言。
     */
    public void table(List<String> headers, List<? extends List<?>> rows) {
        var all = new ArrayList<List<String>>();
        all.add(headers);
        for (List<?> row : rows) {
            var cells = new ArrayList<String>(row.size());
            for (Object cell : row) cells.add(cell == null ? "" : String.valueOf(cell));
            all.add(cells);
        }

        int columns = all.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[columns];
        for (List<String> row : all) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], displayWidth(row.get(c)));
            }
        }

        line(border(widths, '┌', '┬', '┐'));
        line(row(all.get(0), widths));
        line(border(widths, '├', '┼', '┤'));
        for (int r = 1; r < all.size(); r++) line(row(all.get(r), widths));
        line(border(widths, '└', '┴', '┘'));
    }

    private String row(List<String> cells, int[] widths) {
        var sb = new StringBuilder("│");
        for (int c = 0; c < widths.length; c++) {
            String cell = c < cells.size() ? cells.get(c) : "";
            sb.append(' ').append(cell)
              .append(" ".repeat(widths[c] - displayWidth(cell)))
              .append(" │");
        }
        return sb.toString();
    }

    private String border(int[] widths, char left, char middle, char right) {
        var sb = new StringBuilder().append(left);
        for (int c = 0; c < widths.length; c++) {
            sb.append("─".repeat(widths[c] + 2));
            sb.append(c == widths.length - 1 ? right : middle);
        }
        return sb.toString();
    }

    /**
     * 两列的「键 值」一行(docs/13 §3.1 的 {@code twoColumnDetail})。
     *
     * <pre>
     *   环境            local
     *   数据库          sqlite
     * </pre>
     *
     * <p>文档里写的是 {@code components().twoColumnDetail(...)} ——
     * 这里没有那个 {@code components()} 中间层。它在 Laravel 里是为了给
     * 一组渲染组件分命名空间,而这里只有两三个;多一层间接只是让
     * 补全多按一次回车。
     *
     * <p>宽度按**显示宽度**算 —— 键几乎总是中文,
     * 按 {@code length()} 对齐的话每一行都差两格。
     */
    public void twoColumnDetail(String key, String value) {
        int pad = Math.max(1, DETAIL_WIDTH - displayWidth(key));
        line("  " + key + " ".repeat(pad) + value);
    }

    private static final int DETAIL_WIDTH = 20;

    /** 等宽终端里的显示宽度:CJK 与全角符号算两格 */
    static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            i += Character.charCount(code);
            width += isWide(code) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(int code) {
        return (code >= 0x1100 && code <= 0x115F)      // 韩文字母
            || (code >= 0x2E80 && code <= 0xA4CF)      // CJK 部首 → 彝文
            || (code >= 0xAC00 && code <= 0xD7A3)      // 韩文音节
            || (code >= 0xF900 && code <= 0xFAFF)      // CJK 兼容
            || (code >= 0xFE30 && code <= 0xFE6F)      // CJK 兼容标点
            || (code >= 0xFF00 && code <= 0xFF60)      // 全角
            || (code >= 0xFFE0 && code <= 0xFFE6)
            || (code >= 0x20000 && code <= 0x3FFFD);   // CJK 扩展 B+
    }

    // ================= 进度 =================

    public <T> void withProgressBar(Collection<T> items, Consumer<T> action) {
        var bar = progressBar(items.size());
        for (T item : items) {
            action.accept(item);
            bar.advance();
        }
        bar.finish();
    }

    public ProgressBar progressBar(int total) {
        return new ProgressBar(io, total);
    }

    // ================= 交互 =================

    /**
     * 是/否确认。
     *
     * <p>★ **不可交互时直接返回默认值**(管道、CI、{@code Artisan.call}):
     * 阻塞着等一个永远不会来的回车,表现是「命令挂住了」——
     * 而在 CI 里那是一个要等到超时才失败的任务。
     */
    public boolean confirm(String question, boolean defaultAnswer) {
        String answer = io.readLine(question + (defaultAnswer ? " (Y/n) " : " (y/N) "));
        if (answer == null || answer.isBlank()) return defaultAnswer;
        return answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes");
    }

    public String ask(String question, String defaultAnswer) {
        String answer = io.readLine(question
                + (defaultAnswer == null ? ": " : " [" + defaultAnswer + "]: "));
        return answer == null || answer.isBlank() ? defaultAnswer : answer.trim();
    }

    public String secret(String question) {
        return io.readSecret(question + ": ");
    }

    /**
     * 从若干选项里挑一个。输入不在选项里时**重问**,而不是接受它 ——
     * 「目标环境」这种问题上,接受一个拼错的答案比多问一次糟得多。
     */
    public String choice(String question, List<String> choices, String defaultAnswer) {
        if (choices.isEmpty()) throw new ConsoleException("choice(...) 的选项不能为空");

        for (int attempt = 0; attempt < 3; attempt++) {
            line(question + " [" + String.join(" / ", choices) + "]");
            String answer = io.readLine("> ");
            if (answer == null || answer.isBlank()) return defaultAnswer;
            String trimmed = answer.trim();
            if (choices.contains(trimmed)) return trimmed;
            warn("不是可选项:" + trimmed);
        }
        return defaultAnswer;
    }

    // ================= 调用别的命令 =================

    /** docs/13 §3 的 {@code call("search:reindex")} */
    public int call(String command, String... args) {
        if (registry == null) {
            throw new ConsoleException(name() + " 想调用 " + command
                    + ",但它不是通过 CommandRegistry 运行的(没有注册表可查)");
        }
        return registry.run(command, List.of(args), io);
    }
}
