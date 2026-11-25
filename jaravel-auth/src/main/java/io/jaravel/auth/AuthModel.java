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

    // ================= 二步验证(docs/08 §6.4)=================
    //
    // ★ 三列都留空 = 这个模型不支持二步验证(默认)。
    //
    // 为什么走注解而不是让模型实现 TwoFactorAuthenticatable 的七个访问器:
    // 与 Authenticatable 同一个理由(见本注解的类注释)—— 那七个方法是
    // 纯样板,写法只有一种,而「样板只有一种写法」正是该由框架承担的部分。
    //
    // 这件事是 parity 门禁抓出来的:第一版让示例的 User 实现了那个接口,
    // 切片 03 的行数比从 1.28 跳到 1.46 —— 门禁在说「你让应用侧付了
    // 二十多行它不该付的代码」。

    /** TOTP 密钥列。留空 = 不支持二步验证。 */
    String twoFactorSecret() default "";

    /** 「已启用」标志列({@code boolean})。 */
    String twoFactorEnabled() default "";

    /** 恢复码列 —— 字段类型 {@code List<String>}(配 {@code @Cast.Use(AsList)})。 */
    String twoFactorRecoveryCodes() default "";

    /**
     * 二维码上显示的账号(通常是邮箱列)。
     *
     * <p>留空时用主键 —— 能用但不好认:验证器里会显示
     * {@code Jaravel:17} 而不是 {@code Jaravel:tom@example.com}。
     */
    String account() default "";
}
