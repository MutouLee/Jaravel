package io.jaravel.eloquent.meta;

import java.util.List;

/**
 * 关联的运行时定义。由 APT 从类级注解生成。
 *
 * <p>一个 record 覆盖全部关联类型,不同类型用到的字段不同 ——
 * 比每种关联一个类更简单,且预加载逻辑可以统一按 {@link Kind} 分派。
 */
public record RelationDef(
        Kind kind,
        String name,
        Class<?> target,
        /** 中间模型({@code HasManyThrough}) */
        Class<?> through,
        /** 多态关联的候选类型({@code MorphTo}) */
        List<Class<?>> morphTypes,

        /** 外键所在列。语义随 kind 变化,见各 Kind 的注释。 */
        String foreignKey,
        /** 被引用的列 */
        String localKey,
        /** HasManyThrough 的第二段外键 */
        String secondKey,

        /** 中间表名({@code BelongsToMany}) */
        String pivotTable,
        String foreignPivotKey,
        String relatedPivotKey,
        List<String> pivotColumns,
        boolean pivotTimestamps,

        /** 多态列前缀:{@code <prefix>_id} / {@code <prefix>_type} */
        String morphPrefix
) {

    public enum Kind {
        /** 外键在**目标**表上,一对一 */
        HAS_ONE(false),
        /** 外键在**目标**表上,一对多 */
        HAS_MANY(true),
        /** 外键在**本**模型上 */
        BELONGS_TO(false),
        /** 经中间表,多对多 */
        BELONGS_TO_MANY(true),
        /** 经中间模型 */
        HAS_MANY_THROUGH(true),
        /** 多态一对多 */
        MORPH_MANY(true),
        /** 多态反向 */
        MORPH_TO(false);

        private final boolean many;

        Kind(boolean many) { this.many = many; }

        /** 返回集合还是单个 */
        public boolean isMany() { return many; }
    }

    public boolean isMany() { return kind.isMany(); }

    // ---------- 便捷构造 ----------

    public static RelationDef hasMany(String name, Class<?> target, String foreignKey, String localKey) {
        return new RelationDef(Kind.HAS_MANY, name, target, null, List.of(),
                foreignKey, localKey, null, null, null, null, List.of(), false, null);
    }

    public static RelationDef hasOne(String name, Class<?> target, String foreignKey, String localKey) {
        return new RelationDef(Kind.HAS_ONE, name, target, null, List.of(),
                foreignKey, localKey, null, null, null, null, List.of(), false, null);
    }

    public static RelationDef belongsTo(String name, Class<?> target, String foreignKey, String ownerKey) {
        return new RelationDef(Kind.BELONGS_TO, name, target, null, List.of(),
                foreignKey, ownerKey, null, null, null, null, List.of(), false, null);
    }

    public static RelationDef belongsToMany(String name, Class<?> target, String pivot,
                                            String foreignPivotKey, String relatedPivotKey,
                                            List<String> pivotColumns, boolean pivotTimestamps,
                                            String localKey) {
        return new RelationDef(Kind.BELONGS_TO_MANY, name, target, null, List.of(),
                null, localKey, null, pivot, foreignPivotKey, relatedPivotKey,
                pivotColumns, pivotTimestamps, null);
    }

    public static RelationDef hasManyThrough(String name, Class<?> target, Class<?> through,
                                             String firstKey, String secondKey, String localKey) {
        return new RelationDef(Kind.HAS_MANY_THROUGH, name, target, through, List.of(),
                firstKey, localKey, secondKey, null, null, null, List.of(), false, null);
    }

    public static RelationDef morphMany(String name, Class<?> target, String morphPrefix, String localKey) {
        return new RelationDef(Kind.MORPH_MANY, name, target, null, List.of(),
                null, localKey, null, null, null, null, List.of(), false, morphPrefix);
    }

    public static RelationDef morphTo(String name, List<Class<?>> types, String morphPrefix) {
        return new RelationDef(Kind.MORPH_TO, name, null, null, types,
                null, null, null, null, null, null, List.of(), false, morphPrefix);
    }

    // ---------- 多态列名 ----------

    public String morphIdColumn()   { return morphPrefix + "_id"; }
    public String morphTypeColumn() { return morphPrefix + "_type"; }

    @Override public String toString() {
        return kind + "(" + name + " → "
             + (target != null ? target.getSimpleName() : morphTypes) + ")";
    }
}
