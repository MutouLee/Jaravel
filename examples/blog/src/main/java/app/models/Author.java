package app.models;

import net.dobeen.jaravel.eloquent.annotations.Fillable;
import net.dobeen.jaravel.eloquent.annotations.HasMany;
import net.dobeen.jaravel.eloquent.annotations.Hidden;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;
import net.dobeen.jaravel.eloquent.annotations.UseFactory;

@Model
@Fillable({"name", "email"})
@HasMany(name = "posts", target = Post.class, foreignKey = "author_id")
@UseFactory(database.factories.AuthorFactory.class)
public class Author extends AuthorBase {

    @Id public Long id;
    public String name;
    public String email;
    @Hidden public String password;
    public java.time.Instant createdAt;
    public java.time.Instant updatedAt;
}
