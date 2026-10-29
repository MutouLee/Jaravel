package io.jaravel.eloquent.apt;

/**
 * 缩进感知的源码拼接器。
 *
 * <p>不用 JavaPoet:多一个依赖、而生成的代码形态是固定的几种,
 * 手写模板反而更容易对着预期输出调。缩进自己管是因为生成的代码
 * **会被用户读到**(报错指向生成文件、IDE 跳转进来),排版不能乱。
 */
final class Code {

    private final StringBuilder sb = new StringBuilder(4096);
    private int indent;

    Code line(String text) {
        if (!text.isEmpty()) sb.append("    ".repeat(indent)).append(text);
        sb.append('\n');
        return this;
    }

    Code line(String format, Object... args) {
        return line(format.formatted(args));
    }

    Code blank() { return line(""); }

    Code open(String text) {
        line(text);
        indent++;
        return this;
    }

    Code close(String text) {
        indent--;
        return line(text);
    }

    Code close() { return close("}"); }

    /** 多行注释,逐行加缩进 */
    Code doc(String... lines) {
        line("/**");
        for (String l : lines) line(" * " + l);
        return line(" */");
    }

    @Override public String toString() { return sb.toString(); }

    /** Java 字符串字面量 */
    static String str(String value) {
        if (value == null) return "null";
        var out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** {@code "a", "b"} —— 可变参数的实参列表 */
    static String strList(java.util.Collection<String> values) {
        return values.stream().map(Code::str).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
