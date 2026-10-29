package io.jaravel.eloquent.query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import io.jaravel.database.Row;
import io.jaravel.database.query.QueryBuilder;
import io.jaravel.database.query.QueryState.Op;
import io.jaravel.eloquent.Eloquent;
import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.ModelNotFoundException;
import io.jaravel.eloquent.meta.Col;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.RelPath;
import io.jaravel.eloquent.relations.RelationLoader;
import io.jaravel.support.Coll;

/**
 * 类型安全的模型查询。架在 {@link QueryBuilder} 之上,把列名换成 {@link Col}。
 *
 * <pre>{@code
 * Post.query()
 *     .published()                          // @Scope 生成
 *     .where(Post_.views, Op.GT, 100)
 *     .with(Post_.author, Post_.comments)   // 预加载
 *     .orderByDesc(Post_.createdAt)
 *     .paginate(15);
 * }</pre>
 *
 * <p>APT 为每个模型生成 {@code <Name>Query extends Query<Name>},在上面加作用域
 * 与便捷条件方法,因此链式调用全程保持具体类型。
 */
public class Query<M extends Model<M>> {

    protected final ModelMeta<M> meta;
    protected final QueryBuilder builder;

    private final List<RelPath<M, ?>> eagerLoads = new ArrayList<>();
    private final List<String> disabledGlobalScopes = new ArrayList<>();
    private boolean allGlobalScopesDisabled;
    /** 软删除:default=排除已删,WITH=全部,ONLY=只看已删 */
    private Trashed trashed = Trashed.WITHOUT;
    private boolean globalScopesApplied;

    private enum Trashed { WITHOUT, WITH, ONLY }

    protected Query(ModelMeta<M> meta) {
        this.meta = meta;
        this.builder = Eloquent.connection().table(meta.table());
    }

    public static <M extends Model<M>> Query<M> of(ModelMeta<M> meta) {
        return new Query<>(meta);
    }

    @SuppressWarnings("unchecked")
    protected final <Q extends Query<M>> Q cast() {
        return (Q) this;
    }

    // ================= where =================

    public <V> Query<M> where(Col<M, V> col, V value) {
        builder.where(col.name(), value);
        return this;
    }

    public <V> Query<M> where(Col<M, V> col, Op op, V value) {
        builder.where(col.name(), op, value);
        return this;
    }

    public <V> Query<M> orWhere(Col<M, V> col, V value) {
        builder.orWhere(col.name(), value);
        return this;
    }

    @SafeVarargs
    public final <V> Query<M> whereIn(Col<M, V> col, V... values) {
        builder.whereIn(col.name(), java.util.Arrays.asList(values));
        return this;
    }

    public <V> Query<M> whereIn(Col<M, V> col, java.util.Collection<V> values) {
        builder.whereIn(col.name(), values);
        return this;
    }

    public <V> Query<M> whereNotIn(Col<M, V> col, java.util.Collection<V> values) {
        builder.whereNotIn(col.name(), values);
        return this;
    }

    public Query<M> whereNull(Col<M, ?> col) {
        builder.whereNull(col.name());
        return this;
    }

    public Query<M> whereNotNull(Col<M, ?> col) {
        builder.whereNotNull(col.name());
        return this;
    }

    public <V> Query<M> whereBetween(Col<M, V> col, V low, V high) {
        builder.whereBetween(col.name(), low, high);
        return this;
    }

    public Query<M> whereLike(Col<M, String> col, String pattern) {
        builder.whereLike(col.name(), pattern);
        return this;
    }

    /** 嵌套分组:{@code where(q -> q.where(a).orWhere(b))} → {@code and (a or b)} */
    public Query<M> where(Consumer<Query<M>> group) {
        builder.where(nested -> {
            var sub = new Query<>(meta, nested);
            group.accept(sub);
        });
        return this;
    }

    /** 主键条件 */
    public Query<M> whereKey(Object value) {
        builder.where(meta.keyName(), value);
        return this;
    }

    /**
     * 按**列名**筛选 —— 列名来自运行时(属性 map、请求参数的白名单过滤等)。
     *
     * <p>有 {@link #where(Col, Object)} 还要这个,是因为有些入口
     * ({@code firstOrCreate(Map)}、{@code updateOrCreate})天生拿到的是字符串。
     * 列名在这里核对一次,拼错立刻报错并给出已有列,而不是让数据库报
     * 「no such column」。
     */
    @SuppressWarnings("unchecked")
    public Query<M> whereNamed(String column, Object value) {
        Col<M, Object> col = (Col<M, Object>) meta.column(column)
                .orElseThrow(() -> new IllegalArgumentException(
                        "模型 " + meta.type().getSimpleName() + " 没有列 " + column
                      + io.jaravel.support.Arr.closest(column, meta.columnNames())
                              .map(c -> ",是否想用 " + c + " ?").orElse("")
                      + "\n  已声明的列:" + meta.columnNames()));
        return where(col, value);
    }

    /** 原生片段的逃生口 */
    public Query<M> whereRaw(String sql, Object... bindings) {
        builder.whereRaw(sql, bindings);
        return this;
    }

    public Query<M> when(boolean condition, Consumer<Query<M>> then) {
        if (condition) then.accept(this);
        return this;
    }

    public Query<M> tap(Consumer<Query<M>> action) {
        action.accept(this);
        return this;
    }

    /** 供嵌套分组使用的构造 */
    private Query(ModelMeta<M> meta, QueryBuilder nested) {
        this.meta = meta;
        this.builder = nested;
        this.globalScopesApplied = true;   // 嵌套分组不重复套全局作用域
    }

    // ================= 排序、分页 =================

    public Query<M> orderBy(Col<M, ?> col) {
        builder.orderBy(col.name());
        return this;
    }

    public Query<M> orderByDesc(Col<M, ?> col) {
        builder.orderByDesc(col.name());
        return this;
    }

    public Query<M> latest() {
        return orderByKeyDesc();
    }

    /** 按主键倒序。比 {@code created_at} 可靠 —— 同秒创建时顺序确定。 */
    public Query<M> orderByKeyDesc() {
        builder.orderByDesc(meta.keyName());
        return this;
    }

    public Query<M> orderByKey() {
        builder.orderBy(meta.keyName());
        return this;
    }

    public Query<M> limit(int n) {
        builder.limit(n);
        return this;
    }

    public Query<M> offset(int n) {
        builder.offset(n);
        return this;
    }

    public Query<M> forPage(int page, int perPage) {
        builder.forPage(page, perPage);
        return this;
    }

    // ================= 预加载 =================

    /**
     * 预加载关联,从根上解决 N+1。
     *
     * <p>{@code with(Post_.comments.then(Comment_.author))} 可嵌套,
     * 且**路径接错模型会编译失败**(V1 验证)。
     */
    @SafeVarargs
    public final Query<M> with(RelPath<M, ?>... paths) {
        eagerLoads.addAll(List.of(paths));
        return this;
    }

    public List<RelPath<M, ?>> eagerLoads() {
        return List.copyOf(eagerLoads);
    }

    // ================= 作用域 =================

    public Query<M> withoutGlobalScope(String name) {
        disabledGlobalScopes.add(name);
        return this;
    }

    public Query<M> withoutGlobalScopes() {
        allGlobalScopesDisabled = true;
        return this;
    }

    /** 由 APT 生成的 {@code <Name>Query} 在执行前调用,套上未被禁用的全局作用域 */
    protected void applyGlobalScopes() {
        // 默认实现为空:全局作用域的具体逻辑在生成的子类里
    }

    protected boolean isGlobalScopeEnabled(String name) {
        return !allGlobalScopesDisabled && !disabledGlobalScopes.contains(name);
    }

    // ================= 软删除 =================

    public Query<M> withTrashed() {
        trashed = Trashed.WITH;
        return this;
    }

    public Query<M> onlyTrashed() {
        trashed = Trashed.ONLY;
        return this;
    }

    private void applySoftDeleteFilter() {
        if (!meta.softDeletes()) return;
        switch (trashed) {
            case WITHOUT -> builder.whereNull(meta.softDeleteColumn());
            case ONLY -> builder.whereNotNull(meta.softDeleteColumn());
            case WITH -> { /* 不加条件 */ }
        }
    }

    // ================= 执行:读 =================

    /** 执行前的统一准备:全局作用域 + 软删除过滤。只做一次。 */
    private void prepare() {
        if (!globalScopesApplied) {
            globalScopesApplied = true;
            applyGlobalScopes();
            applySoftDeleteFilter();
        }
    }

    public Coll<M> get() {
        prepare();
        Coll<Row> rows = builder.get();
        List<M> models = new ArrayList<>(rows.size());
        for (Row row : rows) {
            models.add(IdentityMap.resolve(meta, row));
        }
        if (!eagerLoads.isEmpty() && !models.isEmpty()) {
            RelationLoader.load(meta, models, eagerLoads);
        }
        return Coll.wrap(List.copyOf(models));
    }

    public Optional<M> first() {
        return limit(1).get().first();
    }

    public M firstOrFail() {
        return first().orElseThrow(() -> new ModelNotFoundException(meta.type(), null));
    }

    /**
     * 按主键取一条。
     *
     * <p>走**预编译 SQL 的快路径**:按主键查询是 Web 应用里最高频的一条
     * 查询,而它的 SQL 形状是固定的。实测显示「构造 Query 对象 + 用 Grammar
     * 拼字符串」单次要 1.47 µs —— 占整条路径框架开销的三成,全花在
     * 每次重新拼一条一模一样的 SQL 上。Hibernate 对 {@code find(id)}
     * 同样是缓存 SQL,不缓存等于白送一个三成的差距。
     *
     * <p>只有「全新的查询 + 没有全局作用域」才走快路径;一旦加了条件、
     * 排序或自定义 select,就回落到通用路径。判断条件写得严,
     * 是因为快路径一旦在不该生效时生效,症状是**查询条件被静默丢弃**。
     */
    public Optional<M> find(Object key) {
        if (canUseKeyFastPath()) return findByKeyFast(key);
        return whereKey(key).first();
    }

    /** 快路径的适用条件 —— 任何一条不满足就回落 */
    private boolean canUseKeyFastPath() {
        return !globalScopesApplied
            && meta.globalScopes().isEmpty()
            && trashed == Trashed.WITHOUT
            && builder.state().isPristine();
    }

    private Optional<M> findByKeyFast(Object key) {
        String sql = keySelectSql(meta);
        Coll<Row> rows = Eloquent.connection().select(sql, List.of(key));

        Optional<M> model = rows.first().map(row -> IdentityMap.resolve(meta, row));
        model.ifPresent(m -> {
            if (!eagerLoads.isEmpty()) {
                RelationLoader.load(meta, List.of(m), eagerLoads);
            }
        });
        return model;
    }

    /**
     * 每个模型一条缓存的 SQL。
     *
     * <p>键是 {@code ModelMeta},而 meta 是每个模型一个单例,
     * 所以这张表的大小等于模型数量,不会无界增长。
     */
    private static final java.util.Map<ModelMeta<?>, String> KEY_SELECT_SQL =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String keySelectSql(ModelMeta<?> meta) {
        return KEY_SELECT_SQL.computeIfAbsent(meta, m -> {
            var grammar = Eloquent.connection().grammar();
            var columns = m.columns().stream()
                    .map(c -> grammar.wrap(c.name()))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("*");
            String base = "select " + columns + " from " + grammar.wrap(m.table())
                        + " where " + grammar.wrap(m.keyName()) + " = ?";
            // 软删除模型要带上「未删除」条件,否则快路径会把已删的行也取回来
            if (m.softDeletes()) {
                base += " and " + grammar.wrap(m.softDeleteColumn()) + " is null";
            }
            return base + " limit 1";
        });
    }

    public M findOrFail(Object key) {
        return find(key)
                .orElseThrow(() -> new ModelNotFoundException(meta.type(), key));
    }

    /** 取单列,避免把整行捞回来 */
    @SuppressWarnings("unchecked")
    public <V> Coll<V> pluck(Col<M, V> col) {
        prepare();
        // col.type() 是擦除后的 Class<?>(泛型字段没有类字面量),所以这里必须自己转
        return builder.select(col.name()).get()
                .map(r -> (V) r.get(col.name(), col.type()));
    }

    public <V> Optional<V> value(Col<M, V> col) {
        return pluck(col).first();
    }

    public boolean exists() {
        prepare();
        return builder.exists();
    }

    public boolean doesntExist() {
        return !exists();
    }

    // ================= 执行:聚合 =================

    public long count() {
        prepare();
        return builder.count();
    }

    public Optional<BigDecimal> sum(Col<M, ?> col) {
        prepare();
        return builder.sum(col.name());
    }

    public Optional<BigDecimal> avg(Col<M, ?> col) {
        prepare();
        return builder.avg(col.name());
    }

    public <V> Optional<Object> max(Col<M, V> col) {
        prepare();
        return builder.max(col.name());
    }

    public <V> Optional<Object> min(Col<M, V> col) {
        prepare();
        return builder.min(col.name());
    }

    // ================= 执行:写 =================

    /** 批量更新。**不触发模型事件** —— 与 Laravel 一致。 */
    public int update(Map<Col<M, ?>, Object> values) {
        prepare();
        Map<String, Object> byName = new LinkedHashMap<>();
        values.forEach((col, v) -> byName.put(col.name(), v));
        if (meta.timestamps()) {
            meta.column("updated_at").ifPresent(c -> byName.put(c.name(), java.time.Instant.now()));
        }
        return builder.update(byName);
    }

    /** 批量删除。软删除模型走 UPDATE,否则 DELETE。不触发模型事件。 */
    public int delete() {
        prepare();
        if (meta.softDeletes()) {
            return builder.update(Map.of(meta.softDeleteColumn(), java.time.Instant.now()));
        }
        return builder.delete();
    }

    public int forceDelete() {
        prepare();
        return builder.delete();
    }

    public int increment(Col<M, ?> col, Number amount) {
        prepare();
        return builder.increment(col.name(), amount);
    }

    public int decrement(Col<M, ?> col, Number amount) {
        prepare();
        return builder.decrement(col.name(), amount);
    }

    // ================= 批量遍历 =================

    /** 按主键游走的分块处理,不受并发插入影响 */
    public void chunkById(int size, Consumer<Coll<M>> handler) {
        prepare();
        builder.chunkById(size, meta.keyName(), rows -> {
            List<M> models = new ArrayList<>(rows.size());
            for (Row row : rows) models.add(IdentityMap.resolve(meta, row));
            if (!eagerLoads.isEmpty()) RelationLoader.load(meta, models, eagerLoads);
            handler.accept(Coll.wrap(List.copyOf(models)));
        });
    }

    // ================= 调试与逃生口 =================

    public String toSql() {
        prepare();
        return builder.toSql();
    }

    /** 绑定值内联的 SQL,便于复制到数据库客户端(仅调试) */
    public String toRawSql() {
        prepare();
        return builder.toRawSql();
    }

    /** 底层查询构造器。需要构造器表达不了的东西时用它。 */
    public QueryBuilder rawBuilder() {
        prepare();
        return builder;
    }

    public ModelMeta<M> meta() {
        return meta;
    }

    @Override public String toString() {
        return toSql();
    }
}
