package io.jaravel.http.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 绑定任意输入(查询串 + 表单体 + JSON 体),与 {@code request.input(name)} 同源。
 *
 * <p>与 {@link Query} 分开是因为「我明确要查询串里的」和「哪来的都行」
 * 是两种不同的意图,而在 POST 请求里这两者会给出不同的值。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Input {
    String value();

    String defaultValue() default "";

    boolean required() default false;
}
