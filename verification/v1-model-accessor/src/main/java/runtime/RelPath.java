package runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * 类型安全的关联路径,用于预加载:
 *     .with(User_.posts)
 *     .with(User_.posts.then(Post_.comments).then(Comment_.author))
 *
 * 泛型 <M, T> 让「跨模型拼错路径」在编译期暴露 —— 这是 Laravel 的字符串
 * "posts.comments.author" 做不到的。
 */
public final class RelPath<M, T> {

    private final List<String> segments;
    private final Class<T> leaf;

    private RelPath(List<String> segments, Class<T> leaf) {
        this.segments = segments;
        this.leaf = leaf;
    }

    public static <M, T> RelPath<M, T> of(String name, Class<T> target) {
        return new RelPath<>(List.of(name), target);
    }

    /** 只能接在「上一段的叶子模型」的关联上,类型由 T 约束 */
    public <N> RelPath<M, N> then(RelPath<T, N> next) {
        List<String> merged = new ArrayList<>(segments);
        merged.addAll(next.segments);
        return new RelPath<>(List.copyOf(merged), next.leaf);
    }

    public List<String> segments() { return segments; }
    public Class<T> leaf()         { return leaf; }
    public String path()           { return String.join(".", segments); }
    @Override public String toString() { return path(); }
}
