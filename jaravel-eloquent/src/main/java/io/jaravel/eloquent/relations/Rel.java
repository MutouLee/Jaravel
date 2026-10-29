package io.jaravel.eloquent.relations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.jaravel.eloquent.Eloquent;
import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.RelationDef;
import io.jaravel.eloquent.query.Query;
import io.jaravel.support.Coll;

/**
 * 关联句柄。由 APT 生成的访问器返回。
 *
 * <pre>{@code
 * post.comments().get()                    // 已预加载则读缓存
 * post.comments().query().latest().get()   // 继续加条件
 * post.tags().attach(tagId)                // 多对多的中间表操作
 * }</pre>
 *
 * <p><b>N+1 的第二道防线在这里</b>:{@link #get()} 在未预加载且
 * {@code Eloquent.isLazyLoadingPrevented()} 为真时直接抛异常,
 * 并在消息里给出**准确的修复表达式**。开发模式下默认开启 ——
 * 让 N+1 在开发期就无法通过,而不是上线后看慢查询日志。
 */
public final class Rel<T extends Model<T>> {

    private final Model<?> owner;
    private final RelationDef def;

    /** null = 未加载。空列表 ≠ 未加载,这个区分很重要。 */
    private List<T> loaded;

    public Rel(Model<?> owner, RelationDef def) {
        this.owner = owner;
        this.def = def;
    }

    /** 持有这个关联的模型 —— 写入类操作(associate 等)需要回写它的外键 */
    public Model<?> owner() { return owner; }

    public boolean isLoaded() { return loaded != null; }
    public boolean isMany()   { return def.isMany(); }
    public String name()      { return def.name(); }
    public RelationDef def()  { return def; }

    /** 预加载器回填 */
    public void setLoaded(List<T> items) {
        this.loaded = items == null ? List.of() : List.copyOf(items);
    }

    /** 已加载的内容;未加载时返回空列表而**不**触发查询(供序列化使用) */
    public List<T> getLoaded() {
        return loaded == null ? List.of() : loaded;
    }

    // ================= 读取 =================

    /**
     * 取关联数据。已预加载则读缓存,否则按配置决定懒加载还是报错。
     */
    public Coll<T> get() {
        if (loaded != null) return Coll.wrap(loaded);

        if (Eloquent.isLazyLoadingPrevented()) {
            throw new LazyLoadingViolationException(owner, def);
        }
        // 懒加载:单条查询,并缓存结果
        List<T> result = query().get().toList();
        this.loaded = result;
        return Coll.wrap(result);
    }

    /** 单值关联的便捷取值 */
    public Optional<T> first() {
        return get().first();
    }

    public T firstOrFail() {
        return first().orElseThrow(() -> new io.jaravel.eloquent.ModelNotFoundException(
                def.target(), null));
    }

    public long count() {
        return loaded != null ? loaded.size() : query().count();
    }

    public boolean isEmpty() {
        return count() == 0;
    }

    /**
     * 继续在关联上加条件。
     *
     * <p>注意:这会**绕过预加载缓存**发新查询 —— 想用缓存请用 {@link #get()}。
     * 这是刻意的:加了条件就不是同一个结果集了。
     */
    @SuppressWarnings("unchecked")
    public Query<T> query() {
        ModelMeta<T> targetMeta = (ModelMeta<T>) RelationSupport.metaOf(def.target());
        Query<T> q = Query.of(targetMeta);
        RelationSupport.constrainForOwner(q, def, owner);
        return q;
    }

    // ================= 写入 =================

    /** 创建并关联一条新记录(一对多 / 一对一) */
    @SuppressWarnings("unchecked")
    public T create(Map<String, ?> attributes) {
        requireKind("create", RelationDef.Kind.HAS_MANY, RelationDef.Kind.HAS_ONE,
                RelationDef.Kind.MORPH_MANY);

        ModelMeta<T> targetMeta = (ModelMeta<T>) RelationSupport.metaOf(def.target());
        T model = targetMeta.instantiate();
        model.fill(attributes);
        RelationSupport.linkToOwner(model, def, owner);
        model.save();

        if (loaded != null) {
            var copy = new ArrayList<>(loaded);
            copy.add(model);
            loaded = List.copyOf(copy);
        }
        return model;
    }

    /** 把已有记录关联到本模型并保存 */
    public void save(T model) {
        requireKind("save", RelationDef.Kind.HAS_MANY, RelationDef.Kind.HAS_ONE,
                RelationDef.Kind.MORPH_MANY);
        RelationSupport.linkToOwner(model, def, owner);
        model.save();
        loaded = null;      // 关联集合已变,丢弃缓存
    }

    // ---------- 多对多 ----------

    public void attach(Object... targetKeys) {
        attach(List.of(targetKeys), Map.of());
    }

    public void attach(java.util.Collection<?> targetKeys, Map<String, ?> pivotValues) {
        requireKind("attach", RelationDef.Kind.BELONGS_TO_MANY);
        RelationSupport.attach(def, owner, targetKeys, pivotValues);
        loaded = null;
    }

    public void detach(Object... targetKeys) {
        requireKind("detach", RelationDef.Kind.BELONGS_TO_MANY);
        RelationSupport.detach(def, owner, List.of(targetKeys));
        loaded = null;
    }

    /** 清空本模型的全部关联 */
    public void detachAll() {
        requireKind("detachAll", RelationDef.Kind.BELONGS_TO_MANY);
        RelationSupport.detach(def, owner, null);
        loaded = null;
    }

    /** 同步为给定集合:不在集合里的解除,不存在的新增 */
    public void sync(java.util.Collection<?> targetKeys) {
        requireKind("sync", RelationDef.Kind.BELONGS_TO_MANY);
        RelationSupport.sync(def, owner, targetKeys);
        loaded = null;
    }

    private void requireKind(String operation, RelationDef.Kind... allowed) {
        for (RelationDef.Kind k : allowed) {
            if (def.kind() == k) return;
        }
        throw new UnsupportedOperationException(
                operation + "() 不适用于 " + def.kind() + " 关联 \"" + def.name() + "\"。"
              + "\n  该操作仅支持:" + List.of(allowed));
    }

    @Override public String toString() {
        return "Rel<" + (def.target() != null ? def.target().getSimpleName() : "?")
             + ">(" + def.name() + ", " + (isLoaded() ? "loaded=" + loaded.size() : "not loaded") + ")";
    }

    /**
     * 未预加载却访问了关联。消息里直接给出可粘贴的修复表达式 ——
     * docs/06 §4.3 的要求。
     */
    public static class LazyLoadingViolationException extends IllegalStateException {

        public LazyLoadingViolationException(Model<?> owner, RelationDef def) {
            super(buildMessage(owner, def));
        }

        private static String buildMessage(Model<?> owner, RelationDef def) {
            String ownerName = owner.meta().type().getSimpleName();
            return "试图在未预加载的情况下访问 " + ownerName + "#" + def.name()
                 + "\n  修复:在查询上加预加载"
                 + "\n    " + ownerName + ".query().with(" + ownerName + "_." + def.name() + ") ..."
                 + "\n  或对已有实例补加载"
                 + "\n    model.load(" + ownerName + "_." + def.name() + ")"
                 + "\n  确实需要单条懒加载时:"
                 + "\n    Eloquent.allowingLazyLoading(() -> model." + def.name() + "().get())"
                 + "\n  或在配置里关闭 eloquent.prevent-lazy-loading(不推荐)";
        }
    }
}
