package io.jaravel.auth.middleware;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.auth.AuthManager;
import io.jaravel.http.HttpException;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * 要求**未**登录。{@code middleware("guest")}。
 *
 * <p>挂在登录页/注册页上:已登录的人访问时直接去首页,而不是看到一个
 * 「登录」表单 —— 填了它会覆盖当前会话,而用户完全不知道自己刚换了身份。
 */
public class RedirectIfAuthenticated implements Middleware.Parameterized {

    private final AuthManager manager;
    private final String redirectTo;
    private List<String> guards = List.of();

    public RedirectIfAuthenticated(AuthManager manager) {
        this(manager, "/");
    }

    public RedirectIfAuthenticated(AuthManager manager, String redirectTo) {
        this.manager = manager;
        this.redirectTo = redirectTo;
    }

    @Override
    public void configure(String... params) {
        this.guards = List.of(params);
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        var candidates = guards.isEmpty() ? List.of(manager.defaultGuardName()) : guards;

        for (String name : candidates) {
            if (manager.guard(name).check()) {
                if (request.expectsJson()) {
                    // API 场景:已登录却请求登录接口。409 而不是 302 ——
                    // 重定向对 API 客户端没有意义,而 409「与当前状态冲突」
                    // 正是这件事的语义
                    throw new HttpException(409, "已经登录。");
                }
                throw new HttpException(302, "已经登录", Map.of("Location", redirectTo));
            }
        }
        return next.apply(request);
    }
}
