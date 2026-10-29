package app.models;

import java.time.Instant;

import io.jaravel.eloquent.annotations.BelongsTo;
import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.eloquent.annotations.Scope;
import io.jaravel.eloquent.query.Query;

/** 用来演示 Policy 授权的资源:每条笔记属于一个用户 */
@Model
@Fillable({"title", "body"})
@BelongsTo(name = "owner", target = User.class, foreignKey = "user_id")
public class Note extends NoteBase {

    @Id public Long id;
    public Long userId;
    public String title;
    public String body;
    public Instant createdAt;
    public Instant updatedAt;

    @Scope
    public static Query<Note> ownedBy(Query<Note> q, Long userId) {
        return q.where(Note_.userId, userId);
    }
}
