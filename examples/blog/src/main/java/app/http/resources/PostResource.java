package app.http.resources;

import java.util.Map;

import app.models.Post;
import io.jaravel.api.JsonResource;
import io.jaravel.http.Request;

/**
 * 文章的 API 输出形状(docs/12 §1)。
 *
 * <p>两个关联字段都用 {@code putWhenLoaded*}:**资源层绝不触发懒加载**。
 * 这个接口预加载了 author、没有预加载 comments,
 * 所以响应里有 {@code author} 而没有 {@code comments} ——
 * 那是一个可见的提示,而不是一次看不见的 N+1。
 */
public class PostResource extends JsonResource<Post> {

    public PostResource(Post post) {
        super(post);
    }

    @Override
    public Map<String, Object> toArray(Request request) {
        return map(request)
                .put("id", model.id)
                .put("title", model.title)
                .put("slug", model.slug)
                .put("views", model.views)
                .putWhen(model.published, "published_at", () -> model.createdAt)
                .putWhenLoadedOne("author", model.author(), AuthorResource::new)
                .putWhenLoaded("comment_count", model.comments(), java.util.List::size)
                .build();
    }

    @Override
    public Map<String, Object> with(Request request) {
        return Map.of("meta", Map.of("version", "1.0"));
    }
}
