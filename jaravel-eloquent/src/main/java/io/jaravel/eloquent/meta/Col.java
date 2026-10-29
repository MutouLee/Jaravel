package io.jaravel.eloquent.meta;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 类型化列引用。**Eloquent 类型安全的支点**(V1 验证已确认)。
 *
 * <p>两个类型参数各有分工:
 * <ul>
 *   <li>{@code M} —— 保证列属于正确的模型。把 {@code Post_.title} 用在
 *       {@code User.query()} 上会编译失败:
 *       {@code Col<Post,String> cannot be converted to Col<User,?>};
 *   <li>{@code V} —— 保证比较值类型正确。{@code where(User_.name, 123)}
 *       编译失败:{@code int cannot be converted to String}。
 * </ul>
 *
 * <p>读写走方法引用({@code u -> u.name} / {@code (u,v) -> u.name = v}),
 * 由 APT 生成时填入 —— 因此运行时读写模型属性**既无反射也无方法调用间接层**。
 * 这是「零运行时反射」承诺的一部分,也是 public 字段(方案 E')带来的直接收益。
 *
 * @param <M> 所属模型
 * @param <V> 列的 Java 类型
 */
public final class Col<M, V> {

    private final String name;
    /**
     * 字段的声明类型。
     *
     * <p>是 {@code Class<?>} 而非 {@code Class<V>}:泛型字段
     * ({@code Map<String,Object>})没有对应的类字面量,写成 {@code Class<V>}
     * 只对非泛型类型成立 —— 那是个会在生成代码里炸掉的谎。
     * 这里存擦除后的类型,只用于诊断和取值兜底。
     */
    private final Class<?> type;
    private final int index;
    private final Function<M, V> getter;
    private final BiConsumer<M, V> setter;
    private final Cast<V, ?> cast;
    private final boolean hidden;

    private Col(String name, Class<?> type, int index,
                Function<M, V> getter, BiConsumer<M, V> setter,
                Cast<V, ?> cast, boolean hidden) {
        this.name = name;
        this.type = type;
        this.index = index;
        this.getter = getter;
        this.setter = setter;
        this.cast = cast;
        this.hidden = hidden;
    }

    public static <M, V> Col<M, V> of(String name, Class<?> type, int index,
                                      Function<M, V> getter, BiConsumer<M, V> setter) {
        return new Col<>(name, type, index, getter, setter, null, false);
    }

    /** 带类型转换器的列({@code @Cast}) */
    public static <M, V> Col<M, V> cast(String name, Class<?> type, int index,
                                        Function<M, V> getter, BiConsumer<M, V> setter,
                                        Cast<V, ?> cast) {
        return new Col<>(name, type, index, getter, setter, cast, false);
    }

    /** 序列化时排除({@code @Hidden}) */
    public Col<M, V> asHidden() {
        return new Col<>(name, type, index, getter, setter, cast, true);
    }

    // ---------- 读取 ----------

    public String name()      { return name; }
    public Class<?> type()    { return type; }
    /** 在模型的列数组中的位置,快照脏检查按它定位 */
    public int index()        { return index; }
    public boolean isHidden() { return hidden; }
    public boolean hasCast()  { return cast != null; }

    public V get(M model)              { return getter.apply(model); }
    public void set(M model, V value)  { setter.accept(model, value); }

    /** 模型值 → 数据库值 */
    @SuppressWarnings("unchecked")
    public Object toDatabase(M model) {
        V value = get(model);
        return cast == null ? value : ((Cast<V, Object>) cast).toDatabase(value);
    }

    /** 数据库值 → 模型值,并写入模型 */
    @SuppressWarnings("unchecked")
    public void fromDatabase(M model, Object raw) {
        if (cast != null) {
            set(model, ((Cast<V, Object>) cast).fromDatabase(raw));
            return;
        }
        if (raw == null) {
            set(model, null);
            return;
        }
        if (type.isInstance(raw)) {
            set(model, (V) raw);
            return;
        }
        set(model, (V) io.jaravel.support.Cast.to(raw, type));
    }

    /**
     * 快照里存什么。
     *
     * <p>可变对象(List / Map / byte[])必须存**序列化后的形态**而非引用 ——
     * 否则 {@code post.tags.add("x")} 这种原地修改,快照与当前值指向同一对象,
     * 永远比不出差异。V1 验证时记录的已知缺陷就是这个,在这里解决。
     *
     * <p>注:Hibernate 的脏检查有完全相同的问题,这不是方案 E' 特有的。
     */
    public Object snapshot(M model) {
        V value = get(model);
        if (value == null) return null;
        if (cast != null) return toDatabase(model);           // 转换后通常是不可变的标量
        if (value instanceof java.util.Collection<?> c) return java.util.List.copyOf(c);
        if (value instanceof java.util.Map<?, ?> m) return java.util.Map.copyOf(m);
        if (value.getClass().isArray()) return deepArrayCopy(value);
        return value;
    }

    private static Object deepArrayCopy(Object array) {
        int n = java.lang.reflect.Array.getLength(array);
        Object copy = java.lang.reflect.Array.newInstance(
                array.getClass().getComponentType(), n);
        System.arraycopy(array, 0, copy, 0, n);
        // 数组的 equals 是引用相等,包成 List 才能按内容比较
        var out = new java.util.ArrayList<Object>(n);
        for (int i = 0; i < n; i++) out.add(java.lang.reflect.Array.get(copy, i));
        return out;
    }

    /** 当前值与快照值是否不同 */
    public boolean differsFrom(M model, Object snapshotValue) {
        return !java.util.Objects.equals(snapshot(model), snapshotValue);
    }

    @Override public String toString() { return name; }

    @Override public boolean equals(Object o) {
        return o instanceof Col<?, ?> c && name.equals(c.name) && index == c.index;
    }

    @Override public int hashCode() { return name.hashCode() * 31 + index; }
}
