package app.models;

import java.time.Instant;

import net.dobeen.jaravel.eloquent.annotations.BelongsTo;
import net.dobeen.jaravel.eloquent.annotations.BelongsToMany;
import net.dobeen.jaravel.eloquent.annotations.Fillable;
import net.dobeen.jaravel.eloquent.annotations.HasMany;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;
import net.dobeen.jaravel.eloquent.annotations.Scope;
import net.dobeen.jaravel.eloquent.annotations.SoftDeletes;
import net.dobeen.jaravel.eloquent.annotations.UseFactory;
import net.dobeen.jaravel.eloquent.query.Query;

@Model
@SoftDeletes
@Fillable({"title", "slug", "body", "author_id", "published"})
@BelongsTo(name = "author", target = Author.class)
@HasMany(name = "comments", target = Comment.class)
@BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag")
@UseFactory(database.factories.PostFactory.class)
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
