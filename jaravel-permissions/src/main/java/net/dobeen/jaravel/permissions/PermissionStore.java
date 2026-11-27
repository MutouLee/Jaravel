package net.dobeen.jaravel.permissions;

import java.util.List;

/**
 * 角色与权限的存储契约(docs/08 §5.4)。
 *
 * <p>键是 {@code (subjectType, subjectId)} —— 与 spatie/laravel-permission
 * 的 {@code model_type/model_id} 同构。用类名而不是表名:一个应用可以有
 * 两个用户类型(admin / member),它们共用角色表但不该互相继承。
 *
 * <h2>assign / give 会顺手建角色与权限</h2>
 * <p>{@code user.assignRole("editor")} 在角色不存在时创建它。代价是
 * 拼错名字会静默多一个没人拥有的角色;换来的是「加个角色」不需要
 * 先跑一个 seeder。真正在意拼写就用 {@link #createRole} 显式建。
 */
public interface PermissionStore {

    /** 建角色(已存在则补上权限)。 */
    void createRole(String role, List<String> permissions);

    /** 建一个权限(已存在则什么都不做)。 */
    void createPermission(String permission);

    void assignRole(String subjectType, String subjectId, String role);

    void removeRole(String subjectType, String subjectId, String role);

    boolean hasRole(String subjectType, String subjectId, String role);

    List<String> rolesOf(String subjectType, String subjectId);

    /** 直接授予权限(不经角色)。 */
    void givePermissionTo(String subjectType, String subjectId, String permission);

    void revokePermissionFrom(String subjectType, String subjectId, String permission);

    /**
     * 是否有这个权限 —— **直接授予或经角色继承**。
     */
    boolean hasPermissionTo(String subjectType, String subjectId, String permission);

    /** 有效权限:直接授予 + 所有角色的权限。 */
    List<String> permissionsOf(String subjectType, String subjectId);

    /** 一个角色拥有哪些权限。 */
    List<String> rolePermissions(String role);
}
