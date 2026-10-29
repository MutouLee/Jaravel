package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多对一(外键在<b>本</b>模型上)。生成 {@code Rel1<Target>} 访问器。
 *
 * <pre>{@code
 * @BelongsTo(name = "author", target = User.class, foreignKey = "user_id")
 * }</pre>
 *
 * <p>{@code foreignKey} 留空时按约定推导为 {@code <目标表单数>_id};
 * 推导出的列在本模型上不存在时编译报错,并列出已声明的字段。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(BelongsTo.List.class)
public @interface BelongsTo {
    String name();
    Class<?> target();

    /** 本模型上的外键列。留空按约定:{@code <目标单数>_id}。 */
    String foreignKey() default "";

    /** 目标表上被引用的列。留空用目标的主键。 */
    String ownerKey() default "";

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { BelongsTo[] value(); }
}
