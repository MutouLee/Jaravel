package io.jaravel.migration.schema;

import java.util.ArrayList;
import java.util.List;

import io.jaravel.migration.schema.ColumnDefinition.Type;

/**
 * 一张表的结构声明。
 *
 * <pre>{@code
 * Schema.create("posts", t -> {
 *     t.id();
 *     t.foreignId("user_id").constrained().cascadeOnDelete();
 *     t.string("title", 200);
 *     t.timestamps();
 * });
 * }</pre>
 *
 * <p><b>关于方法名的下划线</b>:{@code boolean} / {@code float} / {@code enum} /
 * {@code default} 都是 Java 关键字,不能当方法名。统一加尾部下划线,
 * 同时给每个都配一个无下划线别名({@code bool} / {@code floating} /
 * {@code enumeration} / {@code withDefault})—— 让两种口味的人都不用查文档。
 */
public final class Blueprint {

    private final String table;
    private final List<ColumnDefinition> columns = new ArrayList<>();
    private final List<IndexDefinition> indexes = new ArrayList<>();
    private final List<ForeignKeyDefinition> foreignKeys = new ArrayList<>();
    private final List<Command> commands = new ArrayList<>();

    private String tableComment;
    private String engine;
    private String charset;
    private String collation;
    private boolean temporary;

    public Blueprint(String table) {
        this.table = table;
    }

    /** DROP / RENAME 之类不产生列的操作 */
    public record Command(String kind, List<String> args) {}

    public record IndexDefinition(String kind, List<String> columns, String name,
                                  String algorithm) {}

    // ================= 主键 =================

    /** {@code bigIncrements("id")} —— 最常见的一行,所以有专名 */
    public ColumnDefinition id() {
        return id("id");
    }

    public ColumnDefinition id(String name) {
        return add(new ColumnDefinition(name, Type.BIG_INCREMENTS))
                .autoIncrement().unsigned().primary();
    }

    public ColumnDefinition increments(String name) {
        return add(new ColumnDefinition(name, Type.INCREMENTS))
                .autoIncrement().unsigned().primary();
    }

    public ColumnDefinition bigIncrements(String name) {
        return id(name);
    }

    public ColumnDefinition uuid()              { return uuid("uuid"); }
    public ColumnDefinition uuid(String name)   { return add(new ColumnDefinition(name, Type.UUID)); }
    public ColumnDefinition ulid()              { return ulid("ulid"); }
    public ColumnDefinition ulid(String name)   { return add(new ColumnDefinition(name, Type.ULID)); }

    /** 无符号大整数,专用于外键 */
    public ColumnDefinition foreignId(String name) {
        return add(new ColumnDefinition(name, Type.FOREIGN_ID)).unsigned();
    }

    /** {@code foreignIdFor(User.class)} → {@code user_id} */
    public ColumnDefinition foreignIdFor(Class<?> modelType) {
        String column = io.jaravel.support.Str.snake(modelType.getSimpleName()) + "_id";
        return foreignId(column).constrained(
                io.jaravel.support.Str.plural(io.jaravel.support.Str.snake(modelType.getSimpleName())));
    }

    // ================= 字符串 =================

    public ColumnDefinition string(String name) {
        return string(name, 255);
    }

    public ColumnDefinition string(String name, int length) {
        ColumnDefinition c = add(new ColumnDefinition(name, Type.STRING));
        c.length = length;
        return c;
    }

    public ColumnDefinition char_(String name, int length) {
        ColumnDefinition c = add(new ColumnDefinition(name, Type.CHAR));
        c.length = length;
        return c;
    }

    public ColumnDefinition character(String name, int length) { return char_(name, length); }

    public ColumnDefinition text(String name)        { return add(new ColumnDefinition(name, Type.TEXT)); }
    public ColumnDefinition mediumText(String name)  { return add(new ColumnDefinition(name, Type.MEDIUM_TEXT)); }
    public ColumnDefinition longText(String name)    { return add(new ColumnDefinition(name, Type.LONG_TEXT)); }

    /** 用户表的 remember_token,固定 100 字符 —— 与 Laravel 一致 */
    public ColumnDefinition rememberToken() {
        return string("remember_token", 100).nullable();
    }

    // ================= 数值 =================

    public ColumnDefinition tinyInteger(String name)  { return add(new ColumnDefinition(name, Type.TINY_INTEGER)); }
    public ColumnDefinition smallInteger(String name) { return add(new ColumnDefinition(name, Type.SMALL_INTEGER)); }
    public ColumnDefinition integer(String name)      { return add(new ColumnDefinition(name, Type.INTEGER)); }
    public ColumnDefinition bigInteger(String name)   { return add(new ColumnDefinition(name, Type.BIG_INTEGER)); }

    public ColumnDefinition decimal(String name, int precision, int scale) {
        ColumnDefinition c = add(new ColumnDefinition(name, Type.DECIMAL));
        c.precision = precision;
        c.scale = scale;
        return c;
    }

    /** 金额专用:{@code decimal(name, 10, 2)} 的别名,表明意图 */
    public ColumnDefinition money(String name) {
        return decimal(name, 19, 4);
    }

    public ColumnDefinition float_(String name, int precision, int scale) {
        ColumnDefinition c = add(new ColumnDefinition(name, Type.FLOAT));
        c.precision = precision;
        c.scale = scale;
        return c;
    }

    public ColumnDefinition floating(String name, int precision, int scale) {
        return float_(name, precision, scale);
    }

    public ColumnDefinition double_(String name) { return add(new ColumnDefinition(name, Type.DOUBLE)); }
    public ColumnDefinition doublePrecision(String name) { return double_(name); }

    public ColumnDefinition boolean_(String name) { return add(new ColumnDefinition(name, Type.BOOLEAN)); }
    public ColumnDefinition bool(String name)     { return boolean_(name); }

    // ================= 时间 =================

    public ColumnDefinition date(String name)      { return add(new ColumnDefinition(name, Type.DATE)); }
    public ColumnDefinition time(String name)      { return add(new ColumnDefinition(name, Type.TIME)); }
    public ColumnDefinition year(String name)      { return add(new ColumnDefinition(name, Type.YEAR)); }

    public ColumnDefinition dateTime(String name) { return dateTime(name, 0); }

    public ColumnDefinition dateTime(String name, int precision) {
        ColumnDefinition c = add(new ColumnDefinition(name, Type.DATE_TIME));
        c.precision = precision;
        return c;
    }

    public ColumnDefinition timestamp(String name) { return timestamp(name, 0); }

    public ColumnDefinition timestamp(String name, int precision) {
        ColumnDefinition c = add(new ColumnDefinition(name, Type.TIMESTAMP));
        c.precision = precision;
        return c;
    }

    /** {@code created_at} + {@code updated_at},都可空 —— 与 Eloquent 的 timestamps 对应 */
    public void timestamps() {
        timestamp("created_at").nullable();
        timestamp("updated_at").nullable();
    }

    public void timestampsTz() {
        timestamps();
    }

    /** {@code deleted_at} —— 与 {@code @SoftDeletes} 对应 */
    public ColumnDefinition softDeletes() {
        return softDeletes("deleted_at");
    }

    public ColumnDefinition softDeletes(String name) {
        return timestamp(name).nullable();
    }

    // ================= 结构化 =================

    public ColumnDefinition json(String name)  { return add(new ColumnDefinition(name, Type.JSON)); }
    public ColumnDefinition jsonb(String name) { return add(new ColumnDefinition(name, Type.JSONB)); }

    public ColumnDefinition enum_(String name, List<String> allowed) {
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException(
                    "enum_(\"" + name + "\", ...) 的候选值不能为空 —— "
                  + "空枚举列无法插入任何值。");
        }
        ColumnDefinition c = add(new ColumnDefinition(name, Type.ENUM));
        c.allowed = List.copyOf(allowed);
        return c;
    }

    public ColumnDefinition enumeration(String name, List<String> allowed) {
        return enum_(name, allowed);
    }

    /** Java 枚举直接转:候选值取 {@code name()} */
    public <E extends Enum<E>> ColumnDefinition enum_(String name, Class<E> enumType) {
        return enum_(name, java.util.Arrays.stream(enumType.getEnumConstants())
                .map(Enum::name).toList());
    }

    public ColumnDefinition set(String name, List<String> allowed) {
        ColumnDefinition c = add(new ColumnDefinition(name, Type.SET));
        c.allowed = List.copyOf(allowed);
        return c;
    }

    public ColumnDefinition binary(String name)     { return add(new ColumnDefinition(name, Type.BINARY)); }
    public ColumnDefinition ipAddress(String name)  { return add(new ColumnDefinition(name, Type.IP_ADDRESS)); }
    public ColumnDefinition macAddress(String name) { return add(new ColumnDefinition(name, Type.MAC_ADDRESS)); }
    public ColumnDefinition geometry(String name)   { return add(new ColumnDefinition(name, Type.GEOMETRY)); }

    // ================= 多态 =================

    /**
     * {@code <name>_id} + {@code <name>_type},并建联合索引。
     *
     * <p>索引不是可选项:多态查询一定带 {@code where x_type = ? and x_id = ?},
     * 少了它就是全表扫。Laravel 同样默认建。
     */
    public void morphs(String name) {
        foreignId(name + "_id");
        string(name + "_type");
        index(List.of(name + "_type", name + "_id"), name + "_index");
    }

    public void nullableMorphs(String name) {
        foreignId(name + "_id").nullable();
        string(name + "_type").nullable();
        index(List.of(name + "_type", name + "_id"), name + "_index");
    }

    public void uuidMorphs(String name) {
        uuid(name + "_id");
        string(name + "_type");
        index(List.of(name + "_type", name + "_id"), name + "_index");
    }

    // ================= 索引 =================

    public void primary(List<String> columns) {
        indexes.add(new IndexDefinition("primary", List.copyOf(columns), null, null));
    }

    public void unique(String column) {
        unique(List.of(column), null);
    }

    public void unique(List<String> columns) {
        unique(columns, null);
    }

    public void unique(List<String> columns, String name) {
        indexes.add(new IndexDefinition("unique", List.copyOf(columns), name, null));
    }

    public void index(String column) {
        index(List.of(column), null);
    }

    public void index(List<String> columns) {
        index(columns, null);
    }

    public void index(List<String> columns, String name) {
        indexes.add(new IndexDefinition("index", List.copyOf(columns), name, null));
    }

    public void fullText(List<String> columns) {
        indexes.add(new IndexDefinition("fulltext", List.copyOf(columns), null, null));
    }

    public void spatialIndex(String column) {
        indexes.add(new IndexDefinition("spatial", List.of(column), null, null));
    }

    public ForeignKeyDefinition foreign(String column) {
        return foreign(List.of(column));
    }

    public ForeignKeyDefinition foreign(List<String> columns) {
        var fk = new ForeignKeyDefinition(List.copyOf(columns), null, List.of());
        foreignKeys.add(fk);
        return fk;
    }

    public ForeignKeyDefinition foreign(String column, String table, String referenced) {
        var fk = new ForeignKeyDefinition(List.of(column), table, List.of(referenced));
        foreignKeys.add(fk);
        return fk;
    }

    // ================= 修改类操作 =================

    public void renameColumn(String from, String to) {
        commands.add(new Command("renameColumn", List.of(from, to)));
    }

    public void dropColumn(String... names) {
        commands.add(new Command("dropColumn", List.of(names)));
    }

    public void dropIndex(String name) {
        commands.add(new Command("dropIndex", List.of(name)));
    }

    public void dropUnique(String name) {
        commands.add(new Command("dropUnique", List.of(name)));
    }

    public void dropForeign(String name) {
        commands.add(new Command("dropForeign", List.of(name)));
    }

    public void dropPrimary() {
        commands.add(new Command("dropPrimary", List.of()));
    }

    public void dropTimestamps() {
        dropColumn("created_at", "updated_at");
    }

    public void dropSoftDeletes() {
        dropColumn("deleted_at");
    }

    // ================= 表级选项 =================

    public Blueprint comment(String text)   { this.tableComment = text; return this; }
    public Blueprint engine(String name)    { this.engine = name; return this; }
    public Blueprint charset(String name)   { this.charset = name; return this; }
    public Blueprint collation(String name) { this.collation = name; return this; }
    public Blueprint temporary()            { this.temporary = true; return this; }

    // ================= 访问器 =================

    public String table()                        { return table; }
    public List<ColumnDefinition> columns()      { return List.copyOf(columns); }
    public List<IndexDefinition> indexes()       { return List.copyOf(indexes); }
    public List<Command> commands()              { return List.copyOf(commands); }
    public String tableComment()                 { return tableComment; }
    public String engineName()                   { return engine; }
    public String charsetName()                  { return charset; }
    public String collationName()                { return collation; }
    public boolean isTemporary()                 { return temporary; }

    /** 显式 foreign() 声明的 + 各列 constrained() 产生的,合在一起 */
    public List<ForeignKeyDefinition> foreignKeys() {
        var all = new ArrayList<>(foreignKeys);
        for (ColumnDefinition c : columns) {
            if (c.foreignKey != null) all.add(c.foreignKey);
        }
        return all;
    }

    private ColumnDefinition add(ColumnDefinition column) {
        for (ColumnDefinition existing : columns) {
            if (existing.name.equals(column.name)) {
                throw new IllegalArgumentException(
                        "表 " + table + " 上重复声明了列 `" + column.name + "`。"
                      + "\n  若想修改已有列,用 Schema.table(...) 配 .change();"
                      + "\n  若是 timestamps()/softDeletes() 与手写列撞了,去掉其中一个。");
            }
        }
        columns.add(column);
        return column;
    }
}
