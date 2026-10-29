package io.jaravel.migration.schema;

import java.util.List;

/**
 * SQLite 的 DDL。
 *
 * <p>SQLite 的 ALTER TABLE 能力极弱(不能改列类型、不能加约束),这里的做法是
 * **明确报错并给出唯一可行的替代**,而不是生成一条会在运行时失败的 SQL ——
 * 迁移失败在半路是最难收拾的状态。
 */
public class SqliteSchemaGrammar extends SchemaGrammar {

    @Override public String name() { return "sqlite"; }

    @Override protected char openQuote()  { return '"'; }
    @Override protected char closeQuote() { return '"'; }

    /**
     * SQLite 只有 5 种存储类,所以类型映射很粗。
     *
     * <p>仍然写出 {@code varchar(200)} 这种精确类型:SQLite 会忽略长度,
     * 但同一份迁移换到 MySQL 跑时就有意义了,而且 {@code .db} 文件的
     * schema 自描述性更好。
     */
    @Override
    protected String typeFor(ColumnDefinition c) {
        return switch (c.type()) {
            case BIG_INCREMENTS, INCREMENTS -> "integer";
            case FOREIGN_ID, BIG_INTEGER    -> "integer";
            case TINY_INTEGER, SMALL_INTEGER, INTEGER, YEAR -> "integer";
            case BOOLEAN                    -> "integer";
            case STRING                     -> "varchar(" + (c.length() == null ? 255 : c.length()) + ")";
            case CHAR                       -> "char(" + (c.length() == null ? 1 : c.length()) + ")";
            case UUID                       -> "varchar(36)";
            case ULID                       -> "varchar(26)";
            case TEXT, MEDIUM_TEXT, LONG_TEXT -> "text";
            case DECIMAL                    -> "numeric(" + c.precision() + ", " + c.scale() + ")";
            case FLOAT, DOUBLE              -> "real";
            // 存 ISO-8601 文本:SQLite 没有时间类型,而文本的字典序等于时间序
            case DATE, DATE_TIME, TIMESTAMP, TIME -> "text";
            case JSON, JSONB                -> "text";
            case ENUM, SET                  -> enumType(c);
            case BINARY                     -> "blob";
            case IP_ADDRESS                 -> "varchar(45)";
            case MAC_ADDRESS                -> "varchar(17)";
            case GEOMETRY                   -> "text";
        };
    }

    /**
     * SQLite 没有 ENUM,降级为带 CHECK 约束的 varchar。
     *
     * <p>不是单纯降级成 text:那样枚举列就彻底失去约束,
     * 「本地 SQLite 测试通过、线上 MySQL 报非法枚举值」才是最糟的。
     */
    private String enumType(ColumnDefinition c) {
        String values = c.allowed().stream()
                .map(v -> "'" + escape(v) + "'")
                .reduce((a, b) -> a + ", " + b).orElse("''");
        return "varchar(255) check (" + wrap(c.name()) + " in (" + values + "))";
    }

    /** SQLite 的 {@code integer primary key autoincrement} 必须写在列上 */
    @Override
    protected boolean primaryKeyIsInlineWithAutoIncrement() {
        return true;
    }

    @Override
    protected String autoIncrementClause() {
        return " primary key autoincrement";
    }

    /** SQLite 的自增列必须可空声明在前,这里把 not null 去掉以免与 primary key 冲突 */
    @Override
    protected void appendModifiers(ColumnDefinition c, StringBuilder sql) {
        if (c.isAutoIncrement()) {
            sql.append(autoIncrementClause());
            return;
        }
        super.appendModifiers(c, sql);
    }

    @Override
    protected List<String> compileChangeColumn(String table, ColumnDefinition c) {
        throw new UnsupportedOperationException(
                "SQLite 不支持修改已有列(" + table + "." + c.name() + ")。"
              + "\n  这是 SQLite 本身的限制,不是 Jaravel 的。唯一可靠的做法是四步走:"
              + "\n    1. Schema.create(\"" + table + "_new\", t -> { /* 新结构 */ });"
              + "\n    2. DB.statement(\"insert into " + table + "_new select ... from " + table + "\");"
              + "\n    3. Schema.drop(\"" + table + "\");"
              + "\n    4. Schema.rename(\"" + table + "_new\", \"" + table + "\");"
              + "\n  若这个迁移只在 MySQL/PG 上跑,用 Schema.onlyOn(\"mysql\", ...) 把它圈起来。");
    }

    @Override
    protected List<String> compileCommand(String table, Blueprint.Command cmd) {
        return switch (cmd.kind()) {
            // SQLite 3.35+ 支持 drop column,但有约束;3.25+ 支持 rename column
            case "dropForeign" -> throw new UnsupportedOperationException(
                    "SQLite 不能单独删除外键约束 —— 它把外键存在表定义里。"
                  + "\n  做法同「修改列」:重建表。");
            case "dropPrimary" -> throw new UnsupportedOperationException(
                    "SQLite 不能删除主键,需重建表。");
            default -> super.compileCommand(table, cmd);
        };
    }

    @Override
    public String compileTableExists(String table) {
        return "select name from sqlite_master where type = 'table' and name = '"
             + escape(table) + "'";
    }

    @Override
    public String compileColumnListing(String table) {
        return "select name from pragma_table_info('" + escape(table) + "')";
    }

    @Override public String compileEnableForeignKeys()  { return "pragma foreign_keys = ON"; }
    @Override public String compileDisableForeignKeys() { return "pragma foreign_keys = OFF"; }

    @Override
    protected String currentTimestamp() {
        return "CURRENT_TIMESTAMP";
    }
}
