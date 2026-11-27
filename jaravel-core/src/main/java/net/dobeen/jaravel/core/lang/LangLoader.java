package net.dobeen.jaravel.core.lang;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.dobeen.jaravel.core.config.ConfigLoader;

/**
 * 把 {@code resources/lang} 下的 YAML 读成消息目录。
 *
 * <pre>
 * resources/lang/zh_CN/posts.yml    created: 文章已发布    → posts.created
 * resources/lang/zh_CN/posts.yml    form:
 *                                     title: 标题         → posts.form.title
 * resources/lang/en/posts.yml                             → 同样的键,en 目录
 * </pre>
 *
 * <h2>★ 第一段永远是文件名</h2>
 * <p>与 Laravel 一致。于是「{@code posts.form.title} 这条消息在哪」
 * 不需要全局搜索 —— 它在 {@code posts.yml} 里。
 *
 * <h2>两个来源,顺序固定</h2>
 * <ol>
 *   <li>classpath 上的 {@code lang/}(打进 jar 的那份,也是包提供的那份);</li>
 *   <li>磁盘上的 {@code resources/lang/}(开发期改了立刻生效)。</li>
 * </ol>
 * <p>磁盘后装 → 同名键覆盖 classpath。开发时想临时改一条消息不必重新打包,
 * 而打包后的产物里磁盘那份通常不存在,于是用 jar 内的。
 */
public final class LangLoader {

    private LangLoader() {
    }

    /** 从目录读:{@code <dir>/<locale>/<group>.yml} */
    public static Map<String, Map<String, String>> fromDirectory(Path langRoot) {
        var out = new LinkedHashMap<String, Map<String, String>>();
        if (langRoot == null || !Files.isDirectory(langRoot)) return out;

        try (var locales = Files.list(langRoot)) {
            for (Path localeDir : locales.filter(Files::isDirectory).sorted().toList()) {
                String locale = localeDir.getFileName().toString();
                var messages = new LinkedHashMap<String, String>();

                try (var files = Files.list(localeDir)) {
                    for (Path file : files.filter(LangLoader::isYaml).sorted().toList()) {
                        messages.putAll(readGroup(group(file),
                                Files.readString(file, StandardCharsets.UTF_8),
                                file.toString()));
                    }
                }
                if (!messages.isEmpty()) out.put(locale, messages);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "无法读取语言目录 " + langRoot.toAbsolutePath(), e);
        }
        return out;
    }

    /**
     * 从 classpath 读。
     *
     * <p>要读的 locale 与分组**必须显式给出** —— classpath 上没有
     * 「列出一个目录」这件事(jar 里只能按名字取资源)。调用方通常
     * 把配置里的 locale 与 fallback 传进来。
     */
    public static Map<String, Map<String, String>> fromClasspath(
            ClassLoader loader, List<String> locales, List<String> groups) {

        var out = new LinkedHashMap<String, Map<String, String>>();
        for (String locale : locales) {
            var messages = new LinkedHashMap<String, String>();
            for (String group : groups) {
                String resource = "lang/" + locale + "/" + group + ".yml";
                try (InputStream in = loader.getResourceAsStream(resource)) {
                    if (in == null) continue;
                    messages.putAll(readGroup(group,
                            new String(in.readAllBytes(), StandardCharsets.UTF_8),
                            resource));
                } catch (IOException e) {
                    throw new UncheckedIOException("无法读取 " + resource, e);
                }
            }
            if (!messages.isEmpty()) out.put(locale, messages);
        }
        return out;
    }

    /**
     * classpath 上有哪些分组可读。
     *
     * <p>约定:{@code lang/<locale>/_groups.yml} 里列出分组名。
     * 没有这个文件时返回空 —— 于是「只有磁盘上有语言文件」的开发期
     * 不会因为 classpath 读不到而报错。
     *
     * <p>为什么需要这个约定:jar 里无法枚举目录。替代方案是扫整个
     * classpath 找 {@code lang/**},那在一个有几百个 jar 的应用上
     * 是启动期的明显开销,且会把依赖里同名的资源一起拖进来。
     */
    public static List<String> groupsOnClasspath(ClassLoader loader, String locale) {
        String resource = "lang/" + locale + "/_groups.yml";
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return List.of();
            Map<String, Object> tree = ConfigLoader.parse(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
            Object groups = tree.get("groups");
            if (!(groups instanceof List<?> list)) return List.of();
            var out = new ArrayList<String>();
            for (Object g : list) out.add(String.valueOf(g));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 " + resource, e);
        }
    }

    // ================= 内部 =================

    private static boolean isYaml(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p)
                && (name.endsWith(".yml") || name.endsWith(".yaml"))
                // _groups.yml 是给 classpath 用的索引,不是消息
                && !name.startsWith("_");
    }

    private static String group(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** 一个分组文件 → 扁平的「group.a.b → 消息」 */
    private static Map<String, String> readGroup(String group, String yaml, String source) {
        Map<String, Object> tree = ConfigLoader.parse(yaml, source);
        var out = new LinkedHashMap<String, String>();
        flatten(group, tree, out);
        return out;
    }

    private static void flatten(String prefix, Map<String, Object> tree,
                                Map<String, String> out) {
        tree.forEach((key, value) -> {
            String full = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                var child = new LinkedHashMap<String, Object>();
                nested.forEach((k, v) -> child.put(String.valueOf(k), v));
                flatten(full, child, out);
            } else if (value instanceof List<?> items) {
                // 列表按下标寻址:choices.0 / choices.1。
                // Laravel 没有这个,但 YAML 里写选项列表太自然了,
                // 而不支持的表现是「那条消息整个取不到」
                for (int i = 0; i < items.size(); i++) {
                    out.put(full + "." + i, String.valueOf(items.get(i)));
                }
            } else if (value != null) {
                out.put(full, String.valueOf(value));
            }
        });
    }
}
