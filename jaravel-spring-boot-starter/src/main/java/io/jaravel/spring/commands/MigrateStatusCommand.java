package io.jaravel.spring.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.database.Connection;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Migrator;

/**
 * {@code jaravel migrate:status}(docs/13 §2.3)。
 *
 * <pre>
 * ┌─────┬──────────────────────────────────────┬──────┐
 * │ 已跑 │ 迁移                                  │ 批次 │
 * ├─────┼──────────────────────────────────────┼──────┤
 * │ ✓   │ 2026_10_29_000100_CreateBlogTables   │ 1    │
 * │ ✗   │ 2026_11_04_123000_CreatePostsTable   │      │
 * └─────┴──────────────────────────────────────┴──────┘
 * </pre>
 *
 * <h2>★ 同时列出「库里有记录但代码里没有」的迁移</h2>
 * <p>那通常意味着**切了分支**:当前分支少了一个已经执行过的迁移,
 * 而表结构里还留着它改的东西。只看代码侧的列表看不出这件事,
 * 而它恰恰是「我的库为什么和同事不一样」的常见答案。
 */
@Command(name = "migrate:status", description = "迁移的执行状态")
public final class MigrateStatusCommand extends RuntimeCommand {

    private final Supplier<Connection> connection;
    private final Supplier<List<Migration>> migrations;

    public MigrateStatusCommand(Supplier<Connection> connection,
                                Supplier<List<Migration>> migrations) {
        this.connection = connection;
        this.migrations = migrations;
    }

    @Override
    public int handle() {
        var migrator = new Migrator(connection.get());
        if (!migrator.repositoryExists()) {
            comment("迁移记录表还不存在 —— 一次 migrate 都没跑过。");
            return 0;
        }

        var ran = migrator.ranMigrations();
        var rows = new ArrayList<List<Object>>();
        var seen = new ArrayList<String>();

        for (Migration migration : migrations.get()) {
            String name = migrator.nameOf(migration);
            seen.add(name);
            var record = ran.get(name);
            rows.add(List.of(record == null ? "✗" : "✓", name,
                    record == null ? "" : String.valueOf(record.batch())));
        }

        var orphans = ran.keySet().stream().filter(name -> !seen.contains(name)).sorted().toList();
        orphans.forEach(name -> rows.add(List.of("?", name, String.valueOf(ran.get(name).batch()))));

        table(List.of("已跑", "迁移", "批次"), rows);

        if (!orphans.isEmpty()) {
            newLine();
            warn("带 ? 的 " + orphans.size() + " 条:库里有执行记录,但当前代码里找不到。");
            line("  通常意味着切了分支 —— 表结构里还留着它改过的东西。");
        }
        return 0;
    }
}
