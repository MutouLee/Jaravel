package io.jaravel.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.jaravel.support.Arr;
import io.jaravel.support.Cast;

/**
 * 基于嵌套 Map 的配置实现,支持 {@code ${ENV_VAR:default}} 占位符解析。
 *
 * <p>占位符在**读取时**解析而非加载时,这样 {@code .env} 的后续变更(开发期热重载)
 * 能立即生效,无需重建整个配置树。
 */
public class MapConfigRepository implements ConfigRepository {

    /** ${NAME} / ${NAME:default} —— default 部分允许包含 : 与 / (如 URL) */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)(?::([^}]*))?}");

    private static final List<String> SENSITIVE =
            List.of("password", "secret", "key", "token", "credential", "dsn");

    private final Map<String, Object> tree;
    private final java.util.function.Function<String, String> envResolver;

    public MapConfigRepository(Map<String, Object> tree) {
        this(tree, System::getenv);
    }

    public MapConfigRepository(Map<String, Object> tree,
                               java.util.function.Function<String, String> envResolver) {
        this.tree = tree == null ? new LinkedHashMap<>() : tree;
        this.envResolver = envResolver;
    }

    @Override
    public Optional<Object> raw(String path) {
        Object v = Arr.get(tree, path);
        return Optional.ofNullable(v instanceof String s ? interpolate(s) : v);
    }

    @Override
    public boolean has(String path) {
        return Arr.get(tree, path) != null;
    }

    @Override
    public <T> Optional<T> get(String path, Class<T> type) {
        Optional<Object> v = raw(path);
        if (v.isEmpty()) return Optional.empty();
        try {
            return Optional.ofNullable(Cast.to(v.get(), type));
        } catch (Cast.CastException e) {
            // 配置错误必须指出是哪个键 —— 否则用户要在几十个 yml 里找
            throw new MissingConfigException(
                    "配置项 " + path + " 的值无法转换为 " + type.getSimpleName()
                    + ":" + e.getMessage());
        }
    }

    @Override
    public <T> T get(String path, Class<T> type, T defaultValue) {
        return get(path, type).orElse(defaultValue);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> stringList(String path) {
        Object v = Arr.get(tree, path);
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(o == null ? null : interpolate(String.valueOf(o)));
            return out;
        }
        // 容忍单值写法与逗号分隔
        String s = interpolate(String.valueOf(v));
        if (s.isBlank()) return List.of();
        return List.of(s.split("\\s*,\\s*"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String path) {
        Object v = Arr.get(tree, path);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @Override
    public Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        Arr.dot(tree).forEach((k, v) -> out.put(k, isSensitive(k) ? "********" : v));
        return out;
    }

    private static boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        return SENSITIVE.stream().anyMatch(lower::contains);
    }

    /**
     * 解析 {@code ${VAR}} / {@code ${VAR:default}}。
     *
     * <p>未设置且无默认值时**保留原样**而不是替换为空串 —— 这样 `jaravel doctor`
     * 能检测出「配置里还有未解析的占位符」,而不是让应用带着空密码启动。
     */
    String interpolate(String value) {
        if (value == null || value.indexOf('$') < 0) return value;
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String fallback = m.group(2);
            String resolved = envResolver.apply(name);
            if (resolved == null) resolved = envResolver.apply(name.replace('.', '_').toUpperCase());
            String replacement = resolved != null ? resolved
                               : fallback != null ? fallback
                               : m.group(0);               // 保留 ${VAR} 原样
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
