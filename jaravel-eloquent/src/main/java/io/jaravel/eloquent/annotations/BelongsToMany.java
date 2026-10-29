package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多对多(经中间表)。生成 {@code RelMany<Target>} 访问器,
 * 带 {@code attach / detach / sync / toggle} 等中间表操作。
 *
 * <pre>{@code
 * @BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag")
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(BelongsToMany.List.class)
public @interface BelongsToMany {
    String name();
    Class<?> target();

    /** 中间表名。留空按约定:两表名单数按字典序拼接({@code post_tag})。 */
    String pivot() default "";

    /** 中间表上指向本模型的列。留空:{@code <本模型单数>_id} */
    String foreignPivotKey() default "";

    /** 中间表上指向目标的列。留空:{@code <目标单数>_id} */
    String relatedPivotKey() default "";

    /** 需要一并读出的中间表额外列 */
    String[] withPivot() default {};

    /** 中间表是否有 created_at / updated_at */
    boolean pivotTimestamps() default false;

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { BelongsToMany[] value(); }
}
