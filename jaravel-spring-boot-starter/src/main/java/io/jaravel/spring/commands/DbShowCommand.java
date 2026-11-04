package io.jaravel.spring.commands;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.database.Connection;

/**
 * {@code jaravel db:show}(docs/13 §2.5)—— 这个库里有什么。
 *
 * <pre>
 * 连接
 *   驱动                SQLite 3.47.1
 *   URL                 jdbc:sqlite:storage/blog.db
 * 表(4)
 *   posts               6 列
 *   comments            5 列
 * </pre>
 *
 * <h2>★ 用 JDBC 的元数据,不写三份 SQL</h2>
 * <p>「列出所有表」在每个数据库里都不一样:SQLite 查
 * {@code sqlite_master},MySQL 是 {@code SHOW TABLES},
 * Postgres 查 {@code information_schema}。按方言分支意味着三条代码路径,
 * 而其中两条在这台开发机上**永远不会被执行** —— 它们会悄悄腐坏。
 *
 * <p>{@link DatabaseMetaData} 是驱动自己实现的同一套接口,所以只有一条路径。
 * 这条命令住在 starter 里,拿得到 {@code DataSource} ——
 * 而 {@link Connection} 那个契约只有 SQL,故意不暴露底层连接。
 *
 * <h2>★ 行数默认**不**统计</h2>
 * <p>{@code select count(*)} 在大表上是一次全扫。这条命令通过通道和
 * 启动器可达,也就是说它可能跑在一台生产机器上 ——
 * 一条「看看库里有什么」的命令不该顺手给生产库来一遍全表扫描。
 * 要数就显式 {@code --counts}(Laravel 的 {@code db:show} 也是这么划的)。
 */
@Command(name = "db:show", description = "显示数据库连接与表清单")
public final class DbShowCommand extends RuntimeCommand {

    private final DataSource dataSource;
    private final Connection connection;
    private final ConfigRepository config;

    @Option(names = "--counts", description = "统计每张表的行数(大表上是全表扫描)")
    boolean counts;

    public DbShowCommand(DataSource dataSource, Connection connection, ConfigRepository config) {
        this.dataSource = dataSource;
        this.connection = connection;
        this.config = config;
    }

    @Override
    public int handle() {
        comment("连接");
        try (var jdbc = dataSource.getConnection()) {
            DatabaseMetaData meta = jdbc.getMetaData();
            twoColumnDetail("驱动", meta.getDatabaseProductName() + " "
                    + meta.getDatabaseProductVersion());
            twoColumnDetail("配置名", config.string("database.default", "(未配置)"));
            twoColumnDetail("URL", scrub(meta.getURL()));
            twoColumnDetail("Grammar", connection.grammar().name());

            List<Table> tables = tables(meta);
            newLine();
            comment("表(" + tables.size() + ")");
            if (tables.isEmpty()) {
                line("  一张表都没有 —— 迁移跑过了吗(migrate:status)?");
                return 0;
            }
            for (Table table : tables) {
                twoColumnDetail(table.name, describe(table));
            }

            newLine();
            if (!counts) {
                line("行数没有统计 —— 加 --counts(大表上那是一次全表扫描)。");
            }
            line("某一张表的列与索引:db:table <表名>");
            return 0;
        } catch (SQLException e) {
            throw new ConsoleException("读不了数据库元数据:" + e.getMessage(), e);
        }
    }

    private record Table(String name, int columns, Long rows) {}

    private List<Table> tables(DatabaseMetaData meta) throws SQLException {
        var out = new ArrayList<Table>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (isSystemTable(name)) continue;
                out.add(new Table(name, columnCount(meta, name), counts ? rowCount(name) : null));
            }
        }
        out.sort(java.util.Comparator.comparing(Table::name));
        return out;
    }

    /**
     * 驱动自己的表要滤掉。
     *
     * <p>{@code getTables} 的 {@code "TABLE"} 过滤器挡不住它们:
     * SQLite 的 {@code sqlite_sequence} 就是一张普通表。
     * 它们出现在清单里只会让人以为是自己建的 —— 而「这张表哪来的」
     * 是个很浪费时间的问题。
     */
    private boolean isSystemTable(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("sqlite_") || lower.startsWith("pg_")
                || lower.startsWith("sys_") || lower.equals("information_schema");
    }

    private int columnCount(DatabaseMetaData meta, String table) throws SQLException {
        int count = 0;
        try (ResultSet rs = meta.getColumns(null, null, table, "%")) {
            while (rs.next()) count++;
        }
        return count;
    }

    /**
     * 数一张表的行数。
     *
     * <p>表名走 {@code grammar().wrap(...)} —— 引号规则每个库不一样
     * (反引号 / 双引号 / 中括号),而一张叫 {@code order} 的表
     * 不加引号会得到一条语法错误。
     */
    private Long rowCount(String table) {
        try {
            return connection.select("select count(*) as c from "
                            + connection.grammar().wrap(table))
                    .first()
                    .map(row -> row.longValue("c"))
                    .orElse(null);
        } catch (RuntimeException e) {
            // 一张数不了的表(权限、视图伪装成表)不该让整个清单打不出来
            return null;
        }
    }

    private String describe(Table table) {
        String columns = table.columns + " 列";
        if (!counts) return columns;
        return columns + " · " + (table.rows == null ? "行数读不到" : table.rows + " 行");
    }

    /**
     * JDBC URL 里可能带着密码。
     *
     * <p>{@code jdbc:mysql://root:hunter2@localhost/app} —— 这条命令的输出
     * 会被贴进工单、落进 CI 日志,而**密码进日志是不可逆的**。
     *
     * <p>注意这件事 {@code config:show} 管不着:那边按**键名**判断敏感
     * ({@code password} / {@code secret} / …),而这里的密码藏在一个
     * 叫 {@code url} 的值**里面**。两种判据都需要。
     */
    static String scrub(String url) {
        if (url == null) return "(未知)";
        // user:password@host 这种形式
        String scrubbed = url.replaceAll("://([^:/@]+):([^@/]+)@", "://$1:" + ConfigRepository.MASK + "@");
        // ?password=... / &user=...&password=... 这种形式
        return scrubbed.replaceAll("(?i)([?&](?:password|pwd|secret|token)=)[^&]*",
                "$1" + ConfigRepository.MASK);
    }
}
