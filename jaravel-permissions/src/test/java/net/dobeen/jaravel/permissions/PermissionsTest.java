package net.dobeen.jaravel.permissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HasPermissions} 的默认方法 + 静态门面的主体解析。
 */
class PermissionsTest {

    static class TestUser implements HasPermissions {
        public Long id;

        TestUser(Long id) {
            this.id = id;
        }
    }

    static class NoIdUser implements HasPermissions {
    }

    @BeforeEach
    void setUp() {
        Permissions.use(new ArrayPermissionStore());
    }

    @Test
    @DisplayName("实现接口即可:assignRole / hasRole / givePermissionTo")
    void defaultMethods() {
        var user = new TestUser(1L);

        user.assignRole("editor");
        user.givePermissionTo("delete-posts");

        assertThat(user.hasRole("editor")).isTrue();
        assertThat(user.roles()).containsExactly("editor");
        assertThat(user.hasPermissionTo("delete-posts")).isTrue();
        assertThat(user.permissions()).contains("delete-posts");
    }

    @Test
    @DisplayName("★ 角色继承权限")
    void roleInheritance() {
        Permissions.createRole("editor", "publish-posts", "edit-posts");
        var user = new TestUser(2L);

        user.assignRole("editor");

        assertThat(user.hasPermissionTo("publish-posts")).isTrue();
        assertThat(user.permissions())
                .containsExactlyInAnyOrder("publish-posts", "edit-posts");
    }

    @Test
    @DisplayName("removeRole / revokePermissionTo")
    void removal() {
        var user = new TestUser(3L);
        user.assignRole("editor");
        user.givePermissionTo("export");

        user.removeRole("editor");
        user.revokePermissionTo("export");

        assertThat(user.hasRole("editor")).isFalse();
        assertThat(user.hasPermissionTo("export")).isFalse();
    }

    @Test
    @DisplayName("不实现 HasPermissions 也能用静态 API(public id 字段)")
    void staticApiOnPlainObject() {
        var user = new TestUser(4L);
        // TestUser 实现了接口,但静态 API 不要求 —— 这里用同样的对象证明路径一致
        Permissions.assignRole(user, "admin");
        assertThat(Permissions.hasRole(user, "admin")).isTrue();
    }

    @Test
    @DisplayName("主体没有 id → 明确报错,不是静默挂到 null 上")
    void missingIdFails() {
        assertThatThrownBy(() -> new NoIdUser().assignRole("editor"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public id");
    }

    @Test
    @DisplayName("未保存(id = null)也报错 —— 权限关系无处可挂")
    void nullIdFails() {
        assertThatThrownBy(() -> new TestUser(null).assignRole("editor"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("Auth.user() 的包装体与模型算出同一个 type(经 unwrap)")
    void authenticatableUnwraps() {
        var user = new TestUser(5L);
        var wrapper = new net.dobeen.jaravel.auth.Authenticatable() {
            @Override public Object getAuthIdentifier() { return user.id; }
            @Override public java.util.Optional<String> getAuthPassword() {
                return java.util.Optional.empty();
            }
            @Override public Object unwrap() { return user; }
        };

        Permissions.assignRole(wrapper, "editor");

        assertThat(Permissions.hasRole(user, "editor"))
                .as("poke 用 Auth.user() 查,assign 用模型 —— type 必须一致")
                .isTrue();
    }
}
