package app.models;

import net.dobeen.jaravel.eloquent.annotations.BelongsToMany;
import net.dobeen.jaravel.eloquent.annotations.Fillable;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;
import net.dobeen.jaravel.eloquent.annotations.UseFactory;

@Model(timestamps = false)
@Fillable({"name", "slug"})
@BelongsToMany(name = "posts", target = Post.class, pivot = "post_tag")
@UseFactory(database.factories.TagFactory.class)
public class Tag extends TagBase {

    @Id public Long id;
    public String name;
    public String slug;
}
