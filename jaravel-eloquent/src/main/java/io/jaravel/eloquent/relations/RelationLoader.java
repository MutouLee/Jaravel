package io.jaravel.eloquent.relations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.jaravel.eloquent.Eloquent;
import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.Col;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.RelPath;
import io.jaravel.eloquent.meta.RelationDef;
import io.jaravel.eloquent.query.Query;

/**
 * 预加载器 —— **N+1 的第一道防线**。
 *
 * <p>核心是「一层一条查询」:
 * <pre>
 * select * from posts where ...                        1 条
 * select * from comments where post_id in (?, ?, ?)    1 条
 * select * from users where id in (?, ?)               1 条(comments.author)
 * </pre>
 * 而不是每个 post 发一条、每个 comment 再发一条。
 *
 * <p>嵌套路径({@code comments.author})按层递归:先加载 comments,
 * 再把所有 comments 当作下一层的 owner 加载 author。
 */
@SuppressWarnings("rawtypes")   // 递归跨层时 owner 类型无法在编译期表达,见 RelPath#tail
public final class RelationLoader {

    private RelationLoader() {}

    /** {@code in (?, ...)} 的参数数量上限。Oracle 1000、SQL Server 2100,取保守值。 */
    private static final int MAX_IN_PARAMS = 900;

    public static <M extends Model<M>> void load(
            ModelMeta<M> meta, List<M> models, List<RelPath<M, ?>> paths) {

        if (models.isEmpty() || paths.isEmpty()) return;

        // 按第一段分组:with(a.b) 与 with(a.c) 共享 a 这一层的查询
        Map<String, List<RelPath>> byHead = new LinkedHashMap<>();
        for (RelPath<M, ?> path : paths) {
            byHead.computeIfAbsent(path.head(), h -> new ArrayList<>()).add(path);
        }

        byHead.forEach((relationName, group) -> {
            RelationDef def = meta.relation(relationName).orElseThrow(() ->
                    unknownRelation(meta, relationName));

            List<? extends Model<?>> related = loadOneLevel(meta, models, def, group);

            // 递归加载更深的层级
            List<RelPath> deeper = new ArrayList<>();
            for (RelPath path : group) {
                RelPath tail = path.tail();
                if (tail != null) deeper.add(tail);
            }

            if (!deeper.isEmpty() && !related.isEmpty()) {
                loadDeeper(related, deeper);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void loadDeeper(List<? extends Model<?>> models, List<RelPath> paths) {
        ModelMeta nextMeta = models.get(0).meta();
        load(nextMeta, (List) models, (List) paths);
    }

    // ================= 单层加载 =================

    @SuppressWarnings("unchecked")
    private static <M extends Model<M>, T extends Model<T>> List<T> loadOneLevel(
            ModelMeta<M> ownerMeta, List<M> owners, RelationDef def,
            List<RelPath> group) {

        return switch (def.kind()) {
            case HAS_ONE, HAS_MANY, MORPH_MANY ->
                    loadByForeignKey(ownerMeta, owners, def, group);
            case BELONGS_TO ->
                    loadBelongsTo(ownerMeta, owners, def, group);
            case BELONGS_TO_MANY ->
                    loadBelongsToMany(ownerMeta, owners, def, group);
            case HAS_MANY_THROUGH ->
                    loadThrough(ownerMeta, owners, def, group);
            case MORPH_TO ->
                    loadMorphTo(owners, def);
        };
    }

    /** HAS_ONE / HAS_MANY / MORPH_MANY:外键在目标表上,按外键分组回填 */
    @SuppressWarnings("unchecked")
    private static <M extends Model<M>, T extends Model<T>> List<T> loadByForeignKey(
            ModelMeta<M> ownerMeta, List<M> owners, RelationDef def,
            List<RelPath> group) {

        Set<Object> ownerKeys = collectKeys(owners, o -> RelationSupport.localKeyValue(def, o));
        if (ownerKeys.isEmpty()) {
            owners.forEach(o -> o.fillRelation(def.name(), List.<T>of()));
            return List.of();
        }

        ModelMeta<T> targetMeta = RelationSupport.metaOf(def.target());
        String fkName = def.kind() == RelationDef.Kind.MORPH_MANY
                ? def.morphIdColumn() : def.foreignKey();
        Col<T, Object> fk = (Col<T, Object>) RelationSupport.columnOf(targetMeta, fkName);

        List<T> all = new ArrayList<>();
        for (List<Object> batch : partition(ownerKeys)) {
            Query<T> q = Query.of(targetMeta);
            q.whereIn(fk, batch);
            if (def.kind() == RelationDef.Kind.MORPH_MANY) {
                Col<T, Object> typeCol = (Col<T, Object>)
                        RelationSupport.columnOf(targetMeta, def.morphTypeColumn());
                q.where(typeCol, RelationSupport.morphAlias(ownerMeta.type()));
            }
            applyConstraints(q, group);
            all.addAll(q.get().toList());
        }

        // 按外键分组回填
        Map<Object, List<T>> grouped = new LinkedHashMap<>();
        for (T item : all) {
            grouped.computeIfAbsent(normalize(fk.get(item)), k -> new ArrayList<>()).add(item);
        }
        for (M owner : owners) {
            Object key = normalize(RelationSupport.localKeyValue(def, owner));
            owner.fillRelation(def.name(), grouped.getOrDefault(key, List.of()));
        }
        return all;
    }

    /** BELONGS_TO:外键在 owner 上,按目标主键回填 */
    @SuppressWarnings("unchecked")
    private static <M extends Model<M>, T extends Model<T>> List<T> loadBelongsTo(
            ModelMeta<M> ownerMeta, List<M> owners, RelationDef def,
            List<RelPath> group) {

        Col<M, Object> fk = (Col<M, Object>) RelationSupport.columnOf(ownerMeta, def.foreignKey());
        Set<Object> fkValues = collectKeys(owners, fk::get);
        if (fkValues.isEmpty()) {
            owners.forEach(o -> o.fillRelation(def.name(), List.<T>of()));
            return List.of();
        }

        ModelMeta<T> targetMeta = RelationSupport.metaOf(def.target());
        String ownerKeyName = (def.localKey() == null || def.localKey().isBlank())
                ? targetMeta.keyName() : def.localKey();
        Col<T, Object> ownerCol = (Col<T, Object>) RelationSupport.columnOf(targetMeta, ownerKeyName);

        List<T> all = new ArrayList<>();
        for (List<Object> batch : partition(fkValues)) {
            Query<T> q = Query.of(targetMeta);
            q.whereIn(ownerCol, batch);
            applyConstraints(q, group);
            all.addAll(q.get().toList());
        }

        Map<Object, T> byKey = new LinkedHashMap<>();
        for (T item : all) byKey.put(normalize(ownerCol.get(item)), item);

        for (M owner : owners) {
            Object key = normalize(fk.get(owner));
            T match = key == null ? null : byKey.get(key);
            owner.fillRelation(def.name(), match == null ? List.<T>of() : List.of(match));
        }
        return all;
    }

    /**
     * BELONGS_TO_MANY:两条查询 —— 先查中间表拿映射,再按目标主键批量取目标。
     *
     * <p>不用 JOIN 是为了让目标模型的列干净(JOIN 会混入中间表的列,
     * 且列名冲突时行为依赖驱动)。中间表的额外列单独附在结果上。
     */
    @SuppressWarnings("unchecked")
    private static <M extends Model<M>, T extends Model<T>> List<T> loadBelongsToMany(
            ModelMeta<M> ownerMeta, List<M> owners, RelationDef def,
            List<RelPath> group) {

        Set<Object> ownerKeys = collectKeys(owners, o -> RelationSupport.localKeyValue(def, o));
        if (ownerKeys.isEmpty()) {
            owners.forEach(o -> o.fillRelation(def.name(), List.<T>of()));
            return List.of();
        }

        // 1. 中间表:owner_key → [target_key]
        Map<Object, List<Object>> links = new LinkedHashMap<>();
        Set<Object> targetKeys = new LinkedHashSet<>();
        for (List<Object> batch : partition(ownerKeys)) {
            var pivotRows = Eloquent.connection().table(def.pivotTable())
                    .whereIn(def.foreignPivotKey(), batch)
                    .get();
            for (var row : pivotRows) {
                Object ownerKey = normalize(row.get(def.foreignPivotKey()));
                Object targetKey = row.get(def.relatedPivotKey());
                links.computeIfAbsent(ownerKey, k -> new ArrayList<>()).add(targetKey);
                targetKeys.add(targetKey);
            }
        }
        if (targetKeys.isEmpty()) {
            owners.forEach(o -> o.fillRelation(def.name(), List.<T>of()));
            return List.of();
        }

        // 2. 目标表
        ModelMeta<T> targetMeta = RelationSupport.metaOf(def.target());
        Col<T, Object> targetKeyCol = (Col<T, Object>) targetMeta.key();

        List<T> all = new ArrayList<>();
        for (List<Object> batch : partition(targetKeys)) {
            Query<T> q = Query.of(targetMeta);
            q.whereIn(targetKeyCol, batch);
            applyConstraints(q, group);
            all.addAll(q.get().toList());
        }

        Map<Object, T> byKey = new LinkedHashMap<>();
        for (T item : all) byKey.put(normalize(targetKeyCol.get(item)), item);

        // 3. 回填
        for (M owner : owners) {
            Object ownerKey = normalize(RelationSupport.localKeyValue(def, owner));
            List<T> items = links.getOrDefault(ownerKey, List.of()).stream()
                    .map(k -> byKey.get(normalize(k)))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            owner.fillRelation(def.name(), items);
        }
        return all;
    }

    /** HAS_MANY_THROUGH:同样两条查询,中间模型只用来建立映射 */
    @SuppressWarnings("unchecked")
    private static <M extends Model<M>, T extends Model<T>> List<T> loadThrough(
            ModelMeta<M> ownerMeta, List<M> owners, RelationDef def,
            List<RelPath> group) {

        Set<Object> ownerKeys = collectKeys(owners, o -> RelationSupport.localKeyValue(def, o));
        if (ownerKeys.isEmpty()) {
            owners.forEach(o -> o.fillRelation(def.name(), List.<T>of()));
            return List.of();
        }

        ModelMeta<?> through = RelationSupport.metaOf(def.through());

        // 1. 中间模型:owner_key → [through_key]
        Map<Object, List<Object>> links = new LinkedHashMap<>();
        Set<Object> throughKeys = new LinkedHashSet<>();
        for (List<Object> batch : partition(ownerKeys)) {
            var rows = Eloquent.connection().table(through.table())
                    .select(through.keyName(), def.foreignKey())
                    .whereIn(def.foreignKey(), batch)
                    .get();
            for (var row : rows) {
                Object ownerKey = normalize(row.get(def.foreignKey()));
                Object throughKey = row.get(through.keyName());
                links.computeIfAbsent(ownerKey, k -> new ArrayList<>()).add(throughKey);
                throughKeys.add(throughKey);
            }
        }
        if (throughKeys.isEmpty()) {
            owners.forEach(o -> o.fillRelation(def.name(), List.<T>of()));
            return List.of();
        }

        // 2. 目标表
        ModelMeta<T> targetMeta = RelationSupport.metaOf(def.target());
        Col<T, Object> secondKey = (Col<T, Object>)
                RelationSupport.columnOf(targetMeta, def.secondKey());

        List<T> all = new ArrayList<>();
        for (List<Object> batch : partition(throughKeys)) {
            Query<T> q = Query.of(targetMeta);
            q.whereIn(secondKey, batch);
            applyConstraints(q, group);
            all.addAll(q.get().toList());
        }

        Map<Object, List<T>> byThrough = new LinkedHashMap<>();
        for (T item : all) {
            byThrough.computeIfAbsent(normalize(secondKey.get(item)), k -> new ArrayList<>()).add(item);
        }

        // 3. 回填
        for (M owner : owners) {
            Object ownerKey = normalize(RelationSupport.localKeyValue(def, owner));
            List<T> items = links.getOrDefault(ownerKey, List.of()).stream()
                    .flatMap(tk -> byThrough.getOrDefault(normalize(tk), List.<T>of()).stream())
                    .toList();
            owner.fillRelation(def.name(), items);
        }
        return all;
    }

    /** MORPH_TO:按 type 列分组,每种类型一条查询 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <M extends Model<M>, T extends Model<T>> List<T> loadMorphTo(
            List<M> owners, RelationDef def) {

        ModelMeta<M> ownerMeta = owners.get(0).meta();
        Col<M, Object> idCol = (Col<M, Object>)
                RelationSupport.columnOf(ownerMeta, def.morphIdColumn());
        Col<M, Object> typeCol = (Col<M, Object>)
                RelationSupport.columnOf(ownerMeta, def.morphTypeColumn());

        // type → [id]
        Map<String, Set<Object>> byType = new LinkedHashMap<>();
        for (M owner : owners) {
            Object type = typeCol.get(owner);
            Object id = idCol.get(owner);
            if (type == null || id == null) continue;
            byType.computeIfAbsent(String.valueOf(type), t -> new LinkedHashSet<>()).add(id);
        }

        Map<String, Map<Object, Model<?>>> resolved = new LinkedHashMap<>();
        List<T> all = new ArrayList<>();

        byType.forEach((typeName, ids) -> {
            Class<?> targetType = def.morphTypes().stream()
                    .filter(t -> RelationSupport.morphAlias(t).equals(typeName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "多态列 " + def.morphTypeColumn() + " 里出现了未声明的类型 \""
                            + typeName + "\"。"
                          + "\n  @MorphTo(types = {...}) 已声明:" + def.morphTypes()
                          + "\n  修复:把该类型加进 types,或清理库里的脏数据。"));

            ModelMeta target = RelationSupport.metaOf(targetType);
            Map<Object, Model<?>> byKey = new LinkedHashMap<>();
            for (List<Object> batch : partition(ids)) {
                Query q = Query.of(target);
                q.whereIn((Col) target.key(), batch);
                for (Object m : q.get().toList()) {
                    Model<?> model = (Model<?>) m;
                    byKey.put(normalize(model.keyValue()), model);
                    all.add((T) model);
                }
            }
            resolved.put(typeName, byKey);
        });

        for (M owner : owners) {
            Object type = typeCol.get(owner);
            Object id = idCol.get(owner);
            Model<?> match = (type == null || id == null) ? null
                    : resolved.getOrDefault(String.valueOf(type), Map.of()).get(normalize(id));
            owner.fillRelation(def.name(), match == null ? List.of() : List.of((T) match));
        }
        return all;
    }

    // ================= 工具 =================

    @SuppressWarnings("unchecked")
    private static <T extends Model<T>> void applyConstraints(
            Query<T> query, List<RelPath> group) {
        for (RelPath<?, ?> path : group) {
            // 只有「这一层就是叶子」的路径上的约束才适用于本层
            if (path.segments().size() == 1 && path.hasConstraint()) {
                path.constraint().accept((Query<?>) query);
            }
        }
    }

    private static <M> Set<Object> collectKeys(
            List<M> models, java.util.function.Function<M, Object> extractor) {
        Set<Object> keys = new LinkedHashSet<>();
        for (M m : models) {
            Object k = extractor.apply(m);
            if (k != null) keys.add(k);
        }
        return keys;
    }

    /**
     * {@code in (?, ...)} 的分批。
     *
     * <p>Oracle 限 1000、SQL Server 限 2100 —— 超限不会报「参数太多」这种
     * 好懂的错,而是语法错误或静默截断,所以必须主动分批。
     */
    private static List<List<Object>> partition(java.util.Collection<Object> keys) {
        List<Object> all = new ArrayList<>(keys);
        if (all.size() <= MAX_IN_PARAMS) return List.of(all);

        List<List<Object>> batches = new ArrayList<>();
        for (int i = 0; i < all.size(); i += MAX_IN_PARAMS) {
            batches.add(all.subList(i, Math.min(all.size(), i + MAX_IN_PARAMS)));
        }
        return batches;
    }

    /**
     * 键的归一化。
     *
     * <p>必须做:SQLite 可能把主键读成 Integer,而外键读成 Long ——
     * 直接用 equals 比会全部匹配失败,且**表现为「预加载后关联是空的」**,
     * 极难排查。统一转字符串比较。
     */
    private static Object normalize(Object key) {
        if (key == null) return null;
        if (key instanceof Number n) return n.toString();
        return key.toString();
    }

    private static IllegalArgumentException unknownRelation(ModelMeta<?> meta, String name) {
        List<String> known = meta.relations().stream().map(RelationDef::name).toList();
        String hint = io.jaravel.support.Arr.closest(name, known)
                .map(c -> " 是否想写 " + c + " ?").orElse("");
        return new IllegalArgumentException(
                meta.type().getSimpleName() + " 上没有名为 \"" + name + "\" 的关联。" + hint
              + "\n  已声明的关联:" + (known.isEmpty() ? "(无)" : known)
              + "\n  修复:在模型类上加 @HasMany / @BelongsTo 等注解。");
    }
}
