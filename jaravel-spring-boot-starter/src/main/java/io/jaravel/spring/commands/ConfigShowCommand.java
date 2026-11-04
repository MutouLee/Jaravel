package io.jaravel.spring.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.core.config.ConfigRepository;

/**
 * {@code jaravel config:show database}(docs/13 §2.5)。
 *
 * <pre>
 * database.default                     sqlite
 * database.connections.sqlite.url      jdbc:sqlite:storage/blog.db
 * database.connections.sqlite.password  ********
 * </pre>
 *
 * <h2>★ 它要回答的问题:这个键**最后**是什么值</h2>
 * <p>看 yml 看不出来。一个值要经过 {@code ${ENV:默认值}} 解析、
 * 多个配置源的叠加,才变成应用真正读到的东西。
 * 「为什么我改了配置不生效」一半的答案都在这一步 ——
 * 而上一轮查 {@code examples/blog} 起不来时,缺的正是这条命令。
 *
 * <h2>★ 默认打码,因为这条命令能跑在生产机器上</h2>
 * <p>它通过命令通道、也通过冷启动启动器可达 —— 也就是说
 * {@code jaravel config:show database} 可能在一台生产机器上执行,
 * 而输出可能落进 CI 日志。**密码进日志是不可逆的**:
 * 日志会被归档、被检索、被转发,而密码一旦进去就得当作已泄露。
 *
 * <p>所以敏感键默认打码,要看真值得显式 {@code --show-secrets};
 * 生产环境下那个开关还要过一道确认。打码的判据只有一份
 * ({@link ConfigRepository#isSensitive}),理由写在那里。
 *
 * <h2>为什么用点号键而不是缩进树</h2>
 * <p>树更好看,但点号键是**可以直接用的**:
 * 复制 {@code database.connections.sqlite.url} 就能贴进
 * {@code config.string(...)} 或下一条 {@code config:show}。
 * 而这条命令的使用场景正是「我要去代码里读这个键」。
 *
 * <h2>没解析掉的占位符会被点出来</h2>
 * <p>环境变量没设且没有默认值时,{@code interpolate} **保留原样**
 * (那是故意的,好让 {@code doctor} 能发现)。所以这里把
 * 还剩 {@code ${...}} 的值标成「⚠ 未解析」—— 一个显示成
 * {@code ${DB_PASSWORD}} 的密码意味着应用正拿着那个字面量去连库,
 * 而报错会是「认证失败」,离真正的原因(环境变量没设)很远。
 */
@Command(name = "config:show", description = "显示某一段配置解析后的最终值")
public final class ConfigShowCommand extends RuntimeCommand {

    private final ConfigRepository config;

    @Argument(description = "配置键或前缀,如 database / database.default", required = false)
    String key;

    @Option(names = "--show-secrets", description = "不打码(生产环境下要确认)")
    boolean showSecrets;

    public ConfigShowCommand(ConfigRepository config) {
        this.config = config;
    }

    @Override
    public int handle() {
        if (key == null || key.isBlank()) {
            return listSections();
        }
        if (showSecrets && !confirmInProduction("把**明文密钥**打印到这个终端(以及可能的日志里)")) {
            return 1;
        }

        List<String> paths = pathsUnder(key);
        if (paths.isEmpty()) {
            return missing();
        }

        int unresolved = 0;
        comment(key);
        for (String path : paths) {
            Object value = config.raw(path).orElse(null);
            String shown = render(path, value);
            if (isUnresolved(value)) {
                unresolved++;
                shown = shown + "   ⚠ 未解析";
            }
            twoColumnDetail(path, shown);
        }

        newLine();
        line(paths.size() + " 项" + (masked(paths) > 0 && !showSecrets
                ? ",其中 " + masked(paths) + " 项已打码(--show-secrets 看真值)" : ""));
        if (unresolved > 0) {
            newLine();
            failure(unresolved + " 项里还有 ${...} 没解析 —— 对应的环境变量没设,"
                    + "而且那个键没有默认值。");
            line("""
                      应用现在就拿着那个字面量在用(比如把 "${DB_PASSWORD}" 当密码),
                      所以报错会是「认证失败」之类,离真正的原因很远。
                      修复:在 .env 里把那几个变量补上。""");
        }
        return 0;
    }

    /**
     * 不给键时**不**打印全部配置。
     *
     * <p>全量输出有几百行,而且里面什么都有 —— 一个「什么都给你」的默认行为
     * 在一条可能跑在生产机器上的命令里不是好默认。列出有哪些段,
     * 让下一步是明确的。
     */
    private int listSections() {
        var sections = new TreeMap<String, Integer>();
        config.all().keySet().forEach(dotted -> {
            String head = dotted.contains(".") ? dotted.substring(0, dotted.indexOf('.')) : dotted;
            sections.merge(head, 1, Integer::sum);
        });

        if (sections.isEmpty()) {
            warn("一条配置都没有 —— config/*.yml 读到了吗?");
            return 1;
        }

        comment("配置段(传一个进来看它的值)");
        sections.forEach((name, count) -> twoColumnDetail(name, count + " 项"));
        newLine();
        line("例:config:show " + sections.firstKey());
        return 0;
    }

    private int missing() {
        failure("没有 " + key + " 这个配置键(也没有以它开头的键)。");
        newLine();

        List<String> near = config.all().keySet().stream()
                .filter(k -> k.contains(key) || key.contains(k))
                .limit(5).toList();
        if (!near.isEmpty()) {
            line("有点像的:");
            near.forEach(k -> line("  " + k));
        } else {
            line("config:show 不带参数可以列出有哪些配置段。");
        }
        return 1;
    }

    /**
     * {@code key} 本身是个叶子就只有它;是前缀就是它下面所有的叶子。
     *
     * <p>键的集合取自 {@link ConfigRepository#all()}(它已经扁平化),
     * 但**值不从那里拿** —— {@code all()} 返回的是配置树里的原样值,
     * 没有过 {@code ${...}} 解析。值要走 {@code raw(path)}。
     *
     * <p>这个区别正是这条命令的全部意义所在:拿 {@code all()} 的值直接打,
     * 显示的会是 {@code ${DB_URL:jdbc:sqlite:…}} —— 一个**看起来像配置文件**
     * 的输出,而用户要的恰恰是「文件之后变成了什么」。
     */
    private List<String> pathsUnder(String prefix) {
        var paths = new ArrayList<String>();
        for (String candidate : new TreeMap<>(config.all()).keySet()) {
            if (candidate.equals(prefix) || candidate.startsWith(prefix + ".")) {
                paths.add(candidate);
            }
        }
        return paths;
    }

    private String render(String path, Object value) {
        if (!showSecrets && ConfigRepository.isSensitive(path)) return ConfigRepository.MASK;
        if (value == null) return "(null)";
        if (value instanceof List<?> items) {
            return items.isEmpty() ? "(空列表)" : String.valueOf(items);
        }
        if (value instanceof Map<?, ?> map) return "(" + map.size() + " 项子配置)";
        String text = String.valueOf(value);
        return text.isEmpty() ? "(空字符串)" : text;
    }

    private boolean isUnresolved(Object value) {
        return value instanceof String text && text.contains("${");
    }

    private long masked(List<String> paths) {
        return paths.stream().filter(ConfigRepository::isSensitive).count();
    }
}
