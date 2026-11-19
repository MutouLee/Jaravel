package io.jaravel.view;

import java.util.Collection;
import java.util.List;
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

    // ================= 框架指令的落点(docs/04 §1.8) =================

    /**
     * {@code @csrf} —— 一个带当前令牌的隐藏 input。
     *
     * <p>输出的是**整个标签**而不只是令牌值,因为那是这个指令存在的
     * 全部理由:手写 {@code <input type="hidden" name="_token" value="{{ csrf }}">}
     * 的人会漏掉它(表单一提交就 419),或者把 name 写成 {@code csrf_token}
     * (同样 419,而且更难看出来)。
     *
     * <p>值在这里转义:令牌是 Base64,正常不含需要转义的字符,
     * 但一个「正常不会」的假设不该出现在拼 HTML 的代码里。
     *
     * <p>没有请求/会话时**报错**而不是输出一个空令牌 ——
     * 空令牌的后果是表单在 419 上失败,而那个 419 与
     * 「模板里写了 @csrf」离得很远。
     */
    public static String csrfField() {
        String token = ViewRequestState.current().csrfToken();
        return "<input type=\"hidden\" name=\"_token\" value=\"" + escapeAttribute(token) + "\">";
    }

    /** {@code @error(field)} 的条件 */
    public static boolean hasError(String field) {
        return !ViewRequestState.current().errors(field).isEmpty();
    }

    /**
     * {@code @error(field)} 块里的 {@code message}。
     *
     * <p>**第一条**而不是全部拼起来:一个字段上同时挂三条错误时,
     * 表单旁边显示三行会把布局挤坏,而用户真正要做的只有一件事。
     * 要全部显示的用 {@link #errors(String)}。
     */
    public static String firstError(String field) {
        List<String> all = ViewRequestState.current().errors(field);
        return all.isEmpty() ? "" : all.get(0);
    }

    /** 某个字段的全部错误消息 */
    public static List<String> errors(String field) {
        return ViewRequestState.current().errors(field);
    }

    /** {@code @old(field)} —— 没有旧输入时是空串 */
    public static String old(String field) {
        return old(field, "");
    }

    /**
     * {@code @old(field, fallback)} —— 没有旧输入时用 fallback。
     *
     * <p>这是编辑表单的标准写法:
     * {@code value="@old('title', post.title)"} —— 校验失败回跳时显示
     * 用户刚填的,正常进入时显示库里的。
     */
    public static String old(String field, Object fallback) {
        // ★ 返回 String 而不是 Object:jte 的 ${} 不接 Object
        // (它按类型选 writeUserContent 的重载),而 @old 的值来自
        // 会话里的闪存数据 —— 那是一个 Map<String, Object>。
        // 返回 Object 的话每个 @old 都会以一屏「方法不适用」的候选列表
        // 编译失败,而那段错误与模板里那一行看不出关系。
        Object value = ViewRequestState.current().old(field).orElse(fallback);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * HTML 属性值的转义。
     *
     * <p>只给 {@link #csrfField()} 用 —— 模板里普通的 {@code {{ }}}
     * 由 jte 的上下文感知转义负责,这里是我们自己拼标签的那一处。
     */
    private static String escapeAttribute(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("\"", "&quot;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
    }

    // ---------- 条件指令(docs/04 §1.8) ----------

    /** {@code @auth} / {@code @auth('api')} */
    public static boolean isAuth(String guard) {
        return ViewRequestState.current().authenticated(guard);
    }

    /** {@code @guest} / {@code @guest('api')} */
    public static boolean isGuest(String guard) {
        return !isAuth(guard);
    }

    /** {@code @can('update', post)} */
    public static boolean allows(String ability, Object... arguments) {
        return ViewRequestState.current().allows(ability, arguments);
    }

    /** {@code @cannot('delete', post)} */
    public static boolean denies(String ability, Object... arguments) {
        return !allows(ability, arguments);
    }

    /**
     * {@code @env('local')} / {@code @env('local', 'staging')}。
     *
     * <p>读 {@code Application.environment()} —— 与 {@code app.env} 配置
     * 同一个值,所以模板里的判断与 starter 的判断不会分叉。
     */
    public static boolean isEnv(String... names) {
        String current = io.jaravel.core.Application.environment();
        for (String name : names) {
            if (name != null && name.equals(current)) return true;
        }
        return false;
    }

    /** {@code @production} —— 等价于 {@code @env('production')} */
    public static boolean isProduction() {
        return isEnv("production");
    }
}
