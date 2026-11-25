package app.models;

import io.jaravel.eloquent.annotations.BelongsToMany;
import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.eloquent.annotations.UseFactory;

@Model(timestamps = false)
@Fillable({"name", "slug"})
@BelongsToMany(name = "posts", target = Post.class, pivot = "post_tag")
@UseFactory(database.factories.TagFactory.class)
public class Tag extends TagBase {

    @Id public Long id;
    public String name;
    public String slug;
}
