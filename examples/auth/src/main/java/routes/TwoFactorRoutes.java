package routes;

import org.springframework.stereotype.Component;

import app.http.controllers.TwoFactorController;
import io.jaravel.routing.RouteDefinition;
import io.jaravel.routing.Router;

/**
 * 二步验证的四条路由(docs/08 §6.4)。
 *
 * <p>分成两组,因为它们的前提刚好相反:
 * <ul>
 *   <li><b>开启</b>({@code /settings/two-factor})—— 要已登录;</li>
 *   <li><b>挑战</b>({@code /two-factor})—— 要**还没**登录
 *       (密码对了,但还差第二步)。</li>
 * </ul>
 */
@Component
public class TwoFactorRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        // 开启:已登录才能开。真实项目里还该加 password.confirm ——
        // 「开启二步验证」是一个改变账号安全性的操作
        r.prefix("/settings").middleware("web", "auth").group(settings -> {
            settings.get("/two-factor", TwoFactorController::twoFactorSetup)
                    .name("two-factor.setup");
            settings.post("/two-factor", TwoFactorController::confirmTwoFactor)
                    .name("two-factor.confirm");
        });

        // 挑战:此刻还没登录,所以**不能**挂 auth。
        //
        // ⚠ throttle:5,1 不是可选的:6 位数字只有一百万种,
        // 不挂节流的话这条路由就是一个可暴破的登录接口
        r.get("/two-factor", TwoFactorController::twoFactorChallengePage)
         .middleware("web").name("two-factor.challenge");
        r.post("/two-factor", TwoFactorController::twoFactorChallenge)
         .middleware("web", "throttle:5,1").name("two-factor.verify");
    }
}
