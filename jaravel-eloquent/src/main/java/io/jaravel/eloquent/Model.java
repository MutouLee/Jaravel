package io.jaravel.eloquent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.jaravel.database.Row;
import io.jaravel.eloquent.event.ModelEvent;
import io.jaravel.eloquent.event.Observers;
import io.jaravel.eloquent.meta.Col;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.query.Query;
import io.jaravel.eloquent.relations.Rel;
import io.jaravel.eloquent.relations.RelationLoader;
import io.jaravel.support.Arr;

/**
 * Active Record 基类(ADR-010 方案 E')。
 *
 * <p><b>脏跟踪靠快照比对,不靠拦截写入</b> —— 这是让 public 字段成为可能的关键洞察
 * (V1 验证):写入拦截不是脏跟踪的必要条件,而 Hibernate 本来就是用快照比对的。
 *
 * <pre>{@code
 * Post p = Post.findOrFail(1L);
 * p.title = "新标题";     // 与 $post->title = '...' 等长
 * p.save();               // 与快照比对,只 UPDATE 变化的列
 * }</pre>
 *
 * <p><b>关联不是字段</b> —— V1 验证发现 public 字段既无法拦截懒加载,
 * 又会被用户的显式初始化器静默覆盖(父类构造器初始化子类字段的陷阱)。
 * 因此关联句柄存在本基类的 map 里,由 APT 生成的访问器方法暴露。
 */
public abstract class Model<M extends Model<M>> {

    /** 原值快照。null 表示该实例是新建的(未从数据库加载)。 */
    private Object[] original;
    private boolean exists;
    private boolean recentlyCreated;

    /** 关联句柄。惰性创建,不作为用户模型的字段存在(见类注释)。 */
    private Map<String, Rel<?>> relations;

    /** APT 生成的 {@code <Name>Base} 实现它,返回 {@code <Name>_.META} */
    public abstract ModelMeta<M> meta();

    @SuppressWarnings("unchecked")
    protected final M self() {
        return (M) this;
    }

    // ================= 生命周期 =================

    /**
     * 从数据库行填充并标记为「已存在」。由 Query 的结果映射调用。
     */
    public static <M extends Model<M>> M hydrate(ModelMeta<M> meta, Row row) {
        M model = meta.instantiate();
        for (Col<M, ?> col : meta.columns()) {
            if (row.has(col.name())) col.fromDatabase(model, row.get(col.name()));
        }
        model.markLoaded();
        Observers.fire(ModelEvent.RETRIEVED, model);
        return model;
    }

    /** 标记为已从数据库加载:拍快照、置 exists */
    public final M markLoaded() {
        this.original = snapshot();
        this.exists = true;
        this.recentlyCreated = false;
        return self();
    }

    private Object[] snapshot() {
        List<Col<M, ?>> cols = meta().columns();
        Object[] snap = new Object[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            snap[i] = cols.get(i).snapshot(self());
        }
        return snap;
    }

    public final boolean exists()            { return exists; }
    public final boolean wasRecentlyCreated() { return recentlyCreated; }

    // ================= 脏检查 =================

    /**
     * 需要写回的列 → 新值。
     *
     * <p>新建时返回全部非 null 列;已存在时返回与快照有差异的列。
     */
    public final Map<String, Object> changes() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Col<M, ?>> cols = meta().columns();

        for (int i = 0; i < cols.size(); i++) {
            Col<M, ?> col = cols.get(i);
            if (original == null) {
                Object v = col.toDatabase(self());
                if (v != null) out.put(col.name(), v);
            } else if (col.differsFrom(self(), original[i])) {
                out.put(col.name(), col.toDatabase(self()));
            }
        }
        return out;
    }

    public final boolean isDirty() {
        return !changes().isEmpty();
    }

    public final boolean isDirty(Col<M, ?> col) {
        if (original == null) return col.get(self()) != null;
        return col.differsFrom(self(), original[col.index()]);
    }

    public final boolean isClean() {
        return !isDirty();
    }

    /** 快照里的原值。新建的实例返回 null。 */
    public final Object getOriginal(Col<M, ?> col) {
        return original == null ? null : original[col.index()];
    }

    // ================= 持久化 =================

    /**
     * 新建则 INSERT,已存在则只 UPDATE 变化的列。
     *
     * @return 是否真的写了库(无变化时返回 false)
     */
    @SuppressWarnings("unchecked")
    public final boolean save() {
        ModelMeta<M> meta = meta();

        if (!exists) {
            if (!Observers.fire(ModelEvent.CREATING, self())) return false;
            if (!Observers.fire(ModelEvent.SAVING, self())) return false;

            touchTimestamps(true);
            Map<String, Object> values = changes();

            if (meta.incrementing()) {
                Optional<Long> id = Query.of(meta).rawBuilder().insertGetId(values);
                id.ifPresent(v -> ((Col<M, Object>) meta.key())
                        .fromDatabase(self(), v));
            } else {
                Query.of(meta).rawBuilder().insert(values);
            }

            markLoaded();
            this.recentlyCreated = true;
            Observers.fire(ModelEvent.CREATED, self());
            Observers.fire(ModelEvent.SAVED, self());
            return true;
        }

        if (!Observers.fire(ModelEvent.UPDATING, self())) return false;
        if (!Observers.fire(ModelEvent.SAVING, self())) return false;

        // 顺序很重要:**先判脏,再打时间戳**。
        // 反过来写的话 updated_at 自己就成了变化,于是每次 save() 都发一条
        // `set updated_at = ?` 的 UPDATE,脏检查等于没有。Laravel 同样是先判脏。
        if (changes().isEmpty()) return false;

        touchTimestamps(false);
        Map<String, Object> values = changes();

        Query.of(meta).whereKey(keyValue()).rawBuilder().update(values);

        markLoaded();
        Observers.fire(ModelEvent.UPDATED, self());
        Observers.fire(ModelEvent.SAVED, self());
        return true;
    }

    /**
     * 原子自增一列,不读旧值。
     *
     * <p>生成 {@code set views = views + 1},而**不是**「读出来 +1 再写回」——
     * 后者在并发下会丢计数(两个请求都读到 10,都写 11)。浏览量、库存这类
     * 字段几乎总是并发的,所以这个区别不是优化而是正确性。
     *
     * <p>不触发模型事件,也不走脏检查 —— 它是一条直接的 SQL,
     * 与 Laravel 的 {@code increment()} 行为一致。
     */
    public final M increment(Col<M, ? extends Number> col, Number amount) {
        requirePersisted("increment()");
        Query.of(meta()).whereKey(keyValue()).rawBuilder().increment(col.name(), amount);

        // 把内存里的值同步成新值,免得紧接着的渲染显示旧数字
        Object current = col.get(self());
        if (current instanceof Number n) {
            setNumeric(col, n, amount);
        }
        return self();
    }

    public final M increment(Col<M, ? extends Number> col) {
        return increment(col, 1);
    }

    public final M decrement(Col<M, ? extends Number> col, Number amount) {
        requirePersisted("decrement()");
        Query.of(meta()).whereKey(keyValue()).rawBuilder().decrement(col.name(), amount);

        Object current = col.get(self());
        if (current instanceof Number n) {
            setNumeric(col, n, -amount.doubleValue());
        }
        return self();
    }

    public final M decrement(Col<M, ? extends Number> col) {
        return decrement(col, 1);
    }

    /** 按列的声明类型回写,避免把 int 列写成 Double */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setNumeric(Col<M, ? extends Number> col, Number current, Number delta) {
        double updated = current.doubleValue() + delta.doubleValue();
        Col<M, Object> raw = (Col<M, Object>) (Col) col;

        Object typed;
        if (current instanceof Integer)         typed = (int) updated;
        else if (current instanceof Long)       typed = (long) updated;
        else if (current instanceof Short)       typed = (short) updated;
        else if (current instanceof java.math.BigDecimal) {
            typed = java.math.BigDecimal.valueOf(updated);
        } else typed = updated;

        raw.set(self(), typed);
        // 快照同步:否则紧随其后的 save() 会把这一列当成「用户改的」再写一遍
        markLoaded();
    }

    /** 软删除(若启用),否则硬删除 */
    public final boolean delete() {
        requirePersisted("delete()");
        ModelMeta<M> meta = meta();

        if (!Observers.fire(ModelEvent.DELETING, self())) return false;

        if (meta.softDeletes()) {
            Query.of(meta).whereKey(keyValue()).rawBuilder()
                    .update(Map.of(meta.softDeleteColumn(), Instant.now()));
            meta.column(meta.softDeleteColumn())
                .ifPresent(c -> c.fromDatabase(self(), Instant.now()));
            markLoaded();
        } else {
            Query.of(meta).whereKey(keyValue()).rawBuilder().delete();
            this.exists = false;
        }

        Observers.fire(ModelEvent.DELETED, self());
        return true;
    }

    /** 无视软删除,真正从库里删掉 */
    public final boolean forceDelete() {
        requirePersisted("forceDelete()");
        if (!Observers.fire(ModelEvent.DELETING, self())) return false;

        // 必须 withTrashed:否则 rawBuilder 会带上「deleted_at is null」,
        // 于是「强制删除一条已软删的记录」这个唯一有意义的场景恰好删不掉。
        Query.of(meta()).withTrashed().whereKey(keyValue()).rawBuilder().delete();
        this.exists = false;

        Observers.fire(ModelEvent.DELETED, self());
        return true;
    }

    /** 撤销软删除 */
    public final boolean restore() {
        ModelMeta<M> meta = meta();
        if (!meta.softDeletes()) {
            throw new IllegalStateException(
                    meta.type().getSimpleName() + " 没有启用软删除,restore() 无意义。"
                  + "\n  修复:给模型加上 @SoftDeletes。");
        }
        requirePersisted("restore()");

        // withTrashed 不可省:不加的话 where 里会多一个「deleted_at is null」,
        // 与「把 deleted_at 置空」的目标自相矛盾 —— restore() 会变成永久空操作。
        Query.of(meta).withTrashed().whereKey(keyValue()).rawBuilder()
                .update(nullableMap(meta.softDeleteColumn()));
        meta.column(meta.softDeleteColumn()).ifPresent(c -> c.fromDatabase(self(), null));
        markLoaded();

        Observers.fire(ModelEvent.RESTORED, self());
        return true;
    }

    public final boolean isTrashed() {
        ModelMeta<M> meta = meta();
        if (!meta.softDeletes()) return false;
        return meta.column(meta.softDeleteColumn())
                .map(c -> c.get(self()) != null)
                .orElse(false);
    }

    /** 重新从库里读一遍,丢弃本地未保存的修改 */
    public final M refresh() {
        requirePersisted("refresh()");
        ModelMeta<M> meta = meta();

        M fresh = Query.of(meta).withTrashed().whereKey(keyValue()).first()
                .orElseThrow(() -> new ModelNotFoundException(meta.type(), keyValue()));

        for (Col<M, ?> col : meta.columns()) {
            copyValue(col, fresh, self());
        }
        if (relations != null) relations.clear();
        markLoaded();
        return self();
    }

    @SuppressWarnings("unchecked")
    private static <M extends Model<M>, V> void copyValue(Col<M, V> col, M from, M to) {
        col.set(to, col.get(from));
    }

    /** 复制为一个未保存的新实例(不含主键与时间戳) */
    public final M replicate() {
        ModelMeta<M> meta = meta();
        M copy = meta.instantiate();
        for (Col<M, ?> col : meta.columns()) {
            if (col.name().equals(meta.keyName())) continue;
            if (col.name().equals("created_at") || col.name().equals("updated_at")) continue;
            copyValue((Col<M, Object>) col, self(), copy);
        }
        return copy;
    }

    // ================= 批量赋值 =================

    /**
     * 按列名批量赋值,受 {@code @Guarded} / {@code @Fillable} 保护。
     *
     * <p>被保护的列**静默跳过**(与 Laravel 一致),因为这是常态
     * (表单里总会有 {@code _token} 之类的多余字段)。
     * 需要知道哪些被跳过时用 {@link #fillStrict}。
     */
    public final M fill(Map<String, ?> attributes) {
        applyFill(attributes, false);
        return self();
    }

    /** 同 {@link #fill},但遇到被保护或不存在的列时报错 */
    public final M fillStrict(Map<String, ?> attributes) {
        applyFill(attributes, true);
        return self();
    }

    @SuppressWarnings("unchecked")
    private void applyFill(Map<String, ?> attributes, boolean strict) {
        ModelMeta<M> meta = meta();
        List<String> rejected = new ArrayList<>();

        attributes.forEach((rawKey, value) -> {
            String column = io.jaravel.support.Str.snake(rawKey);
            Optional<Col<M, ?>> col = meta.column(column);

            if (col.isEmpty()) {
                if (strict) rejected.add(column + "(不是该模型的列)");
                return;
            }
            if (!meta.isFillable(column)) {
                if (strict) rejected.add(column + "(被 @Guarded/@Fillable 保护)");
                return;
            }
            ((Col<M, Object>) col.get()).fromDatabase(self(), value);
        });

        if (!rejected.isEmpty()) {
            String hint = attributes.keySet().stream()
                    .map(io.jaravel.support.Str::snake)
                    .filter(k -> meta.column(k).isEmpty())
                    .findFirst()
                    .flatMap(k -> Arr.closest(k, meta.columnNames()))
                    .map(c -> "\n  是否想写 " + c + " ?")
                    .orElse("");
            throw new MassAssignmentException(
                    "fillStrict 拒绝了以下键:" + rejected + hint
                  + "\n  该模型的列:" + meta.columnNames()
                  + "\n  可批量赋值的列:" + meta.columnNames().stream()
                            .filter(meta::isFillable).toList());
        }
    }

    // ================= 关联 =================

    /**
     * 关联句柄的惰性创建 + 按名缓存。
     *
     * <p>状态存在基类的 map 里,**不存在用户模型的字段中**(见类注释的两条原因)。
     * 代价是每个实例多一个 map —— 仅在实际访问关联时才创建。
     */
    @SuppressWarnings("unchecked")
    public final <T extends Model<T>> Rel<T> rel(String name) {
        if (relations == null) relations = new LinkedHashMap<>();
        return (Rel<T>) relations.computeIfAbsent(name, n -> {
            var def = meta().relation(n).orElseThrow(() -> new IllegalArgumentException(
                    meta().type().getSimpleName() + " 上没有名为 \"" + n + "\" 的关联。"
                  + "\n  已声明的关联:" + meta().relations().stream()
                            .map(io.jaravel.eloquent.meta.RelationDef::name).toList()));
            return new Rel<>(this, def);
        });
    }

    /** 预加载器回填入口 */
    /**
     * 单值关联的句柄。生成代码对 {@code BelongsTo}/{@code HasOne}/{@code MorphTo}
     * 调用这个而不是 {@link #rel(String)},好处是返回类型里就写着「它是单值」。
     */
    public final <T extends Model<T>> io.jaravel.eloquent.relations.Rel1<T> rel1(String name) {
        return new io.jaravel.eloquent.relations.Rel1<>(this.<T>rel(name));
    }

    public final <T extends Model<T>> void fillRelation(String name, List<T> items) {
        this.<T>rel(name).setLoaded(items);
    }

    public final boolean relationLoaded(String name) {
        return relations != null && relations.containsKey(name) && relations.get(name).isLoaded();
    }

    /** 已加载的关联名 —— 调试与 N+1 诊断用 */
    public final List<String> loadedRelations() {
        if (relations == null) return List.of();
        return relations.entrySet().stream()
                .filter(e -> e.getValue().isLoaded())
                .map(Map.Entry::getKey).toList();
    }

    /** 事后补加载关联(已有模型实例时) */
    @SafeVarargs
    public final M load(io.jaravel.eloquent.meta.RelPath<M, ?>... paths) {
        RelationLoader.load(meta(), List.of(self()), List.of(paths));
        return self();
    }

    /**
     * 给**一批**已取回的模型补加载关联 —— 对应 Laravel 的
     * {@code $collection->load('posts')}。
     *
     * <p>有了 {@code query().load(...)} 还需要这个,是因为取回和决定要不要
     * 关联常常不在同一个地方:控制器先查出列表,序列化层才知道要带关联。
     * 这时逐个 {@code model.load()} 就是 N+1,必须有批量入口。
     */
    @SafeVarargs
    public static <T extends Model<T>> void loadAll(
            List<T> models, io.jaravel.eloquent.meta.RelPath<T, ?>... paths) {
        if (models.isEmpty() || paths.length == 0) return;
        RelationLoader.load(models.get(0).meta(), models, List.of(paths));
    }

    // ================= 序列化 =================

    /**
     * 转为 Map,{@code @Hidden} 的列被排除。
     *
     * <p>已加载的关联会一并输出;**未加载的关联不会触发查询** ——
     * 否则序列化就成了 N+1 的温床(这是 Laravel 的 `$appends` 常见坑)。
     *
     * <h2>为什么标 {@code @JsonValue}</h2>
     * <p>不标的话 Jackson 会按 JavaBean 规则直接序列化 public 字段和
     * {@code isXxx()} 方法,后果有两个,都在 v0.2 的端到端验收里暴露了:
     * <ul>
     *   <li><b>安全问题</b>:{@code @Hidden} 完全失效 —— 标了 @Hidden 的
     *       密码字段会原样出现在 API 响应里。{@code @Hidden} 只在 toMap()
     *       里生效,而控制器返回模型时走的是 Jackson;</li>
     *   <li>ORM 内部状态({@code dirty} / {@code clean} / {@code trashed})
     *       被当成业务字段输出。</li>
     * </ul>
     * <p>把 toMap() 指定为唯一的 JSON 形态,两个问题一起消失,而且
     * 「API 看到什么」与「toMap 返回什么」从此是同一件事。
     */
    @com.fasterxml.jackson.annotation.JsonValue
    public final Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Col<M, ?> col : meta().columns()) {
            if (col.isHidden()) continue;
            out.put(col.name(), col.get(self()));
        }
        if (relations != null) {
            relations.forEach((name, rel) -> {
                if (!rel.isLoaded()) return;
                out.put(name, rel.isMany()
                        ? rel.getLoaded().stream().map(Model::toMap).toList()
                        : rel.getLoaded().stream().findFirst().map(Model::toMap).orElse(null));
            });
        }
        return out;
    }

    // ================= 内部 =================

    public final Object keyValue() {
        return meta().key().get(self());
    }

    private void requirePersisted(String operation) {
        if (exists) return;
        throw new IllegalStateException(
                meta().type().getSimpleName() + " 实例尚未保存,不能调用 " + operation
              + "\n  先调用 save(),或用 Query 的批量操作。");
    }

    private void touchTimestamps(boolean creating) {
        ModelMeta<M> meta = meta();
        if (!meta.timestamps()) return;
        Instant now = Instant.now();
        if (creating) {
            meta.column("created_at").ifPresent(c -> {
                if (c.get(self()) == null) c.fromDatabase(self(), now);
            });
        }
        meta.column("updated_at").ifPresent(c -> c.fromDatabase(self(), now));
    }

    /** Map.of 不接受 null 值,软删除恢复需要显式写 null */
    private static Map<String, Object> nullableMap(String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, null);
        return m;
    }

    // ================= Object =================

    /** 同类型 + 同主键视为相等。主键为 null(未保存)时退化为引用相等。 */
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !meta().type().isInstance(o)) return false;
        Object mine = keyValue();
        if (mine == null) return false;
        @SuppressWarnings("unchecked") M other = (M) o;
        return Objects.equals(mine, other.keyValue());
    }

    @Override public int hashCode() {
        Object key = keyValue();
        return key == null ? System.identityHashCode(this) : key.hashCode();
    }

    @Override public String toString() {
        var sb = new StringBuilder(meta().type().getSimpleName()).append(" {");
        boolean first = true;
        for (Col<M, ?> col : meta().columns()) {
            if (!first) sb.append(", ");
            sb.append(col.name()).append('=');
            sb.append(col.isHidden() ? "<hidden>" : col.get(self()));
            first = false;
        }
        if (relations != null && !loadedRelations().isEmpty()) {
            sb.append(", loaded=").append(loadedRelations());
        }
        if (isDirty()) sb.append(", dirty=").append(changes().keySet());
        return sb.append('}').toString();
    }

    // ================= 异常 =================

    public static class MassAssignmentException extends RuntimeException {
        public MassAssignmentException(String message) { super(message); }
    }
}
