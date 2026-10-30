package io.jaravel.eloquent.relations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jaravel.eloquent.Eloquent;
import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.Col;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.ModelRegistry;
import io.jaravel.eloquent.meta.RelationDef;
import io.jaravel.eloquent.query.Query;

/**
 * 各类关联的 SQL 逻辑。按 {@link RelationDef.Kind} 分派。
 *
 * <p>集中在一处而不是每种关联一个类:关联的差异只在「用哪些列连接」,
 * 分散成 7 个类反而让「加一种关联要改哪些地方」变得不明显。
 */
public final class RelationSupport {

    private RelationSupport() {}

    /**
     * 关联目标的元信息。
     *
     * <p>必须经原始类型转换:注解里的 {@code Class<?> target()} 丢失了
     * 「它是个 Model」这个信息,Java 无法在此处恢复。这是注解的固有限制,
     * APT 已在**编译期**校验过 target 确实是 {@code @Model} 标注的类型,
     * 因此这里的转换是安全的。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T extends Model<T>> ModelMeta<T> metaOf(Class<?> modelType) {
        return (ModelMeta<T>) ModelRegistry.of((Class) modelType);
    }

    // ================= 单个 owner 的约束 =================

    /** 给目标查询加上「属于这个 owner」的条件 */
    @SuppressWarnings("unchecked")
    public static <T extends Model<T>> void constrainForOwner(
            Query<T> query, RelationDef def, Model<?> owner) {

        Object ownerKey = localKeyValue(def, owner);

        switch (def.kind()) {
            case HAS_ONE, HAS_MANY -> {
                Col<T, Object> fk = (Col<T, Object>) columnOf(query.meta(), def.foreignKey());
                query.where(fk, ownerKey);
            }
            case BELONGS_TO -> {
                Object fkValue = columnValue(owner, def.foreignKey());
                Col<T, Object> owner2 = (Col<T, Object>) columnOf(
                        query.meta(), ownerKeyName(def, query.meta()));
                query.where(owner2, fkValue);
            }
            case BELONGS_TO_MANY -> constrainBelongsToMany(query, def, ownerKey);
            case MORPH_MANY, MORPH_ONE -> {
                Col<T, Object> idCol = (Col<T, Object>) columnOf(query.meta(), def.morphIdColumn());
                Col<T, Object> typeCol = (Col<T, Object>) columnOf(query.meta(), def.morphTypeColumn());
                query.where(idCol, ownerKey).where(typeCol, morphAlias(owner.meta().type()));
            }
            case HAS_MANY_THROUGH, HAS_ONE_THROUGH -> constrainThrough(query, def, ownerKey);
            case MORPH_TO -> throw new UnsupportedOperationException(
                    "MORPH_TO 的目标类型在运行时才确定,不能用统一的 Query 约束;"
                  + "应走 MorphTo 的专用解析路径");
        }
    }

    private static <T extends Model<T>> void constrainBelongsToMany(
            Query<T> query, RelationDef def, Object ownerKey) {

        ModelMeta<T> target = query.meta();
        // where target.key in (select related_key from pivot where foreign_key = ?)
        String sub = "select " + quote(def.relatedPivotKey())
                   + " from " + quote(def.pivotTable())
                   + " where " + quote(def.foreignPivotKey()) + " = ?";
        query.whereRaw(quote(target.keyName()) + " in (" + sub + ")", ownerKey);
    }

    private static <T extends Model<T>> void constrainThrough(
            Query<T> query, RelationDef def, Object ownerKey) {

        ModelMeta<?> through = metaOf(def.through());
        // where target.second_key in (select through.key from through where through.first_key = ?)
        String sub = "select " + quote(through.keyName())
                   + " from " + quote(through.table())
                   + " where " + quote(def.foreignKey()) + " = ?";
        query.whereRaw(quote(def.secondKey()) + " in (" + sub + ")", ownerKey);
    }

    // ================= 批量约束(预加载用) =================

    /** 给目标查询加上「属于这批 owner 之一」的条件 */
    @SuppressWarnings("unchecked")
    public static <T extends Model<T>> void constrainForOwners(
            Query<T> query, RelationDef def, Collection<Object> ownerKeys, Class<?> ownerType) {

        switch (def.kind()) {
            case HAS_ONE, HAS_MANY -> {
                Col<T, Object> fk = (Col<T, Object>) columnOf(query.meta(), def.foreignKey());
                query.whereIn(fk, ownerKeys);
            }
            case BELONGS_TO -> {
                Col<T, Object> ownerCol = (Col<T, Object>) columnOf(
                        query.meta(), ownerKeyName(def, query.meta()));
                query.whereIn(ownerCol, ownerKeys);
            }
            case MORPH_MANY, MORPH_ONE -> {
                Col<T, Object> idCol = (Col<T, Object>) columnOf(query.meta(), def.morphIdColumn());
                Col<T, Object> typeCol = (Col<T, Object>) columnOf(query.meta(), def.morphTypeColumn());
                query.whereIn(idCol, ownerKeys).where(typeCol, morphAlias(ownerType));
            }
            case BELONGS_TO_MANY, HAS_MANY_THROUGH, HAS_ONE_THROUGH ->
                // 这两种需要把连接键一起选出来才能分组回填,走专用路径
                throw new IllegalStateException(
                        def.kind() + " 的预加载走 RelationLoader 的专用分支");
            case MORPH_TO -> throw new IllegalStateException("MORPH_TO 的预加载走专用分支");
        }
    }

    // ================= 写入 =================

    /** 把目标模型的外键指向 owner */
    @SuppressWarnings("unchecked")
    public static <T extends Model<T>> void linkToOwner(T model, RelationDef def, Model<?> owner) {
        ModelMeta<T> meta = model.meta();
        Object ownerKey = localKeyValue(def, owner);

        switch (def.kind()) {
            case HAS_ONE, HAS_MANY -> {
                Col<T, Object> fk = (Col<T, Object>) columnOf(meta, def.foreignKey());
                fk.fromDatabase(model, ownerKey);
            }
            case MORPH_MANY, MORPH_ONE -> {
                ((Col<T, Object>) columnOf(meta, def.morphIdColumn())).fromDatabase(model, ownerKey);
                ((Col<T, Object>) columnOf(meta, def.morphTypeColumn()))
                        .fromDatabase(model, morphAlias(owner.meta().type()));
            }
            default -> throw new UnsupportedOperationException(
                    def.kind() + " 关联不能通过设置目标外键来建立;"
                  + (def.kind() == RelationDef.Kind.BELONGS_TO_MANY
                        ? "多对多请用 attach()" : "请改设本模型上的外键"));
        }
    }

    public static void attach(RelationDef def, Model<?> owner,
                              Collection<?> targetKeys, Map<String, ?> pivotValues) {
        if (targetKeys.isEmpty()) return;
        Object ownerKey = localKeyValue(def, owner);

        List<Map<String, Object>> rows = new ArrayList<>(targetKeys.size());
        for (Object key : targetKeys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(def.foreignPivotKey(), ownerKey);
            row.put(def.relatedPivotKey(), key);
            pivotValues.forEach(row::put);
            if (def.pivotTimestamps()) {
                Instant now = Instant.now();
                row.put("created_at", now);
                row.put("updated_at", now);
            }
            rows.add(row);
        }
        Eloquent.connection().table(def.pivotTable()).insert(rows);
    }

    public static void detach(RelationDef def, Model<?> owner, Collection<?> targetKeys) {
        Object ownerKey = localKeyValue(def, owner);
        var q = Eloquent.connection().table(def.pivotTable())
                .where(def.foreignPivotKey(), ownerKey);
        if (targetKeys != null && !targetKeys.isEmpty()) {
            q.whereIn(def.relatedPivotKey(), targetKeys);
        }
        q.delete();
    }

    public static void sync(RelationDef def, Model<?> owner, Collection<?> targetKeys) {
        Object ownerKey = localKeyValue(def, owner);

        List<Object> existing = Eloquent.connection().table(def.pivotTable())
                .where(def.foreignPivotKey(), ownerKey)
                .get()
                .map(r -> r.get(def.relatedPivotKey()))
                .toList();

        // 用字符串比较,避免 Integer/Long 混用导致的误判
        var wanted = targetKeys.stream().map(String::valueOf).toList();
        var have = existing.stream().map(String::valueOf).toList();

        var toDetach = existing.stream().filter(k -> !wanted.contains(String.valueOf(k))).toList();
        var toAttach = targetKeys.stream().filter(k -> !have.contains(String.valueOf(k))).toList();

        if (!toDetach.isEmpty()) detach(def, owner, toDetach);
        if (!toAttach.isEmpty()) attach(def, owner, toAttach, Map.of());
    }

    // ================= 工具 =================

    /** owner 上被引用的列的值(默认主键) */
    public static Object localKeyValue(RelationDef def, Model<?> owner) {
        if (def.localKey() == null || def.localKey().isBlank()) return owner.keyValue();
        return columnValue(owner, def.localKey());
    }

    @SuppressWarnings("unchecked")
    public static Object columnValue(Model<?> model, String columnName) {
        ModelMeta<Object> meta = (ModelMeta<Object>) model.meta();
        return meta.column(columnName)
                .orElseThrow(() -> missingColumn(meta, columnName))
                .get(model);
    }

    @SuppressWarnings("unchecked")
    public static <T> Col<T, ?> columnOf(ModelMeta<T> meta, String columnName) {
        return meta.column(columnName).orElseThrow(() -> missingColumn(meta, columnName));
    }

    private static IllegalStateException missingColumn(ModelMeta<?> meta, String columnName) {
        String hint = io.jaravel.support.Arr.closest(columnName, meta.columnNames())
                .map(c -> " 是否想用 " + c + " ?").orElse("");
        return new IllegalStateException(
                "关联需要列 " + meta.type().getSimpleName() + "." + columnName
              + ",但该模型没有这一列。" + hint
              + "\n  已声明的列:" + meta.columnNames()
              + "\n  修复:补上该字段,或在关联注解里显式指定正确的键名。");
    }

    private static <T> String ownerKeyName(RelationDef def, ModelMeta<T> targetMeta) {
        return (def.localKey() == null || def.localKey().isBlank())
                ? targetMeta.keyName() : def.localKey();
    }

    /**
     * 多态类型列里存什么。
     *
     * <p>存**全限定类名**:简单名会在不同包有同名类时冲突,而重命名包的代价
     * 由用户在迁移里处理(Laravel 的 morph map 同理)。
     */
    public static String morphAlias(Class<?> type) {
        return type.getName();
    }

    private static String quote(String identifier) {
        return Eloquent.connection().grammar().wrap(identifier);
    }
}
