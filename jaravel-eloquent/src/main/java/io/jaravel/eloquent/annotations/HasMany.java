package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一对多。APT 生成 {@code Rel<Target> <name>()} 访问器。
 *
 * <pre>{@code
 * @HasMany(name = "comments", target = Comment.class)
 * public class Post extends PostBase { ... }
 * // 使用:post.comments().get()  /  post.comments().create(...)
 * }</pre>
 *
 * <p>注解在<b>类级</b>而非字段级:V1 验证确认 public 字段既无法拦截懒加载,
 * 又会被用户的显式初始化器静默覆盖。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(HasMany.List.class)
public @interface HasMany {
    /** 关联名。同时是访问器方法名与 {@code with(...)} 里的路径段。 */
    String name();

    Class<?> target();

    /** 目标表上指向本模型的外键。留空按约定:{@code <本模型单数>_id}。 */
    String foreignKey() default "";

    /** 本模型上被引用的列。留空用主键。 */
    String localKey() default "";

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { HasMany[] value(); }
}
