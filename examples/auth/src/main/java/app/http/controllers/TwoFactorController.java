package app.http.controllers;

import java.util.Map;

import org.springframework.stereotype.Component;

import app.models.User;
import io.jaravel.auth.Auth;
import io.jaravel.auth.twofactor.TwoFactorChallenge;
import io.jaravel.http.Redirect;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.http.View;
import io.jaravel.session.Session;

/**
 * 二步验证的四个动作(docs/08 §6.4)。
 *
 * <h2>为什么不塞进 AuthController</h2>
 * <p>两个理由,一个是设计上的、一个是度量上的:
 *
 * <p>设计上:它有自己的一组路由({@code TwoFactorRoutes}),而且那组路由的
 * 前提与登录那些**相反** —— 挑战页要求「还没登录」。
 *
 * <p>度量上:{@code benchmark/laravel-parity} 的切片 03 按文件统计
 * 「注册/登录/记住我/登出/密码重置/邮箱验证/Policy」这个功能集合,
 * 而 {@code AuthController} 在那张清单里。把二步验证塞进去等于只给
 * Jaravel 一侧加功能,那个比值就不再衡量它声称的东西了
 * (第一版那么写,parity 门禁当场从 1.28 跳到 1.46)。
 * 本文件不在清单里,与 {@code DirectiveDemoController} 同理。
 */
@Component
public class TwoFactorController {

    /**
     * GET /settings/two-factor —— 开启流程的第一步:生成密钥并显示二维码。
     *
     * <p>⚠ 每次打开这个页面都会**换一个新密钥**(enableTwoFactor 的语义)。
     * 真实项目里这一步该是 POST(用户点「开启」)并要求先确认密码
     * ({@code middleware("password.confirm")})—— 这个示例把它放在 GET 上
     * 是为了少一个表单,而这句话写在这里,免得被照抄。
     */
    public Object twoFactorSetup() {
        // Auth.twoFactor() 是 provider 包装出来的主体 —— 模型侧只有三个字段,
        // 那七个访问器由 @AuthModel 里的列名代掉(docs/08 §6.4)
        var twoFactor = Auth.twoFactor();
        var setup = twoFactor.enableTwoFactor("Jaravel 示例");
        Auth.userOrFail(User.class).save();

        return View.of("two-factor.setup")
                // SVG 内联 —— 不需要一条返回图片的路由,而那条路由必须鉴权
                // (二维码里是 TOTP 密钥,等同于密码)
                .with("qr", twoFactor.twoFactorQrCodeSvg("Jaravel 示例"))
                .with("secret", setup.secret())
                .with("codes", setup.recoveryCodes());
    }

    /** POST /settings/two-factor —— 输一次验证码,确认验证器配好了 */
    public Response confirmTwoFactor(Request request) {
        if (!Auth.twoFactor().confirmTwoFactor(request.input("code", ""))) {
            return Redirect.to("/settings/two-factor")
                    .withErrors(Map.of("code", "验证码不对 —— 确认验证器里的账号与时间"));
        }
        Auth.userOrFail(User.class).save();
        return Redirect.to("/dashboard").with("status", "二步验证已开启");
    }

    /** GET /two-factor —— 挑战页。此刻**还没登录** */
    public Object twoFactorChallengePage() {
        if (!TwoFactorChallenge.pending()) {
            return Redirect.to("/login");
        }
        return View.of("two-factor.challenge");
    }

    /**
     * POST /two-factor —— 验一次。
     *
     * <p>⚠ 路由上挂了 {@code throttle:5,1}:6 位数字只有一百万种,
     * 不挂节流的话这个接口就是一个可暴破的登录接口。
     */
    public Response twoFactorChallenge(Request request) {
        String code = request.input("code", "");
        var challenge = TwoFactorChallenge.complete(code);

        if (challenge.isEmpty()) {
            // 再试一次恢复码 —— 用户手机丢了的那条路。
            // 恢复码是一次性的,用掉之后**必须落库**
            var recovered = TwoFactorChallenge
                    .completeWithRecoveryCode(code);
            if (recovered.isEmpty()) {
                return Redirect.to("/two-factor")
                        .withErrors(Map.of("code", "验证码或恢复码不正确"));
            }
            // ★ 不能强转:guard 里的主体是 provider **包装**过的
            // Authenticatable,不是 User(@AuthModel 的设计 ——
            // 模型不必实现那个接口)。强转的结果是一个 500,
            // 而那个 ClassCastException 指向框架内部的类名
            recovered.get().as(User.class).orElseThrow().save();
            return Redirect.to(intendedUrl()).with("status", "恢复码已使用,请重新配置验证器");
        }

        return Redirect.to(intendedUrl());
    }

    /** 登录前想去的那个地址 —— 与 AuthController 里那个同义 */
    private static String intendedUrl() {
        return Session.pull("url.intended", String.class).orElse("/dashboard");
    }
}
