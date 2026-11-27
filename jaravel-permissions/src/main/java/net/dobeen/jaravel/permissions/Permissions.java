package net.dobeen.jaravel.permissions;

import java.lang.reflect.Field;
import java.util.List;

import net.dobeen.jaravel.auth.Authenticatable;

/**
 * 角色与权限的静态门面(docs/08 §5.4)。
 *
 * <pre>{@code
 * Permissions.assignRole(user, "editor");
 * Permissions.givePermissionTo(user, "publish-posts");
 * Permissions.hasPermissionTo(user, "publish-posts");
 *
 * // 用户模型 implements HasPermissions 之后:
 * user.assignRole("editor");
 * user.hasPermissionTo("publish-posts");
 * }</pre>
 *
 * <p>主体可以是 Eloquent 模型、{@link Authenticatable} 包装体,或任何有
 * public {@code id} 字段的对象。{@code Auth.user()} 返回的是包装体,
 * 这里会 unwrap 成模型再取类名 —— 与 {@link HasPermissions} 在模型上
 * 算出的 type 一致。
 */
public final class Permissions {

    private Permissions() {
    }

    private static volatile PermissionStore store = new ArrayPermissionStore();

    public static void use(PermissionStore configured) {
        store = configured == null ? new ArrayPermissionStore() : configured;
    }

    public static PermissionStore store() {
        return store;
    }

    // ================= 角色 =================

    public static void createRole(String role, String... permissions) {
        store.createRole(role, List.of(permissions));
    }

    public static void assignRole(Object subject, String role) {
        store.assignRole(typeOf(subject), idOf(subject), role);
    }

    public static void assignRole(String subjectType, Object subjectId, String role) {
        store.assignRole(subjectType, String.valueOf(subjectId), role);
    }

    public static void removeRole(Object subject, String role) {
        store.removeRole(typeOf(subject), idOf(subject), role);
    }

    public static boolean hasRole(Object subject, String role) {
        return store.hasRole(typeOf(subject), idOf(subject), role);
    }

    public static List<String> rolesOf(Object subject) {
        return store.rolesOf(typeOf(subject), idOf(subject));
    }

    // ================= 权限 =================

    public static void givePermissionTo(Object subject, String permission) {
        store.givePermissionTo(typeOf(subject), idOf(subject), permission);
    }

    public static void revokePermissionFrom(Object subject, String permission) {
        store.revokePermissionFrom(typeOf(subject), idOf(subject), permission);
    }

    public static boolean hasPermissionTo(Object subject, String permission) {
        return store.hasPermissionTo(typeOf(subject), idOf(subject), permission);
    }

    public static List<String> permissionsOf(Object subject) {
        return store.permissionsOf(typeOf(subject), idOf(subject));
    }

    public static List<String> rolePermissions(String role) {
        return store.rolePermissions(role);
    }

    // ================= 主体 =================

    static String typeOf(Object subject) {
        Object raw = unwrap(subject);
        return raw.getClass().getName();
    }

    /**
     * 主体 id。
     *
     * <p>{@link Authenticatable} 用自己的 {@code getAuthIdentifier()};
     * 普通模型走 public {@code id} 字段 —— 这正是 jblade 模型与
     * {@code @Id} 的约定。字段名不是 id 的模型可以调
     * {@link #assignRole(String, Object, String)} 这类带显式 type/id 的重载。
     */
    static String idOf(Object subject) {
        if (subject instanceof Authenticatable authenticatable) {
            return String.valueOf(authenticatable.getAuthIdentifier());
        }

        try {
            Field id = subject.getClass().getField("id");
            Object value = id.get(subject);
            if (value != null) return String.valueOf(value);
            throw new IllegalStateException("""
                    %s 的 id 字段是 null —— 先保存再分配权限。
                      未保存的模型没有主键,权限关系无处可挂。"""
                    .formatted(subject.getClass().getSimpleName()));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("""
                    %s 没有可用的 public id 字段。
                      实现 HasPermissions 的模型需要一个 public id(与 @Id 一致),
                      或改用 Permissions.assignRole(type, id, role) 显式指定。"""
                    .formatted(subject.getClass().getSimpleName()));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读不到 " + subject.getClass().getSimpleName()
                    + " 的 id 字段:" + e.getMessage(), e);
        }
    }

    private static Object unwrap(Object subject) {
        return subject instanceof Authenticatable a ? a.unwrap() : subject;
    }
}
