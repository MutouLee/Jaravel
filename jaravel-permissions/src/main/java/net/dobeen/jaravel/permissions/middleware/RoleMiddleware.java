package net.dobeen.jaravel.permissions.middleware;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import net.dobeen.jaravel.auth.Auth;
import net.dobeen.jaravel.auth.Authenticatable;
import net.dobeen.jaravel.http.Middleware;
import net.dobeen.jaravel.http.Request;
import net.dobeen.jaravel.http.Response;
import net.dobeen.jaravel.permissions.Permissions;

/**
 * {@code r.get("/admin", X::y).middleware("auth", "role:admin")}(docs/08 §5.4)。
 *
 * <p>多个角色是 **any** 语义:{@code role:admin,editor} 有其中一个就过。
 * 必须把它放在 {@code auth} 之后 —— 未登录时这里返回 401,而不是 403。
 */
public class RoleMiddleware implements Middleware.Parameterized {

    private final Supplier<Optional<Authenticatable>> currentUser;
    private final List<String> roles = new ArrayList<>();

    public RoleMiddleware() {
        this(Auth::user);
    }

    /** 测试用:不依赖静态 Auth */
    RoleMiddleware(Supplier<Optional<Authenticatable>> currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void configure(String... params) {
        roles.clear();
        roles.addAll(Arrays.asList(params));
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        if (roles.isEmpty()) return next.apply(request);

        var user = currentUser.get();
        if (user.isEmpty()) {
            return Response.json(java.util.Map.of("message", "需要登录"))
                    .status(401);
        }

        boolean allowed = roles.stream().anyMatch(role -> Permissions.hasRole(user.get(), role));
        if (!allowed) {
            return Response.json(java.util.Map.of(
                    "message", "没有所需的角色(" + String.join(" / ", roles) + ")"))
                    .status(403);
        }
        return next.apply(request);
    }
}
