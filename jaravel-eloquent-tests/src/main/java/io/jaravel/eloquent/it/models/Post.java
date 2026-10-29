package io.jaravel.eloquent.it.models;

import java.time.Instant;

import io.jaravel.eloquent.annotations.BelongsTo;
import io.jaravel.eloquent.annotations.BelongsToMany;
import io.jaravel.eloquent.annotations.Guarded;
import io.jaravel.eloquent.annotations.HasMany;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.eloquent.annotations.MorphMany;
import io.jaravel.eloquent.annotations.Scope;
import io.jaravel.eloquent.query.Query;

@Model
@Guarded({"id"})
@BelongsTo(name = "author", target = User.class, foreignKey = "user_id")
@HasMany(name = "comments", target = Comment.class)
@BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag", pivotTimestamps = false)
@MorphMany(name = "images", target = Image.class, morphName = "imageable")
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
        return q.where(Post_.views, io.jaravel.database.query.QueryState.Op.GTE, minViews);
    }
}
