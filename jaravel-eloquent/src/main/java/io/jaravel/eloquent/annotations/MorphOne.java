package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多态一对一。目标表上有 {@code <morphName>_id} 与
 * {@code <morphName>_type} 两列：
 *
 * <pre>{@code
 * @MorphOne(name = "image", target = Image.class, morphName = "imageable")
 * }</pre>
 *
 * <p>与 {@link MorphMany} 的**查询完全相同**，差别只在
 * 返回 {@code Rel1<T>}。典型用法是「一个帖子只有一张封面图」。
 *
 * <p>⚠ 同 {@link HasOneThrough}：没有唯一约束时它只是一个断言。
 * 多态关联尤其容易出这个问题 —— 多态表很少能在
 * {@code (xxx_id, xxx_type)} 上加唯一索引，因为那会挤到
 * 其它宿主类型。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(MorphOne.List.class)
public @interface MorphOne {
    String name();
    Class<?> target();

    /** 多态列前缀。留空用关联名。 */
    String morphName() default "";

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { MorphOne[] value(); }
}
