package io.jaravel.migration.schema;

import java.util.List;

/**
 * 一个列的声明。链式修饰符对应 Laravel 的 column modifiers。
 *
 * <p>是可变对象而不是 record:DSL 的写法是
 * {@code t.string("title", 200).nullable().index()},修饰符必须就地改。
 * 改成不可变就得每个修饰符返回新对象,再由 Blueprint 替换列表里的旧对象 ——
 * 多一层间接,换不到任何好处。
 */
public final class ColumnDefinition {

    public enum Type {
        BIG_INCREMENTS, INCREMENTS, UUID, ULID, FOREIGN_ID,
        STRING, CHAR, TEXT, MEDIUM_TEXT, LONG_TEXT,
        TINY_INTEGER, SMALL_INTEGER, INTEGER, BIG_INTEGER,
        DECIMAL, FLOAT, DOUBLE, BOOLEAN,
        DATE, DATE_TIME, TIMESTAMP, TIME, YEAR,
        JSON, JSONB, ENUM, SET, BINARY,
        IP_ADDRESS, MAC_ADDRESS, GEOMETRY
    }

    final String name;
    final Type type;

    Integer length;
    Integer precision;
    Integer scale;
    List<String> allowed = List.of();      // ENUM / SET

    boolean nullable;
    boolean unsigned;
    boolean autoIncrement;
    boolean primary;
    boolean unique;
    boolean index;
    boolean useCurrent;
    boolean useCurrentOnUpdate;
    boolean change;
    boolean first;

    Object defaultValue;
    boolean hasDefault;
    String comment;
    String after;
    String collation;
    String storedAs;
    String virtualAs;

    /** constrained() 产生的外键 */
    ForeignKeyDefinition foreignKey;

    ColumnDefinition(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    // ================= 修饰符 =================

    public ColumnDefinition nullable()              { this.nullable = true; return this; }
    public ColumnDefinition nullable(boolean value) { this.nullable = value; return this; }
    public ColumnDefinition unsigned()              { this.unsigned = true; return this; }
    public ColumnDefinition autoIncrement()         { this.autoIncrement = true; return this; }
    public ColumnDefinition primary()               { this.primary = true; return this; }
    public ColumnDefinition unique()                { this.unique = true; return this; }
    public ColumnDefinition index()                 { this.index = true; return this; }
    public ColumnDefinition comment(String text)    { this.comment = text; return this; }
    public ColumnDefinition after(String column)    { this.after = column; return this; }
    public ColumnDefinition first()                 { this.first = true; return this; }
    public ColumnDefinition collation(String c)     { this.collation = c; return this; }
    public ColumnDefinition storedAs(String expr)   { this.storedAs = expr; return this; }
    public ColumnDefinition virtualAs(String expr)  { this.virtualAs = expr; return this; }

    /** 默认值。{@code default} 是 Java 关键字,故加下划线。 */
    public ColumnDefinition default_(Object value) {
        this.defaultValue = value;
        this.hasDefault = true;
        return this;
    }

    /** {@link #default_} 的别名,给不喜欢下划线的人 */
    public ColumnDefinition withDefault(Object value) {
        return default_(value);
    }

    public ColumnDefinition useCurrent() {
        this.useCurrent = true;
        return this;
    }

    public ColumnDefinition useCurrentOnUpdate() {
        this.useCurrentOnUpdate = true;
        return this;
    }

    /**
     * 标记为「修改已有列」。
     *
     * <p>SQLite 不支持 {@code ALTER COLUMN},语法生成器会在那里报错并
     * 告诉用户唯一可行的做法(建新表 + 复制 + 改名),而不是生成一条跑不通的 SQL。
     */
    public ColumnDefinition change() {
        this.change = true;
        return this;
    }

    // ================= 外键快捷方式 =================

    /**
     * 按约定推断被引用的表与列。
     *
     * <p>{@code t.foreignId("user_id").constrained()} → 引用 {@code users.id}。
     * 表名由列名去掉 {@code _id} 后复数化得到,与 Eloquent 的外键约定同源。
     */
    public ColumnDefinition constrained() {
        String base = name.endsWith("_id") ? name.substring(0, name.length() - 3) : name;
        return constrained(io.jaravel.support.Str.plural(base), "id");
    }

    public ColumnDefinition constrained(String table) {
        return constrained(table, "id");
    }

    public ColumnDefinition constrained(String table, String column) {
        this.foreignKey = new ForeignKeyDefinition(List.of(name), table, List.of(column));
        return this;
    }

    public ColumnDefinition cascadeOnDelete()  { return onDelete("cascade"); }
    public ColumnDefinition nullOnDelete()     { return onDelete("set null"); }
    public ColumnDefinition restrictOnDelete() { return onDelete("restrict"); }
    public ColumnDefinition cascadeOnUpdate()  { return onUpdate("cascade"); }

    public ColumnDefinition onDelete(String action) {
        requireConstrained("onDelete");
        foreignKey.onDelete = action;
        return this;
    }

    public ColumnDefinition onUpdate(String action) {
        requireConstrained("onUpdate");
        foreignKey.onUpdate = action;
        return this;
    }

    private void requireConstrained(String method) {
        if (foreignKey != null) return;
        throw new IllegalStateException(
                method + "() 需要先调用 constrained() 建立外键。"
              + "\n  写法:t.foreignId(\"" + name + "\").constrained().cascadeOnDelete()"
              + "\n  只写 " + method + "() 的话没有外键可以附加动作。");
    }

    // ================= 访问器(供语法生成器读) =================

    public String name()            { return name; }
    public Type type()              { return type; }
    public Integer length()         { return length; }
    public Integer precision()      { return precision; }
    public Integer scale()          { return scale; }
    public List<String> allowed()   { return allowed; }
    public boolean isNullable()     { return nullable; }
    public boolean isUnsigned()     { return unsigned; }
    public boolean isAutoIncrement() { return autoIncrement; }
    public boolean isPrimary()      { return primary; }
    public boolean isUnique()       { return unique; }
    public boolean hasIndex()       { return index; }
    public boolean isChange()       { return change; }
    public boolean hasDefault()     { return hasDefault; }
    public Object defaultValue()    { return defaultValue; }
    public boolean usesCurrent()    { return useCurrent; }
    public boolean usesCurrentOnUpdate() { return useCurrentOnUpdate; }
    public String comment()         { return comment; }
    public String after()           { return after; }
    public boolean isFirst()        { return first; }
    public String collationName()   { return collation; }
    public String storedAsExpr()    { return storedAs; }
    public String virtualAsExpr()   { return virtualAs; }
    public ForeignKeyDefinition foreignKeyDefinition() { return foreignKey; }

    @Override public String toString() {
        return name + " " + type + (nullable ? " null" : " not null");
    }
}
