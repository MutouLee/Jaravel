package io.jaravel.migration.schema;

import java.util.ArrayList;
import java.util.List;

/** MySQL / MariaDB 的 DDL。标识符用反引号。 */
public class MySqlSchemaGrammar extends SchemaGrammar {

    @Override public String name() { return "mysql"; }

    @Override protected char openQuote()  { return '`'; }
    @Override protected char closeQuote() { return '`'; }

    @Override
    protected String typeFor(ColumnDefinition c) {
        String base = switch (c.type()) {
            case BIG_INCREMENTS, FOREIGN_ID -> "bigint";
            case INCREMENTS                 -> "int";
            case BIG_INTEGER                -> "bigint";
            case INTEGER                    -> "int";
            case SMALL_INTEGER              -> "smallint";
            case TINY_INTEGER               -> "tinyint";
            case BOOLEAN                    -> "tinyint(1)";
            case YEAR                       -> "year";
            case STRING                     -> "varchar(" + (c.length() == null ? 255 : c.length()) + ")";
            case CHAR                       -> "char(" + (c.length() == null ? 1 : c.length()) + ")";
            case UUID                       -> "char(36)";
            case ULID                       -> "char(26)";
            case TEXT                       -> "text";
            case MEDIUM_TEXT                -> "mediumtext";
            case LONG_TEXT                  -> "longtext";
            case DECIMAL                    -> "decimal(" + c.precision() + ", " + c.scale() + ")";
            case FLOAT                      -> "float(" + c.precision() + ", " + c.scale() + ")";
            case DOUBLE                      -> "double";
            case DATE                       -> "date";
            case DATE_TIME                  -> withPrecision("datetime", c);
            case TIMESTAMP                  -> withPrecision("timestamp", c);
            case TIME                       -> withPrecision("time", c);
            // MySQL 的 json 是原生类型;jsonb 是 PG 的,这里降级
            case JSON, JSONB                -> "json";
            case ENUM                       -> listType("enum", c);
            case SET                        -> listType("set", c);
            case BINARY                     -> "blob";
            case IP_ADDRESS                 -> "varchar(45)";
            case MAC_ADDRESS                -> "varchar(17)";
            case GEOMETRY                   -> "geometry";
        };
        // unsigned 必须紧跟类型
        return c.isUnsigned() && isNumeric(c) ? base + " unsigned" : base;
    }

    private static boolean isNumeric(ColumnDefinition c) {
        return switch (c.type()) {
            case BIG_INCREMENTS, INCREMENTS, FOREIGN_ID, BIG_INTEGER, INTEGER,
                 SMALL_INTEGER, TINY_INTEGER, DECIMAL, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    private static String withPrecision(String type, ColumnDefinition c) {
        return c.precision() != null && c.precision() > 0 ? type + "(" + c.precision() + ")" : type;
    }

    private String listType(String keyword, ColumnDefinition c) {
        String values = c.allowed().stream()
                .map(v -> "'" + escape(v) + "'")
                .reduce((a, b) -> a + ", " + b).orElse("''");
        return keyword + "(" + values + ")";
    }

    @Override protected String autoIncrementClause()     { return " auto_increment"; }
    @Override protected boolean supportsColumnPositioning() { return true; }

    @Override
    protected void appendColumnComment(ColumnDefinition c, StringBuilder sql) {
        sql.append(" comment '").append(escape(c.comment())).append('\'');
    }

    @Override
    protected void appendTableOptions(Blueprint blueprint, StringBuilder sql) {
        if (blueprint.engineName() != null)    sql.append(" engine = ").append(blueprint.engineName());
        if (blueprint.charsetName() != null)   sql.append(" default character set ").append(blueprint.charsetName());
        if (blueprint.collationName() != null) sql.append(" collate ").append(blueprint.collationName());
        if (blueprint.tableComment() != null) {
            sql.append(" comment = '").append(escape(blueprint.tableComment())).append('\'');
        }
    }

    @Override
    protected List<String> compileChangeColumn(String table, ColumnDefinition c) {
        return List.of("alter table " + wrap(table) + " modify " + compileColumn(c));
    }

    @Override
    protected String createFullTextIndex(String table, List<String> columns, String name) {
        String idx = name != null ? name : indexName(table, columns, "fulltext");
        return "alter table " + wrap(table) + " add fulltext " + wrap(idx)
             + " (" + wrapList(columns) + ")";
    }

    @Override
    protected String createSpatialIndex(String table, List<String> columns, String name) {
        String idx = name != null ? name : indexName(table, columns, "spatial");
        return "alter table " + wrap(table) + " add spatial index " + wrap(idx)
             + " (" + wrapList(columns) + ")";
    }

    @Override
    protected String dropIndexStatement(String table, String name) {
        return "alter table " + wrap(table) + " drop index " + wrap(name);
    }

    @Override
    protected List<String> compileCommand(String table, Blueprint.Command cmd) {
        if (cmd.kind().equals("dropColumn")) {
            // MySQL 允许一句删多列,少一次表重建
            var parts = new ArrayList<String>();
            for (String col : cmd.args()) parts.add("drop column " + wrap(col));
            return List.of("alter table " + wrap(table) + " " + String.join(", ", parts));
        }
        if (cmd.kind().equals("dropForeign")) {
            return List.of("alter table " + wrap(table) + " drop foreign key " + wrap(cmd.args().get(0)));
        }
        return super.compileCommand(table, cmd);
    }

    @Override
    public String compileTableExists(String table) {
        return "select table_name from information_schema.tables "
             + "where table_schema = database() and table_name = '" + escape(table) + "'";
    }

    @Override
    public String compileColumnListing(String table) {
        return "select column_name as name from information_schema.columns "
             + "where table_schema = database() and table_name = '" + escape(table) + "'";
    }
}
