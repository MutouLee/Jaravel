package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多态反向关联。本模型上有 {@code <morphName>_id} 与 {@code <morphName>_type}。
 *
 * <p><b>必须显式列出可能的目标类型</b>({@code types}):
 * 这让 APT 能生成基于 {@code sealed} 风格的类型安全访问 —— 配合 Java 21 的
 * 模式匹配,比 Laravel 返回一个无类型的 Model 强得多:
 *
 * <pre>{@code
 * switch (image.imageable().get()) {
 *     case Post p -> p.title;
 *     case User u -> u.name;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(MorphTo.List.class)
public @interface MorphTo {
    String name();

    /** 可能的目标类型。为空则编译报错 —— 无类型的多态关联没有价值。 */
    Class<?>[] types();

    String morphName() default "";

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { MorphTo[] value(); }
}
