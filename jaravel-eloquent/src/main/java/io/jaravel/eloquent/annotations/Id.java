package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 主键。一个模型必须且只能有一个。
 *
 * <p>未标注时 APT 会寻找名为 {@code id} 的字段;都没有则编译报错。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.FIELD)
public @interface Id {
    /** 列名。留空则用字段名的 snake_case。 */
    String name() default "";

    /** 是否数据库自增。UUID / ULID 主键应置 false。 */
    boolean incrementing() default true;
}
