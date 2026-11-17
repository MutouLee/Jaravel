package io.jaravel.permissions.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.auth.Authenticatable;
import io.jaravel.http.BasicRequest;
import io.jaravel.http.Response;
import io.jaravel.permissions.ArrayPermissionStore;
import io.jaravel.permissions.Permissions;

/**
 * {@code role:...} / {@code permission:...} 中间件的 401 / 403 / 200。
 *
 * <p>用注入的 currentUser supplier,不依赖完整的 Auth 上下文 ——
 * 端到端的那一半在 examples/auth 的 {@code PermissionFeatureTest}。
 */
class MiddlewareTest {

    record FakeUser(long id) implements Authenticatable {
        @Override public Object getAuthIdentifier() { return id; }
        @Override public Optional<String> getAuthPassword() { return Optional.empty(); }
    }

    private FakeUser user;

    @BeforeEach
    void setUp() {
        Permissions.use(new ArrayPermissionStore());
        user = new FakeUser(1L);
    }

    private Response run(RoleMiddleware middleware) {
        return middleware.handle(BasicRequest.of("GET", "/admin"), r -> Response.text("ok"));
    }

    private Response run(PermissionMiddleware middleware) {
        return middleware.handle(BasicRequest.of("GET", "/admin"), r -> Response.text("ok"));
    }

    @Test
    @DisplayName("有角色 → 200")
    void roleAllows() {
        Permissions.assignRole(user, "admin");
        var middleware = new RoleMiddleware(() -> Optional.of(user));
        middleware.configure("admin");

        assertThat(run(middleware).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("多个角色是 any 语义")
    void anyRole() {
        Permissions.assignRole(user, "editor");
        var middleware = new RoleMiddleware(() -> Optional.of(user));
        middleware.configure("admin", "editor");

        assertThat(run(middleware).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("没有角色 → 403")
    void roleDenies() {
        var middleware = new RoleMiddleware(() -> Optional.of(user));
        middleware.configure("admin");

        assertThat(run(middleware).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("未登录 → 401(不是 403)")
    void unauthenticatedIs401() {
        var middleware = new RoleMiddleware(Optional::empty);
        middleware.configure("admin");

        assertThat(run(middleware).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("直接权限 → 200")
    void directPermission() {
        Permissions.givePermissionTo(user, "publish-posts");
        var middleware = new PermissionMiddleware(() -> Optional.of(user));
        middleware.configure("publish-posts");

        assertThat(run(middleware).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("★ 角色继承的权限也通过")
    void inheritedPermission() {
        Permissions.createRole("editor", "publish-posts");
        Permissions.assignRole(user, "editor");
        var middleware = new PermissionMiddleware(() -> Optional.of(user));
        middleware.configure("publish-posts");

        assertThat(run(middleware).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("没有权限 → 403;未登录 → 401")
    void permissionDenied() {
        var middleware = new PermissionMiddleware(() -> Optional.of(user));
        middleware.configure("publish-posts");
        assertThat(run(middleware).status()).isEqualTo(403);

        var guest = new PermissionMiddleware(Optional::empty);
        guest.configure("publish-posts");
        assertThat(run(guest).status()).isEqualTo(401);
    }
}
