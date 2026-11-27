package database.migrations.m2026_11_17_000000;

import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.permissions.PermissionSchema;

/**
 * RBAC 的表(docs/08 §5.4)。
 *
 * <p>单独一个迁移而不是塞进 {@code CreateAuthTables}:那个文件是 parity
 * 切片 03 的被测对象,往里加表会让基准变成 apples-to-oranges。
 */
public class CreatePermissionTables implements Migration {

    @Override
    public void up() {
        PermissionSchema.create();
    }

    @Override
    public void down() {
        PermissionSchema.drop();
    }
}
