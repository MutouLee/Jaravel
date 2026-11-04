package io.jaravel.console;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一个位置参数(docs/13 §3):{@code jaravel make:model Post} 里的 {@code Post}。
 *
 * <p>顺序**按字段声明顺序**。Java 反射保证同一个类里字段的声明顺序
 * (getDeclaredFields 对 javac 的产物是稳定的),所以不必让作者再写一个
 * {@code index = 0} —— 那是一个可以与声明顺序不一致的事实来源。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Argument {

    String description() default "";

    /** 必填的参数缺失时报错并打印用法,而不是用 null 继续跑 */
    boolean required() default true;
}
