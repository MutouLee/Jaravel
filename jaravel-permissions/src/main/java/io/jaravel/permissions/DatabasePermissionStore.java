package io.jaravel.permissions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.database.Connection;
import io.jaravel.database.Row;

/**
 * 数据库权限存储(docs/08 §5.4)。
 *
 * <p>五张表(与 spatie/laravel-permission 同构):
 *
 * <pre>
 * roles(id, name unique)
 * permissions(id, name unique)
 * role_has_permissions(role_id, permission_id)
 * model_has_roles(role_id, model_type, model_id)
 * model_has_permissions(permission_id, model_type, model_id)
 * </pre>
 *
 * <p>建表用 {@link PermissionSchema}(在应用自己的迁移里调)。
 *
 * <h2>唯一冲突就是「已经有了」</h2>
 * <p>assign / give 先查再插,但两个并发请求可能同时查到空。
 * 插入撞唯一约束时**再查一次**:查到就当成成功,查不到才把异常抛出去。
 * 直接把异常吞掉会把「表不存在」「连接断了」也当成幂等 ——
 * 那会让权限静默地没写进去。
 */
public class DatabasePermissionStore implements PermissionStore {

    private final Connection connection;
    private final String roles;
    private final String permissions;
    private final String roleHasPermissions;
    private final String modelHasRoles;
    private final String modelHasPermissions;

    public DatabasePermissionStore(Connection connection) {
        this(connection, "roles", "permissions", "role_has_permissions",
                "model_has_roles", "model_has_permissions");
    }

    public DatabasePermissionStore(Connection connection,
                                   String roles, String permissions,
                                   String roleHasPermissions,
                                   String modelHasRoles,
                                   String modelHasPermissions) {
        this.connection = connection;
        this.roles = roles;
        this.permissions = permissions;
        this.roleHasPermissions = roleHasPermissions;
        this.modelHasRoles = modelHasRoles;
        this.modelHasPermissions = modelHasPermissions;
    }

    // ================= 建角色 / 权限 =================

    @Override
    public void createRole(String role, List<String> permissions) {
        long roleId = findOrCreate(roles, role);
        if (permissions == null) return;
        for (String permission : permissions) {
            long permissionId = findOrCreate(this.permissions, permission);
            linkOrIgnore(roleHasPermissions, "role_id", roleId,
                    "permission_id", permissionId);
        }
    }

    @Override
    public void createPermission(String permission) {
        findOrCreate(permissions, permission);
    }

    // ================= 授予 =================

    @Override
    public void assignRole(String subjectType, String subjectId, String role) {
        long roleId = findOrCreate(roles, role);
        if (modelRoleExists(roleId, subjectType, subjectId)) return;

        try {
            connection.table(modelHasRoles).insert(Map.of(
                    "role_id", roleId, "model_type", subjectType,
                    "model_id", subjectId));
        } catch (RuntimeException e) {
            if (!modelRoleExists(roleId, subjectType, subjectId)) throw e;
        }
    }

    @Override
    public void removeRole(String subjectType, String subjectId, String role) {
        Optional<Long> roleId = findId(roles, role);
        if (roleId.isEmpty()) return;
        connection.table(modelHasRoles)
                .where("role_id", roleId.get())
                .where("model_type", subjectType)
                .where("model_id", subjectId)
                .delete();
    }

    @Override
    public boolean hasRole(String subjectType, String subjectId, String role) {
        return findId(roles, role)
                .map(id -> modelRoleExists(id, subjectType, subjectId))
                .orElse(false);
    }

    @Override
    public List<String> rolesOf(String subjectType, String subjectId) {
        List<Long> ids = connection.table(modelHasRoles)
                .where("model_type", subjectType)
                .where("model_id", subjectId)
                .get()
                .stream()
                .map(row -> ((Number) row.get("role_id")).longValue())
                .toList();
        if (ids.isEmpty()) return List.of();

        return connection.table(roles).whereIn("id", ids).get().stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }

    @Override
    public void givePermissionTo(String subjectType, String subjectId, String permission) {
        long permissionId = findOrCreate(permissions, permission);
        if (directPermissionExists(permissionId, subjectType, subjectId)) return;

        try {
            connection.table(modelHasPermissions).insert(Map.of(
                    "permission_id", permissionId, "model_type", subjectType,
                    "model_id", subjectId));
        } catch (RuntimeException e) {
            if (!directPermissionExists(permissionId, subjectType, subjectId)) throw e;
        }
    }

    @Override
    public void revokePermissionFrom(String subjectType, String subjectId, String permission) {
        Optional<Long> permissionId = findId(permissions, permission);
        if (permissionId.isEmpty()) return;
        connection.table(modelHasPermissions)
                .where("permission_id", permissionId.get())
                .where("model_type", subjectType)
                .where("model_id", subjectId)
                .delete();
    }

    @Override
    public boolean hasPermissionTo(String subjectType, String subjectId, String permission) {
        Optional<Long> permissionId = findId(permissions, permission);
        if (permissionId.isEmpty()) return false;

        if (directPermissionExists(permissionId.get(), subjectType, subjectId)) return true;

        List<Long> roleIds = roleIdsOf(subjectType, subjectId);
        if (roleIds.isEmpty()) return false;

        return !connection.table(roleHasPermissions)
                .where("permission_id", permissionId.get())
                .whereIn("role_id", roleIds)
                .get().isEmpty();
    }

    @Override
    public List<String> permissionsOf(String subjectType, String subjectId) {
        var ids = new LinkedHashSet<Long>();

        connection.table(modelHasPermissions)
                .where("model_type", subjectType)
                .where("model_id", subjectId)
                .get()
                .forEach(row -> ids.add(((Number) row.get("permission_id")).longValue()));

        List<Long> roleIds = roleIdsOf(subjectType, subjectId);
        if (!roleIds.isEmpty()) {
            connection.table(roleHasPermissions).whereIn("role_id", roleIds).get()
                    .forEach(row -> ids.add(((Number) row.get("permission_id")).longValue()));
        }

        if (ids.isEmpty()) return List.of();
        return connection.table(permissions).whereIn("id", List.copyOf(ids)).get().stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }

    @Override
    public List<String> rolePermissions(String role) {
        Optional<Long> roleId = findId(roles, role);
        if (roleId.isEmpty()) return List.of();

        List<Long> ids = connection.table(roleHasPermissions)
                .where("role_id", roleId.get())
                .get().stream()
                .map(row -> ((Number) row.get("permission_id")).longValue())
                .toList();
        if (ids.isEmpty()) return List.of();

        return connection.table(permissions).whereIn("id", ids).get().stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
    }

    // ================= 内部 =================

    private long findOrCreate(String table, String name) {
        Optional<Long> existing = findId(table, name);
        if (existing.isPresent()) return existing.get();

        try {
            connection.table(table).insert(Map.of("name", name));
        } catch (RuntimeException e) {
            // 唯一冲突 = 并发下别人刚建好;再查一次,查不到才是真的失败
            Optional<Long> raced = findId(table, name);
            if (raced.isPresent()) return raced.get();
            throw e;
        }
        return findId(table, name).orElseThrow(() -> new IllegalStateException(
                "刚插入的 " + table + "(" + name + ") 读不回来 —— 检查表结构与主键"));
    }

    private Optional<Long> findId(String table, String name) {
        return connection.table(table).where("name", name).get().first()
                .map(row -> ((Number) row.get("id")).longValue());
    }

    private boolean modelRoleExists(long roleId, String type, String id) {
        return connection.table(modelHasRoles)
                .where("role_id", roleId)
                .where("model_type", type)
                .where("model_id", id)
                .count() > 0;
    }

    private boolean directPermissionExists(long permissionId, String type, String id) {
        return connection.table(modelHasPermissions)
                .where("permission_id", permissionId)
                .where("model_type", type)
                .where("model_id", id)
                .count() > 0;
    }

    private List<Long> roleIdsOf(String type, String id) {
        List<Long> ids = new ArrayList<>();
        connection.table(modelHasRoles)
                .where("model_type", type)
                .where("model_id", id)
                .get()
                .forEach(row -> ids.add(((Number) row.get("role_id")).longValue()));
        return ids;
    }

    private void linkOrIgnore(String table, String left, Object leftId,
                              String right, Object rightId) {
        boolean exists = connection.table(table)
                .where(left, leftId).where(right, rightId).count() > 0;
        if (exists) return;

        try {
            connection.table(table).insert(Map.of(left, leftId, right, rightId));
        } catch (RuntimeException e) {
            boolean raced = connection.table(table)
                    .where(left, leftId).where(right, rightId).count() > 0;
            if (!raced) throw e;
        }
    }

    // ================= 表 =================

    public String rolesTable() { return roles; }
    public String permissionsTable() { return permissions; }
}
