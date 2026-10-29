package io.jaravel.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 嵌套 Map 的点号路径访问。Laravel {@code Arr} 的等价物。
 *
 * <p>配置读取、请求输入、JSON 载荷都依赖这个:{@code Arr.get(map, "user.profile.city")}。
 */
public final class Arr {

    private Arr() {}

    /** 按点号路径取值。路径不存在返回 null。 */
    @SuppressWarnings("unchecked")
    public static Object get(Map<String, ?> map, String path) {
        if (map == null || path == null || path.isEmpty()) return null;
        Object current = map;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(segment);
            } else if (current instanceof List<?> list) {
                // 支持 items.0.name
                try {
                    int idx = Integer.parseInt(segment);
                    if (idx < 0 || idx >= list.size()) return null;
                    current = list.get(idx);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) return null;
        }
        return current;
    }

    public static Object get(Map<String, ?> map, String path, Object defaultValue) {
        Object v = get(map, path);
        return v == null ? defaultValue : v;
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> get(Map<String, ?> map, String path, Class<T> type) {
        Object v = get(map, path);
        if (v == null) return Optional.empty();
        if (type.isInstance(v)) return Optional.of((T) v);
        return Optional.ofNullable(Cast.to(v, type));
    }

    public static boolean has(Map<String, ?> map, String path) {
        if (map == null || path == null) return false;
        Object current = map;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                if (!m.containsKey(segment)) return false;
                current = m.get(segment);
            } else {
                return false;
            }
        }
        return true;
    }

    /** 按点号路径写值,中间层级自动创建。 */
    @SuppressWarnings("unchecked")
    public static void set(Map<String, Object> map, String path, Object value) {
        if (map == null || path == null || path.isEmpty()) return;
        String[] segments = path.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.get(segments[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(segments[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(segments[segments.length - 1], value);
    }

    /** 嵌套 Map 扁平化为 {@code a.b.c -> value} */
    public static Map<String, Object> dot(Map<String, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        flatten("", map, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, ?> map, Map<String, Object> out) {
        map.forEach((k, v) -> {
            String key = prefix.isEmpty() ? k : prefix + "." + k;
            if (v instanceof Map<?, ?> m && !m.isEmpty()) {
                flatten(key, (Map<String, ?>) m, out);
            } else {
                out.put(key, v);
            }
        });
    }

    /** 扁平化的反操作:{@code a.b -> v} 还原为嵌套 Map */
    public static Map<String, Object> undot(Map<String, ?> flat) {
        Map<String, Object> out = new LinkedHashMap<>();
        flat.forEach((k, v) -> set(out, k, v));
        return out;
    }

    public static Map<String, Object> only(Map<String, ?> map, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> wanted = Set.of(keys);
        map.forEach((k, v) -> { if (wanted.contains(k)) out.put(k, v); });
        return out;
    }

    public static Map<String, Object> except(Map<String, ?> map, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> excluded = Set.of(keys);
        map.forEach((k, v) -> { if (!excluded.contains(k)) out.put(k, v); });
        return out;
    }

    /** 浅合并,后者覆盖前者 */
    @SafeVarargs
    public static Map<String, Object> merge(Map<String, ?>... maps) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, ?> m : maps) if (m != null) out.putAll(m);
        return out;
    }

    /** 深合并:嵌套 Map 递归合并,而非整体覆盖。配置分层加载需要这个。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mergeDeep(Map<String, ?> base, Map<String, ?> override) {
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (override == null) return out;
        override.forEach((k, v) -> {
            Object existing = out.get(k);
            if (existing instanceof Map<?, ?> em && v instanceof Map<?, ?> vm) {
                out.put(k, mergeDeep((Map<String, ?>) em, (Map<String, ?>) vm));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    public static <T> List<T> wrap(T value) {
        if (value == null) return List.of();
        if (value instanceof List<?> l) {
            @SuppressWarnings("unchecked") List<T> cast = (List<T>) l;
            return cast;
        }
        return List.of(value);
    }

    @SafeVarargs
    public static <T> List<T> concat(List<T>... lists) {
        List<T> out = new ArrayList<>();
        for (List<T> l : lists) if (l != null) out.addAll(l);
        return out;
    }

    public static boolean isEmpty(Map<?, ?> m)  { return m == null || m.isEmpty(); }
    public static boolean isEmpty(List<?> l)    { return l == null || l.isEmpty(); }

    /**
     * 找出与目标最相似的候选项,用于「列 `nmae` 不存在,是否想找 `name`?」这类提示。
     * 编辑距离 ≤ max(2, 目标长度/3) 才算相似。
     */
    public static Optional<String> closest(String target, Iterable<String> candidates) {
        if (target == null) return Optional.empty();
        int threshold = Math.max(2, target.length() / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = levenshtein(target.toLowerCase(), c.toLowerCase());
            if (d < bestDistance) { bestDistance = d; best = c; }
        }
        return bestDistance <= threshold ? Optional.ofNullable(best) : Optional.empty();
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
