package io.jaravel.eloquent.it.models;

import java.time.Instant;
import java.util.List;

import io.jaravel.eloquent.annotations.BelongsTo;
import io.jaravel.eloquent.annotations.Fillable;
import io.jaravel.eloquent.annotations.HasMany;
import io.jaravel.eloquent.annotations.HasOne;
import io.jaravel.eloquent.annotations.Hidden;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;
import io.jaravel.eloquent.annotations.MorphMany;
import io.jaravel.eloquent.annotations.Scope;
import io.jaravel.eloquent.annotations.SoftDeletes;
import io.jaravel.eloquent.cast.AsList;
import io.jaravel.eloquent.meta.Cast;
import io.jaravel.eloquent.query.Query;

/**
 * 测试用的「全家桶」模型:覆盖软删除、隐藏列、类型转换、
 * 四种关联和带参作用域。
 */
@Model
@SoftDeletes
@Fillable({"name", "email", "country_id", "roles"})
@HasMany(name = "posts", target = Post.class)
@HasOne(name = "profile", target = Profile.class)
@BelongsTo(name = "country", target = Country.class)
@MorphMany(name = "images", target = Image.class, morphName = "imageable")
public class User extends UserBase {

    @Id public Long id;
    public String name;
    public String email;

    /** 不该出现在 toMap() 里 */
    @Hidden public String password;

    public Long countryId;

    /** 逗号分隔 ↔ List 的类型转换 */
    @Cast.Use(AsList.class)
    public List<String> roles;

    public Instant createdAt;
    public Instant updatedAt;
    public Instant deletedAt;

    /** 带参作用域 —— 生成 UserQuery.named(String) 与 User.named(String) */
    @Scope
    public static Query<User> named(Query<User> q, String value) {
        return q.where(User_.name, value);
    }

    /** 无参作用域 */
    @Scope
    public static Query<User> withEmail(Query<User> q) {
        return q.whereNotNull(User_.email);
    }
}
