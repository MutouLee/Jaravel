package app.models;

import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.HasMany;
import io.jaravel.eloquent.annotations.Hidden;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

@Model
@Fillable({"name", "email"})
@HasMany(name = "posts", target = Post.class, foreignKey = "author_id")
public class Author extends AuthorBase {

    @Id public Long id;
    public String name;
    public String email;
    @Hidden public String password;
    public java.time.Instant createdAt;
    public java.time.Instant updatedAt;
}
