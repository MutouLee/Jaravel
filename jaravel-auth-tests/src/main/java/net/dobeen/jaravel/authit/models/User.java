package net.dobeen.jaravel.authit.models;

import net.dobeen.jaravel.auth.AuthModel;
import net.dobeen.jaravel.auth.MustVerifyEmail;
import net.dobeen.jaravel.eloquent.annotations.Hidden;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

@Model
@AuthModel(password = "password", rememberToken = "remember_token",
           twoFactorSecret = "two_factor_secret",
           twoFactorEnabled = "two_factor_enabled",
           twoFactorRecoveryCodes = "two_factor_recovery_codes",
           account = "email")
@MustVerifyEmail
public class User extends UserBase {

    @Id public Long id;
    public String email;
    @Hidden public String password;
    @Hidden public String rememberToken;
    public java.time.Instant emailVerifiedAt;
    public String status;
    public java.time.Instant createdAt;
    public java.time.Instant updatedAt;

    // 二步验证(docs/08 §6.4):三个字段 + @AuthModel 里的三个列名,
    // 访问器由 provider 的包装体提供。两个 @Hidden 的理由与 password 一样
    @Hidden public String twoFactorSecret;
    public boolean twoFactorEnabled;

    @Hidden
    @net.dobeen.jaravel.eloquent.meta.Cast.Use(net.dobeen.jaravel.eloquent.cast.AsList.class)
    public java.util.List<String> twoFactorRecoveryCodes = java.util.List.of();
}
