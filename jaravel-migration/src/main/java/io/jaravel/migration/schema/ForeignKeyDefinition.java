package io.jaravel.migration.schema;

import java.util.List;

/** 外键约束 */
public final class ForeignKeyDefinition {

    final List<String> columns;
    final String referencedTable;
    final List<String> referencedColumns;

    String name;
    String onDelete;
    String onUpdate;

    ForeignKeyDefinition(List<String> columns, String referencedTable,
                         List<String> referencedColumns) {
        this.columns = columns;
        this.referencedTable = referencedTable;
        this.referencedColumns = referencedColumns;
    }

    public ForeignKeyDefinition named(String name)        { this.name = name; return this; }
    public ForeignKeyDefinition onDelete(String action)   { this.onDelete = action; return this; }
    public ForeignKeyDefinition onUpdate(String action)   { this.onUpdate = action; return this; }
    public ForeignKeyDefinition cascadeOnDelete()         { return onDelete("cascade"); }
    public ForeignKeyDefinition nullOnDelete()            { return onDelete("set null"); }
    public ForeignKeyDefinition restrictOnDelete()        { return onDelete("restrict"); }

    public List<String> columns()            { return columns; }
    public String referencedTable()          { return referencedTable; }
    public List<String> referencedColumns()  { return referencedColumns; }
    public String onDeleteAction()           { return onDelete; }
    public String onUpdateAction()           { return onUpdate; }

    /**
     * 约束名。未指定时按 Laravel 的约定生成:
     * {@code <表>_<列...>_foreign}。
     *
     * <p>名字可预测很重要 —— {@code dropForeign} 要靠它,而让用户去数据库里
     * 查自动生成的名字是最糟的体验。
     */
    public String name(String table) {
        if (name != null) return name;
        return table + "_" + String.join("_", columns) + "_foreign";
    }
}
