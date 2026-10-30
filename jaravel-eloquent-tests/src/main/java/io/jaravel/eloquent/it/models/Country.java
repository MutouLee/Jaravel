package io.jaravel.eloquent.it.models;

import io.jaravel.eloquent.annotations.HasMany;
import io.jaravel.eloquent.annotations.HasManyThrough;
import io.jaravel.eloquent.annotations.HasOneThrough;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

/** HasManyThrough 的起点:country → users → posts */
@Model(timestamps = false)
@HasMany(name = "users", target = User.class)
@HasManyThrough(name = "posts", target = Post.class, through = User.class,
                firstKey = "country_id", secondKey = "user_id")
// 与上面那条的查询完全相同，只是返回 Rel1 ——
// 两条并存正好证明它们共用机制而不互相干扰
@HasOneThrough(name = "firstPost", target = Post.class, through = User.class,
               firstKey = "country_id", secondKey = "user_id")
public class Country extends CountryBase {
    @Id public Long id;
    public String name;
}
