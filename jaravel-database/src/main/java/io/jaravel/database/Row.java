package io.jaravel.database;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.jaravel.support.Arr;
import io.jaravel.support.Cast;

/**
 * 结果集的一行。
 *
 * <p><b>为什么不直接用 {@code Map<String,Object>}:</b>独立类型才能提供类型化取值器
 * 与**友好的错误信息** —— 列名拼错时给出「列 `nmae` 不存在,是否想找 `name`?」,
 * 而 Map 只会静默返回 null,把问题推到更远的地方。
 */
public final class Row {

    /** 列名大小写不敏感:不同数据库/驱动返回的大小写不一致 */
    private final Map<String, Object> values;

    private Row(Map<String, Object> values) {
        this.values = values;
    }

    public static Row of(Map<String, Object> values) {
        var m = new LinkedHashMap<String, Object>(values.size());
        values.forEach((k, v) -> m.put(k == null ? null : k.toLowerCase(), v));
        return new Row(m);
    }

    /**
     * 接管一个**已经小写化**的 map,不再拷贝。
     *
     * <p>只给 {@link JdbcConnection} 用:它刚刚新建了这个 map,没有别人持有引用,
     * 再拷一遍纯属浪费。{@link #of} 保留拷贝语义给外部调用者 ——
     * 公开 API 收到别人的 map 时必须防御性复制。
     *
     * <p>这不是微优化:原先每行要建**两个** LinkedHashMap,
     * 千行结果集就是两千次哈希表分配。
     */
    static Row adopt(Map<String, Object> lowercasedValues) {
        return new Row(lowercasedValues);
    }

    // ---------- 取值 ----------

    public Object get(String column) {
        String key = normalize(column);
        if (!values.containsKey(key)) throw unknownColumn(column);
        return values.get(key);
    }

    public <T> T get(String column, Class<T> type) {
        Object v = get(column);
        if (v == null) return null;
        if (type.isInstance(v)) return type.cast(v);
        try {
            return Cast.to(v, type);
        } catch (Cast.CastException e) {
            throw new ColumnTypeException(
                    "列 " + column + " 的值 " + v + "(" + v.getClass().getSimpleName()
                    + ")无法转换为 " + type.getSimpleName() + ":" + e.getMessage());
        }
    }

    public Optional<Object> tryGet(String column) {
        return Optional.ofNullable(values.get(normalize(column)));
    }

    public boolean has(String column) {
        return values.containsKey(normalize(column));
    }

    public boolean isNull(String column) {
        return get(column) == null;
    }

    // ---------- 类型化便捷取值器 ----------

    public String string(String c)          { return get(c, String.class); }
    public Long longValue(String c)         { return get(c, Long.class); }
    public Integer intValue(String c)       { return get(c, Integer.class); }
    public BigDecimal decimal(String c)     { return get(c, BigDecimal.class); }
    public Instant instant(String c)        { return get(c, Instant.class); }
    public LocalDate date(String c)         { return get(c, LocalDate.class); }
    public LocalDateTime dateTime(String c) { return get(c, LocalDateTime.class); }

    /**
     * SQLite 用 0/1 表示布尔,MySQL 用 tinyint(1),PostgreSQL 用真布尔 ——
     * 统一在这里归一化。
     */
    public boolean bool(String c) {
        Object v = get(c);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return Cast.to(v, Boolean.class);
    }

    // ---------- 互操作 ----------

    public Map<String, Object> toMap() {
        return Map.copyOf(values);
    }

    public java.util.Set<String> columns() {
        return values.keySet();
    }

    public int size() {
        return values.size();
    }

    private static String normalize(String column) {
        if (column == null) return null;
        // 兼容 "users.name" 这类带表前缀的别名
        int dot = column.lastIndexOf('.');
        return (dot >= 0 ? column.substring(dot + 1) : column).toLowerCase();
    }

    private IllegalArgumentException unknownColumn(String column) {
        String hint = Arr.closest(normalize(column), values.keySet())
                .map(c -> " 是否想找 " + c + " ?").orElse("");
        return new IllegalArgumentException(
                "结果集中没有列 " + column + "。" + hint
              + "\n  已有的列:" + values.keySet()
              + "\n  若用了 select(...),确认该列在选择列表中。");
    }

    @Override public String toString() {
        return "Row" + values;
    }

    public static class ColumnTypeException extends IllegalArgumentException {
        public ColumnTypeException(String message) { super(message); }
    }
}
