package io.jaravel.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按点路径读写嵌套数据。
 *
 * <pre>{@code
 * Data.get(data, "user.profile.city")
 * Data.get(data, "items.0.sku")
 * Data.expand(data, "items.*.qty")   // → [items.0.qty, items.1.qty]
 * }</pre>
 *
 * <p>单独成类是因为「路径」这件事在验证器里到处用到:取值、报错键、
 * 通配符展开、条件规则引用别的字段。散在 Validator 里会让那个类看不清主线。
 */
final class Data {

    private Data() {}

    /** 路径不存在返回 empty;存在但值为 null 返回 {@code Optional.of(NULL)} 的区分见 {@link #has} */
    static Object get(Map<String, Object> data, String path) {
        Object current = data;
        for (String segment : path.split("\\.", -1)) {
            current = step(current, segment);
            if (current == ABSENT) return null;
        }
        return current;
    }

    /** 区分「不存在」与「存在但是 null」—— required 与 nullable 的语义差别全靠它 */
    static boolean has(Map<String, Object> data, String path) {
        Object current = data;
        for (String segment : path.split("\\.", -1)) {
            current = step(current, segment);
            if (current == ABSENT) return false;
        }
        return true;
    }

    private static final Object ABSENT = new Object();

    private static Object step(Object container, String segment) {
        if (container instanceof Map<?, ?> map) {
            if (!map.containsKey(segment)) return ABSENT;
            return map.get(segment);
        }
        if (container instanceof List<?> list) {
            int index = parseIndex(segment);
            if (index < 0 || index >= list.size()) return ABSENT;
            return list.get(index);
        }
        if (container != null && container.getClass().isArray()) {
            int index = parseIndex(segment);
            int length = java.lang.reflect.Array.getLength(container);
            if (index < 0 || index >= length) return ABSENT;
            return java.lang.reflect.Array.get(container, index);
        }
        return ABSENT;
    }

    private static int parseIndex(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    static void set(Map<String, Object> data, String path, Object value) {
        String[] segments = path.split("\\.", -1);
        Map<String, Object> current = data;

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

    @SuppressWarnings("unchecked")
    static void remove(Map<String, Object> data, String path) {
        String[] segments = path.split("\\.", -1);
        Object current = data;

        for (int i = 0; i < segments.length - 1; i++) {
            if (!(current instanceof Map<?, ?> map)) return;
            current = map.get(segments[i]);
        }
        if (current instanceof Map) {
            ((Map<String, Object>) current).remove(segments[segments.length - 1]);
        }
    }

    /**
     * 把含 {@code *} 的路径展开成实际存在的具体路径。
     *
     * <p>{@code items.*.qty} 在有 3 个 item 时展开成三条。展开而不是在校验时
     * 遍历,是为了让**错误键带上真实索引**({@code items.2.qty}),
     * 前端才能把错误标到对应的那一行表单上。
     */
    static List<String> expand(Map<String, Object> data, String pattern) {
        if (!pattern.contains("*")) return List.of(pattern);

        List<String> results = new ArrayList<>();
        expandInto(data, pattern.split("\\.", -1), 0, "", results);
        return results;
    }

    private static void expandInto(Object container, String[] segments, int index,
                                   String prefix, List<String> out) {
        if (index == segments.length) {
            out.add(prefix);
            return;
        }
        String segment = segments[index];

        if (!segment.equals("*")) {
            Object next = step(container, segment);
            // 路径不存在也要产出:required 需要对缺失字段报错
            String path = prefix.isEmpty() ? segment : prefix + "." + segment;
            if (next == ABSENT && index < segments.length - 1 && containsWildcardAfter(segments, index)) {
                return;      // 中间层不存在,下面的通配符无从展开
            }
            expandInto(next == ABSENT ? null : next, segments, index + 1, path, out);
            return;
        }

        // 通配符:按实际元素个数展开
        int size = sizeOf(container);
        if (size < 0) return;
        for (int i = 0; i < size; i++) {
            String path = prefix.isEmpty() ? String.valueOf(i) : prefix + "." + i;
            expandInto(step(container, String.valueOf(i)), segments, index + 1, path, out);
        }
    }

    private static boolean containsWildcardAfter(String[] segments, int index) {
        for (int i = index + 1; i < segments.length; i++) {
            if (segments[i].equals("*")) return true;
        }
        return false;
    }

    private static int sizeOf(Object container) {
        if (container instanceof List<?> list) return list.size();
        if (container instanceof Map<?, ?> map) return map.size();
        if (container != null && container.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(container);
        }
        return -1;
    }

    /**
     * 把 {@code items.2.qty} 里的索引填回 {@code items.*.qty} 风格的引用。
     *
     * <p>用于 {@code required_with:items.*.sku} 这类同层引用:当前校验的是
     * {@code items.2.qty},引用的 {@code items.*.sku} 应该解析成
     * {@code items.2.sku} 而不是全部的 sku。没有这个替换,数组里的
     * 条件必填规则就全错了。
     */
    static String resolveSibling(String currentPath, String reference) {
        if (!reference.contains("*")) return reference;

        String[] current = currentPath.split("\\.", -1);
        String[] ref = reference.split("\\.", -1);
        var out = new ArrayList<String>(ref.length);

        for (int i = 0; i < ref.length; i++) {
            if (ref[i].equals("*") && i < current.length) {
                out.add(current[i]);
            } else {
                out.add(ref[i]);
            }
        }
        return String.join(".", out);
    }

    /** 叶子段 —— 默认错误消息里的字段名用它 */
    static String leaf(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }

    static Optional<Integer> sizeIfCountable(Object value) {
        if (value instanceof CharSequence s) return Optional.of(s.length());
        if (value instanceof java.util.Collection<?> c) return Optional.of(c.size());
        if (value instanceof Map<?, ?> m) return Optional.of(m.size());
        if (value != null && value.getClass().isArray()) {
            return Optional.of(java.lang.reflect.Array.getLength(value));
        }
        return Optional.empty();
    }
}
