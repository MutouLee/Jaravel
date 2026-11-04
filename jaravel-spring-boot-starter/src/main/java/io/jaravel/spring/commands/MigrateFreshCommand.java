package io.jaravel.spring.commands;

import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.database.Connection;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Migrator;

/**
 * {@code jaravel migrate:fresh}(docs/13 §2.3)—— 全部回滚再全部执行。
 *
 * <p>⚠ 这条命令**清掉所有数据**。它在开发期很常用(改了迁移要重来),
 * 在生产环境里是灾难,所以生产护栏对它尤其重要 ——
 * 而护栏在不可交互时一律拒绝(见 {@link RuntimeCommand#confirmInProduction})。
 */
@Command(name = "migrate:fresh", description = "回滚全部迁移再重新执行(清数据)")
public final class MigrateFreshCommand extends RuntimeCommand {

    @Option(names = "--seed", description = "重建完成后执行 db:seed")
    boolean seed;

    private final Supplier<Connection> connection;
    private final Supplier<List<Migration>> migrations;

    public MigrateFreshCommand(Supplier<Connection> connection,
                               Supplier<List<Migration>> migrations) {
        this.connection = connection;
        this.migrations = migrations;
    }

    @Override
    public int handle() {
        if (!confirmInProduction("**删掉全部表并重建** —— 所有数据都会没了")) return 1;

        List<Migration> found = migrations.get();
        var migrator = new Migrator(connection.get());
        try {
            migrator.reset(found);
            migrator.migrate(found);
        } finally {
            migrator.output().forEach(this::line);
        }

        if (seed) {
            newLine();
            return call("db:seed");
        }
        return 0;
    }
}
