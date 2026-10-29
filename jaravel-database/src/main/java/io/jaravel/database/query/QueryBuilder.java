package io.jaravel.database.query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import io.jaravel.database.Connection;
import io.jaravel.database.Row;
import io.jaravel.database.grammar.Grammar;
import io.jaravel.database.query.QueryState.Bool;
import io.jaravel.database.query.QueryState.Op;
import io.jaravel.database.query.QueryState.Where;
import io.jaravel.support.Coll;

/**
 * 流式查询构造器。表达力对标 Laravel 的 {@code Illuminate\Database\Query\Builder}。
 *
 * <p>所有**值**走占位符绑定;标识符走 {@link Grammar#wrap} 的白名单校验。
 * 唯一允许拼接 SQL 的地方是显式的 {@code raw} 系列(方法名即警示)。
 */
public class QueryBuilder {

    protected final QueryState state = new QueryState();
    protected final Connection connection;
    protected final Grammar grammar;
    /** 当前的布尔连接词,orWhere 会临时切到 OR */
    private Bool nextBool = Bool.AND;

    public QueryBuilder(Connection connection, Grammar grammar) {
        this.connection = connection;
        this.grammar = grammar;
    }

    // ================= from / select =================

    public QueryBuilder from(String table) {
        state.table = table;
        return this;
    }

    public QueryBuilder from(String table, String alias) {
        state.table = table;
        state.tableAlias = alias;
        return this;
    }

    public QueryBuilder select(String... columns) {
        state.columns.addAll(List.of(columns));
        return this;
    }

    /** 原生 select 片段。<b>不做转义</b>,内容由调用方负责。 */
    public QueryBuilder selectRaw(String expression) {
        state.rawColumns.add(expression);
        return this;
    }

    public QueryBuilder distinct() {
        state.distinct = true;
        return this;
    }

    // ================= where =================

    public QueryBuilder where(String column, Object value) {
        return where(column, Op.EQ, value);
    }

    /** 字符串运算符形式,保留 Laravel 的书写习惯 */
    public QueryBuilder where(String column, String operator, Object value) {
        return where(column, Op.of(operator), value);
    }

    public QueryBuilder where(String column, Op op, Object value) {
        // where("x", null) 几乎总是想表达 is null,而 "x = null" 恒为 unknown
        if (value == null) {
            return op == Op.NEQ ? whereNotNull(column) : whereNull(column);
        }
        state.wheres.add(new Where.Basic(column, op, value, consumeBool()));
        return this;
    }

    public QueryBuilder orWhere(String column, Object value) {
        nextBool = Bool.OR;
        return where(column, value);
    }

    public QueryBuilder orWhere(String column, String operator, Object value) {
        nextBool = Bool.OR;
        return where(column, operator, value);
    }

    public QueryBuilder whereNull(String column) {
        state.wheres.add(new Where.Null(column, false, consumeBool()));
        return this;
    }

    public QueryBuilder whereNotNull(String column) {
        state.wheres.add(new Where.Null(column, true, consumeBool()));
        return this;
    }

    public QueryBuilder orWhereNull(String column) {
        nextBool = Bool.OR;
        return whereNull(column);
    }

    public QueryBuilder whereIn(String column, java.util.Collection<?> values) {
        state.wheres.add(new Where.In(column, new ArrayList<>(values), false, consumeBool()));
        return this;
    }

    public QueryBuilder whereIn(String column, Object... values) {
        return whereIn(column, Arrays.asList(values));
    }

    public QueryBuilder whereNotIn(String column, java.util.Collection<?> values) {
        state.wheres.add(new Where.In(column, new ArrayList<>(values), true, consumeBool()));
        return this;
    }

    public QueryBuilder whereBetween(String column, Object low, Object high) {
        state.wheres.add(new Where.Between(column, low, high, false, consumeBool()));
        return this;
    }

    public QueryBuilder whereNotBetween(String column, Object low, Object high) {
        state.wheres.add(new Where.Between(column, low, high, true, consumeBool()));
        return this;
    }

    public QueryBuilder whereColumn(String left, String right) {
        return whereColumn(left, Op.EQ, right);
    }

    public QueryBuilder whereColumn(String left, Op op, String right) {
        state.wheres.add(new Where.Columns(left, op, right, consumeBool()));
        return this;
    }

    public QueryBuilder whereLike(String column, String pattern) {
        return where(column, Op.LIKE, pattern);
    }

    /** 大小写不敏感的 like。非 PostgreSQL 方言会降级为 like。 */
    public QueryBuilder whereILike(String column, String pattern) {
        return where(column, Op.ILIKE, pattern);
    }

    /** 原生 where 片段。绑定值仍走占位符,只有 SQL 片段是原样的。 */
    public QueryBuilder whereRaw(String sql, Object... bindings) {
        state.wheres.add(new Where.Raw(sql, Arrays.asList(bindings), consumeBool()));
        return this;
    }

    /**
     * 嵌套分组:{@code where(q -> q.where("a",1).orWhere("b",2))} → {@code and (a = 1 or b = 2)}
     *
     * <p>没有这个,{@code where(a).orWhere(b).where(c)} 的优先级会与直觉不符 ——
     * 这是 SQL 构造器最常见的 bug 来源。
     */
    public QueryBuilder where(Consumer<QueryBuilder> group) {
        var nested = newQuery();
        group.accept(nested);
        state.wheres.add(new Where.Nested(nested.state.wheres, consumeBool()));
        return this;
    }

    public QueryBuilder orWhere(Consumer<QueryBuilder> group) {
        nextBool = Bool.OR;
        return where(group);
    }

    public QueryBuilder whereExists(Consumer<QueryBuilder> sub) {
        var q = newQuery();
        sub.accept(q);
        state.wheres.add(new Where.Exists(q.state, false, consumeBool()));
        return this;
    }

    public QueryBuilder whereNotExists(Consumer<QueryBuilder> sub) {
        var q = newQuery();
        sub.accept(q);
        state.wheres.add(new Where.Exists(q.state, true, consumeBool()));
        return this;
    }

    // ================= 条件式构造 =================

    /** 让「有筛选条件时才加 where」不打断链式 */
    public QueryBuilder when(boolean condition, Consumer<QueryBuilder> then) {
        if (condition) then.accept(this);
        return this;
    }

    public QueryBuilder unless(boolean condition, Consumer<QueryBuilder> then) {
        return when(!condition, then);
    }

    /** 调试钩子,返回自身 */
    public QueryBuilder tap(Consumer<QueryBuilder> action) {
        action.accept(this);
        return this;
    }

    // ================= join =================

    public QueryBuilder join(String table, String first, String operator, String second) {
        state.joins.add(new QueryState.Join("inner", table, first, Op.of(operator), second));
        return this;
    }

    public QueryBuilder leftJoin(String table, String first, String operator, String second) {
        state.joins.add(new QueryState.Join("left", table, first, Op.of(operator), second));
        return this;
    }

    public QueryBuilder rightJoin(String table, String first, String operator, String second) {
        state.joins.add(new QueryState.Join("right", table, first, Op.of(operator), second));
        return this;
    }

    // ================= 分组、排序、分页 =================

    public QueryBuilder groupBy(String... columns) {
        state.groups.addAll(List.of(columns));
        return this;
    }

    public QueryBuilder having(String column, String operator, Object value) {
        state.havings.add(new Where.Basic(column, Op.of(operator), value, Bool.AND));
        return this;
    }

    public QueryBuilder havingRaw(String sql, Object... bindings) {
        state.havings.add(new Where.Raw(sql, Arrays.asList(bindings), Bool.AND));
        return this;
    }

    public QueryBuilder orderBy(String column) {
        return orderBy(column, false);
    }

    public QueryBuilder orderBy(String column, boolean descending) {
        state.orders.add(new QueryState.Order(column, descending));
        return this;
    }

    public QueryBuilder orderByDesc(String column) {
        return orderBy(column, true);
    }

    public QueryBuilder latest() { return orderByDesc("created_at"); }
    public QueryBuilder oldest() { return orderBy("created_at"); }

    /** 清空排序。分页统计总数时需要去序以提速。 */
    public QueryBuilder reorder() {
        state.orders.clear();
        state.orderCleared = true;
        return this;
    }

    public QueryBuilder limit(int n) {
        state.limit = n;
        return this;
    }

    public QueryBuilder offset(int n) {
        state.offset = n;
        return this;
    }

    /** 1 起的页码 */
    public QueryBuilder forPage(int page, int perPage) {
        return offset(Math.max(0, (page - 1) * perPage)).limit(perPage);
    }

    // ================= 编译(不执行) =================

    public Grammar.Compiled compile() {
        return grammar.compileSelect(state);
    }

    public String toSql() {
        return compile().sql();
    }

    /** 绑定值内联的 SQL。<b>仅供调试</b>,便于复制到数据库客户端执行。 */
    public String toRawSql() {
        return grammar.inlineBindings(compile());
    }

    public List<Object> bindings() {
        return compile().bindings();
    }

    // ================= 执行:读 =================

    public Coll<Row> get() {
        var c = compile();
        return connection.select(c.sql(), c.bindings());
    }

    public <T> Coll<T> get(Class<T> type) {
        var c = compile();
        return connection.select(c.sql(), c.bindings(), type);
    }

    public Optional<Row> first() {
        return limit(1).get().first();
    }

    public <T> Optional<T> first(Class<T> type) {
        return limit(1).get(type).first();
    }

    public Optional<Row> find(Object id) {
        return where("id", id).first();
    }

    /** 取单列的首个值 */
    public <T> Optional<T> value(String column, Class<T> type) {
        return select(column).first().map(r -> r.get(column, type));
    }

    /** 取单列的全部值 */
    public <T> Coll<T> pluck(String column, Class<T> type) {
        return select(column).get().map(r -> r.get(column, type));
    }

    public boolean exists() {
        return count() > 0;
    }

    public boolean doesntExist() {
        return !exists();
    }

    // ================= 执行:聚合 =================

    public long count() {
        return count("*");
    }

    public long count(String column) {
        return aggregate("count", column).map(v -> ((Number) v).longValue()).orElse(0L);
    }

    public Optional<BigDecimal> sum(String column) {
        return aggregate("sum", column).map(QueryBuilder::toDecimal);
    }

    public Optional<BigDecimal> avg(String column) {
        return aggregate("avg", column).map(QueryBuilder::toDecimal);
    }

    public Optional<Object> max(String column) { return aggregate("max", column); }
    public Optional<Object> min(String column) { return aggregate("min", column); }

    private Optional<Object> aggregate(String function, String column) {
        // 聚合时临时去掉排序与分页 —— order by 对聚合无意义且某些方言会报错
        var saved = state.copy();
        state.aggregate = new QueryState.Aggregate(function, column);
        state.orders.clear();
        state.limit = null;
        state.offset = null;
        try {
            var c = compile();
            return connection.select(c.sql(), c.bindings()).first()
                    .map(r -> r.get("aggregate"));
        } finally {
            restore(saved);
        }
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(String.valueOf(v));
    }

    private void restore(QueryState saved) {
        state.aggregate = saved.aggregate;
        state.orders = saved.orders;
        state.limit = saved.limit;
        state.offset = saved.offset;
        state.columns = saved.columns;
    }

    // ================= 执行:写 =================

    public int insert(Map<String, Object> row) {
        return insert(List.of(row));
    }

    public int insert(List<Map<String, Object>> rows) {
        var c = grammar.compileInsert(state, rows);
        return connection.update(c.sql(), c.bindings());
    }

    /** 插入并返回自增主键 */
    public Optional<Long> insertGetId(Map<String, Object> row) {
        var c = grammar.compileInsert(state, List.of(row));
        return connection.insertGetId(c.sql(), c.bindings());
    }

    public int update(Map<String, Object> values) {
        guardUnconditional("update");
        var c = grammar.compileUpdate(state, values);
        return connection.update(c.sql(), c.bindings());
    }

    public int delete() {
        guardUnconditional("delete");
        var c = grammar.compileDelete(state);
        return connection.update(c.sql(), c.bindings());
    }

    public int increment(String column) { return increment(column, 1); }

    public int increment(String column, Number amount) {
        guardUnconditional("increment");
        var c = grammar.compileIncrement(state, column, amount, false);
        return connection.update(c.sql(), c.bindings());
    }

    public int decrement(String column) { return decrement(column, 1); }

    public int decrement(String column, Number amount) {
        guardUnconditional("decrement");
        var c = grammar.compileIncrement(state, column, amount, true);
        return connection.update(c.sql(), c.bindings());
    }

    public void truncate() {
        connection.statement(grammar.compileTruncate(state));
    }

    /** 明确绕过无条件保护的全表更新/删除 */
    public QueryBuilder allowUnconditional() {
        this.unconditionalAllowed = true;
        return this;
    }

    private boolean unconditionalAllowed = false;

    /**
     * 无 where 的 update/delete 会动全表,几乎总是 bug。
     * 默认拦住并要求显式表态 —— 这是对 Laravel 的改进。
     */
    private void guardUnconditional(String operation) {
        if (state.hasConditions() || unconditionalAllowed) return;
        throw new UnconditionalWriteException(
                operation + " 没有任何 where 条件,将影响表 " + state.table + " 的全部行。"
              + "\n  这通常是 bug(忘了加条件)。"
              + "\n  若确实想操作全表,显式表态:"
              + "\n    DB.table(\"" + state.table + "\").allowUnconditional()." + operation + "(...)");
    }

    // ================= 批量遍历 =================

    /**
     * 按主键游走的分块处理。
     *
     * <p>比 offset 分块更可靠:offset 在并发插入/删除时会漏行或重复,
     * 按主键游走不受影响。
     */
    public void chunkById(int size, Consumer<Coll<Row>> handler) {
        chunkById(size, "id", handler);
    }

    public void chunkById(int size, String keyColumn, Consumer<Coll<Row>> handler) {
        Object lastId = null;
        while (true) {
            var q = cloneForChunk();
            if (lastId != null) q.where(keyColumn, Op.GT, lastId);
            var batch = q.reorder().orderBy(keyColumn).limit(size).get();
            if (batch.isEmpty()) break;

            handler.accept(batch);
            lastId = batch.last().orElseThrow().get(keyColumn);
            if (batch.size() < size) break;
        }
    }

    private QueryBuilder cloneForChunk() {
        var q = newQuery();
        q.state.table = state.table;
        q.state.tableAlias = state.tableAlias;
        q.state.columns = new ArrayList<>(state.columns);
        q.state.rawColumns = new ArrayList<>(state.rawColumns);
        q.state.wheres = new ArrayList<>(state.wheres);
        q.state.joins = new ArrayList<>(state.joins);
        return q;
    }

    // ================= 内部 =================

    protected QueryBuilder newQuery() {
        return new QueryBuilder(connection, grammar);
    }

    private Bool consumeBool() {
        Bool b = nextBool;
        nextBool = Bool.AND;
        return b;
    }

    public QueryState state() { return state; }

    @Override public String toString() {
        return toSql();
    }

    public static class UnconditionalWriteException extends IllegalStateException {
        public UnconditionalWriteException(String message) { super(message); }
    }
}
