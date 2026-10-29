package io.jaravel.eloquent.it.models;

import io.jaravel.eloquent.annotations.HasMany;
import io.jaravel.eloquent.annotations.HasManyThrough;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/** HasManyThrough 的起点:country → users → posts */
@Model(timestamps = false)
@HasMany(name = "users", target = User.class)
@HasManyThrough(name = "posts", target = Post.class, through = User.class,
                firstKey = "country_id", secondKey = "user_id")
public class Country extends CountryBase {
    @Id public Long id;
    public String name;
}
