package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 跨中间模型的一对多。{@code Country → User → Post}:
 *
 * <pre>{@code
 * @HasManyThrough(name = "posts", target = Post.class, through = User.class)
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(HasManyThrough.List.class)
public @interface HasManyThrough {
    String name();
    Class<?> target();
    Class<?> through();

    /** 中间模型上指向本模型的外键。留空:{@code <本模型单数>_id} */
    String firstKey() default "";

    /** 目标上指向中间模型的外键。留空:{@code <中间模型单数>_id} */
    String secondKey() default "";

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { HasManyThrough[] value(); }
}
