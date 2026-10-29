package io.jaravel.eloquent.relations;

import java.util.List;
import java.util.Optional;

import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.RelationDef;

/**
 * {@code MorphTo} 的解析 —— 目标类型由数据里的 {@code *_type} 列决定。
 *
 * <p>这是唯一一类**无法**在编译期确定返回类型的关联,因此访问器只能返回
 * {@code Model<?>}。用户要用具体类型时 {@code instanceof} 一下:
 * <pre>{@code
 * if (comment.commentable() instanceof Post post) { ... }
 * }</pre>
 * 比 Laravel 的「返回什么全靠运行时」多了一层编译期提醒:类型是 {@code Model<?>},
 * 用户一眼看出必须自己分支。
 */
public final class MorphResolver {

    private MorphResolver() {}

    /** 解析单个 owner 的多态归属;外键为空返回 null */
    @SuppressWarnings("unchecked")
    public static Model<?> resolve(Model<?> owner, String relationName) {
        RelationDef def = owner.meta().relation(relationName).orElseThrow(
                () -> new IllegalArgumentException("未知关联:" + relationName));

        Object typeValue = RelationSupport.columnValue(owner, def.morphTypeColumn());
        Object idValue = RelationSupport.columnValue(owner, def.morphIdColumn());
        if (typeValue == null || idValue == null) return null;

        Class<?> target = resolveType(def, String.valueOf(typeValue));
        return (Model<?>) rawQuery(target).find(idValue).orElse(null);
    }

    /**
     * 多态的类型擦除**集中在这两个辅助方法**里。
     *
     * <p>多态关联的目标类型在编译期不存在(那正是「多态」的含义),
     * 所以泛型一定要在某处断开。把断点收在这里,而不是散落在各处 cast,
     * 这样「哪里不安全」一目了然。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static io.jaravel.eloquent.query.Query rawQuery(Class<?> modelType) {
        return io.jaravel.eloquent.query.Query.of((ModelMeta) RelationSupport.metaOf(modelType));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void fill(Model<?> owner, String relationName, List<? extends Model<?>> items) {
        ((Model) owner).fillRelation(relationName, (List) items);
    }

    /**
     * {@code *_type} 列的值 → 候选类型。
     *
     * <p>必须在 {@code @MorphTo(types = {...})} 列出的范围内。限制范围而不是
     * 直接 {@code Class.forName}:后者等于让数据库里的一个字符串决定加载哪个类,
     * 数据被篡改就是任意类加载。
     */
    static Class<?> resolveType(RelationDef def, String alias) {
        List<Class<?>> candidates = def.morphTypes();
        for (Class<?> c : candidates) {
            if (RelationSupport.morphAlias(c).equals(alias)) return c;
            if (c.getSimpleName().equals(alias)) return c;      // 兼容简名别名
        }
        throw new IllegalStateException("""
                多态关联 "%s" 的 %s 列值是 "%s",但它不在声明的候选类型里。
                  已声明:%s
                  修复:把该类型加进 @MorphTo(name = "%s", types = {..., %s.class})
                        或清理数据库里的脏值。
                  (刻意不做 Class.forName —— 否则数据库里的字符串就能决定加载哪个类)"""
                .formatted(def.name(), def.morphTypeColumn(), alias,
                           candidates.stream().map(Class::getName).toList(),
                           def.name(), simple(alias)));
    }

    /** 批量解析:按类型分组,每组一条查询,而不是每行一条 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void loadAll(List<? extends Model<?>> owners, RelationDef def) {
        var byType = new java.util.LinkedHashMap<String, List<Model<?>>>();
        for (Model<?> owner : owners) {
            Object typeValue = RelationSupport.columnValue(owner, def.morphTypeColumn());
            if (typeValue == null) {
                fill(owner, def.name(), List.of());
                continue;
            }
            byType.computeIfAbsent(String.valueOf(typeValue), t -> new java.util.ArrayList<>())
                  .add(owner);
        }

        byType.forEach((alias, group) -> {
            Class<?> target = resolveType(def, alias);
            ModelMeta meta = RelationSupport.metaOf(target);

            var keys = group.stream()
                    .map(o -> RelationSupport.columnValue(o, def.morphIdColumn()))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (keys.isEmpty()) {
                group.forEach(o -> fill(o, def.name(), List.of()));
                return;
            }

            var loaded = rawQuery(target)
                    .whereIn((io.jaravel.eloquent.meta.Col) meta.key(), keys)
                    .get()
                    .toList();

            var byKey = new java.util.LinkedHashMap<String, Model<?>>();
            for (Object item : loaded) {
                byKey.put(String.valueOf(((Model<?>) item).keyValue()), (Model<?>) item);
            }
            for (Model<?> owner : group) {
                Object id = RelationSupport.columnValue(owner, def.morphIdColumn());
                Model<?> match = id == null ? null : byKey.get(String.valueOf(id));
                fill(owner, def.name(), match == null ? List.of() : List.of(match));
            }
        });
    }

    public static Optional<Model<?>> resolveOptional(Model<?> owner, String relationName) {
        return Optional.ofNullable(resolve(owner, relationName));
    }

    private static String simple(String qualified) {
        int i = qualified.lastIndexOf('.');
        return i < 0 ? qualified : qualified.substring(i + 1);
    }
}
