package io.jaravel.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code .env} 加载器。
 *
 * <p>优先级(高 → 低),前者**不被**后者覆盖:
 * <pre>
 *   1. 环境变量 / 系统属性
 *   2. .env.local
 *   3. .env.{profile}
 *   4. .env
 * </pre>
 *
 * <p>语法:{@code KEY=value}、{@code KEY="quoted value"}、{@code # 注释}、
 * {@code export KEY=value}、{@code KEY=${OTHER}} 变量引用。
 */
public final class DotEnv {

    private DotEnv() {}

    /** 按优先级加载并合并。返回的 Map 中不含被环境变量覆盖的项。 */
    public static Map<String, String> load(Path dir, String profile) {
        Map<String, String> merged = new LinkedHashMap<>();

        // 从低优先级到高优先级依次 putIfAbsent 的反向:先加载高优先级
        List<String> files = profile == null || profile.isBlank()
                ? List.of(".env.local", ".env")
                : List.of(".env.local", ".env." + profile, ".env");

        for (String f : files) {
            Path p = dir.resolve(f);
            if (!Files.isRegularFile(p)) continue;
            parse(read(p)).forEach(merged::putIfAbsent);   // 先到者优先
        }

        // 展开 .env 内部的 ${OTHER} 引用
        Map<String, String> out = new LinkedHashMap<>();
        merged.forEach((k, v) -> out.put(k, expand(v, merged)));
        return out;
    }

    public static Map<String, String> parse(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null) return out;

        for (String rawLine : content.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).strip();

            int eq = line.indexOf('=');
            if (eq <= 0) continue;                      // 无 = 或以 = 开头,跳过

            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();

            // 去引号;双引号内处理转义,单引号内原样
            if (value.length() >= 2) {
                char first = value.charAt(0), last = value.charAt(value.length() - 1);
                if (first == '"' && last == '"') {
                    value = value.substring(1, value.length() - 1)
                                 .replace("\\n", "\n").replace("\\t", "\t")
                                 .replace("\\\"", "\"").replace("\\\\", "\\");
                } else if (first == '\'' && last == '\'') {
                    value = value.substring(1, value.length() - 1);
                } else {
                    // 无引号时,行内 # 之后视为注释
                    int hash = value.indexOf(" #");
                    if (hash >= 0) value = value.substring(0, hash).strip();
                }
            }
            out.put(key, value);
        }
        return out;
    }

    /** 展开 ${OTHER} —— 只在 .env 自身范围内解析,不回落到系统环境变量 */
    private static String expand(String value, Map<String, String> scope) {
        if (value == null || value.indexOf('$') < 0) return value;
        String result = value;
        for (int i = 0; i < 5 && result.contains("${"); i++) {   // 限制递归深度,防循环引用
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            while (true) {
                int start = result.indexOf("${", pos);
                if (start < 0) { sb.append(result.substring(pos)); break; }
                int end = result.indexOf('}', start);
                if (end < 0) { sb.append(result.substring(pos)); break; }
                sb.append(result, pos, start);
                String name = result.substring(start + 2, end);
                sb.append(scope.getOrDefault(name, "${" + name + "}"));
                pos = end + 1;
            }
            String next = sb.toString();
            if (next.equals(result)) break;
            result = next;
        }
        return result;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOExceptionWithPath(p, e);
        }
    }

    public static class UncheckedIOExceptionWithPath extends RuntimeException {
        public UncheckedIOExceptionWithPath(Path p, IOException cause) {
            super("读取 " + p.toAbsolutePath() + " 失败:" + cause.getMessage(), cause);
        }
    }
}
