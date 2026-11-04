package io.jaravel.spring.commands;

import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.Option;
import io.jaravel.database.Connection;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Migrator;

/**
 * {@code jaravel migrate}(docs/13 §2.3)。
 *
 * <pre>
 * jaravel migrate                执行未运行的迁移
 * jaravel migrate --pretend      只打印 SQL,不执行
 * jaravel migrate --step         每个迁移单独一个批次(便于逐个回滚)
 * jaravel migrate --seed         跑完顺带 db:seed
 * </pre>
 *
 * <p>Migrator 的能力(含 checksum 漂移检测)早就齐了,这里只是壳子 ——
 * 它把 {@code Migrator.output()} 的行转成控制台输出,并加上生产护栏。
 */
@Command(name = "migrate", description = "执行未运行的数据库迁移")
public final class MigrateCommand extends RuntimeCommand {

    @Option(names = "--pretend", description = "只打印 SQL,不执行")
    boolean pretend;

    @Option(names = "--step", description = "每个迁移单独一个批次")
    boolean step;

    @Option(names = "--allow-drift", description = "允许已执行迁移的内容与记录不一致")
    boolean allowDrift;

    @Option(names = "--seed", description = "迁移完成后执行 db:seed")
    boolean seed;

    private final Supplier<Connection> connection;
    private final Supplier<List<Migration>> migrations;

    public MigrateCommand(Supplier<Connection> connection,
                          Supplier<List<Migration>> migrations) {
        this.connection = connection;
        this.migrations = migrations;
    }

    @Override
    public int handle() {
        // --pretend 不改任何东西,所以不需要生产确认 ——
        // 「想看看它会做什么」恰恰是生产环境里最该允许的操作
        if (!pretend && !confirmInProduction("修改数据库结构")) return 1;

        List<Migration> found = migrations.get();
        if (found.isEmpty()) {
            comment("没有找到迁移(约定位置:database.migrations 包下)。");
            return 0;
        }

        var migrator = new Migrator(connection.get());
        var options = new Migrator.Options(step, allowDrift, pretend);

        try {
            migrator.migrate(found, options);
        } finally {
            // finally:**失败时也要把已经执行的那些打出来** ——
            // 「第几个迁移炸的」是排查的第一个问题,而异常本身只说了原因
            migrator.output().forEach(this::line);
        }

        if (seed) {
            newLine();
            return call("db:seed");
        }
        return 0;
    }
}
