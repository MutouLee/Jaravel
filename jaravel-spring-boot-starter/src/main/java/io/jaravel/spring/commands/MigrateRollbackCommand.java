package io.jaravel.spring.commands;

import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.database.Connection;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Migrator;

/** {@code jaravel migrate:rollback [--step=N]}(docs/13 §2.3) */
@Command(name = "migrate:rollback", description = "回滚上一个批次的迁移")
public final class MigrateRollbackCommand extends RuntimeCommand {

    @Option(names = "--step", description = "回滚多少个批次", defaultValue = "1")
    int step;

    private final Supplier<Connection> connection;
    private final Supplier<List<Migration>> migrations;

    public MigrateRollbackCommand(Supplier<Connection> connection,
                                  Supplier<List<Migration>> migrations) {
        this.connection = connection;
        this.migrations = migrations;
    }

    @Override
    public int handle() {
        if (!confirmInProduction("回滚数据库结构(down() 里的 drop 会丢数据)")) return 1;

        var migrator = new Migrator(connection.get());
        try {
            migrator.rollback(migrations.get(), step);
        } finally {
            migrator.output().forEach(this::line);
        }
        return 0;
    }
}
