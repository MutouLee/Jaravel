package app.http.controllers;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;

import app.http.requests.LoginRequest;
import app.http.requests.RegisterRequest;
import app.http.requests.ResetPasswordRequest;
import app.models.User;
import io.jaravel.auth.Auth;
import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.EmailVerification;
import io.jaravel.auth.LoginThrottle;
import io.jaravel.auth.StatefulGuard;
import io.jaravel.auth.hash.Hash;
import io.jaravel.auth.middleware.ConfirmPassword;
import io.jaravel.auth.passwords.PasswordBroker;
import io.jaravel.http.HttpException;
import io.jaravel.http.Redirect;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.session.Session;
import io.jaravel.validation.Validator;

/**
 * 注册 / 登录 / 登出 / 密码重置 / 邮箱验证。
 *
 * <p>这是 install:auth 生成的控制器。它的长度几乎全是**流程**
 * (该跳哪、该闪存什么消息),安全相关的部分都在框架里:
 * 会话重建、令牌哈希、时序攻击防护、constant-time 比对都不出现在这里。
 *
 * <p>{@link #login} 最能说明问题 —— 一句 {@code attempt},
 * 外面两行是节流的记账。
 */
@Component
public class AuthController {

    private final PasswordBroker passwords;
    private final EmailVerification verification;
    private final LoginThrottle throttle;

    public AuthController(PasswordBroker passwords, EmailVerification verification,
                          LoginThrottle throttle) {
        this.passwords = passwords;
        this.verification = verification;
        this.throttle = throttle;
    }

    // ================= 注册 =================

    /** POST /register */
    public Response register(Request request, RegisterRequest form) {
        User user = new User();
        user.name = form.name();
        user.email = form.email();
        // password 不在 @Fillable 里,只能这样显式赋值 —— 于是
        // 「请求体里塞个 password 就能改密码」在模型层就不成立
        user.password = Hash.make(form.password());
        user.save();

        var principal = principalOf(user.id);
        ((StatefulGuard) Auth.guard("web")).login(principal);

        // 真实项目里这里是 Mail.send(...);示例直接闪存出来,
        // 好让集成测试拿得到链接
        return Redirect.to("/dashboard")
                .with("status", "注册成功,请验证邮箱")
                .with("verification_link",
                      verification.verificationUrl(appUrl(request), principal));
    }

    // ================= 登录 =================

    /** POST /login */
    public Response login(Request request, LoginRequest form) {
        String email = form.email();
        String ip = request.ip();

        // 超限直接抛 429(带 Retry-After)。放在凭据校验**之前** ——
        // 节流的意义就是在查库 + 哈希这两件昂贵的事之前挡住洪水
        throttle.guardAttempt(email, ip);

        if (((StatefulGuard) Auth.guard("web"))
                .attempt(form.credentials(), form.remember())) {
            throttle.clear(email, ip);
            return Redirect.to(intendedUrl());
        }

        throttle.recordFailure(email, ip);
        return Redirect.to("/login")
                // 不区分「邮箱不存在」与「密码错误」——
                // 区分开来就是一个账号枚举接口
                .withErrors(Map.of("email", "邮箱或密码不正确"))
                .withInput();
    }

    /** POST /logout */
    public Response logout() {
        // 清会话 + 换 CSRF token + 让 remember cookie 失效,都在这一句里
        ((StatefulGuard) Auth.guard("web")).logout();
        return Redirect.to("/").with("status", "已登出");
    }

    // ================= 密码重置 =================

    /** POST /forgot-password */
    public Response sendResetLink(Request request) {
        var data = Validator.make(request.all(), Map.of("email", "required|email"))
                .validate();
        var captured = new String[1];

        var status = passwords.sendResetLink(
                Map.of("email", data.string("email")),
                (user, token) -> captured[0] = token);

        return switch (status) {
            // SENT 也覆盖「这个邮箱没注册」—— 区分开来的话,
            // 这个接口就是最方便的邮箱枚举工具
            // captured[0] 为 null 时说明这个邮箱没注册 —— 回应与注册过的
            // 完全一样,只是没有令牌。闪存 null 是合法的(读取端拿到空),
            // 但这里直接不放,免得调试时误以为生成了一个空令牌
            case SENT -> captured[0] == null
                    ? Redirect.to("/login")
                            .with("status", "若该邮箱已注册,重置链接已发出")
                    : Redirect.to("/login")
                            .with("status", "若该邮箱已注册,重置链接已发出")
                            .with("reset_token", captured[0]);
            case THROTTLED -> Redirect.to("/forgot-password")
                    .withErrors(Map.of("email", "请求过于频繁,请稍后再试"));
            default -> Redirect.to("/forgot-password")
                    .withErrors(Map.of("email", "无法发送重置链接"));
        };
    }

    /** POST /reset-password */
    public Response resetPassword(ResetPasswordRequest form) {
        var status = passwords.reset(form.credentials(), (principal, plaintext) -> {
            User user = (User) principal.unwrap();
            user.password = Hash.make(plaintext);
            user.save();
        });

        if (status == PasswordBroker.Status.RESET) {
            // 改了密码就清掉二次确认状态 —— 否则「密码可能已泄露」
            // 之后那个确认窗口仍然开着
            ConfirmPassword.forget();
            return Redirect.to("/login").with("status", "密码已重置,请重新登录");
        }

        return Redirect.to("/reset-password").withErrors(Map.of(
                "token", switch (status) {
                    case EXPIRED_TOKEN -> "重置链接已过期,请重新申请";
                    case INVALID_TOKEN -> "重置链接无效或已被使用";
                    default -> "无法重置密码";
                }));
    }

    // ================= 邮箱验证 =================

    /**
     * GET /email/verify/{id}/{hash}
     *
     * <p>这条路由挂的是 {@code auth} 而不是 {@code verified} ——
     * 后者会把未验证的用户挡在验证接口之外,也就是永远验证不了。
     */
    public Response verifyEmail(Request request, Long id, String hash) {
        var principal = Auth.manager().provider("users").retrieveById(id)
                .orElseThrow(() -> new HttpException(404, "用户不存在"));

        var result = verification.verify(request.fullUrl(), principal, hash, user -> {
            User model = (User) user.unwrap();
            model.emailVerifiedAt = Instant.now();
            model.save();
        });

        return switch (result) {
            // 已验证过不算错误:用户可能点了两次,或邮件被转发
            case VERIFIED, ALREADY_VERIFIED ->
                    Redirect.to("/dashboard").with("status", "邮箱已验证");
            case EMAIL_CHANGED -> Redirect.to("/dashboard")
                    .withErrors(Map.of("email", "该链接对应的邮箱已变更,请重新申请验证"));
            case INVALID_LINK -> Redirect.to("/dashboard")
                    .withErrors(Map.of("email", "验证链接无效或已过期"));
        };
    }

    /** POST /email/verification-notification */
    public Response resendVerification(Request request) {
        var principal = Auth.guard("web").user()
                .orElseThrow(() -> new HttpException(401, "未登录"));

        if (EmailVerification.isVerified(principal)) {
            return Redirect.to("/dashboard").with("status", "邮箱已验证");
        }

        return Redirect.to("/dashboard")
                .with("status", "验证邮件已重新发送")
                .with("verification_link",
                      verification.verificationUrl(appUrl(request), principal));
    }

    // ================= 二次密码确认 =================

    /** POST /confirm-password */
    public Response confirmPassword(Request request) {
        var data = Validator.make(request.all(), Map.of("password", "required|string"))
                .validate();

        // validate 而不是 attempt —— 后者会写会话,而这里只是确认身份
        if (!Auth.guard("web").validate(Map.of(
                "email", Auth.userOrFail(User.class).email,
                "password", data.string("password")))) {
            return Redirect.to("/confirm-password")
                    .withErrors(Map.of("password", "密码不正确"));
        }

        ConfirmPassword.markConfirmed();
        return Redirect.to(intendedUrl());
    }

    // ================= 内部 =================

    private static Authenticatable principalOf(Long id) {
        return Auth.manager().provider("users").retrieveById(id).orElseThrow();
    }

    /**
     * 登录前想去的那个地址。
     *
     * <p>{@code Authenticate} 中间件在把游客踢到 /login 之前把它存进了
     * 会话。{@code pull} 而不是 {@code get}:用一次就该清掉,
     * 否则下次登录会莫名跳到几天前那个页面。
     */
    private static String intendedUrl() {
        return Session.pull("url.intended", String.class).orElse("/dashboard");
    }

    private static String appUrl(Request request) {
        String url = request.url();
        String path = request.path();
        return url.endsWith(path) ? url.substring(0, url.length() - path.length()) : url;
    }
}
