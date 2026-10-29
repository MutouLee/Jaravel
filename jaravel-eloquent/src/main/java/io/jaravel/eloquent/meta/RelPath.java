package io.jaravel.eloquent.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.query.Query;

/**
 * 类型安全的关联路径,用于预加载。
 *
 * <pre>{@code
 * Post.query().with(Post_.author).get();
 * Post.query().with(Post_.comments.then(Comment_.author)).get();
 * Post.query().with(Post_.comments, q -> q.latest().limit(5)).get();
 * }</pre>
 *
 * <p>{@code M}/{@code T} 两个类型参数让**跨模型拼错路径在编译期暴露**
 * (V1 验证已确认):
 * <pre>
 * required: RelPath&lt;Post,N&gt;
 * found:    RelPath&lt;User,Post&gt;
 * </pre>
 * 这是 Laravel 的字符串 {@code "comments.author"} 做不到的。
 */
public final class RelPath<M extends Model<M>, T extends Model<T>> {

    private final List<String> segments;
    private final Class<T> leaf;
    /** 预加载时附加到子查询上的约束 */
    private final Consumer<Query<?>> constraint;

    private RelPath(List<String> segments, Class<T> leaf, Consumer<Query<?>> constraint) {
        this.segments = segments;
        this.leaf = leaf;
        this.constraint = constraint;
    }

    public static <M extends Model<M>, T extends Model<T>> RelPath<M, T> of(String name, Class<T> target) {
        return new RelPath<>(List.of(name), target, null);
    }

    /**
     * 接在上一段的**叶子模型**的关联上。类型由 {@code T} 约束,
     * 因此接错模型会编译失败。
     */
    public <N extends Model<N>> RelPath<M, N> then(RelPath<T, N> next) {
        var merged = new ArrayList<>(segments);
        merged.addAll(next.segments);
        return new RelPath<>(List.copyOf(merged), next.leaf, next.constraint);
    }

    /**
     * 给这一层的预加载加约束。
     * {@code with(Post_.comments.where(q -> q.where(Comment_.approved, true)))}
     */
    @SuppressWarnings("unchecked")
    public RelPath<M, T> where(Consumer<Query<T>> constraint) {
        return new RelPath<>(segments, leaf, q -> constraint.accept((Query<T>) q));
    }

    public List<String> segments()            { return segments; }
    public Class<T> leaf()                    { return leaf; }
    public String path()                      { return String.join(".", segments); }
    public Consumer<Query<?>> constraint()     { return constraint; }
    public boolean hasConstraint()             { return constraint != null; }

    /** 第一段 —— 预加载按层处理时需要 */
    public String head() { return segments.get(0); }

    /**
     * 去掉第一段后的剩余路径;只有一段时返回 null。
     *
     * <p>返回类型用原始 {@code RelPath}:下一层的 owner 类型在编译期无法表达
     * (它取决于上一段关联的目标)。这个类型擦除只发生在预加载器内部,
     * 用户面对的 {@code with(...)} 全程类型安全。
     */
    @SuppressWarnings("rawtypes")
    public RelPath tail() {
        if (segments.size() <= 1) return null;
        return new RelPath(segments.subList(1, segments.size()), leaf, constraint);
    }

    @Override public String toString() { return path(); }

    @Override public boolean equals(Object o) {
        return o instanceof RelPath<?, ?> p && segments.equals(p.segments);
    }

    @Override public int hashCode() { return segments.hashCode(); }
}
