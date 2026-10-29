package io.jaravel.eloquent.query;

import java.util.HashMap;
import java.util.Map;

import io.jaravel.core.context.ExecutionScope;
import io.jaravel.database.Row;
import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.ModelMeta;

/**
 * 执行级身份映射:同一次执行内,同一主键的同一模型只实例化一次。
 *
 * <p>两个收益:
 * <ul>
 *   <li>预加载回填后,{@code post.author().get()} 与外部拿到的同一个 user
 *       是**同一对象** —— 改一处另一处可见,不会出现两个版本的同一行;
 *   <li>避免同一行被重复实例化。
 * </ul>
 *
 * <p>生命周期挂在 {@link ExecutionScope} 上:HTTP 请求内共享,
 * 请求结束自动释放。**脱离作用域时退化为「每次新建」** —— 单元测试与脚本里
 * 既不报错也不泄漏(这是 {@code ExecutionScope.scopedOrFresh} 的用途)。
 *
 * <p>可用 {@code @Model(identityMap = false)} 关闭。
 */
public final class IdentityMap {

    /** (模型类型 → (主键 → 实例)) */
    private final Map<Class<?>, Map<Object, Object>> byType = new HashMap<>();

    private IdentityMap() {}

    private static IdentityMap current() {
        return ExecutionScope.scopedOrFresh(IdentityMap.class, IdentityMap::new);
    }

    /**
     * 从数据库行取得模型实例:映射里已有则**更新已有实例**并返回它,
     * 否则新建并登记。
     *
     * <p>更新已有实例而不是直接返回旧值,是因为这次查询可能选了更多列。
     * 但**不覆盖未保存的本地修改**:若已有实例是脏的,原样返回 ——
     * 否则用户刚改的字段会被一次无关查询悄悄覆盖掉,这类 bug 极难排查。
     */
    @SuppressWarnings("unchecked")
    public static <M extends Model<M>> M resolve(ModelMeta<M> meta, Row row) {
        if (!meta.identityMap()) return Model.hydrate(meta, row);

        Object key = row.has(meta.keyName()) ? row.get(meta.keyName()) : null;
        if (key == null) return Model.hydrate(meta, row);   // 没取主键,无法去重

        IdentityMap map = current();
        Map<Object, Object> forType = map.byType.computeIfAbsent(meta.type(), t -> new HashMap<>());

        Object existing = forType.get(key);
        if (existing != null) {
            M model = (M) existing;
            if (!model.isDirty()) {
                for (var col : meta.columns()) {
                    if (row.has(col.name())) col.fromDatabase(model, row.get(col.name()));
                }
                model.markLoaded();
            }
            return model;
        }

        M model = Model.hydrate(meta, row);
        forType.put(key, model);
        return model;
    }

    /** 测试与长任务里手动清空 */
    public static void clear() {
        ExecutionScope.tryCurrent()
                .flatMap(s -> s.scopedIfPresent(IdentityMap.class))
                .ifPresent(m -> m.byType.clear());
    }

    /** 当前映射里缓存了多少实例 —— 诊断用 */
    public static int size() {
        return ExecutionScope.tryCurrent()
                .flatMap(s -> s.scopedIfPresent(IdentityMap.class))
                .map(m -> m.byType.values().stream().mapToInt(Map::size).sum())
                .orElse(0);
    }
}
