package io.jaravel.auth.middleware;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.http.HttpException;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.session.Session;

/**
 * 二次密码确认:{@code middleware("auth", "password.confirm")}。
 *
 * <p>挂在危险操作上(删除账号、改邮箱、导出全部数据、查看 API 密钥)。
 * 要求用户在最近一段时间内确认过密码。
 *
 * <h2>为什么「已登录」不够</h2>
 * <p>会话可能是几天前建立的,期间设备可能被他人接触(没锁屏的电脑、
 * 共用的浏览器)。二次确认把「谁坐在这台电脑前」重新验证一次 ——
 * 这是 GitHub / AWS 控制台对敏感操作的标准做法。
 *
 * <h2>确认时间存会话而不是数据库</h2>
 * <p>它是「这个会话在这台设备上刚确认过」的属性,换一台设备就该重新确认。
 * 存数据库会让 A 设备的确认让 B 设备也通过 —— 那正好绕过了这个机制的目的。
 */
public class ConfirmPassword implements Middleware.Parameterized {

    static final String SESSION_KEY = "auth.password_confirmed_at";

    private Duration validity = Duration.ofHours(3);
    private String redirectTo = "/confirm-password";

    /** {@code password.confirm:分钟} */
    @Override
    public void configure(String... params) {
        if (params.length > 0) {
            try {
                validity = Duration.ofMinutes(Long.parseLong(params[0].trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "password.confirm 的参数应是分钟数,当前是 \"" + params[0] + "\"。"
                      + "\n  写法:middleware(\"password.confirm:30\")", e);
            }
        }
        if (params.length > 1) redirectTo = params[1];
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        if (confirmedRecently()) {
            return next.apply(request);
        }

        if (request.expectsJson()) {
            // 423 Locked —— 比 403 准确:不是「没权限」,而是
            // 「需要先解锁」。前端据此弹密码确认框而不是报错页
            throw new HttpException(423, """
                    需要确认密码才能执行此操作。
                      请先 POST 到密码确认接口,再重试本请求。""");
        }

        if (Session.isActive()) {
            Session.put("url.intended", request.fullUrl());
        }
        throw new HttpException(302, "需要确认密码",
                Map.of("Location", redirectTo));
    }

    private boolean confirmedRecently() {
        if (!Session.isActive()) return false;

        return Session.get(SESSION_KEY, Long.class)
                .map(Instant::ofEpochSecond)
                .filter(at -> at.plus(validity).isAfter(Instant.now()))
                .isPresent();
    }

    /**
     * 确认成功后调用,记下时间。
     *
     * <p>静态方法而不是实例方法:调用它的是密码确认控制器,
     * 而那里拿不到中间件实例。
     */
    public static void markConfirmed() {
        Session.put(SESSION_KEY, Instant.now().getEpochSecond());
    }

    /** 清掉确认状态 —— 改密码后应该调用 */
    public static void forget() {
        if (Session.isActive()) Session.forget(SESSION_KEY);
    }

    public Duration validity() {
        return validity;
    }
}
