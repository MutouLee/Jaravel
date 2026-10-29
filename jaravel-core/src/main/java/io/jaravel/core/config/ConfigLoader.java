package io.jaravel.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import io.jaravel.support.Arr;

/**
 * 加载 {@code config/*.yml} 并合并为一棵配置树。
 *
 * <p>每个文件的内容按原样合并到根(即 {@code config/app.yml} 里写 {@code app:} 顶层键),
 * 与 Laravel 的 {@code config/app.php} 对应到 {@code config('app.name')} 一致。
 *
 * <p>YAML 语法错误会带上**文件名与行号**抛出 —— 配置错误是开发期最常见的问题之一,
 * 报错必须能直接定位。
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** 从目录加载全部 *.yml / *.yaml,按文件名字典序合并(保证可重现) */
    public static Map<String, Object> fromDirectory(Path dir) {
        Map<String, Object> tree = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return tree;

        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String n = p.getFileName().toString();
                      return n.endsWith(".yml") || n.endsWith(".yaml");
                  })
                  .sorted()
                  .forEach(files::add);
        } catch (IOException e) {
            throw new ConfigRepository.MissingConfigException(
                    "无法列出配置目录 " + dir.toAbsolutePath() + ":" + e.getMessage());
        }

        for (Path f : files) {
            try {
                String content = Files.readString(f, StandardCharsets.UTF_8);
                tree = Arr.mergeDeep(tree, parse(content, f.getFileName().toString()));
            } catch (IOException e) {
                throw new ConfigRepository.MissingConfigException(
                        "读取配置文件 " + f + " 失败:" + e.getMessage());
            }
        }
        return tree;
    }

    /** 从 classpath 下的 config/ 加载(打进 jar 后的生产路径) */
    public static Map<String, Object> fromClasspath(ClassLoader loader, List<String> names) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (String name : names) {
            String resource = "config/" + name;
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) continue;
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                tree = Arr.mergeDeep(tree, parse(content, resource));
            } catch (IOException e) {
                throw new ConfigRepository.MissingConfigException(
                        "读取 classpath 配置 " + resource + " 失败:" + e.getMessage());
            }
        }
        return tree;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String yaml, String sourceName) {
        if (yaml == null || yaml.isBlank()) return new LinkedHashMap<>();
        try {
            Object loaded = new Yaml().load(yaml);
            if (loaded == null) return new LinkedHashMap<>();
            if (!(loaded instanceof Map)) {
                throw new ConfigRepository.MissingConfigException(
                        sourceName + " 的顶层必须是键值映射,实际是 "
                        + loaded.getClass().getSimpleName());
            }
            return normalizeKeys((Map<Object, Object>) loaded);
        } catch (MarkedYAMLException e) {
            var mark = e.getProblemMark();
            throw new ConfigRepository.MissingConfigException(
                    sourceName + (mark != null ? ":" + (mark.getLine() + 1) : "")
                    + " YAML 语法错误:" + e.getProblem()
                    + (mark != null ? "\n" + mark.get_snippet() : ""));
        }
    }

    /** 键统一为 String;snakeyaml 会把 {@code 3306: x} 的键解析为 Integer */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeKeys(Map<Object, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (v instanceof Map<?, ?> m) {
                out.put(key, normalizeKeys((Map<Object, Object>) m));
            } else if (v instanceof List<?> list) {
                List<Object> items = new ArrayList<>(list.size());
                for (Object o : list) {
                    items.add(o instanceof Map<?, ?> m2 ? normalizeKeys((Map<Object, Object>) m2) : o);
                }
                out.put(key, items);
            } else {
                out.put(key, v);
            }
        });
        return out;
    }
}
