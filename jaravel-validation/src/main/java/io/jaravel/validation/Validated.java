package io.jaravel.validation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.support.Cast;

/**
 * 通过校验的数据,带类型化取值器。
 *
 * <pre>{@code
 * String title = v.string("title");
 * int age      = v.integer("age", 0);
 * List<Long> t = v.longs("tags");
 * }</pre>
 *
 * <p>为什么不直接返回 {@code Map<String, Object>}:那样每个控制器都要写
 * {@code (String) data.get("title")},类型错了在运行时才炸,而且 {@code null}
 * 的处理散落各处。有了取值器,「这个字段缺失时该怎样」只在一个地方表达。
 */
public final class Validated {

    private final Map<String, Object> data;

    Validated(Map<String, Object> data) {
        this.data = data;
    }

    public Map<String, Object> all() {
        return Map.copyOf(data);
    }

    public boolean has(String key) {
        return Data.has(data, key);
    }

    public Object raw(String key) {
        return Data.get(data, key);
    }

    /** 只取部分键 —— 传给 {@code Model.create()} 前裁剪 */
    public Map<String, Object> only(String... keys) {
        var out = new java.util.LinkedHashMap<String, Object>();
        for (String key : keys) {
            if (Data.has(data, key)) out.put(key, Data.get(data, key));
        }
        return out;
    }

    public Map<String, Object> except(String... keys) {
        var out = new java.util.LinkedHashMap<>(data);
        for (String key : keys) out.remove(key);
        return out;
    }

    // ================= 取值器 =================

    /**
     * 必有的字符串。缺失时抛异常而不是返回 null。
     *
     * <p>既然校验通过了,{@code required|string} 的字段一定在 —— 如果不在,
     * 说明规则里漏了 {@code required},那是个**代码 bug**,不该让它变成
     * 下游某处的 NPE。所以这里直接说清是哪个字段、该怎么改。
     */
    public String string(String key) {
        Object value = Data.get(data, key);
        if (value == null) {
            throw missing(key, "string");
        }
        return String.valueOf(value);
    }

    public String string(String key, String fallback) {
        Object value = Data.get(data, key);
        return value == null ? fallback : String.valueOf(value);
    }

    public Optional<String> stringOptional(String key) {
        Object value = Data.get(data, key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    public int integer(String key) {
        return convert(key, Integer.class, "integer");
    }

    public int integer(String key, int fallback) {
        Object value = Data.get(data, key);
        return value == null ? fallback : Cast.to(value, Integer.class);
    }

    public long longValue(String key) {
        return convert(key, Long.class, "integer");
    }

    public long longValue(String key, long fallback) {
        Object value = Data.get(data, key);
        return value == null ? fallback : Cast.to(value, Long.class);
    }

    public double doubleValue(String key) {
        return convert(key, Double.class, "numeric");
    }

    public BigDecimal decimal(String key) {
        return convert(key, BigDecimal.class, "numeric");
    }

    public boolean bool(String key) {
        return convert(key, Boolean.class, "boolean");
    }

    public boolean bool(String key, boolean fallback) {
        Object value = Data.get(data, key);
        return value == null ? fallback : Cast.to(value, Boolean.class);
    }

    public Instant instant(String key) {
        Object value = Data.get(data, key);
        return value == null ? null : Rules.parseDate(value);
    }

    public LocalDate date(String key) {
        Object value = Data.get(data, key);
        if (value == null) return null;
        return Cast.to(value, LocalDate.class);
    }

    public <E extends Enum<E>> E enumValue(String key, Class<E> type) {
        Object value = Data.get(data, key);
        if (value == null) return null;
        if (type.isInstance(value)) return type.cast(value);
        String name = String.valueOf(value);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "字段 `" + key + "` 的值 `" + name + "` 不是 "
                  + type.getSimpleName() + " 的枚举常量。"
                  + "\n  可选:" + java.util.Arrays.stream(type.getEnumConstants())
                          .map(Enum::name).toList()
                  + "\n  规则里应加上 Rule.enumOf(" + type.getSimpleName() + ".class)"
                  + ",这样校验阶段就会拦住它。", e);
        }
    }

    // ================= 集合取值器 =================

    public List<String> strings(String key) {
        return elements(key).stream().map(String::valueOf).toList();
    }

    public List<Long> longs(String key) {
        return elements(key).stream().map(v -> Cast.to(v, Long.class)).toList();
    }

    public List<Integer> integers(String key) {
        return elements(key).stream().map(v -> Cast.to(v, Integer.class)).toList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> maps(String key) {
        var out = new ArrayList<Map<String, Object>>();
        for (Object item : elements(key)) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            } else {
                throw new IllegalStateException(
                        "字段 `" + key + "` 的元素不是对象,而是 "
                      + (item == null ? "null" : item.getClass().getSimpleName()) + "。"
                      + "\n  maps() 用于 items.*.sku 这种对象数组;"
                      + "标量数组请用 strings()/longs()。");
            }
        }
        return out;
    }

    /** 缺失时返回空列表 —— 「没传标签」和「传了空标签列表」在业务上通常等价 */
    private List<Object> elements(String key) {
        Object value = Data.get(data, key);
        if (value == null) return List.of();
        if (value instanceof java.util.Collection<?> c) return new ArrayList<>(c);
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            var out = new ArrayList<Object>(length);
            for (int i = 0; i < length; i++) out.add(java.lang.reflect.Array.get(value, i));
            return out;
        }
        // 单值当成一元数组:HTML 表单里只选一项时常常不带 []
        return List.of(value);
    }

    private <T> T convert(String key, Class<T> type, String ruleHint) {
        Object value = Data.get(data, key);
        if (value == null) throw missing(key, ruleHint);
        try {
            return Cast.to(value, type);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "字段 `" + key + "` 的值 `" + value + "` 无法转成 " + type.getSimpleName() + "。"
                  + "\n  规则里应声明 " + ruleHint + ",这样校验阶段就会拦住它,"
                  + "而不是让取值器在控制器里炸。", e);
        }
    }

    private IllegalStateException missing(String key, String ruleHint) {
        return new IllegalStateException(
                "字段 `" + key + "` 不在校验结果里。"
              + "\n  已通过校验的字段:" + data.keySet()
              + "\n  两种可能:"
              + "\n    · 规则里没写 required —— 可选字段请用带默认值的重载,"
              + "如 " + key + "(\"" + key + "\", 默认值);"
              + "\n    · 字段名拼错了 —— 规则声明用的名字必须与这里一致。");
    }

    @Override public String toString() {
        return "Validated" + data.keySet();
    }
}
