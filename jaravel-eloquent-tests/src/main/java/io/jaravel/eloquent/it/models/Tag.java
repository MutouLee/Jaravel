package io.jaravel.eloquent.it.models;

import io.jaravel.eloquent.annotations.BelongsToMany;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

@Model(timestamps = false)
@BelongsToMany(name = "posts", target = Post.class)
public class Tag extends TagBase {
    @Id public Long id;
    public String name;
}
