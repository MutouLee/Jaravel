package app.models;

import java.time.Instant;

import net.dobeen.jaravel.auth.AuthModel;
import net.dobeen.jaravel.auth.MustVerifyEmail;
import net.dobeen.jaravel.eloquent.annotations.Fillable;
import net.dobeen.jaravel.eloquent.annotations.HasMany;
import net.dobeen.jaravel.eloquent.annotations.Hidden;
import net.dobeen.jaravel.eloquent.annotations.Id;
import net.dobeen.jaravel.eloquent.annotations.Model;

/**
 * 用户。
 *
 * <p>这个类就是「加个登录」在模型侧的全部代价:两个注解。
 * {@code Authenticatable} 不需要实现 —— provider 从 {@code @AuthModel}
 * 读列名再把模型包装起来(见 Authenticatable 的类注释)。
 *
 * <h2>两个 @Hidden 都是必须的</h2>
 * <p>{@code password} 显而易见。{@code rememberToken} 同样重要 ——
 * 它也是一个可直接用于登录的凭据,而且有效期以月计。
 * 控制器里一句 {@code return user;} 就能把它们发给客户端,
 * 所以这道防线放在模型上而不是靠每个控制器自觉。
 */
@Model
@AuthModel(twoFactorSecret = "two_factor_secret",
           twoFactorEnabled = "two_factor_enabled",
           twoFactorRecoveryCodes = "two_factor_recovery_codes",
           account = "email")
@MustVerifyEmail
@Fillable({"name", "email"})
@HasMany(name = "notes", target = Note.class)
public class User extends UserBase implements net.dobeen.jaravel.notifications.Notifiable {

    @Id public Long id;
    public String name;
    public String email;

    // ================= 二步验证(docs/08 §6.4)=================
    //
    // ★ 只有三个字段 —— 那七个访问器由 @AuthModel 的列名声明代掉了
    // (与 Authenticatable 同一套机制:provider 包装模型)。
    //
    // 两个 @Hidden 的理由与 password 完全一样,而且更要紧:
    // secret 等同于密码(拿到它就能生成验证码),恢复码是绕过二步验证的后门

    @Hidden public String twoFactorSecret;
    public boolean twoFactorEnabled;

    /** 存 JSON 数组 —— 恢复码是十个哈希,一列装得下 */
    @Hidden
    @net.dobeen.jaravel.eloquent.meta.Cast.Use(net.dobeen.jaravel.eloquent.cast.AsList.class)
    public java.util.List<String> twoFactorRecoveryCodes = java.util.List.of();

    // ================= Notifiable(docs/11 §2.3)=================
    //
    // 三个方法,各自解决一件事:
    //   · routeNotificationFor —— 「这个渠道往哪发」
    //   · notifiableId / Type  —— 「站内通知属于谁」
    //
    // 返回 null 表示「这个渠道不可用」,那个渠道会被**跳过**
    // (并发一个 Skipped 事件),而不是报错 ——
    // 「有邮箱但没填手机号」是完全正常的。

    @Override
    public String routeNotificationFor(String channel) {
        return switch (channel) {
            case "mail" -> email;
            default -> null;
        };
    }

    @Override
    public Object notifiableId() {
        return id;
    }

    /**
     * 用**表名**而不是类的全限定名。
     *
     * <p>全限定名的话,一次包重构(com.app.User → com.app.models.User)
     * 会让所有历史通知查不到归属 —— 而那些数据还在表里,
     * 只是没人能读到了。
     */
    @Override
    public String notifiableType() {
        return "users";
    }

    @Hidden public String password;
    @Hidden public String rememberToken;

    public Instant emailVerifiedAt;
    public Instant createdAt;
    public Instant updatedAt;

    /** password 不在 @Fillable 里 —— 它永远只能显式赋值,不能来自请求体 */
    public boolean hasVerifiedEmail() {
        return emailVerifiedAt != null;
    }
}
