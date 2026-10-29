package io.jaravel.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 Eloquent 模型可用于认证,并声明相关列。
 *
 * <pre>{@code
 * @Model
 * @AuthModel(password = "password", rememberToken = "remember_token")
 * public class User extends UserBase {
 *     @Id public Long id;
 *     public String email;
 *     @Hidden public String password;
 *     @Hidden public String rememberToken;
 * }
 * }</pre>
 *
 * <p>**运行时保留**(不像 Eloquent 的其它注解是 SOURCE)——
 * {@code EloquentUserProvider} 在运行时读它来定位列,从而让用户的模型
 * 不必实现 {@link Authenticatable} 的四个方法。
 *
 * <p>docs/08 §3.1 原本打算用 APT 生成 Authenticatable 实现。换成运行时读注解
 * 是因为:这里需要的信息只有三个列名,而 APT 的收益(编译期校验、零反射)
 * 在「每次登录一次」这个频率上无足轻重 —— 不值得再加一轮代码生成。
 * 列名写错会在首次登录时报错并列出模型的所有列。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AuthModel {

    /** 主键列。留空表示用模型的主键。 */
    String identifier() default "";

    /** 密码列。设为空字符串表示该模型不用密码登录(纯 Token / OAuth)。 */
    String password() default "password";

    /** remember me 令牌列。留空表示不支持 remember me。 */
    String rememberToken() default "remember_token";
}
