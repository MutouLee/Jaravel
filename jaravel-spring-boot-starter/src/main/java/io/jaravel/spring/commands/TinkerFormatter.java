package io.jaravel.spring.commands;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;

/**
 * tinker 里的值怎么打印(docs/13 §5.1 第 5 条)。
 *
 * <pre>
 * Post.find(1)        → Post{id=1, title=第一篇, published=true}
 * Post.all()          → [Post{id=1, …}, Post{id=2, …}]   (超过 20 个截断)
 * </pre>
 *
 * <h2>★ 为什么必须有它</h2>
 * <p>JShell 把值交回来时**只给一个字符串**(执行侧 toString 的结果)——
 * 所以 {@code Post.find(1)} 在原生 jshell 里是 {@code app.models.Post@397fbdb}。
 * 那个输出对探索数据毫无用处,而「探索数据」正是 tinker 的全部用途。
 *
 * <p>于是表达式被包进 {@code TinkerFormatter.of(…)} 再求值 ——
 * 格式化发生在**执行侧**,那里还有真对象。
 *
 * <h2>公共字段:正好是模型的形状</h2>
 * <p>ADR-010 让模型的标量列是 public 字段,所以反射读公共字段
 * 恰好打印出「这行数据是什么」。不需要 getter 约定,也不需要注解。
 */
public final class TinkerFormatter {

    private TinkerFormatter() {
    }

    /** 集合最多打这么多个 —— 一个 10000 行的查询不该把终端刷满 */
    private static final int MAX_ELEMENTS = 20;

    /** 嵌套最多这么深 —— 关联对象之间可能成环(Post.author.posts) */
    private static final int MAX_DEPTH = 3;

    public static String of(Object value) {
        return format(value, 0);
    }

    private static String format(Object value, int depth) {
        if (value == null) return "null";
        if (depth > MAX_DEPTH) return "…";

        if (value instanceof CharSequence text) return "\"" + text + "\"";
        if (value instanceof Character c) return "'" + c + "'";
        if (isSimple(value)) return String.valueOf(value);

        if (value instanceof Collection<?> items) return formatElements(items, depth);
        if (value instanceof Map<?, ?> map) return formatMap(map, depth);
        if (value.getClass().isArray()) return formatArray(value, depth);

        // record:它的 toString 本来就带字段名,不用自己拼
        if (value.getClass().isRecord()) return String.valueOf(value);

        return formatObject(value, depth);
    }

    private static boolean isSimple(Object value) {
        return value instanceof Number || value instanceof Boolean
            || value instanceof Enum<?> || value instanceof java.time.temporal.Temporal
            || value instanceof java.util.Date || value instanceof java.util.UUID;
    }

    private static String formatElements(Collection<?> items, int depth) {
        var out = new StringBuilder("[");
        int shown = 0;
        for (Object item : items) {
            if (shown == MAX_ELEMENTS) {
                out.append(", …共 ").append(items.size()).append(" 个");
                break;
            }
            if (shown > 0) out.append(", ");
            out.append(format(item, depth + 1));
            shown++;
        }
        return out.append(']').toString();
    }

    private static String formatMap(Map<?, ?> map, int depth) {
        var out = new StringBuilder("{");
        int shown = 0;
        for (var entry : map.entrySet()) {
            if (shown == MAX_ELEMENTS) {
                out.append(", …共 ").append(map.size()).append(" 项");
                break;
            }
            if (shown > 0) out.append(", ");
            out.append(format(entry.getKey(), depth + 1)).append('=')
               .append(format(entry.getValue(), depth + 1));
            shown++;
        }
        return out.append('}').toString();
    }

    private static String formatArray(Object array, int depth) {
        int length = java.lang.reflect.Array.getLength(array);
        var out = new StringBuilder("[");
        for (int i = 0; i < Math.min(length, MAX_ELEMENTS); i++) {
            if (i > 0) out.append(", ");
            out.append(format(java.lang.reflect.Array.get(array, i), depth + 1));
        }
        if (length > MAX_ELEMENTS) out.append(", …共 ").append(length).append(" 个");
        return out.append(']').toString();
    }

    /**
     * 公共字段 → {@code 类名{字段=值, …}}。
     *
     * <p>一个公共字段都没有时退回 {@code toString()} ——
     * 那种类通常自己写了个有意义的 toString(而我们不该把它盖掉)。
     */
    private static String formatObject(Object value, int depth) {
        var fields = new java.util.ArrayList<Field>();
        for (Field field : value.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            fields.add(field);
        }
        if (fields.isEmpty()) return String.valueOf(value);

        var out = new StringBuilder(value.getClass().getSimpleName()).append('{');
        boolean first = true;
        for (Field field : fields) {
            if (!first) out.append(", ");
            first = false;
            out.append(field.getName()).append('=');
            try {
                out.append(format(field.get(value), depth + 1));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 读一个字段失败不该让整个值打不出来 ——
                // 懒加载未初始化的关联正是这种情况
                out.append("<读不了:").append(e.getClass().getSimpleName()).append('>');
            }
        }
        return out.append('}').toString();
    }
}
