package io.jaravel.console.generators;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.jaravel.console.ConsoleException;

/**
 * 代码模板(docs/13 §4)。
 *
 * <pre>{@code
 * Stub.of("model").with("pkg", "app.models").with("className", "Post").render()
 * }</pre>
 *
 * <h2>★ 查找顺序:项目的 stubs/ 优先,再回落到 classpath</h2>
 * <p>项目里存在 {@code stubs/model.stub} 时用它 —— 这就是 docs/13 §4.2
 * 的「用户覆盖」:团队加版权头、加审计字段、换包结构约定,
 * 不需要改框架。
 *
 * <p>顺序与 jblade 的视图查找刻意一致(应用目录优先、再 classpath)。
 * 同一件事在两个地方有两种顺序,是个没人记得住的规则。
 *
 * <h2>★ 为什么只认 {@code {{ 变量 }}},不是 jblade</h2>
 * <p>docs/13 §4.1 原本写的是用 jblade 渲染 stub(「自举」)。实现时这条
 * 与模块的头号设计目标冲突:{@code make:*} 是**离线命令**,
 * 要在 100ms 内跑完,而且终将跑在 GraalVM native 的 {@code jaravel-cli} 里
 * —— 那里没有 javac,jte 的运行时编译根本不可用,
 * 而 jblade 的预编译排在 v0.9。
 *
 * <p>于是这里只做变量替换。它够用,因为 docs/13 自己就用
 * 「每个变体一个 stub 文件」({@code controller.resource}、
 * {@code migration.create})来避免模板里的条件分支 ——
 * 真正需要的只有插值。
 *
 * <p>代价:stub 里写不了循环与条件。真需要时的出路是再加一个 stub 文件,
 * 而不是把一个模板引擎塞进离线命令。
 *
 * <h2>没替换掉的变量要报错</h2>
 * <p>模板里写了 {@code {{ tableName }}} 而调用方没给,
 * 静默留下那串字面量的话,生成出来的是一个**编译不过的 Java 文件**,
 * 而报错会指向用户的新文件,与真正的原因(stub 的参数少给了一个)无关。
 */
public final class Stub {

    /** 项目里放覆盖版 stub 的目录(docs/13 §4.2) */
    public static final String PROJECT_DIRECTORY = "stubs";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    private final String name;
    private final Path projectRoot;
    private final Map<String, String> values = new java.util.LinkedHashMap<>();

    private Stub(String name, Path projectRoot) {
        this.name = name;
        this.projectRoot = projectRoot;
    }

    /** {@code name} 不带扩展名:{@code "model"} → {@code stubs/model.stub} */
    public static Stub of(String name) {
        return new Stub(name, Path.of(""));
    }

    public static Stub of(String name, Path projectRoot) {
        return new Stub(name, projectRoot);
    }

    public Stub with(String key, String value) {
        values.put(key, value == null ? "" : value);
        return this;
    }

    public String render() {
        String template = read();
        Matcher matcher = PLACEHOLDER.matcher(template);
        var missing = new ArrayList<String>();
        var out = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                missing.add(key);
                value = matcher.group();   // 原样留着,报错里能看见它
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);

        if (!missing.isEmpty()) {
            throw new ConsoleException("""
                    stub "%s" 里有没给值的变量:%s

                      给的是:%s

                      ⚠ 留着那串字面量的话,生成出来的是一个编译不过的 Java 文件,
                      而 javac 的报错会指向用户的新文件,与真正的原因无关。"""
                    .formatted(name, String.join("、", missing),
                               values.isEmpty() ? "(什么都没给)" : String.join("、", values.keySet())));
        }
        return out.toString();
    }

    private String read() {
        Path override = projectRoot.resolve(PROJECT_DIRECTORY).resolve(name + ".stub");
        if (Files.isRegularFile(override)) {
            try {
                return Files.readString(override, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ConsoleException("读不了 " + override + ":" + e.getMessage(), e);
            }
        }

        String resource = "stubs/" + name + ".stub";
        try (InputStream in = Stub.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ConsoleException("没有 stub \"" + name + "\""
                        + "\n  找过:" + override + " 与 classpath:" + resource
                        + "\n  可用的:" + String.join(" ", bundled()));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConsoleException("读不了 classpath:" + resource + ":" + e.getMessage(), e);
        }
    }

    /**
     * 框架自带的 stub 名单 —— {@code stub:publish} 与「没有这个 stub」
     * 的报错都要它。
     *
     * <p>写成一张**显式的表**而不是扫 jar:扫 classpath 目录在
     * 可执行 jar 与 native 镜像里都不可靠(前者要按 jar 协议遍历,
     * 后者根本没有目录项),而那恰好是这个模块最终要跑的两个环境。
     */
    public static List<String> bundled() {
        return List.of("model", "controller", "controller.resource", "controller.api",
                       "migration.create", "migration.update",
                       "request", "job", "middleware", "event",
                       "listener", "listener.event", "policy", "seeder",
                       "mail", "notification", "command",
                       "test.feature", "test.unit");
    }
}
