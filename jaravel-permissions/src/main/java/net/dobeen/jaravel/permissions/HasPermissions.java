package net.dobeen.jaravel.permissions;

import java.util.List;

/**
 * 用户模型的可选混入(docs/08 §5.4)。
 *
 * <pre>{@code
 * public class User extends UserBase implements HasPermissions {
 *     @Id public Long id;
 *     // 不需要写任何权限相关的方法
 * }
 *
 * user.assignRole("editor");
 * user.hasPermissionTo("publish-posts");
 * }</pre>
 *
 * <p>模型需要一个 public {@code id} 字段(与 {@code @Id} 的约定一致)。
 * 不实现这个接口也能用 {@link Permissions} 的静态方法 —— 它接受任何
 * 有 public id 的对象。
 */
public interface HasPermissions {

    default void assignRole(String role) {
        Permissions.assignRole(this, role);
    }

    default void removeRole(String role) {
        Permissions.removeRole(this, role);
    }

    default boolean hasRole(String role) {
        return Permissions.hasRole(this, role);
    }

    default List<String> roles() {
        return Permissions.rolesOf(this);
    }

    default void givePermissionTo(String permission) {
        Permissions.givePermissionTo(this, permission);
    }

    default void revokePermissionTo(String permission) {
        Permissions.revokePermissionFrom(this, permission);
    }

    /** 直接授予或经角色继承 */
    default boolean hasPermissionTo(String permission) {
        return Permissions.hasPermissionTo(this, permission);
    }

    default List<String> permissions() {
        return Permissions.permissionsOf(this);
    }
}
