package net.dobeen.jaravel.support;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 带泛型参数的类型标记 —— {@code Class<T>} 装不下 {@code List<Post>} 时用它。
 *
 * <pre>{@code
 * List<Post> posts = Cache.get("posts", new TypeRef<List<Post>>() {}).orElse(List.of());
 * }</pre>
 *
 * <h2>为什么需要它</h2>
 * <p>Java 的泛型在运行时被擦除,所以 {@code List<Post>.class} 这种写法不存在,
 * 而反序列化**必须**知道元素类型 —— 只给 {@code List.class} 会得到一个
 * {@code List<Map<String,Object>>},然后在第一次 {@code posts.get(0).title}
 * 时抛 ClassCastException,离真正的原因(取缓存时没说元素类型)很远。
 *
 * <p>匿名子类({@code new TypeRef<List<Post>>() {}})把泛型参数留在了
 * **class 文件的签名里**,于是 {@link #type()} 能把它读回来。
 * 这是 Java 里唯一可行的办法,Jackson 的 {@code TypeReference}、
 * Guava 的 {@code TypeToken} 都是同一招。
 *
 * <h2>为什么不直接用 Jackson 的 TypeReference</h2>
 * <p>那会把 Jackson 写进框架的**公开 API**:缓存的 caffeine 驱动根本不
 * 序列化(存的是原对象),而用户为了取一个 {@code List<Post>} 却要
 * {@code import com.fasterxml.jackson...}。换序列化实现时,那个 import
 * 会出现在所有用户代码里。
 *
 * <p>所以这里只暴露一个 {@link Type} —— 各模块自己把它转成本地的表示
 * (缓存那边是 {@code TypeFactory.constructType(ref.type())})。
 */
public abstract class TypeRef<T> {

    private final Type type;

    protected TypeRef() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType parameterized)) {
            throw new IllegalArgumentException("""
                    TypeRef 的泛型参数必须写出来,而且只能往下继承一层:
                      new TypeRef<List<Post>>() {}               ← 注意末尾的 {}
                      class Posts extends TypeRef<List<Post>> {} ← 也可以
                    拿不到类型参数的情形:
                      new TypeRef() {}                           ← 裸类型,擦除后什么都不剩
                      class Deeper extends Posts {}              ← 隔了一层,签名里是 Posts""");
        }
        this.type = parameterized.getActualTypeArguments()[0];
    }

    /** 捕获到的类型,例如 {@code java.util.List<app.models.Post>} */
    public final Type type() {
        return type;
    }

    @Override
    public final String toString() {
        return "TypeRef<" + type.getTypeName() + ">";
    }

    @Override
    public final boolean equals(Object other) {
        return other instanceof TypeRef<?> that && type.equals(that.type);
    }

    @Override
    public final int hashCode() {
        return type.hashCode();
    }
}
