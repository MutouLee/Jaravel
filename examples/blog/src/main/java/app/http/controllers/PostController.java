package app.http.controllers;

import org.springframework.stereotype.Component;

import app.http.requests.StorePostRequest;
import app.models.Author;
import app.models.Comment_;
import app.models.Post;
import app.models.Post_;
import app.models.Tag;
import io.jaravel.http.Redirect;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.http.View;

/**
 * 文章控制器。**零裸 SQL** —— 全部经 Eloquent。
 */
@Component
public class PostController {

    /** GET / */
    public View index(Request request) {
        var query = Post.query().published()
                .withAuthor()
                .withTags()
                .orderByKeyDesc();

        request.input("q").ifPresent(query::search);

        return View.of("posts.index").with("posts", query.get().toList());
    }

    /** GET /posts/{slug} */
    public View show(String slug) {
        Post post = Post.query().whereSlug(slug)
                .withAuthor()
                .withTags()
                .withComments(q -> q.where(Comment_.approved, true).orderByKeyDesc())
                .first()
                .orElseThrow(() -> new io.jaravel.http.HttpException(404, "文章不存在:" + slug));

        post.increment(Post_.views, 1);
        return View.of("posts.show").with("post", post);
    }

    /** GET /posts/create */
    public View create() {
        return View.of("posts.form")
                   .with("authors", Author.all().toList())
                   .with("tags", Tag.all().toList());
    }

    /** POST /posts */
    public Response store(StorePostRequest request) {
        Post post = Post.create(request.attributes_());
        post.tags().sync(request.tagIds());
        return Redirect.to("/posts/" + post.slug).with("status", "已发布");
    }

    /** POST /posts/{id}/delete —— 软删除 */
    public Response destroy(Long id) {
        Post.findOrFail(id).delete();
        return Redirect.to("/").with("status", "已移入回收站");
    }

    /** POST /posts/{id}/restore */
    public Response restore(Long id) {
        Post.withTrashed().whereId(id).firstOrFail().restore();
        return Redirect.to("/").with("status", "已恢复");
    }

    /** GET /api/posts */
    public Object apiIndex() {
        return Post.query().published().withAuthor().get().toList();
    }

    /**
     * GET /api/posts/paged?page=2 —— 分页(docs/12 §2)。
     *
     * <p>页码不用自己读:{@code paginate(5)} 从请求里取 {@code ?page=N}
     * (starter 装了 resolver),返回的 JSON 是 docs/12 §2.2 那个
     * 与 Laravel 一致的 {@code data / links / meta} 形状。
     */
    public Object apiPaged() {
        return Post.query().published().withAuthor().paginate(5);
    }

    /**
     * GET /api/posts/resources —— 分页 + 资源(docs/12 §2.3)。
     *
     * <p>注意响应里**没有** {@code comment_count}:这个接口预加载了 author、
     * 没有预加载 comments。资源层绝不触发懒加载,所以那个字段直接不输出 ——
     * 一个可见的提示,而不是一次看不见的 N+1。
     */
    public Object apiResources() {
        return app.http.resources.PostResource.collection(
                Post.query().published().withAuthor().paginate(5),
                app.http.resources.PostResource::new);
    }

    /**
     * GET /api/posts/cursor?cursor=… —— 游标分页(docs/12 §2.1)。
     *
     * <p>无限滚动用这个:它不数总行数、翻页期间有人插入新行也不会跳行。
     */
    public Object apiCursor() {
        return Post.query().published().orderByDesc(Post_.createdAt).cursorPaginate(3);
    }

    /**
     * GET /api/posts/cached —— 条件请求(docs/12 §6.1)。
     *
     * <p>{@code .etag()} 让框架按**最终字节**算摘要;客户端带着
     * {@code If-None-Match} 再来时回 304、不传正文。
     */
    public Object apiCached() {
        return io.jaravel.http.Response
                .json(Post.query().published().get().toList())
                .etag()
                .cacheControl("public, max-age=300");
    }

    /**
     * GET /api/posts/{id} —— 单个资源(docs/12 §1)。
     *
     * <p>这个接口预加载了 author **与** comments,所以两个字段都在。
     * 最外层会包一层 {@code data},并带上资源自己的 {@code with}
     * ({@code meta.version})—— 那个包裹只在根位置加一次。
     */
    public Object apiShow(Long id) {
        return new app.http.resources.PostResource(
                Post.query().withAuthor().withComments().whereId(id).firstOrFail());
    }
}
