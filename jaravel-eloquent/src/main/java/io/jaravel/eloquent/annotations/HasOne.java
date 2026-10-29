package io.jaravel.eloquent.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 一对一(本模型是「一」的一方,外键在目标表上)。生成 {@code Rel1<Target>} 访问器。 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@Repeatable(HasOne.List.class)
public @interface HasOne {
    String name();
    Class<?> target();
    String foreignKey() default "";
    String localKey() default "";

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    @interface List { HasOne[] value(); }
}
