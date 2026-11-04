package io.jaravel.console;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条命令在「一个字符串」与「一串参数」之间的来回(docs/13 §1)。
 *
 * <pre>
 * ["migrate", "--path=db/x y"]  ──join──→  migrate "--path=db/x y"
 *                              ──split──→  ["migrate", "--path=db/x y"]
 * </pre>
 *
 * <h2>★ 为什么需要它:启动器要把参数塞进一个系统属性</h2>
 * <p>冷启动那条路({@code jaravel migrate} → 新起一个应用 JVM)靠
 * {@code -Djaravel.command=...} 把命令传过去。系统属性只能是**一个字符串**,
 * 所以参数要先拼起来、在那一侧再拆开。
 *
 * <p>拼接与拆分放在同一个类里,是为了让它们的约定只有一份 ——
 * 两边各写一个解析器的话,第一个带空格的参数就会错位,
 * 而那种错位的症状是「命令跑了,但选项莫名其妙没生效」。
 * 有一条测试拿**最难缠的参数**核对 {@code split(join(args)) == args}。
 *
 * <h2>为什么不用 shell 的那套规则</h2>
 * <p>只支持双引号与 {@code \"},不支持单引号、不做变量展开、不认通配符。
 * 这里不是 shell:参数已经被真正的 shell 解析过一轮了,
 * 这一层只需要**无损地过一道**。多认一种语法就多一种
 * 「我的参数里有个 $ 号,结果变了」。
 *
 * <p>人也会手写这个属性(今天就是进 tinker 的那条路),
 * 所以双引号必须认 —— {@code -Djaravel.command="migrate --force"}
 * 里那层引号是 shell 吃掉的,但
 * {@code -Djaravel.command='db:seed --class="BlogSeeder"'} 里的不是。
 */
public final class CommandLine {

    private CommandLine() {
    }

    /**
     * 冷启动那条路传命令用的系统属性名。
     *
     * <p>★ 它和 {@link #join}/{@link #split} 放在一起,是因为
     * **名字和格式是同一个约定**:CLI 那边拼、应用那边拆。
     * 两处各写一个字符串字面量的话,改名字时只改一边 ——
     * 而症状是「启动器起了一个 JVM,那个 JVM 什么也没干就退出了」,
     * 因为条件 bean 根本没装上,没有任何报错。
     *
     * <p>{@code jaravel-cli} 与 {@code jaravel-spring-boot-starter}
     * 之间没有依赖(ADR-001 不允许),而它们都依赖 {@code jaravel-console} ——
     * 所以这里是唯一能放下这个常量的地方。
     */
    public static final String COMMAND_PROPERTY = "jaravel.command";

    /** 拆成一串参数。多余的空白被吃掉;未闭合的引号按「到行尾」处理 */
    public static List<String> split(String line) {
        var args = new ArrayList<String>();
        if (line == null) return args;

        var current = new StringBuilder();
        boolean quoted = false;
        boolean started = false;   // 区分「空参数 ""」与「还没开始」

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                current.append('"');
                started = true;
                i++;
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                started = true;      // "" 是一个合法的空参数
                continue;
            }
            if (!quoted && Character.isWhitespace(c)) {
                if (started) {
                    args.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }
            current.append(c);
            started = true;
        }
        // 未闭合的引号不报错:这个字符串常常是人手敲的,
        // 而「按到行尾处理」是唯一不会丢字符的选择
        if (started) args.add(current.toString());
        return args;
    }

    /** 拼成一个字符串。只给**需要**的参数加引号 —— 加满了没法读 */
    public static String join(List<String> args) {
        var out = new StringBuilder();
        for (String arg : args) {
            if (!out.isEmpty()) out.append(' ');
            out.append(quote(arg));
        }
        return out.toString();
    }

    private static String quote(String arg) {
        boolean needed = arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace)
                || arg.indexOf('"') >= 0 || arg.indexOf('\\') >= 0;
        if (!needed) return arg;
        return '"' + arg.replace("\"", "\\\"") + '"';
    }
}
