package io.jaravel.console;

/**
 * 进度条(docs/13 §3.1)。
 *
 * <pre>{@code
 * var bar = progressBar(items.size());
 * for (var item : items) { work(item); bar.advance(); }
 * bar.finish();
 * }</pre>
 *
 * <h2>★ 不可交互时只打印里程碑,不打回车覆盖</h2>
 * <p>进度条靠回车符({@code \r})在同一行上重画。被重定向到文件或 CI
 * 日志里时那不成立 —— 每次重画都会变成**新的一行**,于是一个
 * 10000 条的任务在 CI 日志里留下 10000 行进度。
 *
 * <p>所以没有彩色能力(也就意味着没有真终端)时改成每 10% 打一行。
 * 用 {@code supportsColor()} 代表「是不是真终端」是个近似,
 * 但它与进度条真正依赖的那件事(回车重画有效)来自同一个判断。
 */
public final class ProgressBar {

    private static final int WIDTH = 30;

    private final ConsoleIo io;
    private final int total;
    private final boolean redraw;
    private int current;
    private int lastMilestone = -1;

    ProgressBar(ConsoleIo io, int total) {
        this.io = io;
        this.total = Math.max(0, total);
        this.redraw = io.supportsColor();
    }

    public void advance() {
        advance(1);
    }

    public void advance(int step) {
        current = Math.min(total, current + step);
        draw();
    }

    public void finish() {
        current = total;
        draw();
        io.write("\n");
    }

    public int current() {
        return current;
    }

    private void draw() {
        int percent = total == 0 ? 100 : (int) Math.round(current * 100.0 / total);

        if (!redraw) {
            int milestone = percent / 10;
            if (milestone == lastMilestone) return;
            lastMilestone = milestone;
            io.write(current + "/" + total + " (" + percent + "%)\n");
            return;
        }

        int filled = total == 0 ? WIDTH : (int) Math.round(current * (double) WIDTH / total);
        io.write("\r[" + "=".repeat(filled) + " ".repeat(WIDTH - filled) + "] "
                 + current + "/" + total + " (" + percent + "%)");
    }
}
