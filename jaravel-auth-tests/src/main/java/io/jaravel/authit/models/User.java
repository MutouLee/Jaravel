package io.jaravel.authit.models;

import io.jaravel.auth.AuthModel;
import io.jaravel.auth.MustVerifyEmail;
import io.jaravel.eloquent.annotations.Hidden;
import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

@Model
@AuthModel(password = "password", rememberToken = "remember_token")
@MustVerifyEmail
public class User extends UserBase
        implements io.jaravel.auth.twofactor.TwoFactorAuthenticatable {

    @Id public Long id;
    public String email;
    @Hidden public String password;
    @Hidden public String rememberToken;
    public java.time.Instant emailVerifiedAt;
    public String status;
    public java.time.Instant createdAt;
    public java.time.Instant updatedAt;

    // 二步验证(docs/08 §6.4)。两个 @Hidden 的理由与 password 一样:
    // secret 等同于密码,恢复码是绕过二步验证的后门
    @Hidden public String twoFactorSecret;
    public boolean twoFactorEnabled;

    @Hidden
    @io.jaravel.eloquent.meta.Cast.Use(io.jaravel.eloquent.cast.AsList.class)
    public java.util.List<String> twoFactorRecoveryCodes = java.util.List.of();

    @Override public String twoFactorSecret()                { return twoFactorSecret; }
    @Override public void twoFactorSecret(String secret)     { twoFactorSecret = secret; }
    @Override public boolean twoFactorEnabled()              { return twoFactorEnabled; }
    @Override public void twoFactorEnabled(boolean enabled)  { twoFactorEnabled = enabled; }

    @Override
    public java.util.List<String> twoFactorRecoveryCodes() {
        return twoFactorRecoveryCodes == null ? java.util.List.of() : twoFactorRecoveryCodes;
    }

    @Override
    public void twoFactorRecoveryCodes(java.util.List<String> hashedCodes) {
        twoFactorRecoveryCodes = hashedCodes;
    }

    @Override public String twoFactorAccount() { return email; }
}
