package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import app.models.User;
import io.jaravel.auth.hash.Hash;
import io.jaravel.database.Connection;
import io.jaravel.permissions.Permissions;
import io.jaravel.testing.FeatureTest;

/**
 * RBAC 的端到端(docs/08 §5.4)。
 *
 * <p>真的 HTTP 管道、真的会话、真的 SQLite 权限表:
 * {@code auth} 之后 {@code role:admin} / {@code permission:publish-posts}
 * 放行或拒绝。控制器本身没有权限代码 —— 能执行到它就说明中间件放行了。
 */
class PermissionFeatureTest extends FeatureTest {

    private static final List<String> RBAC_TABLES = List.of(
            "model_has_permissions", "model_has_roles", "role_has_permissions",
            "permissions", "roles");

    @Autowired Connection db;

    @BeforeEach
    void resetPermissions() {
        // FeatureTest 的上下文与 AuthFlowTest / HotReloadAcceptanceTest 的上下文
        // 会先后启动和关闭;静态 Eloquent 连接与 Permissions 门面可能指向上一个
        // 已关闭的池(症状是 HikariPool has been closed)。显式绑回本上下文。
        io.jaravel.eloquent.Eloquent.useConnection(db);
        Permissions.use(new io.jaravel.permissions.DatabasePermissionStore(db));

        for (String table : RBAC_TABLES) {
            db.table(table).allowUnconditional().delete();
        }
    }

    private User createUser(String email) {
        User user = new User();
        user.name = "RBAC 用户";
        user.email = email;
        user.password = Hash.make("secret");
        user.save();
        return user;
    }

    @Test
    @DisplayName("★ 有 admin 角色 → 200;没有 → 403")
    void roleMiddleware() {
        User admin = createUser("rbac-admin@example.com");
        Permissions.assignRole(admin, "admin");
        actingAs(admin).get("/permission-demo/role").assertOk();

        User plain = createUser("rbac-plain@example.com");
        actingAs(plain).get("/permission-demo/role").assertStatus(403);
    }

    @Test
    @DisplayName("★ 直接权限 → 200;角色继承的权限也 → 200")
    void permissionMiddleware() {
        User direct = createUser("rbac-direct@example.com");
        Permissions.givePermissionTo(direct, "publish-posts");
        actingAs(direct).get("/permission-demo/publish").assertOk();

        Permissions.createRole("editor", "publish-posts");
        User inherited = createUser("rbac-inherited@example.com");
        Permissions.assignRole(inherited, "editor");
        actingAs(inherited).get("/permission-demo/publish").assertOk();

        User denied = createUser("rbac-denied@example.com");
        actingAs(denied).get("/permission-demo/publish").assertStatus(403);
    }

    @Test
    @DisplayName("未登录 → 401,而不是 403")
    void unauthenticated() {
        getJson("/permission-demo/role").assertStatus(401);
    }

    @Test
    @DisplayName("权限存在 store 里 —— 换个请求(新会话)仍然生效")
    void permissionsPersistAcrossRequests() {
        User user = createUser("rbac-persist@example.com");
        Permissions.assignRole(user, "admin");

        // actingAs 每次重新走一遍会话守卫;权限从数据库读,不是请求内存
        actingAs(user).get("/permission-demo/role").assertOk();
        actingAs(user).get("/permission-demo/role").assertOk();

        assertThat(Permissions.hasRole(user, "admin")).isTrue();
    }
}
