package app.models;

import net.dobeen.jaravel.eloquent.annotations.BelongsTo;
import net.dobeen.jaravel.eloquent.annotations.Fillable;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;
import net.dobeen.jaravel.eloquent.annotations.Scope;
import net.dobeen.jaravel.eloquent.annotations.UseFactory;
import net.dobeen.jaravel.eloquent.query.Query;

@Model
@Fillable({"body", "author_name"})
@BelongsTo(name = "post", target = Post.class)
@UseFactory(database.factories.CommentFactory.class)
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
