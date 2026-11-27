package net.dobeen.jaravel.console;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行参数 → 命令对象的字段。
 *
 * <pre>
 * make:model Post -m --table=posts
 *   → @Argument String name = "Post"
 *     @Option  boolean migration = true
 *     @Option  String  table = "posts"
 * </pre>
 *
 * <h2>支持的写法</h2>
 * <ul>
 *   <li>{@code --table=posts} 与 {@code --table posts};</li>
 *   <li>{@code -m}、{@code --force}(boolean 字段,出现即 true);</li>
 *   <li>{@code -mcr} —— **合并的短开关**,等价于 {@code -m -c -r};</li>
 *   <li>其余按顺序填进 {@code @Argument} 字段。</li>
 * </ul>
 *
 * <h2>★ 不认识的选项要报错</h2>
 * <p>{@code make:model Post --migrations}(多一个 s)静默忽略的话,
 * 症状是「迁移没生成」而命令**退出码为 0** —— 于是脚本里它是成功的。
 * artisan 也在这件事上报错,理由相同。
 *
 * <h2>为什么不用 picocli</h2>
 * <p>docs/13 §6 里 picocli 是给 {@code jaravel-cli}(native 二进制)的。
 * 这里是**应用进程内**的命令内核,它的参数来自 {@code Artisan.call(...)}
 * 或 CLI 转发过来的字符串数组 —— 为这点需求把一个库拉进每个应用的
 * 运行时依赖不值得。两边的注解名字刻意取得一样,
 * 将来要换也只是换绑定实现。
 */
final class ArgumentBinder {

    private ArgumentBinder() {
    }

    static void bind(ConsoleCommand command, List<String> args) {
        List<Field> arguments = new ArrayList<>();
        Map<String, Field> options = new LinkedHashMap<>();
        collect(command.getClass(), arguments, options);

        var positionals = new ArrayList<String>();

        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);

            if (token.equals("--")) {                       // 之后的一切都是位置参数
                positionals.addAll(args.subList(i + 1, args.size()));
                break;
            }
            if (!token.startsWith("-") || token.length() == 1) {
                positionals.add(token);
                continue;
            }

            // 合并的短开关:-mcr
            if (!token.startsWith("--") && token.length() > 2
                    && options.get("-" + token.charAt(1)) != null
                    && isFlag(options.get("-" + token.charAt(1)))) {
                for (char c : token.substring(1).toCharArray()) {
                    Field flag = require(options, "-" + c, command, token);
                    set(command, flag, "true");
                }
                continue;
            }

            String name = token;
            String value = null;
            int equals = token.indexOf('=');
            if (equals > 0) {
                name = token.substring(0, equals);
                value = token.substring(equals + 1);
            }

            Field field = require(options, name, command, token);
            if (isFlag(field)) {
                set(command, field, value == null ? "true" : value);
                continue;
            }
            if (value == null) {
                // --table posts 这种分开写的
                if (i + 1 >= args.size()) {
                    throw new ConsoleException(
                            "选项 " + name + " 需要一个值,如 " + name + "=posts");
                }
                value = args.get(++i);
            }
            set(command, field, value);
        }

        fillArguments(command, arguments, positionals);
    }

    /**
     * 字段扫描交给 {@link Usage} —— 那边本来就要为 help 扫一遍。
     *
     * <p>★ 两边各扫一遍的后果是**它们会对「有哪些选项」持不同看法**:
     * help 里列出的与绑定时认的不是一套。于是「文档里写着的选项报不认识」
     * 或者反过来,而两种都不容易查 —— 各自看自己那半边都是对的。
     *
     * <p>顺带去掉了一个把「这个字段有默认值」塞进同一张 map 的前缀键:
     * 它需要一个不可能与选项名相撞的前缀,而那个前缀当时是一个**写进源文件的
     * 控制字符**(0x00)—— grep 会把整个文件当成二进制。默认值现在直接从
     * 注解上读,那张 map 只存真实的选项名。
     */
    private static void collect(Class<?> type, List<Field> arguments, Map<String, Field> options) {
        for (Field field : Usage.argumentsOf(type)) {
            field.setAccessible(true);
            arguments.add(field);
        }
        Usage.optionsOf(type).forEach((field, names) -> {
            field.setAccessible(true);
            names.forEach(name -> options.put(name, field));
        });
    }

    private static void fillArguments(ConsoleCommand command, List<Field> arguments,
                                      List<String> positionals) {
        for (int i = 0; i < arguments.size(); i++) {
            Field field = arguments.get(i);
            if (i < positionals.size()) {
                set(command, field, positionals.get(i));
                continue;
            }
            if (field.getAnnotation(Argument.class).required()) {
                // 用法从注解生成,不手写 —— 手写的那行与真实参数表之间没有关联,
                // 加一个参数不会让它跟着变,而一条说错了用法的报错比没有报错更费时间
                throw new ConsoleException("缺少参数 <" + field.getName() + ">\n\n"
                        + Usage.of(command));
            }
        }
        if (positionals.size() > arguments.size()) {
            throw new ConsoleException("多余的参数:"
                    + String.join(" ", positionals.subList(arguments.size(), positionals.size()))
                    + "\n  " + command.name() + " 只接受 " + arguments.size() + " 个位置参数");
        }
    }

    static void applyDefaults(ConsoleCommand command) {
        Usage.optionsOf(command.getClass()).forEach((field, names) -> {
            String defaultValue = field.getAnnotation(Option.class).defaultValue();
            if (defaultValue.isEmpty()) return;
            field.setAccessible(true);
            set(command, field, defaultValue);
        });
    }

    private static Field require(Map<String, Field> options, String name,
                                 ConsoleCommand command, String token) {
        Field field = options.get(name);
        if (field != null) return field;

        String known = options.keySet().stream()
                .sorted()
                .reduce((a, b) -> a + " " + b)
                .orElse("(没有选项)");
        throw new ConsoleException("""
                %s 不认识选项 %s

                  可用的选项:%s

                  ⚠ 静默忽略它的话,症状是「那件事没发生」而退出码是 0 ——
                  于是脚本里这条命令是成功的。""".formatted(command.name(), token, known));
    }

    private static boolean isFlag(Field field) {
        return field.getType() == boolean.class || field.getType() == Boolean.class;
    }

    private static void set(ConsoleCommand command, Field field, String raw) {
        try {
            field.set(command, convert(field, raw));
        } catch (IllegalAccessException e) {
            throw new ConsoleException("无法写入 " + field.getName() + ":" + e.getMessage());
        }
    }

    private static Object convert(Field field, String raw) {
        Class<?> type = field.getType();
        if (type == String.class) return raw;
        if (type == boolean.class || type == Boolean.class) {
            return !raw.equalsIgnoreCase("false") && !raw.equals("0");
        }
        try {
            if (type == int.class || type == Integer.class) return Integer.parseInt(raw.trim());
            if (type == long.class || type == Long.class)   return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConsoleException("--" + field.getName() + " 需要一个整数,收到 \"" + raw + "\"");
        }
        throw new ConsoleException("不支持的参数类型 " + type.getSimpleName()
                + "(字段 " + field.getName() + ")—— 目前支持 String / boolean / int / long");
    }
}
