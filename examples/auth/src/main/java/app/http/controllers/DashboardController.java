package app.http.controllers;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import app.models.User;
import io.jaravel.auth.Auth;
import io.jaravel.auth.EmailVerification;
import io.jaravel.session.Session;

/**
 * 登录后的落地页。
 *
 * <p>返回 JSON 而不是模板 —— 这个示例要证明的是认证流程,
 * 加一套 jblade 模板只会让集成测试去解析 HTML。
 */
@Component
public class DashboardController {

    /** GET /dashboard —— 挂 auth */
    public Object index() {
        User me = Auth.userOrFail(User.class);
        var principal = Auth.guard("web").user().orElseThrow();

        var body = new LinkedHashMap<String, Object>();
        body.put("id", me.id);
        body.put("name", me.name);
        body.put("email", me.email);
        body.put("email_verified", EmailVerification.isVerified(principal));
        // 本次认证是来自 remember cookie 还是主动登录。
        // 真实应用据此决定「敏感操作要不要再确认一次密码」
        body.put("via_remember", Auth.viaRemember());

        // 当前的 CSRF 令牌。登录与登出都会**轮换**它(安全清单 ①⑩),
        // 所以每个会渲染表单的页面都得带上新的那个 ——
        // 不带的话用户登录后的第一次 POST 会得到 419
        Session.tryCurrent().ifPresent(session -> body.put("csrf_token", session.token()));

        // 上一个请求闪存的消息 —— 注册时的验证链接、重置令牌都在这里
        Session.tryCurrent().ifPresent(session -> {
            session.get("status").ifPresent(v -> body.put("status", v));
            session.get("verification_link").ifPresent(v -> body.put("verification_link", v));
        });
        return body;
    }

    /**
     * GET / —— 公开。
     *
     * <p>同时下发 CSRF 令牌。模板渲染的应用里它藏在表单的隐藏域里,
     * 而 SPA / 移动端需要一个显式的获取入口 —— Laravel 的
     * {@code /sanctum/csrf-cookie} 是同一个作用。
     *
     * <p>把它放在首页而不是单开一条路由:首页本来就要被访问一次,
     * 顺带建立会话、拿到令牌,省掉一次往返。
     */
    public Object home() {
        var body = new LinkedHashMap<String, Object>();
        body.put("app", "Jaravel Auth 示例");
        body.put("authenticated", Auth.check());
        Session.tryCurrent().ifPresent(session -> {
            body.put("csrf_token", session.token());
            session.get("status").ifPresent(v -> body.put("status", v));
            session.get("reset_token").ifPresent(v -> body.put("reset_token", v));
        });
        return body;
    }

    /** GET /api/me —— 挂 auth:api,证明同一套 Gate/Policy 在 token 认证下也工作 */
    public Object me() {
        User me = Auth.userOrFail(User.class);
        return Map.of("id", me.id, "email", me.email, "guard", "api");
    }

    /**
     * POST /api-token —— 签发一个 API 令牌。
     *
     * <p>挂 web + auth:用会话身份换一个无状态令牌,这是 SPA 与移动端的
     * 标准做法。明文**只在这里返回一次** —— 库里存的是 SHA-256,
     * 之后再也取不回来。
     */
    public Object issueToken(io.jaravel.http.Request request) {
        var principal = Auth.guard("web").user().orElseThrow();
        var guard = (io.jaravel.auth.guard.TokenGuard) Auth.guard("api");

        var token = guard.issue(principal, request.input("name", "default"));
        return Map.of("token", token.plainText(), "id", token.id());
    }

    /** GET /settings —— 挂 auth + verified + password.confirm */
    public Object settings() {
        return Map.of("sensitive", true, "email", Auth.userOrFail(User.class).email);
    }
}
