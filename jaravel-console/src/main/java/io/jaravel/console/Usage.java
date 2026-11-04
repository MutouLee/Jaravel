package io.jaravel.console;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@code @Argument} / {@code @Option} 读出一条命令的用法。
 *
 * <pre>
 * 用法:make:model &lt;name&gt; [选项]
 *
 *   新建一个 Eloquent 模型
 *
 * 参数:
 *   &lt;name&gt;             模型类名,如 Post
 *
 * 选项:
 *   -m, --migration    同时生成建表迁移
 *       --table=值     表名。默认由类名推断(Post → posts)
 * </pre>
 *
 * <h2>★ 一处生成,三处用</h2>
 * <p>{@code jaravel help make:model}、{@code jaravel list}、
 * 以及**参数错误时的那条报错**都走这里。
 *
 * <p>原先「缺少参数」的报错是手写的一行
 * ({@code "用法:" + name() + " <" + field + ">"}),它与真实的参数表
 * 之间没有任何关联 —— 加一个参数不会让那行跟着变,
 * 而一条**说错了用法的报错**比没有报错更费时间。
 */
public final class Usage {

    private Usage() {
    }

    /** 一行:{@code make:model <name> [选项]} —— 给 list 用 */
    public static String synopsis(ConsoleCommand command) {
        var line = new StringBuilder(command.name());
        for (Field field : argumentsOf(command.getClass())) {
            boolean required = field.getAnnotation(Argument.class).required();
            line.append(required ? " <" : " [")
                .append(field.getName())
                .append(required ? '>' : ']');
        }
        if (!optionsOf(command.getClass()).isEmpty()) line.append(" [选项]");
        return line.toString();
    }

    /** 完整用法 —— 给 help 与报错用 */
    public static String of(ConsoleCommand command) {
        var out = new StringBuilder("用法:").append(synopsis(command)).append('\n');

        if (!command.description().isEmpty()) {
            out.append('\n').append("  ").append(command.description()).append('\n');
        }

        List<Field> arguments = argumentsOf(command.getClass());
        if (!arguments.isEmpty()) {
            out.append("\n参数:\n");
            for (Field field : arguments) {
                Argument argument = field.getAnnotation(Argument.class);
                out.append(row("<" + field.getName() + ">",
                        argument.description() + (argument.required() ? "" : "(可选)")));
            }
        }

        Map<Field, List<String>> options = optionsOf(command.getClass());
        if (!options.isEmpty()) {
            out.append("\n选项:\n");
            options.forEach((field, names) -> {
                Option option = field.getAnnotation(Option.class);
                out.append(row(flags(field, names), description(option)));
            });
        }
        return out.toString();
    }

    private static String description(Option option) {
        String text = option.description();
        if (!option.defaultValue().isEmpty()) {
            text = (text.isEmpty() ? "" : text + " ") + "(默认 " + option.defaultValue() + ")";
        }
        return text;
    }

    /** {@code -m, --migration} / {@code     --table=值} */
    private static String flags(Field field, List<String> names) {
        var shortFlags = names.stream().filter(n -> !n.startsWith("--")).toList();
        var longFlags = names.stream().filter(n -> n.startsWith("--")).toList();
        boolean takesValue = field.getType() != boolean.class && field.getType() != Boolean.class;

        var line = new StringBuilder();
        line.append(shortFlags.isEmpty() ? "    " : String.join(", ", shortFlags) + ", ");
        line.append(String.join(", ", longFlags));
        if (takesValue) line.append("=值");
        return line.toString();
    }

    /** 左列对齐。宽度按**显示宽度**算 —— 说明里有中文 */
    private static String row(String left, String right) {
        int pad = Math.max(1, 22 - ConsoleCommand.displayWidth(left));
        return "  " + left + " ".repeat(pad) + right + "\n";
    }

    // ================= 注解扫描 =================

    /**
     * 按**声明顺序**。父类的字段在前 —— 基类上的 {@code --force}
     * 之类应当排在子类自己的参数之前,与 {@code ArgumentBinder} 的
     * 收集顺序一致(两边不一致的话,用法里显示的顺序会与实际绑定的不同)。
     */
    static List<Field> argumentsOf(Class<?> type) {
        var out = new ArrayList<Field>();
        if (type.getSuperclass() != null) out.addAll(argumentsOf(type.getSuperclass()));
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Argument.class)) out.add(field);
        }
        return out;
    }

    static Map<Field, List<String>> optionsOf(Class<?> type) {
        var out = new LinkedHashMap<Field, List<String>>();
        if (type.getSuperclass() != null) out.putAll(optionsOf(type.getSuperclass()));
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Option.class)) {
                out.put(field, List.of(field.getAnnotation(Option.class).names()));
            }
        }
        return out;
    }
}
