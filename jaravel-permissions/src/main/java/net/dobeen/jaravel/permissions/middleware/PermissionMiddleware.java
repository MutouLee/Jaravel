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
 * {@code r.get("/admin", X::y).middleware("auth", "permission:view-dashboard")}。
 *
 * <p>多个权限是 **any** 语义。{@link Permissions#hasPermissionTo} 会同时看
 * 直接授予与角色继承 —— 一个 {@code editor} 角色带 {@code publish-posts} 时,
 * 拥有该角色的用户也通过。
 */
public class PermissionMiddleware implements Middleware.Parameterized {

    private final Supplier<Optional<Authenticatable>> currentUser;
    private final List<String> permissions = new ArrayList<>();

    public PermissionMiddleware() {
        this(Auth::user);
    }

    /** 测试用:不依赖静态 Auth */
    PermissionMiddleware(Supplier<Optional<Authenticatable>> currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public void configure(String... params) {
        permissions.clear();
        permissions.addAll(Arrays.asList(params));
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        if (permissions.isEmpty()) return next.apply(request);

        var user = currentUser.get();
        if (user.isEmpty()) {
            return Response.json(java.util.Map.of("message", "需要登录"))
                    .status(401);
        }

        boolean allowed = permissions.stream()
                .anyMatch(permission -> Permissions.hasPermissionTo(user.get(), permission));
        if (!allowed) {
            return Response.json(java.util.Map.of(
                    "message", "没有所需的权限(" + String.join(" / ", permissions) + ")"))
                    .status(403);
        }
        return next.apply(request);
    }
}
