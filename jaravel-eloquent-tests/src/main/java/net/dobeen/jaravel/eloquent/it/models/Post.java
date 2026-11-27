package net.dobeen.jaravel.eloquent.it.models;

import java.time.Instant;

import net.dobeen.jaravel.eloquent.annotations.BelongsTo;
import net.dobeen.jaravel.eloquent.annotations.BelongsToMany;
import net.dobeen.jaravel.eloquent.annotations.Guarded;
import net.dobeen.jaravel.eloquent.annotations.HasMany;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;
import net.dobeen.jaravel.eloquent.annotations.MorphMany;
import net.dobeen.jaravel.eloquent.annotations.MorphOne;
import net.dobeen.jaravel.eloquent.annotations.Scope;
import net.dobeen.jaravel.eloquent.query.Query;

@Model
@Guarded({"id"})
@BelongsTo(name = "author", target = User.class, foreignKey = "user_id")
@HasMany(name = "comments", target = Comment.class)
@BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag", pivotTimestamps = false)
@MorphMany(name = "images", target = Image.class, morphName = "imageable")
@MorphOne(name = "cover", target = Image.class, morphName = "imageable")
public class Post extends PostBase {

    @Id public Long id;
    public String title;
    public String body;
    public Long userId;
    public boolean published;
    public int views;
    public Instant createdAt;
    public Instant updatedAt;

    @Scope
    public static Query<Post> published(Query<Post> q) {
        return q.where(Post_.published, true);
    }

    @Scope
    public static Query<Post> popular(Query<Post> q, int minViews) {
        return q.where(Post_.views, net.dobeen.jaravel.database.query.QueryState.Op.GTE, minViews);
    }
}
