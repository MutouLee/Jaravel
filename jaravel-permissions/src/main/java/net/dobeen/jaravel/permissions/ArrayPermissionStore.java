package net.dobeen.jaravel.permissions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内权限存储 —— 测试、单实例小应用。
 *
 * <p>⚠ 重启即丢、多实例不共享。生产请用 {@link DatabasePermissionStore}
 * (starter 在有连接时会自动选它)。
 */
public class ArrayPermissionStore implements PermissionStore {

    private record Subject(String type, String id) {
    }

    private final Map<String, Set<String>> rolePermissions = new ConcurrentHashMap<>();
    private final Map<Subject, Set<String>> subjectRoles = new ConcurrentHashMap<>();
    private final Map<Subject, Set<String>> subjectPermissions = new ConcurrentHashMap<>();

    @Override
    public void createRole(String role, List<String> permissions) {
        Set<String> granted = rolePermissions.computeIfAbsent(role,
                r -> ConcurrentHashMap.newKeySet());
        if (permissions != null) {
            for (String permission : permissions) {
                createPermission(permission);
                granted.add(permission);
            }
        }
    }

    @Override
    public void createPermission(String permission) {
        // 权限本身不需要独立存储 —— 它只作为名字出现在两张关系表里。
        // 但为了「建了但没授予」也能被 rolePermissions/permissionsOf 记住,
        // 这里给每个已知权限一个存在标记不划算。createPermission 的语义
        // 是「确保这个名字合法」,而任何字符串都合法。
    }

    @Override
    public void assignRole(String subjectType, String subjectId, String role) {
        createRole(role, List.of());
        subjectRoles.computeIfAbsent(new Subject(subjectType, subjectId),
                s -> ConcurrentHashMap.newKeySet()).add(role);
    }

    @Override
    public void removeRole(String subjectType, String subjectId, String role) {
        Set<String> roles = subjectRoles.get(new Subject(subjectType, subjectId));
        if (roles != null) roles.remove(role);
    }

    @Override
    public boolean hasRole(String subjectType, String subjectId, String role) {
        Set<String> roles = subjectRoles.get(new Subject(subjectType, subjectId));
        return roles != null && roles.contains(role);
    }

    @Override
    public List<String> rolesOf(String subjectType, String subjectId) {
        Set<String> roles = subjectRoles.get(new Subject(subjectType, subjectId));
        return roles == null ? List.of() : List.copyOf(roles);
    }

    @Override
    public void givePermissionTo(String subjectType, String subjectId, String permission) {
        subjectPermissions.computeIfAbsent(new Subject(subjectType, subjectId),
                s -> ConcurrentHashMap.newKeySet()).add(permission);
    }

    @Override
    public void revokePermissionFrom(String subjectType, String subjectId, String permission) {
        Set<String> permissions = subjectPermissions.get(new Subject(subjectType, subjectId));
        if (permissions != null) permissions.remove(permission);
    }

    @Override
    public boolean hasPermissionTo(String subjectType, String subjectId, String permission) {
        Subject subject = new Subject(subjectType, subjectId);
        Set<String> direct = subjectPermissions.get(subject);
        if (direct != null && direct.contains(permission)) return true;

        Set<String> roles = subjectRoles.get(subject);
        if (roles == null) return false;
        for (String role : roles) {
            Set<String> permissions = rolePermissions.get(role);
            if (permissions != null && permissions.contains(permission)) return true;
        }
        return false;
    }

    @Override
    public List<String> permissionsOf(String subjectType, String subjectId) {
        Subject subject = new Subject(subjectType, subjectId);
        var all = new LinkedHashSet<String>();
        Set<String> direct = subjectPermissions.get(subject);
        if (direct != null) all.addAll(direct);

        Set<String> roles = subjectRoles.get(subject);
        if (roles != null) {
            for (String role : roles) {
                Set<String> permissions = rolePermissions.get(role);
                if (permissions != null) all.addAll(permissions);
            }
        }
        return List.copyOf(all);
    }

    @Override
    public List<String> rolePermissions(String role) {
        Set<String> permissions = rolePermissions.get(role);
        return permissions == null ? List.of() : List.copyOf(permissions);
    }
}
