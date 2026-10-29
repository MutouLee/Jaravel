package io.jaravel.migration.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 的 DDL。
 *
 * <p>与 MySQL 的主要差异:自增用 {@code serial}/{@code bigserial} 而非
 * {@code auto_increment};布尔是真布尔;{@code jsonb} 是原生且该优先用;
 * 修改列要拆成多条 {@code ALTER COLUMN}。
 */
public class PostgresSchemaGrammar extends SchemaGrammar {

    @Override public String name() { return "postgres"; }

    @Override protected char openQuote()  { return '"'; }
    @Override protected char closeQuote() { return '"'; }

    @Override
    protected String typeFor(ColumnDefinition c) {
        return switch (c.type()) {
            // serial 自带序列与默认值,所以 autoIncrementClause 返回空
            case BIG_INCREMENTS             -> "bigserial";
            case INCREMENTS                 -> "serial";
            case FOREIGN_ID, BIG_INTEGER    -> "bigint";
            case INTEGER                    -> "integer";
            case SMALL_INTEGER, YEAR        -> "smallint";
            case TINY_INTEGER               -> "smallint";   // PG 没有 tinyint
            case BOOLEAN                    -> "boolean";
            case STRING                     -> "varchar(" + (c.length() == null ? 255 : c.length()) + ")";
            case CHAR                       -> "char(" + (c.length() == null ? 1 : c.length()) + ")";
            case UUID                       -> "uuid";
            case ULID                       -> "char(26)";
            case TEXT, MEDIUM_TEXT, LONG_TEXT -> "text";
            case DECIMAL                    -> "decimal(" + c.precision() + ", " + c.scale() + ")";
            case FLOAT                      -> "real";
            case DOUBLE                     -> "double precision";
            case DATE                       -> "date";
            case DATE_TIME                  -> withPrecision("timestamp", c) + " without time zone";
            case TIMESTAMP                  -> withPrecision("timestamp", c) + " with time zone";
            case TIME                       -> withPrecision("time", c);
            // 默认给 jsonb:它可索引、可按路径查询,json 只是原文存储
            case JSON, JSONB                -> "jsonb";
            case ENUM                       -> enumType(c);
            case SET                        -> "text[]";
            case BINARY                     -> "bytea";
            case IP_ADDRESS                 -> "inet";
            case MAC_ADDRESS                -> "macaddr";
            case GEOMETRY                   -> "geography(geometry, 4326)";
        };
    }

    private static String withPrecision(String type, ColumnDefinition c) {
        return c.precision() != null && c.precision() > 0 ? type + "(" + c.precision() + ")" : type;
    }

    /**
     * 用 CHECK 而非 PG 的原生 enum 类型。
     *
     * <p>原生 enum 要先 {@code CREATE TYPE},而且**往里加值在旧版本是不可回滚的** ——
     * 一个迁移失败就留下半个类型。CHECK 约束可以随表一起 drop,行为可预测。
     */
    private String enumType(ColumnDefinition c) {
        String values = c.allowed().stream()
                .map(v -> "'" + escape(v) + "'")
                .reduce((a, b) -> a + ", " + b).orElse("''");
        return "varchar(255) check (" + wrap(c.name()) + " in (" + values + "))";
    }

    /** serial 已经带了序列,不需要额外子句 */
    @Override protected String autoIncrementClause() { return ""; }

    @Override protected String booleanLiteral(boolean value) { return value ? "true" : "false"; }

    @Override
    protected void appendModifiers(ColumnDefinition c, StringBuilder sql) {
        // PG 的 serial 列不能写 default,也不该写 not null(serial 自带)
        if (c.isAutoIncrement()) {
            sql.append(" primary key");
            return;
        }
        super.appendModifiers(c, sql);
    }

    @Override
    protected boolean primaryKeyIsInlineWithAutoIncrement() {
        return true;
    }

    @Override
    protected List<String> compileChangeColumn(String table, ColumnDefinition c) {
        // PG 要把类型、可空、默认值拆成独立的 ALTER COLUMN
        var out = new ArrayList<String>();
        String prefix = "alter table " + wrap(table) + " alter column " + wrap(c.name());

        out.add(prefix + " type " + typeFor(c));
        out.add(prefix + (c.isNullable() ? " drop not null" : " set not null"));
        if (c.hasDefault()) {
            out.add(prefix + " set default " + literal(c.defaultValue()));
        }
        return out;
    }

    @Override
    protected void appendColumnComment(ColumnDefinition c, StringBuilder sql) {
        // PG 的注释是独立语句,不能内联 —— 这里忽略,由 compileCreate 之后补
    }

    @Override
    public List<String> compileCreate(Blueprint blueprint) {
        var statements = new ArrayList<>(super.compileCreate(blueprint));

        // PG 的 COMMENT ON 必须独立成句
        for (ColumnDefinition c : blueprint.columns()) {
            if (c.comment() == null) continue;
            statements.add("comment on column " + wrap(blueprint.table()) + "." + wrap(c.name())
                         + " is '" + escape(c.comment()) + "'");
        }
        if (blueprint.tableComment() != null) {
            statements.add("comment on table " + wrap(blueprint.table())
                         + " is '" + escape(blueprint.tableComment()) + "'");
        }
        return statements;
    }

    @Override
    protected String createFullTextIndex(String table, List<String> columns, String name) {
        String idx = name != null ? name : indexName(table, columns, "fulltext");
        String expr = columns.stream()
                .map(c -> "coalesce(" + wrap(c) + ", '')")
                .reduce((a, b) -> a + " || ' ' || " + b).orElseThrow();
        return "create index " + wrap(idx) + " on " + wrap(table)
             + " using gin (to_tsvector('simple', " + expr + "))";
    }

    @Override
    protected String createSpatialIndex(String table, List<String> columns, String name) {
        String idx = name != null ? name : indexName(table, columns, "spatial");
        return "create index " + wrap(idx) + " on " + wrap(table)
             + " using gist (" + wrapList(columns) + ")";
    }

    @Override
    public String compileTableExists(String table) {
        return "select table_name from information_schema.tables "
             + "where table_schema = current_schema() and table_name = '" + escape(table) + "'";
    }

    @Override
    public String compileColumnListing(String table) {
        return "select column_name as name from information_schema.columns "
             + "where table_schema = current_schema() and table_name = '" + escape(table) + "'";
    }

    /**
     * PG 没有全局的外键开关。
     *
     * <p>不假装成功:返回一条会报错的语句不如直接说清楚,否则用户以为
     * {@code withoutForeignKeyConstraints} 生效了,实际上约束还在。
     */
    @Override
    public String compileDisableForeignKeys() {
        throw new UnsupportedOperationException(
                "PostgreSQL 没有全局的外键开关。"
              + "\n  可选做法:"
              + "\n    · 按表禁用:alter table X disable trigger all;(需要超级用户)"
              + "\n    · 把外键声明为 deferrable initially deferred,在事务内延后检查;"
              + "\n    · 调整 Seeder 的插入顺序,让被引用的行先入库 —— 推荐这个。");
    }

    @Override
    public String compileEnableForeignKeys() {
        throw new UnsupportedOperationException("见 compileDisableForeignKeys 的说明。");
    }
}
