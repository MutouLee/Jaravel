package io.jaravel.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 异常位置的源码片段(docs/15 §4.1 的第一项)。
 *
 * <p>Laravel 的 Ignition 之所以好用,一半原因是它直接把出错那一行
 * 连同上下文显示出来 —— 不需要切回编辑器、找文件、数行号。
 *
 * <h2>只在开发模式、只读应用自己的源码</h2>
 * <p>生产环境里源码通常不在,而就算在也不该显示 —— 源码片段可能包含
 * 注释里的内部信息。所以本类只被开发期错误页调用,且只在
 * {@link #SOURCE_ROOTS} 列出的目录下找文件。
 *
 * <p>不去三方 jar 里找源码:那需要 sources jar,而即使找到了,
 * 展示 Spring 的内部实现对排查业务问题几乎没有帮助 ——
 * 真正有用的是**应用自己**的那一帧。
 */
public final class SourceSnippet {

    /** 在哪找源码 */
    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java", "src/test/java", "src/main/resources");

    /** 出错行的前后各几行 */
    private static final int CONTEXT_LINES = 5;

    private SourceSnippet() {
    }

    /**
     * 取某一帧的源码片段。
     *
     * @return 找不到源文件(三方代码、生成的类、已打包)时为空
     */
    public static Optional<Snippet> forFrame(StackTraceElement frame) {
        if (frame.getFileName() == null || frame.getLineNumber() <= 0) {
            return Optional.empty();
        }

        return locate(frame).flatMap(file -> read(file, frame.getLineNumber()));
    }

    /**
     * 取**第一个应用帧**的源码片段。
     *
     * <p>这是错误页真正要显示的那一个:栈顶往往在框架里
     * (是框架检测到问题并抛的异常),而用户要看的是自己的代码。
     */
    public static Optional<Snippet> forFirstApplicationFrame(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (isApplicationFrame(frame)) {
                    var snippet = forFrame(frame);
                    if (snippet.isPresent()) return snippet;
                }
            }
            if (current.getCause() == current) break;
        }
        return Optional.empty();
    }

    /**
     * 是不是应用自己的代码。
     *
     * <p>按**排除法**:不是框架、不是 JDK、不是已知三方。
     * 正向白名单(「包名以 app. 开头」)做不到 —— 用户的包名可以是任何东西。
     */
    public static boolean isApplicationFrame(StackTraceElement frame) {
        String cls = frame.getClassName();
        return !(cls.startsWith("io.jaravel.")
              || cls.startsWith("org.springframework.")
              || cls.startsWith("java.") || cls.startsWith("javax.")
              || cls.startsWith("jdk.") || cls.startsWith("sun.")
              || cls.startsWith("jakarta.")
              || cls.startsWith("gg.jte.") || cls.startsWith("org.apache.")
              || cls.startsWith("com.fasterxml.")
              || cls.startsWith("com.zaxxer.")
              || cls.startsWith("org.junit.") || cls.startsWith("org.assertj."));
    }

    // ================= 内部 =================

    private static Optional<Path> locate(StackTraceElement frame) {
        String fileName = frame.getFileName();

        // 由类名推包路径。用 getClassName 而不是 getFileName:
        // 后者只有文件名,而同名文件在不同包里很常见(每个包一个 Routes.java)
        String className = frame.getClassName();
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot < 0 ? "" : className.substring(0, lastDot).replace('.', '/');

        for (String root : SOURCE_ROOTS) {
            Path candidate = packagePath.isEmpty()
                    ? Path.of(root, fileName)
                    : Path.of(root, packagePath, fileName);
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static Optional<Snippet> read(Path file, int lineNumber) {
        try {
            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lineNumber > allLines.size()) {
                // 源码比运行中的 class 新(改了但没重新编译)。
                // 显示一个错位的片段比不显示更糟 —— 它会把人引向错误的那一行
                return Optional.empty();
            }

            int from = Math.max(1, lineNumber - CONTEXT_LINES);
            int to = Math.min(allLines.size(), lineNumber + CONTEXT_LINES);

            var lines = new ArrayList<Line>();
            for (int i = from; i <= to; i++) {
                lines.add(new Line(i, allLines.get(i - 1), i == lineNumber));
            }
            return Optional.of(new Snippet(file.toString(), lineNumber, lines));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * @param file        相对路径,直接用于编辑器跳转
     * @param highlighted 出错的行号
     */
    public record Snippet(String file, int highlighted, List<Line> lines) {

        /**
         * 编辑器跳转链接。
         *
         * <p>{@code idea://open?file=...&line=...} 需要绝对路径 ——
         * 相对路径对 IDE 没有意义(它不知道以哪为基准)。
         */
        public String editorUrl() {
            String absolute = Path.of(file).toAbsolutePath().toString().replace('\\', '/');
            return "idea://open?file=" + absolute + "&line=" + highlighted;
        }

        public String vscodeUrl() {
            String absolute = Path.of(file).toAbsolutePath().toString().replace('\\', '/');
            return "vscode://file/" + absolute + ":" + highlighted;
        }
    }

    public record Line(int number, String text, boolean highlighted) {
    }
}
