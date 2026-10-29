package io.jaravel.eloquent.relations;

import java.util.Map;
import java.util.Optional;

import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.ModelNotFoundException;
import io.jaravel.eloquent.meta.Col;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.RelationDef;
import io.jaravel.eloquent.query.Query;

/**
 * 单值关联的句柄({@code BelongsTo} / {@code HasOne} / {@code MorphTo})。
 *
 * <p>与 {@link Rel} 的区别只在 {@link #get()} 返回 {@code Optional<T>} 而非集合 ——
 * 这让 {@code post.author().get()} 的类型一眼可知,不必记「这个关联是单值还是多值」。
 *
 * <pre>{@code
 * post.author().get()           // Optional<User>
 * post.author().getOrFail()     // User,不存在则 404
 * post.author().associate(user) // 设外键并保存
 * }</pre>
 */
public final class Rel1<T extends Model<T>> {

    private final Rel<T> delegate;

    public Rel1(Rel<T> delegate) {
        this.delegate = delegate;
    }

    public boolean isLoaded()   { return delegate.isLoaded(); }
    public String name()        { return delegate.name(); }
    public RelationDef def()    { return delegate.def(); }

    /** 已预加载则读缓存;否则按配置决定懒加载还是报错(N+1 第二道防线) */
    public Optional<T> get() {
        return delegate.get().first();
    }

    public T getOrFail() {
        return get().orElseThrow(() -> new ModelNotFoundException(
                delegate.def().target(), null));
    }

    public boolean isPresent() {
        return delegate.isLoaded() ? !delegate.getLoaded().isEmpty() : delegate.count() > 0;
    }

    /** 未加载时返回空而**不**触发查询(供序列化使用) */
    public Optional<T> getLoaded() {
        return delegate.getLoaded().stream().findFirst();
    }

    public Query<T> query() {
        return delegate.query();
    }

    /**
     * 把本模型的外键指向给定对象(仅 {@code BelongsTo})。
     *
     * <p>**不自动保存** —— 与 Laravel 的 {@code associate()} 一致。
     * 调用方决定何时 {@code save()},这样可以把多处修改合并成一次 UPDATE。
     */
    @SuppressWarnings("unchecked")
    public void associate(T target) {
        RelationDef def = delegate.def();
        requireBelongsTo("associate", def);

        Model<?> owner = owner();
        ModelMeta<Object> ownerMeta = (ModelMeta<Object>) owner.meta();
        Col<Object, Object> fk = (Col<Object, Object>)
                RelationSupport.columnOf(ownerMeta, def.foreignKey());

        Object targetKey = (def.localKey() == null || def.localKey().isBlank())
                ? target.keyValue()
                : RelationSupport.columnValue(target, def.localKey());

        fk.fromDatabase(owner, targetKey);
        delegate.setLoaded(java.util.List.of(target));
    }

    /** 清空外键。同样不自动保存。 */
    @SuppressWarnings("unchecked")
    public void dissociate() {
        RelationDef def = delegate.def();
        requireBelongsTo("dissociate", def);

        Model<?> owner = owner();
        ModelMeta<Object> ownerMeta = (ModelMeta<Object>) owner.meta();
        ((Col<Object, Object>) RelationSupport.columnOf(ownerMeta, def.foreignKey()))
                .fromDatabase(owner, null);
        delegate.setLoaded(java.util.List.of());
    }

    /** 创建并关联({@code HasOne}) */
    public T create(Map<String, ?> attributes) {
        return delegate.create(attributes);
    }

    private Model<?> owner() {
        return delegate.owner();
    }

    private static void requireBelongsTo(String operation, RelationDef def) {
        if (def.kind() == RelationDef.Kind.BELONGS_TO) return;
        throw new UnsupportedOperationException(
                operation + "() 只适用于 BelongsTo 关联(外键在本模型上),"
              + "而 \"" + def.name() + "\" 是 " + def.kind() + "。"
              + "\n  HasOne 的外键在目标表上,请用 create() 或直接设置目标的外键。");
    }

    /** 预加载器回填入口 */
    public void setLoaded(T item) {
        delegate.setLoaded(item == null ? java.util.List.of() : java.util.List.of(item));
    }

    @Override public String toString() {
        return "Rel1<" + (def().target() != null ? def().target().getSimpleName() : "?")
             + ">(" + name() + ", " + (isLoaded() ? "loaded" : "not loaded") + ")";
    }
}
