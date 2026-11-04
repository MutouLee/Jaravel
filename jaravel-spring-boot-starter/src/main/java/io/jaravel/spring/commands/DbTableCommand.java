package io.jaravel.spring.commands;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import io.jaravel.console.Argument;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;
import io.jaravel.database.Connection;

/**
 * {@code jaravel db:table posts}(docs/13 §2.5)—— 一张表的真实结构。
 *
 * <h2>★ 它读的是**库里的现状**,不是迁移文件</h2>
 * <p>这就是它存在的理由。迁移文件说的是「应该变成什么」,而这条命令说的是
 * 「现在是什么」。两者不一致的情况非常常见:某条迁移没跑、有人手动改过、
 * 回滚回滚到一半。而那种不一致的症状通常是「列不存在」这类运行时错误 ——
 * 拿着迁移文件看是看不出来的。
 *
 * <p>所以不读迁移表、不做任何推断,只问 {@link DatabaseMetaData}
 * (理由与 {@link DbShowCommand} 相同:一条代码路径,而不是三份方言 SQL)。
 */
@Command(name = "db:table", description = "显示一张表的列与索引")
public final class DbTableCommand extends RuntimeCommand {

    private final DataSource dataSource;
    private final Connection connection;

    @Argument(description = "要看的表")
    String table;

    @Option(names = "--counts", description = "同时统计行数(大表上是全表扫描)")
    boolean counts;

    public DbTableCommand(DataSource dataSource, Connection connection) {
        this.dataSource = dataSource;
        this.connection = connection;
    }

    @Override
    public int handle() {
        try (var jdbc = dataSource.getConnection()) {
            DatabaseMetaData meta = jdbc.getMetaData();

            String resolved = resolveName(meta, table);
            if (resolved == null) return notFound(meta);

            comment(resolved);
            renderColumns(meta, resolved, primaryKey(meta, resolved));
            renderIndexes(meta, resolved);

            if (counts) {
                newLine();
                twoColumnDetail("行数", String.valueOf(rowCount(resolved)));
            }
            return 0;
        } catch (SQLException e) {
            throw new ConsoleException("读不了表 " + table + " 的元数据:" + e.getMessage(), e);
        }
    }

    /**
     * 表名按**大小写不敏感**找。
     *
     * <p>{@code getColumns} 的表名参数在多数驱动上是大小写敏感的,
     * 而用户敲的是 {@code db:table Posts}。直接传过去会得到
     * 「这张表没有列」—— 一个看起来像「表是空的」的输出,
     * 而真相是名字没对上。所以先在表清单里找一遍。
     */
    private String resolveName(DatabaseMetaData meta, String wanted) throws SQLException {
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name.equalsIgnoreCase(wanted)) return name;
            }
        }
        return null;
    }

    private int notFound(DatabaseMetaData meta) throws SQLException {
        failure("没有叫 " + table + " 的表。");
        newLine();
        var names = new ArrayList<String>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) names.add(rs.getString("TABLE_NAME"));
        }
        if (names.isEmpty()) {
            line("这个库里一张表都没有 —— 迁移跑过了吗(migrate:status)?");
        } else {
            line("库里有这些表:");
            names.stream().sorted().forEach(name -> line("  " + name));
        }
        return 1;
    }

    private Set<String> primaryKey(DatabaseMetaData meta, String name) throws SQLException {
        var keys = new LinkedHashSet<String>();
        try (ResultSet rs = meta.getPrimaryKeys(null, null, name)) {
            while (rs.next()) keys.add(rs.getString("COLUMN_NAME"));
        }
        return keys;
    }

    private void renderColumns(DatabaseMetaData meta, String name, Set<String> primaryKey)
            throws SQLException {
        var rows = new ArrayList<List<String>>();
        try (ResultSet rs = meta.getColumns(null, null, name, "%")) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                rows.add(List.of(
                        column + (primaryKey.contains(column) ? " (主键)" : ""),
                        type(rs),
                        "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? "可空" : "非空",
                        defaultValue(rs)));
            }
        }
        if (rows.isEmpty()) {
            warn("这张表没有列 —— 元数据读到的是空的。");
            return;
        }
        table(List.of("列", "类型", "可空", "默认"), rows);
        noteNullablePrimaryKey(meta, name, primaryKey);
    }

    /**
     * ⚠ SQLite 会把 {@code INTEGER} 主键报成**可空**。
     *
     * <p>照驱动说的显示(这里不替驱动「纠正」事实),但加一句注 ——
     * 否则一个主键列显示「可空」看起来就是个 bug,
     * 而下一个读到它的人会去改这段代码。
     *
     * <p>而且 SQLite 是**对的**:{@code INTEGER PRIMARY KEY} 是 rowid 的别名,
     * 往它插 NULL 会自动填一个值。所以这不是元数据读错了,
     * 是那一列真的接受 NULL 作为「请自动生成」的写法。
     */
    private void noteNullablePrimaryKey(DatabaseMetaData meta, String name,
                                        Set<String> primaryKey) throws SQLException {
        if (primaryKey.isEmpty()) return;
        boolean nullablePk = false;
        try (ResultSet rs = meta.getColumns(null, null, name, "%")) {
            while (rs.next()) {
                if (primaryKey.contains(rs.getString("COLUMN_NAME"))
                        && "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))) {
                    nullablePk = true;
                }
            }
        }
        if (nullablePk) {
            comment("  注:主键显示「可空」是 SQLite 的 rowid 别名特性"
                    + "(插 NULL 会自动填值),不是表定义写错了。");
        }
    }

    /** {@code varchar(255)} 比 {@code varchar} 有用 —— 长度经常就是问题所在 */
    private String type(ResultSet rs) throws SQLException {
        String type = rs.getString("TYPE_NAME");
        int size = rs.getInt("COLUMN_SIZE");
        int digits = rs.getInt("DECIMAL_DIGITS");
        boolean noDigits = rs.wasNull() || digits == 0;
        if (noDigits) {
            return size > 0 && needsSize(type) ? type + "(" + size + ")" : type;
        }
        return type + "(" + size + "," + digits + ")";
    }

    private boolean needsSize(String type) {
        String lower = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("char") || lower.contains("binary");
    }

    private String defaultValue(ResultSet rs) throws SQLException {
        String value = rs.getString("COLUMN_DEF");
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    private void renderIndexes(DatabaseMetaData meta, String name) throws SQLException {
        // 一个索引可能有多列,而元数据里**每列一行** —— 按名字聚起来,
        // 否则一个 (a, b) 的复合索引会显示成两个单列索引,
        // 而那会让人以为复合索引没建上,然后去加一个重复的
        var indexes = new LinkedHashMap<String, List<String>>();
        var unique = new LinkedHashSet<String>();
        try (ResultSet rs = meta.getIndexInfo(null, null, name, false, false)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (index == null || column == null) continue;
                indexes.computeIfAbsent(index, key -> new ArrayList<>()).add(column);
                if (!rs.getBoolean("NON_UNIQUE")) unique.add(index);
            }
        }

        newLine();
        comment("索引(" + indexes.size() + ")");
        if (indexes.isEmpty()) {
            line("  没有索引。");
            return;
        }
        indexes.forEach((index, columns) -> twoColumnDetail(index,
                String.join(", ", columns) + (unique.contains(index) ? "(唯一)" : "")));
    }

    private long rowCount(String name) {
        return connection.select("select count(*) as c from " + connection.grammar().wrap(name))
                .first().map(row -> row.longValue("c")).orElse(0L);
    }
}
