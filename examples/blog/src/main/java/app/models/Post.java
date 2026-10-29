package app.models;

import java.time.Instant;

import io.jaravel.eloquent.annotations.BelongsTo;
import io.jaravel.eloquent.annotations.BelongsToMany;
import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.HasMany;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.eloquent.annotations.Scope;
import io.jaravel.eloquent.annotations.SoftDeletes;
import io.jaravel.eloquent.query.Query;

@Model
@SoftDeletes
@Fillable({"title", "slug", "body", "author_id", "published"})
@BelongsTo(name = "author", target = Author.class)
@HasMany(name = "comments", target = Comment.class)
@BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag")
public class Post extends PostBase {

    @Id public Long id;
    public String title;
    public String slug;
    public String body;
    public Long authorId;
    public boolean published;
    public int views;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant deletedAt;

    @Scope
    public static Query<Post> published(Query<Post> q) {
        return q.where(Post_.published, true);
    }

    @Scope
    public static Query<Post> search(Query<Post> q, String keyword) {
        return q.whereLike(Post_.title, "%" + keyword + "%");
    }

    public String excerpt() {
        return body.length() <= 80 ? body : body.substring(0, 80) + "…";
    }
}
