package net.dobeen.jaravel.permissions;

import java.util.List;

import net.dobeen.jaravel.migration.schema.Schema;

/**
 * 权限表的建表助手(docs/08 §5.4)。
 *
 * <p>框架不自动建表 —— 与其它表一样走应用的迁移:
 *
 * <pre>{@code
 * public class CreatePermissionTables implements Migration {
 *     public void up()   { PermissionSchema.create(); }
 *     public void down() { PermissionSchema.drop(); }
 * }
 * }</pre>
 *
 * <p>表名与 {@link DatabasePermissionStore} 的默认值一致;自定义表名时
 * 两边要一起改。
 */
public final class PermissionSchema {

    private PermissionSchema() {
    }

    public static void create() {
        create(Schema.instance());
    }

    public static void create(Schema schema) {
        schema.createTable("roles", t -> {
            t.id();
            t.string("name", 125);
            t.unique("name");
        });

        schema.createTable("permissions", t -> {
            t.id();
            t.string("name", 125);
            t.unique("name");
        });

        schema.createTable("role_has_permissions", t -> {
            t.bigInteger("role_id");
            t.bigInteger("permission_id");
            t.primary(List.of("role_id", "permission_id"));
        });

        schema.createTable("model_has_roles", t -> {
            t.bigInteger("role_id");
            t.string("model_type", 255);
            t.string("model_id", 255);
            t.primary(List.of("role_id", "model_type", "model_id"));
            t.index(List.of("model_type", "model_id"));
        });

        schema.createTable("model_has_permissions", t -> {
            t.bigInteger("permission_id");
            t.string("model_type", 255);
            t.string("model_id", 255);
            t.primary(List.of("permission_id", "model_type", "model_id"));
            t.index(List.of("model_type", "model_id"));
        });
    }

    public static void drop() {
        drop(Schema.instance());
    }

    public static void drop(Schema schema) {
        schema.dropTableIfExists("model_has_permissions");
        schema.dropTableIfExists("model_has_roles");
        schema.dropTableIfExists("role_has_permissions");
        schema.dropTableIfExists("permissions");
        schema.dropTableIfExists("roles");
    }
}
