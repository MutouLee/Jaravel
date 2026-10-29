package io.jaravel.auth.middleware;

import java.util.Map;
import java.util.function.Function;

import io.jaravel.auth.Auth;
import io.jaravel.auth.EmailVerification;
import io.jaravel.http.HttpException;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * 要求邮箱已验证:{@code middleware("auth", "verified")}。
 *
 * <p>必须排在 {@code auth} 之后 —— 它需要当前用户。顺序由
 * {@code Kernel.priority()} 保证,而不是指望用户每次写对。
 */
public class EnsureEmailVerified implements Middleware.Parameterized {

    private String redirectTo = "/email/verify";

    @Override
    public void configure(String... params) {
        if (params.length > 0) redirectTo = params[0];
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        var user = Auth.user();

        if (user.isEmpty()) {
            // 没有用户说明 auth 中间件没挂或顺序错了。
            // 报一个说明清楚的错,而不是当作「未验证」——
            // 后者会让用户看到「请验证邮箱」而他根本没登录
            throw new IllegalStateException("""
                    verified 中间件需要已登录的用户,但当前没有。
                      几乎总是中间件顺序问题:它必须跑在 auth 之后。
                      修复:middleware("auth", "verified"),
                            或在 Kernel.priority() 里排好 Authenticate → EnsureEmailVerified。""");
        }

        if (EmailVerification.isVerified(user.get())) {
            return next.apply(request);
        }

        if (request.expectsJson()) {
            throw new HttpException(409, "邮箱尚未验证。");
        }
        throw new HttpException(302, "邮箱尚未验证", Map.of("Location", redirectTo));
    }
}
