package io.jaravel.http.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 绑定路径参数:{@code /posts/{id}} → {@code @Path("id") Long id}。
 *
 * <p>**多数情况不需要写它** —— 只要编译时带了 {@code -parameters}
 * (Jaravel 的脚手架默认带),参数名就与 {@code {id}} 自动对上。
 * 需要显式写的场合只有两个:参数名与路径里的名字不一致,
 * 或者项目没开 {@code -parameters}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Path {
    String value();
}
