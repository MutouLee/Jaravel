package net.dobeen.jaravel.eloquent.annotations;

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

    /**
     * 列名。留空则用字段名的 snake_case。
     *
     * <p>★ 叫 {@code column} 而不是 {@code name}:这套注解里
     * {@code name} 已经是**关联名**的意思({@code @HasMany(name = "notes")}),
     * 而列名在 {@code @SoftDeletes(column = ...)} 上也叫 column。
     * 同一个词在两处指不同的东西,是个没人记得住的规则 ——
     * 所以三个词各管一件事:{@code name} = 关联名、{@code column} = 列名、
     * {@code value} = 单属性注解的那个值({@code @Column("post_id")})。
     */
    String column() default "";

    /** 是否数据库自增。UUID / ULID 主键应置 false。 */
    boolean incrementing() default true;
}
