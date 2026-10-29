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

    /** GET /api/posts/{id} */
    public Object apiShow(Long id) {
        return Post.query().withAuthor().withComments().whereId(id).firstOrFail();
    }
}
