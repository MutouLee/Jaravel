package net.dobeen.jaravel.eloquent.it.models;

import java.time.Instant;

import net.dobeen.jaravel.eloquent.annotations.BelongsTo;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

@Model
@BelongsTo(name = "post", target = Post.class)
@BelongsTo(name = "author", target = User.class, foreignKey = "user_id")
public class Comment extends CommentBase {
    @Id public Long id;
    public Long postId;
    public Long userId;
    public String body;
    public boolean approved;
    public Instant createdAt;
    public Instant updatedAt;
}
