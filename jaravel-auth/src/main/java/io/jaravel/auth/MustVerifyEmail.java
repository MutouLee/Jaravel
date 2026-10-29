package io.jaravel.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记模型需要邮箱验证,并声明相关列。
 *
 * <pre>{@code
 * @Model
 * @AuthModel
 * @MustVerifyEmail
 * public class User extends UserBase {
 *     public String email;
 *     public java.time.Instant emailVerifiedAt;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MustVerifyEmail {

    /** 邮箱列 */
    String email() default "email";

    /** 验证时间列。为 null 表示未验证。 */
    String verifiedAt() default "email_verified_at";
}
