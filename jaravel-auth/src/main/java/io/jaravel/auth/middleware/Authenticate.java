package io.jaravel.auth.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.jaravel.auth.Auth;
import io.jaravel.auth.AuthManager;
import io.jaravel.auth.Guard;
import io.jaravel.auth.guard.SessionGuard;
import io.jaravel.http.HttpException;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.session.Session;

/**
 * 要求已登录。{@code middleware("auth")} 或 {@code middleware("auth:admin,web")}。
 *
 * <p>多个 guard 时**任一通过即可**,并把通过的那个设为本请求的默认 guard ——
 * 于是控制器里的 {@code Auth.user()} 自动用对的那个,不必再判一次。
 */
public class Authenticate implements Middleware.Parameterized {

    private final AuthManager manager;
    private List<String> guards = List.of();

    public Authenticate(AuthManager manager) {
        this.manager = manager;
    }

    @Override
    public void configure(String... params) {
        this.guards = List.of(params);
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        List<String> candidates = guards.isEmpty()
                ? List.of(manager.defaultGuardName())
                : guards;

        for (String name : candidates) {
            Guard guard = manager.guard(name);
            if (authenticated(guard, request)) {
                // 让后续代码的 Auth.user() 默认用这个 guard
                manager.shouldUse(name);
                Response response = next.apply(request);
                return attachPendingCookie(guard, response);
            }
        }
        return unauthenticated(request, candidates);
    }

    /**
     * 判断是否已认证,必要时尝试 remember cookie。
     *
     * <p>remember 的恢复放在中间件而不是 guard 的 {@code user()} 里:
     * 它需要读请求 cookie,而 Guard 接口刻意不依赖 Request ——
     * 那样 JWT / Token guard 也会被迫知道 HTTP 的存在。
     */
    private boolean authenticated(Guard guard, Request request) {
        if (guard.check()) return true;

        if (guard instanceof SessionGuard session) {
            String cookie = request.cookie(session.rememberCookieName()).orElse(null);
            return session.recallFromCookie(cookie).isPresent();
        }
        return false;
    }

    /** remember cookie 的签发/清除要搭上响应 */
    private Response attachPendingCookie(Guard guard, Response response) {
        if (guard instanceof SessionGuard session) {
            return session.takePendingCookie()
                    .map(response::cookie)
                    .orElse(response);
        }
        return response;
    }

    /**
     * 未认证时的响应。
     *
     * <p>API → 401 JSON;页面 → 跳登录页,并**记下原本想去的地址**,
     * 登录成功后 {@code Redirect.intended()} 回到那里。
     * 少了这一步,用户点一个深链接、登录完被丢回首页,再自己找回去 ——
     * 这是最常见的体验缺陷之一。
     *
     * <p>覆盖它可以自定义行为(docs/08 §2.1)。
     */
    protected Response unauthenticated(Request request, List<String> guards) {
        if (request.expectsJson()) {
            throw new HttpException(401, "Unauthenticated.");
        }

        // 只记 GET 的 intended:POST 回放会重复提交
        if (request.method().equalsIgnoreCase("GET") && Session.isActive()) {
            Session.put("url.intended", request.fullUrl());
        }

        throw new HttpException(302, "需要登录",
                java.util.Map.of("Location", loginPath()));
    }

    /**
     * 登录页地址。
     *
     * <p>默认 {@code /login}。覆盖它或用命名路由 —— 硬编码一个路径
     * 在多语言站点(/{locale}/login)下会不对,但作为默认值它覆盖绝大多数情况。
     */
    protected String loginPath() {
        return "/login";
    }

    public List<String> guards() {
        return new ArrayList<>(guards);
    }
}
