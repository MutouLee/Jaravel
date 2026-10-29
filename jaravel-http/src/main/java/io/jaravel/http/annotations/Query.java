package io.jaravel.http.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 绑定查询串参数:{@code ?page=2} → {@code @Query("page") int page}。
 *
 * <p>{@code defaultValue} 让「可选参数」不必在方法体里再判一次 null。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Query {
    String value();

    /** 参数缺失时用它。留空表示没有默认值,缺失则为 null / 原始类型零值。 */
    String defaultValue() default "";

    /** 缺失即报 400,而不是回落到默认值 */
    boolean required() default false;
}
