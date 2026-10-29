package io.jaravel.migration.schema;

import java.util.ArrayList;
import java.util.List;

import io.jaravel.migration.schema.Blueprint.IndexDefinition;
import io.jaravel.migration.schema.ColumnDefinition.Type;

/**
 * Blueprint → DDL。按方言子类化。
 *
 * <p>分层照抄 Laravel 的 {@code Illuminate\Database\Schema\Grammars}:
 * 基类负责「语句骨架」,子类只覆盖真正不同的地方(类型映射、ALTER 支持、
 * 索引语法)。这样加一个方言是改十几个方法,而不是重写整个生成器。
 */
public abstract class SchemaGrammar {

    public abstract String name();

    protected abstract char openQuote();
    protected abstract char closeQuote();

    /** 类型映射是方言差异的主要来源 */
    protected abstract String typeFor(ColumnDefinition column);

    // ================= CREATE TABLE =================

    public List<String> compileCreate(Blueprint blueprint) {
        var statements = new ArrayList<String>();
        var sql = new StringBuilder();

        sql.append("create ");
        if (blueprint.isTemporary()) sql.append("temporary ");
        sql.append("table ").append(wrap(blueprint.table())).append(" (");

        var parts = new ArrayList<String>();
        for (ColumnDefinition c : blueprint.columns()) {
            parts.add(compileColumn(c));
        }
        parts.addAll(compileInlineConstraints(blueprint));
        sql.append(String.join(", ", parts)).append(")");

        appendTableOptions(blueprint, sql);
        statements.add(sql.toString());

        // 索引单独成句:多数方言的 CREATE TABLE 里不能写非主键索引
        statements.addAll(compileIndexes(blueprint));
        return statements;
    }

    /** 列定义:{@code "title" varchar(200) not null default '...'} */
    protected String compileColumn(ColumnDefinition c) {
        var sql = new StringBuilder();
        sql.append(wrap(c.name())).append(' ').append(typeFor(c));

        appendModifiers(c, sql);
        return sql.toString();
    }

    protected void appendModifiers(ColumnDefinition c, StringBuilder sql) {
        if (c.collationName() != null) sql.append(" collate ").append(c.collationName());
        sql.append(c.isNullable() ? " null" : " not null");

        if (c.usesCurrent()) {
            sql.append(" default ").append(currentTimestamp());
        } else if (c.hasDefault()) {
            sql.append(" default ").append(literal(c.defaultValue()));
        }

        if (c.isAutoIncrement()) sql.append(autoIncrementClause());
        if (c.comment() != null) appendColumnComment(c, sql);
    }

    /** 主键与唯一约束通常能写在 CREATE TABLE 里 */
    protected List<String> compileInlineConstraints(Blueprint blueprint) {
        var parts = new ArrayList<String>();

        // 列级 primary() 已由 autoIncrementClause 处理的方言会覆盖这里
        var pkColumns = blueprint.columns().stream()
                .filter(ColumnDefinition::isPrimary)
                .map(ColumnDefinition::name)
                .toList();
        var declaredPk = blueprint.indexes().stream()
                .filter(i -> i.kind().equals("primary"))
                .findFirst();

        if (declaredPk.isPresent()) {
            parts.add("primary key (" + wrapList(declaredPk.get().columns()) + ")");
        } else if (!pkColumns.isEmpty() && !primaryKeyIsInlineWithAutoIncrement()) {
            parts.add("primary key (" + wrapList(pkColumns) + ")");
        }

        for (ForeignKeyDefinition fk : blueprint.foreignKeys()) {
            parts.add(compileForeignKey(blueprint.table(), fk));
        }
        return parts;
    }

    protected String compileForeignKey(String table, ForeignKeyDefinition fk) {
        if (fk.referencedTable() == null) {
            throw new IllegalStateException(
                    "外键 " + fk.columns() + " 没有指定被引用的表。"
                  + "\n  写法:t.foreign(\"user_id\", \"users\", \"id\")"
                  + "\n    或:t.foreignId(\"user_id\").constrained()");
        }
        var sql = new StringBuilder();
        sql.append("constraint ").append(wrap(fk.name(table)))
           .append(" foreign key (").append(wrapList(fk.columns())).append(")")
           .append(" references ").append(wrap(fk.referencedTable()))
           .append(" (").append(wrapList(fk.referencedColumns())).append(")");

        if (fk.onDeleteAction() != null) sql.append(" on delete ").append(fk.onDeleteAction());
        if (fk.onUpdateAction() != null) sql.append(" on update ").append(fk.onUpdateAction());
        return sql.toString();
    }

    protected List<String> compileIndexes(Blueprint blueprint) {
        var statements = new ArrayList<String>();

        for (ColumnDefinition c : blueprint.columns()) {
            if (c.isUnique()) {
                statements.add(createIndex(blueprint.table(), List.of(c.name()), null, true));
            } else if (c.hasIndex()) {
                statements.add(createIndex(blueprint.table(), List.of(c.name()), null, false));
            }
        }
        for (IndexDefinition i : blueprint.indexes()) {
            switch (i.kind()) {
                case "unique" -> statements.add(
                        createIndex(blueprint.table(), i.columns(), i.name(), true));
                case "index" -> statements.add(
                        createIndex(blueprint.table(), i.columns(), i.name(), false));
                case "fulltext" -> statements.add(
                        createFullTextIndex(blueprint.table(), i.columns(), i.name()));
                case "spatial" -> statements.add(
                        createSpatialIndex(blueprint.table(), i.columns(), i.name()));
                case "primary" -> { /* 已在 CREATE TABLE 里 */ }
                default -> throw new IllegalStateException("未知索引类型:" + i.kind());
            }
        }
        return statements;
    }

    protected String createIndex(String table, List<String> columns, String name, boolean unique) {
        String idx = name != null ? name : indexName(table, columns, unique ? "unique" : "index");
        return "create " + (unique ? "unique " : "") + "index " + wrap(idx)
             + " on " + wrap(table) + " (" + wrapList(columns) + ")";
    }

    protected String createFullTextIndex(String table, List<String> columns, String name) {
        throw new UnsupportedOperationException(
                name() + " 不支持全文索引。"
              + "\n  可选做法:用外部搜索引擎(Meilisearch / Elasticsearch),"
              + "或改用 like 查询并给前缀加普通索引。");
    }

    protected String createSpatialIndex(String table, List<String> columns, String name) {
        throw new UnsupportedOperationException(name() + " 不支持空间索引。");
    }

    /** {@code posts_user_id_slug_index} —— 可预测,因为 dropIndex 要用 */
    protected String indexName(String table, List<String> columns, String kind) {
        return (table + "_" + String.join("_", columns) + "_" + kind)
                .toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }

    // ================= ALTER TABLE =================

    public List<String> compileAlter(Blueprint blueprint) {
        var statements = new ArrayList<String>();

        for (ColumnDefinition c : blueprint.columns()) {
            if (c.isChange()) {
                statements.addAll(compileChangeColumn(blueprint.table(), c));
            } else {
                statements.add(compileAddColumn(blueprint.table(), c));
            }
        }
        for (Blueprint.Command cmd : blueprint.commands()) {
            statements.addAll(compileCommand(blueprint.table(), cmd));
        }
        statements.addAll(compileIndexes(blueprint));

        for (ForeignKeyDefinition fk : blueprint.foreignKeys()) {
            statements.add("alter table " + wrap(blueprint.table())
                         + " add " + compileForeignKey(blueprint.table(), fk));
        }
        return statements;
    }

    protected String compileAddColumn(String table, ColumnDefinition c) {
        var sql = new StringBuilder("alter table ").append(wrap(table))
                .append(" add column ").append(compileColumn(c));
        if (c.after() != null && supportsColumnPositioning()) {
            sql.append(" after ").append(wrap(c.after()));
        } else if (c.isFirst() && supportsColumnPositioning()) {
            sql.append(" first");
        }
        return sql.toString();
    }

    protected List<String> compileChangeColumn(String table, ColumnDefinition c) {
        throw new UnsupportedOperationException(
                name() + " 不支持修改已有列的类型。" + changeColumnWorkaround(table, c));
    }

    protected String changeColumnWorkaround(String table, ColumnDefinition c) {
        return "";
    }

    protected List<String> compileCommand(String table, Blueprint.Command cmd) {
        return switch (cmd.kind()) {
            case "renameColumn" -> List.of("alter table " + wrap(table)
                    + " rename column " + wrap(cmd.args().get(0))
                    + " to " + wrap(cmd.args().get(1)));
            case "dropColumn" -> cmd.args().stream()
                    .map(col -> "alter table " + wrap(table) + " drop column " + wrap(col))
                    .toList();
            case "dropIndex", "dropUnique" -> List.of(dropIndexStatement(table, cmd.args().get(0)));
            case "dropForeign" -> List.of("alter table " + wrap(table)
                    + " drop constraint " + wrap(cmd.args().get(0)));
            case "dropPrimary" -> List.of("alter table " + wrap(table) + " drop primary key");
            default -> throw new IllegalStateException("未知的 schema 操作:" + cmd.kind());
        };
    }

    protected String dropIndexStatement(String table, String name) {
        return "drop index " + wrap(name);
    }

    // ================= 表级操作 =================

    public String compileDrop(String table) {
        return "drop table " + wrap(table);
    }

    public String compileDropIfExists(String table) {
        return "drop table if exists " + wrap(table);
    }

    public String compileRename(String from, String to) {
        return "alter table " + wrap(from) + " rename to " + wrap(to);
    }

    public abstract String compileTableExists(String table);

    public abstract String compileColumnListing(String table);

    public String compileEnableForeignKeys()  { return "set foreign_key_checks = 1"; }
    public String compileDisableForeignKeys() { return "set foreign_key_checks = 0"; }

    // ================= 可覆盖的细节 =================

    protected void appendTableOptions(Blueprint blueprint, StringBuilder sql) {
        // 默认什么都不加
    }

    protected void appendColumnComment(ColumnDefinition c, StringBuilder sql) {
        // 多数方言的注释要单独一句,默认忽略
    }

    protected String autoIncrementClause() { return " autoincrement"; }

    protected String currentTimestamp() { return "current_timestamp"; }

    /** 自增列是否自带 primary key(SQLite 是) */
    protected boolean primaryKeyIsInlineWithAutoIncrement() { return false; }

    protected boolean supportsColumnPositioning() { return false; }

    // ================= 工具 =================

    public String wrap(String identifier) {
        if (identifier == null) return "";
        if (identifier.equals("*")) return "*";
        // 标识符里不允许出现引号字符 —— 否则就是 DDL 注入
        if (identifier.indexOf(openQuote()) >= 0 || identifier.indexOf(closeQuote()) >= 0) {
            throw new IllegalArgumentException(
                    "标识符 `" + identifier + "` 含引号字符,拒绝生成 DDL。"
                  + "\n  表名/列名应只含字母、数字、下划线。");
        }
        return openQuote() + identifier + closeQuote();
    }

    protected String wrapList(List<String> identifiers) {
        return identifiers.stream().map(this::wrap).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * 默认值字面量。
     *
     * <p>DDL 不能用占位符,所以这里必须自己转义。只放行能安全表达的类型,
     * 其余拒绝 —— 比起猜一个 {@code toString()},报错让用户显式写字符串更安全。
     */
    protected String literal(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean b) return booleanLiteral(b);
        if (value instanceof Number n) return n.toString();
        if (value instanceof Enum<?> e) return "'" + escape(e.name()) + "'";
        if (value instanceof CharSequence s) return "'" + escape(s.toString()) + "'";
        if (value instanceof java.time.temporal.Temporal t) return "'" + escape(t.toString()) + "'";

        throw new IllegalArgumentException(
                "不支持把 " + value.getClass().getName() + " 作为列默认值。"
              + "\n  DDL 里的默认值不能用占位符,必须能安全地写成字面量。"
              + "\n  请改成字符串、数字、布尔或时间类型。");
    }

    protected String booleanLiteral(boolean value) {
        return value ? "1" : "0";
    }

    protected String escape(String value) {
        return value.replace("'", "''");
    }
}
