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
import io.jaravel.database.query.QueryState;
import io.jaravel.database.query.QueryState.Op;
import io.jaravel.eloquent.Eloquent;
import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.ModelNotFoundException;
import io.jaravel.eloquent.meta.Col;
import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.RelPath;
import io.jaravel.eloquent.relations.RelationLoader;
import io.jaravel.support.Coll;
import io.jaravel.support.Cursor;
import io.jaravel.support.Cursored;
import io.jaravel.support.LazyColl;
import io.jaravel.support.Paginated;

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

    // ================= 执行:分页 =================

    /**
     * 带总数的分页(docs/12 §2.1)。
     *
     * <p>页码从 {@code Paginated} 的 resolver 取 —— HTTP 场景下那就是
     * {@code ?page=N};不在请求里(队列任务、命令)时是第 1 页。
     * 理由写在 {@link Paginated#pageResolver} 上。
     */
    public Paginated<M> paginate(int perPage) {
        return paginate(perPage, Paginated.resolveCurrentPage());
    }

    /**
     * 指定页码的分页。
     *
     * <h2>★ 两次查询的顺序与 count 为什么不受 limit 影响</h2>
     * <p>先 {@code count()} 再取这一页。{@code count()} 走的是
     * {@code QueryBuilder.aggregate},而它**临时摘掉 order / limit / offset**
     * 再编译 —— 所以即便调用方已经写了 {@code .limit(3)},
     * 总数仍然是「符合条件的全部」。
     *
     * <p>顺序反过来也能工作,但先 count 让「页码超出范围」这件事
     * 在拿到空列表之前就有数:总数是 0 和总数是 150 而你在第 99 页,
     * 对调用方是两件不同的事。
     *
     * <h2>⚠ 它不保证两次查询之间数据没变</h2>
     * <p>count 与取页之间有人插了一行,这一页就可能与总数不自洽
     * (Laravel 也一样)。要绝对一致得把两次查询放进一个事务,
     * 而那会为了一个分页接口拿住一个事务 —— 不值得。
     * 真的需要稳定翻页时用游标分页(docs/12 §2.1 的第三种,还没实现)。
     */
    public Paginated<M> paginate(int perPage, int page) {
        long total = count();
        if (total == 0) {
            return withResolvedLinks(Paginated.empty(perPage));
        }

        List<M> items = forPage(page, perPage).get().toList();
        return withResolvedLinks(Paginated.of(items, page, perPage, total));
    }

    /** 不查总数的分页(docs/12 §2.1)—— 页码同样来自 resolver */
    public Paginated<M> simplePaginate(int perPage) {
        return simplePaginate(perPage, Paginated.resolveCurrentPage());
    }

    /**
     * 不查总数的分页。
     *
     * <h2>★ 多取一条来判断「还有没有下一页」</h2>
     * <p>这是不查总数的全部办法:要 {@code perPage + 1} 条,
     * 取到了第 {@code perPage + 1} 条就说明还有下一页,然后把它丢掉。
     *
     * <p>为什么值得:{@code count(*)} 在大表上是一次全表扫描,
     * 而「无限滚动」这种界面根本不显示总页数 ——
     * 为一个不显示的数字付一次全扫是纯粹的浪费。
     */
    public Paginated<M> simplePaginate(int perPage, int page) {
        if (perPage <= 0) {
            throw new IllegalArgumentException("perPage 必须大于 0,收到 " + perPage);
        }
        List<M> fetched = forPage(page, perPage + 1).get().toMutableList();

        boolean hasMore = fetched.size() > perPage;
        if (hasMore) fetched.remove(fetched.size() - 1);

        return withResolvedLinks(Paginated.simple(fetched, page, perPage, hasMore));
    }

    /**
     * 把当前请求的路径与查询参数装进分页器。
     *
     * <p>同样走 resolver:{@code jaravel-eloquent} 不认识 {@code Request}
     * (而且按 ADR-001 也不该认识)。
     */
    private <T> Paginated<T> withResolvedLinks(Paginated<T> page) {
        return page.withPath(Paginated.resolvePath()).withQuery(Paginated.resolveQuery());
    }

    // ================= 执行:游标分页 =================

    /**
     * 游标分页(docs/12 §2.1 的第三种)—— 大表与无限滚动用这个。
     *
     * <pre>{@code
     * Cursored<Post> page = Post.query().orderByDesc(Post_.createdAt).cursorPaginate(15);
     * }</pre>
     *
     * <p>游标从 resolver 取({@code ?cursor=…});不在请求里时是第一页。
     */
    public Cursored<M> cursorPaginate(int perPage) {
        return cursorPaginate(perPage, Cursored.resolveCursor());
    }

    /**
     * 指定游标的分页。
     *
     * <h2>★ 排序必须是确定的,所以主键会被追加进去</h2>
     * <p>游标分页的翻页条件是「排序列的字典序比较」。如果排序列上有**重复值**
     * (比如按 {@code created_at} 排,而同一秒里有三条),那么
     * {@code where created_at > '…'} 会把那一秒里剩下的两条**整个跳过**;
     * 反过来用 {@code >=} 又会把它们重复给出。
     *
     * <p>所以这里总是把主键追加为最后一级排序 —— 它唯一,于是
     * (排序列…, 主键)这个组合唯一,字典序比较就不会跳行也不会重复。
     * 一个排序都没写时就只按主键排。
     *
     * <p>这不是防御性编程:「列表里偶尔少一条」是游标分页最常见的 bug,
     * 而它只在有重复排序值的真实数据上出现 —— 开发期的几条测试数据上
     * 完全看不见。
     *
     * @param encodedCursor 上一次给出的游标;null 表示第一页
     */
    public Cursored<M> cursorPaginate(int perPage, String encodedCursor) {
        if (perPage <= 0) {
            throw new IllegalArgumentException("perPage 必须大于 0,收到 " + perPage);
        }
        prepare();

        List<QueryState.Order> orders = effectiveOrders();
        Cursor cursor = encodedCursor == null ? null : Cursor.decode(encodedCursor);
        boolean backwards = cursor != null && !cursor.pointsToNext();

        if (cursor != null) {
            applyCursorConstraint(cursor, orders, backwards);
        }
        applyOrders(orders, backwards);

        // 多取一条探路 —— 与 simplePaginate 同一个办法
        List<M> fetched = new ArrayList<>(limit(perPage + 1).get().toList());
        boolean hasMore = fetched.size() > perPage;
        if (hasMore) fetched.remove(fetched.size() - 1);

        if (backwards) {
            // 往回翻时查询是**反着排**的,结果要翻回来才是用户看到的顺序
            java.util.Collections.reverse(fetched);
        }

        return withResolvedCursorLinks(buildPage(fetched, perPage, orders, cursor, hasMore, backwards));
    }

    /**
     * 实际用于游标的排序:用户写的 + 追加主键。
     *
     * <p>主键已经在里面(用户自己按它排了)时不重复追加 ——
     * 重复的 ORDER BY 列虽然无害,但会让生成的 SQL 变难读,
     * 而那是排查「为什么翻页不对」时第一个要看的东西。
     */
    private List<QueryState.Order> effectiveOrders() {
        var orders = new ArrayList<>(builder.orders());
        boolean hasKey = orders.stream().anyMatch(o -> o.column().equals(meta.keyName()));
        if (!hasKey) {
            // 跟随最后一级的方向:按 created_at 倒序时,同一秒内也该倒序,
            // 否则「同值内的顺序」与整体方向相反,读起来很怪
            boolean descending = !orders.isEmpty() && orders.get(orders.size() - 1).descending();
            orders.add(new QueryState.Order(meta.keyName(), descending));
        }
        return orders;
    }

    /**
     * 字典序比较条件。
     *
     * <p>对 {@code order by a asc, b desc} 与游标 {@code (a0, b0)},往后翻是:
     * <pre>
     * (a > a0) or (a = a0 and b &lt; b0)
     * </pre>
     * 往前翻把每个比较反过来。
     *
     * <p>⚠ 不能简单写成 {@code (a, b) > (a0, b0)}:SQL 的行值比较
     * ({@code ROW(...)})不是所有方言都支持,而且混用升降序时它的语义
     * 根本不对 —— 行值比较要求所有列同向。
     */
    private void applyCursorConstraint(Cursor cursor, List<QueryState.Order> orders,
                                       boolean backwards) {
        builder.where(outer -> {
            for (int level = 0; level < orders.size(); level++) {
                final int depth = level;
                outer.orWhere(branch -> {
                    // 前 depth 级相等
                    for (int i = 0; i < depth; i++) {
                        var order = orders.get(i);
                        branch.where(order.column(), cursor.valueOf(order.column()));
                    }
                    // 第 depth 级严格大于/小于
                    var pivot = orders.get(depth);
                    boolean ascending = pivot.descending() == backwards;
                    branch.where(pivot.column(), ascending ? ">" : "<",
                            cursor.valueOf(pivot.column()));
                });
            }
        });
    }

    private void applyOrders(List<QueryState.Order> orders, boolean backwards) {
        builder.reorder();
        for (QueryState.Order order : orders) {
            boolean descending = order.descending() != backwards;
            if (descending) {
                builder.orderByDesc(order.column());
            } else {
                builder.orderBy(order.column());
            }
        }
    }

    /**
     * 本页两端的游标。
     *
     * <p>「有没有上一页」这件事**无法从本页数据判断** —— 只能看
     * 「我是不是带着游标来的」。第一页(没带游标)一定没有上一页;
     * 带着游标来的一定有(即便往回翻之后是空的)。
     */
    private Cursored<M> buildPage(List<M> items, int perPage, List<QueryState.Order> orders,
                                  Cursor incoming, boolean hasMore, boolean backwards) {
        if (items.isEmpty()) {
            return Cursored.empty(perPage);
        }

        Cursor next = null;
        Cursor previous = null;

        boolean moreAhead = backwards ? incoming != null : hasMore;
        boolean moreBehind = backwards ? hasMore : incoming != null;

        if (moreAhead) next = cursorFor(items.get(items.size() - 1), orders, true);
        if (moreBehind) previous = cursorFor(items.get(0), orders, false);

        return Cursored.of(items, perPage, next, previous);
    }

    /** 某一行在排序列上的值 —— 用生成的访问器读,不反射 */
    private Cursor cursorFor(M model, List<QueryState.Order> orders, boolean pointsToNext) {
        var position = new java.util.LinkedHashMap<String, Object>();
        for (QueryState.Order order : orders) {
            Col<M, ?> col = meta.column(order.column()).orElseThrow(
                    () -> new IllegalStateException("""
                            按 %s 排序,但模型 %s 没有这一列 —— 游标分页读不到它的值。
                              出路:按模型声明过的列排序,或者改用 paginate(…)。"""
                            .formatted(order.column(), meta.type().getSimpleName())));
            position.put(order.column(), col.get(model));
        }
        return Cursor.at(position, pointsToNext);
    }

    private Cursored<M> withResolvedCursorLinks(Cursored<M> page) {
        return page.withPath(Paginated.resolvePath()).withQuery(Paginated.resolveQuery());
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

    /**
     * 惰性遍历(docs/06 §3 的「大数据集」)。
     *
     * <p>按主键分块,每块做一次预加载 —— 于是 {@code lazy()} 不会退化成
     * N+1:每块的用户查询 + 每块每个预加载关系各一条。内存上限是块大小。
     */
    public LazyColl<M> lazy() {
        return lazy(QueryBuilder.DEFAULT_LAZY_SIZE);
    }

    public LazyColl<M> lazy(int size) {
        prepare();
        return builder.lazyChunks(size, meta.keyName()).flatMap(rows -> {
            List<M> models = new ArrayList<>(rows.size());
            for (Row row : rows) models.add(IdentityMap.resolve(meta, row));
            if (!eagerLoads.isEmpty()) RelationLoader.load(meta, models, eagerLoads);
            return models;
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
