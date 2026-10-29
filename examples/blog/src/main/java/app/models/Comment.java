package app.models;

import io.jaravel.eloquent.annotations.BelongsTo;
import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.eloquent.annotations.Scope;
import io.jaravel.eloquent.query.Query;

@Model
@Fillable({"body", "author_name"})
@BelongsTo(name = "post", target = Post.class)
public class Comment extends CommentBase {

    @Id public Long id;
    public Long postId;
    public String authorName;
    public String body;
    public boolean approved;
    public java.time.Instant createdAt;
    public java.time.Instant updatedAt;

    @Scope
    public static Query<Comment> approved(Query<Comment> q) {
        return q.where(Comment_.approved, true);
    }
}
