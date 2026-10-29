package io.jaravel.eloquent.meta;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 模型的静态元信息。由 APT 生成到 {@code <Name>_} 里,运行时只读。
 *
 * <p>这里是「零运行时反射」的落点:表名、列、主键、关联定义全部在编译期固化,
 * 启动时不需要扫描,运行时不需要反射。
 */
public final class ModelMeta<M> {

    private final Class<M> type;
    private final String table;
    private final String connection;
    private final Col<M, ?> key;
    private final boolean incrementing;
    private final List<Col<M, ?>> columns;
    private final boolean timestamps;
    private final String softDeleteColumn;     // null = 不启用软删除
    private final List<String> guarded;
    private final List<String> fillable;        // 非空则为白名单模式
    private final Supplier<M> factory;
    private final List<RelationDef> relations;
    private final List<String> globalScopes;
    private final boolean identityMap;

    private ModelMeta(Builder<M> b) {
        this.type = b.type;
        this.table = b.table;
        this.connection = b.connection;
        this.key = b.key;
        this.incrementing = b.incrementing;
        this.columns = List.copyOf(b.columns);
        this.timestamps = b.timestamps;
        this.softDeleteColumn = b.softDeleteColumn;
        this.guarded = List.copyOf(b.guarded);
        this.fillable = List.copyOf(b.fillable);
        this.factory = b.factory;
        this.relations = List.copyOf(b.relations);
        this.globalScopes = List.copyOf(b.globalScopes);
        this.identityMap = b.identityMap;
    }

    public static <M> Builder<M> builder(Class<M> type, String table) {
        return new Builder<>(type, table);
    }

    // ---------- 读取 ----------

    public Class<M> type()                  { return type; }
    public String table()                   { return table; }
    public Optional<String> connection()    { return Optional.ofNullable(connection); }
    public Col<M, ?> key()                  { return key; }
    public String keyName()                 { return key.name(); }
    public boolean incrementing()           { return incrementing; }
    public List<Col<M, ?>> columns()        { return columns; }
    public boolean timestamps()             { return timestamps; }
    public boolean softDeletes()            { return softDeleteColumn != null; }
    public String softDeleteColumn()        { return softDeleteColumn; }
    public List<String> guarded()           { return guarded; }
    public List<String> fillable()          { return fillable; }
    public List<RelationDef> relations()    { return relations; }
    public List<String> globalScopes()      { return globalScopes; }
    public boolean identityMap()            { return identityMap; }

    public M instantiate()                  { return factory.get(); }

    public Optional<Col<M, ?>> column(String name) {
        for (Col<M, ?> c : columns) {
            if (c.name().equals(name)) return Optional.of(c);
        }
        return Optional.empty();
    }

    public List<String> columnNames() {
        return columns.stream().map(Col::name).toList();
    }

    public Optional<RelationDef> relation(String name) {
        for (RelationDef r : relations) {
            if (r.name().equals(name)) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** 批量赋值是否允许写入该列 */
    public boolean isFillable(String column) {
        if (!fillable.isEmpty()) return fillable.contains(column);
        if (column.equals(key.name())) return false;     // 主键永不可批量赋值
        return !guarded.contains(column);
    }

    @Override public String toString() {
        return "ModelMeta(" + type.getSimpleName() + " → " + table + ")";
    }

    // ---------- 构造 ----------

    public static final class Builder<M> {
        private final Class<M> type;
        private final String table;
        private String connection;
        private Col<M, ?> key;
        private boolean incrementing = true;
        private final List<Col<M, ?>> columns = new java.util.ArrayList<>();
        private boolean timestamps = true;
        private String softDeleteColumn;
        private final List<String> guarded = new java.util.ArrayList<>();
        private final List<String> fillable = new java.util.ArrayList<>();
        private Supplier<M> factory;
        private final List<RelationDef> relations = new java.util.ArrayList<>();
        private final List<String> globalScopes = new java.util.ArrayList<>();
        private boolean identityMap = true;

        private Builder(Class<M> type, String table) {
            this.type = type;
            this.table = table;
        }

        public Builder<M> connection(String c)          { this.connection = c; return this; }
        public Builder<M> key(Col<M, ?> k, boolean inc) { this.key = k; this.incrementing = inc; return this; }
        @SafeVarargs
        public final Builder<M> columns(Col<M, ?>... c) { this.columns.addAll(List.of(c)); return this; }
        public Builder<M> timestamps(boolean t)         { this.timestamps = t; return this; }
        public Builder<M> softDeletes(String col)       { this.softDeleteColumn = col; return this; }
        public Builder<M> guarded(String... g)          { this.guarded.addAll(List.of(g)); return this; }
        public Builder<M> fillable(String... f)         { this.fillable.addAll(List.of(f)); return this; }
        public Builder<M> factory(Supplier<M> f)        { this.factory = f; return this; }
        public Builder<M> relations(RelationDef... r)   { this.relations.addAll(List.of(r)); return this; }
        public Builder<M> globalScopes(String... s)     { this.globalScopes.addAll(List.of(s)); return this; }
        public Builder<M> identityMap(boolean i)        { this.identityMap = i; return this; }

        public ModelMeta<M> build() {
            if (key == null) {
                throw new IllegalStateException(
                        "模型 " + type.getName() + " 没有主键。"
                      + "这是 APT 生成代码的 bug —— 正常情况下缺主键会在编译期报错。");
            }
            if (factory == null) {
                throw new IllegalStateException(
                        "模型 " + type.getName() + " 缺少无参构造工厂(APT 生成代码的 bug)");
            }
            return new ModelMeta<>(this);
        }
    }
}
