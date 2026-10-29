package io.jaravel.view;

import java.util.List;
import java.util.Optional;

/**
 * 模板错误。**必须带上 jblade 文件名与行号** ——
 * {@code docs/04} §2.4 把它列为本模块的头号质量要求:
 * 若错误以生成后的 jte / Java 位置呈现,DX 直接崩塌。
 */
public class JbladeException extends RuntimeException {

    private final String template;
    private final int line;
    private final String sourceSnippet;
    private final String suggestion;

    public JbladeException(String template, int line, String message) {
        this(template, line, message, null, null, null);
    }

    public JbladeException(String template, int line, String message,
                           String sourceSnippet, String suggestion, Throwable cause) {
        super(buildMessage(template, line, message, sourceSnippet, suggestion), cause);
        this.template = template;
        this.line = line;
        this.sourceSnippet = sourceSnippet;
        this.suggestion = suggestion;
    }

    private static String buildMessage(String template, int line, String message,
                                       String snippet, String suggestion) {
        var sb = new StringBuilder();
        sb.append(template);
        if (line > 0) sb.append(':').append(line);
        sb.append("  ").append(message);
        if (snippet != null && !snippet.isBlank()) sb.append('\n').append(snippet);
        if (suggestion != null && !suggestion.isBlank()) sb.append("\n  提示: ").append(suggestion);
        return sb.toString();
    }

    public String template()                 { return template; }
    public int line()                        { return line; }
    public Optional<String> suggestion()     { return Optional.ofNullable(suggestion); }
    public Optional<String> sourceSnippet()  { return Optional.ofNullable(sourceSnippet); }

    /**
     * 渲染出错位置前后几行的源码片段,出错行用 {@code →} 标记。
     * 这是开发期错误页「源码片段」能力的数据来源。
     */
    public static String renderSnippet(String source, int errorLine, int context) {
        if (source == null || errorLine <= 0) return "";
        String[] lines = source.split("\\R", -1);
        int from = Math.max(1, errorLine - context);
        int to = Math.min(lines.length, errorLine + context);

        var sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            boolean isError = i == errorLine;
            sb.append(String.format("  %s %4d │ %s%n", isError ? "→" : " ", i, lines[i - 1]));
        }
        return sb.toString();
    }

    /** 从 javac 的「找不到符号」类错误里提取可操作建议 */
    public static Optional<String> suggestionFor(String javacMessage, List<String> availableNames) {
        if (javacMessage == null) return Optional.empty();

        var m = java.util.regex.Pattern
                .compile("(?:符号|symbol)\\s*:\\s*(?:方法|method|变量|variable)\\s+(\\w+)")
                .matcher(javacMessage);
        if (!m.find()) return Optional.empty();

        String missing = m.group(1);

        // 必须剔除「拼错的名字本身」:候选集里混有模板源码中的标识符,
        // 而拼错的那个词正在其中,距离 0 必然胜出,会给出
        // 「是否想写 titel ?」这种毫无意义的建议。
        List<String> candidates = availableNames.stream()
                .filter(n -> !n.equals(missing))
                .toList();

        return io.jaravel.support.Arr.closest(missing, candidates)
                .map(c -> "是否想写 " + c + " ?");
    }
}
