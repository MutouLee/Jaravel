package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多态一对多。目标表上有 {@code <morphName>_id} 与 {@code <morphName>_type} 两列。
 *
 * <pre>{@code
 * @MorphMany(name = "comments", target = Comment.class, morphName = "commentable")
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(MorphMany.List.class)
public @interface MorphMany {
    String name();
    Class<?> target();

    /** 多态列前缀。留空用关联名。 */
    String morphName() default "";

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { MorphMany[] value(); }
}
