package net.dobeen.jaravel.eloquent.it.models;

import net.dobeen.jaravel.eloquent.annotations.BelongsToMany;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

@Model(timestamps = false)
@BelongsToMany(name = "posts", target = Post.class)
public class Tag extends TagBase {
    @Id public Long id;
    public String name;
}
