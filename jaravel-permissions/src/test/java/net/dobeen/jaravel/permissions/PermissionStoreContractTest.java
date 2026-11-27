package net.dobeen.jaravel.permissions;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import net.dobeen.jaravel.database.JdbcConnection;
import net.dobeen.jaravel.database.grammar.SqliteGrammar;
import net.dobeen.jaravel.migration.schema.Schema;

/**
 * 权限存储契约:同一组断言跑在 Array 与 Database 上。
 *
 * <p>与缓存契约测试同一理由:换存储后行为变了,是这类模块最危险的缺陷。
 */
class PermissionStoreContractTest {

    private static final String TYPE = "app.models.User";
    private static final String ID = "1";

    static Stream<Arguments> stores() {
        return Stream.of(
                Arguments.of("array", (Supplier<PermissionStore>) ArrayPermissionStore::new),
                Arguments.of("database", (Supplier<PermissionStore>) PermissionStoreContractTest::freshDatabase));
    }

    private static PermissionStore freshDatabase() {
        try {
            var real = DriverManager.getConnection("jdbc:sqlite::memory:");
            var connection = new JdbcConnection(nonClosing(real), new SqliteGrammar());
            PermissionSchema.create(Schema.on(connection));
            return new DatabasePermissionStore(connection);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把单条 SQLite 连接包成 DataSource —— {@code :memory:} 的库随连接消失,
     * 若每条 JDBC 操作都拿新连接,会看到不同的空库。
     */
    private static DataSource nonClosing(java.sql.Connection real) {
        var proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[]{java.sql.Connection.class},
                (p, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("isClosed")) return false;
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new DataSource() {
            @Override public java.sql.Connection getConnection() { return proxy; }
            @Override public java.sql.Connection getConnection(String u, String p) { return proxy; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
            @Override public <T> T unwrap(Class<T> iface) { return iface.cast(proxy); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("stores")
    @DisplayName("assign / hasRole / rolesOf,重复 assign 不重复")
    void roles(String name, Supplier<PermissionStore> factory) {
        PermissionStore store = factory.get();
        store.assignRole(TYPE, ID, "editor");
        store.assignRole(TYPE, ID, "editor");

        assertThat(store.hasRole(TYPE, ID, "editor")).isTrue();
        assertThat(store.hasRole(TYPE, ID, "admin")).isFalse();
        assertThat(store.rolesOf(TYPE, ID)).containsExactly("editor");
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("stores")
    @DisplayName("直接授予权限可见,撤销后不可见")
    void directPermissions(String name, Supplier<PermissionStore> factory) {
        PermissionStore store = factory.get();
        store.givePermissionTo(TYPE, ID, "publish-posts");
        store.givePermissionTo(TYPE, ID, "publish-posts");

        assertThat(store.hasPermissionTo(TYPE, ID, "publish-posts")).isTrue();
        assertThat(store.permissionsOf(TYPE, ID)).containsExactly("publish-posts");

        store.revokePermissionFrom(TYPE, ID, "publish-posts");
        assertThat(store.hasPermissionTo(TYPE, ID, "publish-posts")).isFalse();
        assertThat(store.permissionsOf(TYPE, ID)).isEmpty();
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("stores")
    @DisplayName("★ 角色继承权限")
    void permissionsThroughRoles(String name, Supplier<PermissionStore> factory) {
        PermissionStore store = factory.get();
        store.createRole("editor", List.of("publish-posts", "edit-posts"));
        store.assignRole(TYPE, ID, "editor");

        assertThat(store.rolePermissions("editor"))
                .containsExactlyInAnyOrder("publish-posts", "edit-posts");
        assertThat(store.hasPermissionTo(TYPE, ID, "publish-posts")).isTrue();
        assertThat(store.permissionsOf(TYPE, ID))
                .containsExactlyInAnyOrder("publish-posts", "edit-posts");
        assertThat(store.hasPermissionTo(TYPE, ID, "delete-posts")).isFalse();
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("stores")
    @DisplayName("多个角色的权限合并;移除角色后不再继承")
    void multipleRoles(String name, Supplier<PermissionStore> factory) {
        PermissionStore store = factory.get();
        store.createRole("editor", List.of("publish-posts"));
        store.createRole("moderator", List.of("ban-users"));
        store.assignRole(TYPE, ID, "editor");
        store.assignRole(TYPE, ID, "moderator");

        assertThat(store.hasPermissionTo(TYPE, ID, "publish-posts")).isTrue();
        assertThat(store.hasPermissionTo(TYPE, ID, "ban-users")).isTrue();

        store.removeRole(TYPE, ID, "editor");
        assertThat(store.hasRole(TYPE, ID, "editor")).isFalse();
        assertThat(store.hasPermissionTo(TYPE, ID, "publish-posts")).isFalse();
        assertThat(store.hasPermissionTo(TYPE, ID, "ban-users")).isTrue();
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("stores")
    @DisplayName("不存在的角色/权限一律 false / 空,不抛")
    void unknownNamesAreSafe(String name, Supplier<PermissionStore> factory) {
        PermissionStore store = factory.get();
        assertThat(store.hasRole(TYPE, ID, "ghost")).isFalse();
        assertThat(store.hasPermissionTo(TYPE, ID, "ghost")).isFalse();
        assertThat(store.rolesOf(TYPE, ID)).isEmpty();
        assertThat(store.permissionsOf(TYPE, ID)).isEmpty();
        assertThat(store.rolePermissions("ghost")).isEmpty();
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("stores")
    @DisplayName("主体隔离:type 或 id 不同互不影响")
    void subjectsAreIsolated(String name, Supplier<PermissionStore> factory) {
        PermissionStore store = factory.get();
        store.assignRole(TYPE, "1", "editor");
        store.assignRole("other.User", "1", "admin");
        store.assignRole(TYPE, "2", "member");

        assertThat(store.hasRole(TYPE, "1", "editor")).isTrue();
        assertThat(store.hasRole(TYPE, "1", "admin")).isFalse();
        assertThat(store.hasRole("other.User", "1", "admin")).isTrue();
        assertThat(store.hasRole(TYPE, "2", "member")).isTrue();
    }
}
