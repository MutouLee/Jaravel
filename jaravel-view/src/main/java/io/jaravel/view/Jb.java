package io.jaravel.view;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * jblade 生成代码调用的运行时辅助方法。
 *
 * <p>模板里写的 {@code @empty(posts)} 会被转写为 {@code @if(Jb.isEmpty(posts))} ——
 * 这些方法就是那些转写的落点。保持极小且全是静态纯函数。
 */
public final class Jb {

    private Jb() {}

    /**
     * Blade 的「空」语义:null、空串、空白串、空集合、空 Map、空数组、空 Optional
     * 都算空。数值 0 与 false **不算**空(这点与 PHP 的 empty() 不同,
     * PHP 的行为是 bug 之源,刻意不复制)。
     */
    public static boolean isEmpty(Object o) {
        if (o == null) return true;
        if (o instanceof CharSequence cs)  return cs.isEmpty() || cs.toString().isBlank();
        if (o instanceof Collection<?> c)  return c.isEmpty();
        if (o instanceof Map<?, ?> m)      return m.isEmpty();
        if (o instanceof Optional<?> opt)  return opt.isEmpty();
        if (o.getClass().isArray())        return java.lang.reflect.Array.getLength(o) == 0;
        if (o instanceof Iterable<?> it)   return !it.iterator().hasNext();
        return false;
    }

    public static boolean isNotEmpty(Object o) {
        return !isEmpty(o);
    }

    /** {@code {{ a ?? b }}} 的落点 */
    public static <T> T coalesce(T a, T b) {
        return a != null ? a : b;
    }

    /** {@code @isset(x)} 的落点。Optional 的空也算未设置。 */
    public static boolean isset(Object o) {
        if (o == null) return false;
        if (o instanceof Optional<?> opt) return opt.isPresent();
        return true;
    }

    /**
     * {@code @foreach} 的遍历入口,**按类型重载以保留元素类型**。
     *
     * <p>这点很关键:若统一返回 {@code Iterable<?>},循环变量会被推断成 {@code Object},
     * 模板里 {@code {{ item.title }}} 就编译不过了。重载让 javac 在编译期选对签名,
     * 元素类型得以保留。
     *
     * <p>同时容忍 null —— 模板不该因为「控制器传了 null」而炸。
     */
    public static <T> Iterable<T> iter(Iterable<T> items) {
        return items == null ? java.util.List.of() : items;
    }

    public static <T> Iterable<T> iter(T[] items) {
        return items == null ? java.util.List.of() : java.util.Arrays.asList(items);
    }

    /** Map 遍历 entrySet,与 Blade 遍历关联数组的语义一致 */
    public static <K, V> Iterable<Map.Entry<K, V>> iter(Map<K, V> map) {
        return map == null ? java.util.List.of() : map.entrySet();
    }

    /** 弱类型回落。重载都不匹配时使用,元素类型退化为 Object。 */
    public static Iterable<?> iterable(Object o) {
        if (o == null) return java.util.List.of();
        if (o instanceof Iterable<?> it) return it;
        if (o instanceof Map<?, ?> m) return m.entrySet();
        if (o.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(o);
            var out = new java.util.ArrayList<Object>(n);
            for (int i = 0; i < n; i++) out.add(java.lang.reflect.Array.get(o, i));
            return out;
        }
        throw new IllegalArgumentException(
                "@foreach 无法遍历 " + o.getClass().getName()
              + "(支持 Iterable / Collection / Map / 数组 / null)");
    }

    /** 用于 {@code loop} 变量:尽量拿到序列长度,拿不到返回 -1 */
    public static int sizeOf(Object o) {
        if (o == null) return 0;
        if (o instanceof Collection<?> c) return c.size();
        if (o instanceof Map<?, ?> m) return m.size();
        if (o.getClass().isArray()) return java.lang.reflect.Array.getLength(o);
        return -1;
    }

    /** {@code @json(data)} —— 安全地把对象输出到 JS 上下文 */
    public static String json(Object o) {
        // v0.1 用极简实现;v0.2 接 Jackson(由 starter 注入序列化器)
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        return "\"" + String.valueOf(o)
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003C").replace(">", "\\u003E")
                .replace("&", "\\u0026").replace("\n", "\\n") + "\"";
    }
}
