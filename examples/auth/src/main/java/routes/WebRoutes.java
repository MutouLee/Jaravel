package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.AuthController;
import app.http.controllers.AvatarController;
import app.http.controllers.DashboardController;
import app.http.controllers.NoteController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

/**
 * 「加个登录 = 3 行」里的那几行。
 *
 * <p>注意没有 HttpKernel —— {@code auth} / {@code guest} / {@code verified}
 * / {@code throttle} / {@code password.confirm} 这些别名由框架的
 * {@code DefaultKernel} 提供。中间件的执行顺序也由它固定,
 * 这里声明的顺序写反了也不影响(见 DefaultKernel.priority 的注释)。
 */
@Component
public class WebRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {

        // ---------- 公开 ----------
        // 挂 web 组:它要建立会话并下发 CSRF 令牌。
        // GET 是安全方法,VerifyCsrfToken 不会校验它
        r.get("/", DashboardController::home).middleware("web").name("home");

        // ---------- 游客专属 ----------
        // guest 让已登录的人访问登录页时直接跳走,而不是看到一个
        // 「登录」表单然后困惑于「我不是已经登录了吗」
        r.prefix("").middleware("web", "guest").group(guest -> {
            guest.post("/register", AuthController::register).name("register");

            // throttle 挂在路由上而不是写在控制器里:
            // 它要在凭据校验之前跑,而 DefaultKernel.priority 保证了这一点
            guest.post("/login", AuthController::login)
                 .middleware("throttle:10,1").name("login");

            guest.post("/forgot-password", AuthController::sendResetLink)
                 .middleware("throttle:5,1").name("password.email");
            guest.post("/reset-password", AuthController::resetPassword)
                 .name("password.update");
        });

        // ---------- 需要登录 ----------
        r.prefix("").middleware("web", "auth").group(authed -> {
            authed.get("/dashboard", DashboardController::index).name("dashboard");
            authed.post("/logout", AuthController::logout).name("logout");

            // 邮箱验证的两条路由挂 auth 而**不是** verified ——
            // 后者会把未验证用户挡在验证接口之外,也就永远验证不了
            authed.get("/email/verify/{id}/{hash}", AuthController::verifyEmail)
                  .whereNumber("id").name("verification.verify");
            authed.post("/email/verification-notification",
                        AuthController::resendVerification)
                  .middleware("throttle:6,1").name("verification.send");

            // 用会话身份换一个无状态令牌 —— SPA 与移动端的标准做法
            authed.post("/api-token", DashboardController::issueToken).name("api-token");

            authed.post("/confirm-password", AuthController::confirmPassword)
                  .name("password.confirm");

            // 敏感设置页:登录 + 邮箱已验证 + 最近确认过密码
            authed.get("/settings", DashboardController::settings)
                  .middleware("verified", "password.confirm:180").name("settings");
        });

        // ---------- API:Bearer token ----------
        // api 组不含 StartSession —— 无状态接口不该下发会话 cookie
        r.prefix("/api").middleware("api", "auth:api").group(api -> {
            api.get("/me", DashboardController::me);
            api.get("/notes", NoteController::index);
            api.get("/notes/count", NoteController::count);
            api.post("/notes", NoteController::store);
            // {note} 而不是 {id}:隐式模型绑定按参数名匹配,
            // 控制器写 show(Note note) 就自动查出来。
            // can:view,note 的 note 取的是**已解析的实例** ——
            // SubstituteBindings 在 Authorize 之前跑(顺序由 DefaultKernel 固定)
            api.get("/notes/{note}", NoteController::show)
               .whereNumber("note").middleware("can:view,note");
            api.put("/notes/{note}", NoteController::update)
               .whereNumber("note").middleware("can:update,note");
            api.delete("/notes/{note}", NoteController::destroy)
               .whereNumber("note").middleware("can:delete,note");

            // ---------- 上传 ----------
            // 挂在 api 组而不是 web 组：multipart 的 CSRF 令牌要作为
            // 一个表单字段一起传，而这组测试要验证的是
            // 文件解析与安全检查，不是 CSRF（那一层已经有测试）
            api.post("/avatar", AvatarController::store);
            api.post("/avatar/multi", AvatarController::storeMany);
            api.post("/avatar/public", AvatarController::storePublic);
        });
    }
}
