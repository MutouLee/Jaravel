package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 跨中间模型的一对一。{@code Supplier → User → History}：
 *
 * <pre>{@code
 * @HasOneThrough(name = "latestHistory", target = History.class, through = User.class)
 * }</pre>
 *
 * <p>它与 {@link HasManyThrough} 的**查询完全相同**，
 * 差别只在返回 {@code Rel1<T>} 而不是 {@code Rel<T>} ——
 * 也就是「取第一条」。
 *
 * <p>★ 但那不意味着它是多余的：一个语义上是「一对一」
 * 的关联如果返回集合，每个调用点都要写一次
 * {@code .first().orElse(null)}，而那些分支里总会有一两个忘了处理空。
 *
 * <p>⚠ **没有数据库级的唯一约束时它只是一个断言**：
 * 若目标表里真的有多条，这里默默取第一条，
 * 而「第一条」在没有 order by 时是不确定的。
 * 要确定就用 {@link HasManyThrough} + 显式排序。
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(HasOneThrough.List.class)
public @interface HasOneThrough {
    String name();
    Class<?> target();
    Class<?> through();

    /** 中间模型上指向本模型的外键。留空：{@code <本模型单数>_id} */
    String firstKey() default "";

    /** 目标上指向中间模型的外键。留空：{@code <中间模型单数>_id} */
    String secondKey() default "";

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { HasOneThrough[] value(); }
}
