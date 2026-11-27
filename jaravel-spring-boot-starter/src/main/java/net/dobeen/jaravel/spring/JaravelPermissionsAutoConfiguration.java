package net.dobeen.jaravel.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import net.dobeen.jaravel.core.config.ConfigRepository;
import net.dobeen.jaravel.database.Connection;
import net.dobeen.jaravel.permissions.ArrayPermissionStore;
import net.dobeen.jaravel.permissions.DatabasePermissionStore;
import net.dobeen.jaravel.permissions.PermissionStore;
import net.dobeen.jaravel.permissions.Permissions;

/**
 * 角色与权限的自动装配(docs/08 §5.4)。
 *
 * <pre>{@code
 * permissions:
 *   tables:
 *     roles: roles
 *     permissions: permissions
 *     role_has_permissions: role_has_permissions
 *     model_has_roles: model_has_roles
 *     model_has_permissions: model_has_permissions
 * }</pre>
 *
 * <p>有数据库连接时用 {@link DatabasePermissionStore}(多实例共享);
 * 没有则退回进程内 ArrayStore 并**明确警告** —— 重启即丢、多实例不共享,
 * 而「权限看起来有时有时没有」是排查成本很高的一类现象。
 *
 * <p>权限表不自动创建:与其它表一样走应用迁移
 * ({@link net.dobeen.jaravel.permissions.PermissionSchema})。
 */
@AutoConfiguration(after = JaravelAuthAutoConfiguration.class)
public class JaravelPermissionsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PermissionStore jaravelPermissionStore(ConfigRepository config,
                                                  ObjectProvider<Connection> connection) {
        Connection db = connection.getIfAvailable();
        if (db == null) {
            System.err.println("""
                    [jaravel] 权限存储使用进程内 ArrayPermissionStore —— 重启即丢、多实例不共享。
                              生产请配置 spring.datasource.url,并运行 PermissionSchema 的迁移。""");
            return new ArrayPermissionStore();
        }

        String r = config.string("permissions.tables.roles", "roles");
        String p = config.string("permissions.tables.permissions", "permissions");
        String rp = config.string("permissions.tables.role_has_permissions",
                "role_has_permissions");
        String mr = config.string("permissions.tables.model_has_roles", "model_has_roles");
        String mp = config.string("permissions.tables.model_has_permissions",
                "model_has_permissions");
        return new DatabasePermissionStore(db, r, p, rp, mr, mp);
    }

    /**
     * 把 store 装进门面。
     *
     * <p>单独一个 bean 而不是在上面的 factory 里顺手调:用户提供自己的
     * {@code PermissionStore} bean 时,上面的方法被跳过 —— 那样门面就
     * 永远不会被设置。这个 bean 依赖的是**任意** PermissionStore。
     */
    @Bean
    public PermissionWiring jaravelPermissionWiring(PermissionStore store) {
        return new PermissionWiring(store);
    }

    record PermissionWiring(PermissionStore store) {
        PermissionWiring {
            Permissions.use(store);
        }
    }
}
