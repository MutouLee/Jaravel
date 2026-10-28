package runtime;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 类型化列引用。两个泛型参数是 Eloquent 类型安全的支点:
 *   M —— 保证列属于正确的模型(不能把 Post_.title 用在 User.query() 上)
 *   V —— 保证比较值类型正确(where(User_.name, 123) 编译失败)
 *
 * getter/setter 是方法引用,由 APT 生成时填入 —— 这让运行时读写字段
 * 不需要反射,是「零运行时反射」承诺的一部分。
 */
public final class Col<M, V> {

    private final String name;
    private final Class<V> type;
    private final Function<M, V> getter;
    private final BiConsumer<M, V> setter;
    private final int index;

    private Col(String name, Class<V> type, int index,
                Function<M, V> getter, BiConsumer<M, V> setter) {
        this.name = name;
        this.type = type;
        this.index = index;
        this.getter = getter;
        this.setter = setter;
    }

    public static <M, V> Col<M, V> of(String name, Class<V> type, int index,
                                      Function<M, V> getter, BiConsumer<M, V> setter) {
        return new Col<>(name, type, index, getter, setter);
    }

    public String name()      { return name; }
    public Class<V> type()    { return type; }
    public int index()        { return index; }
    public V get(M model)     { return getter.apply(model); }
    public void set(M m, V v) { setter.accept(m, v); }

    @Override public String toString() { return name; }
}
