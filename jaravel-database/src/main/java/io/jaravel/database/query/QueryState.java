package io.jaravel.database.query;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询的结构化中间表示。{@link io.jaravel.database.grammar.Grammar} 把它编译成 SQL。
 *
 * <p>可变对象,由 {@link QueryBuilder} 逐步填充 —— 流式 API 的每一步都在改这个状态。
 * 编译成 SQL 的那一刻才产生字符串。
 */
public final class QueryState {

    public enum Op {
        EQ("="), NEQ("!="), GT(">"), GTE(">="), LT("<"), LTE("<="),
        LIKE("like"), NOT_LIKE("not like"), ILIKE("ilike");

        private final String sql;
        Op(String sql) { this.sql = sql; }
        public String sql() { return sql; }

        /** 兼容字符串写法:{@code where("age", ">", 18)} */
        public static Op of(String symbol) {
            String s = symbol.trim().toLowerCase();
            return switch (s) {
                case "=", "==" -> EQ;
                case "!=", "<>" -> NEQ;
                case ">" -> GT;   case ">=" -> GTE;
                case "<" -> LT;   case "<=" -> LTE;
                case "like" -> LIKE;
                case "not like" -> NOT_LIKE;
                case "ilike" -> ILIKE;
                default -> throw new IllegalArgumentException(
                        "未知的比较运算符 \"" + symbol + "\"。"
                      + "支持:= != <> > >= < <= like \"not like\" ilike");
            };
        }
    }

    public enum Bool { AND, OR }

    // ---------- Where 子句的各种形态 ----------

    public sealed interface Where {
        Bool bool();

        record Basic(String column, Op op, Object value, Bool bool) implements Where {}
        record Null(String column, boolean negated, Bool bool) implements Where {}
        record In(String column, List<Object> values, boolean negated, Bool bool) implements Where {}
        record Between(String column, Object low, Object high, boolean negated, Bool bool) implements Where {}
        record Columns(String left, Op op, String right, Bool bool) implements Where {}
        /** 嵌套分组:AND (a = 1 OR b = 2) */
        record Nested(List<Where> wheres, Bool bool) implements Where {}
        /** 原生片段。唯一允许拼接 SQL 的地方,绑定值仍走占位符。 */
        record Raw(String sql, List<Object> bindings, Bool bool) implements Where {}
        record Exists(QueryState subquery, boolean negated, Bool bool) implements Where {}
    }

    public record Join(String type, String table, String first, Op op, String second) {}

    public record Order(String column, boolean descending) {}

    public record Aggregate(String function, String column) {}

    // ---------- 状态 ----------

    public String table;
    public String tableAlias;
    public List<String> columns = new ArrayList<>();
    public List<String> rawColumns = new ArrayList<>();
    public boolean distinct;
    public List<Where> wheres = new ArrayList<>();
    public List<Join> joins = new ArrayList<>();
    public List<String> groups = new ArrayList<>();
    public List<Where> havings = new ArrayList<>();
    public List<Order> orders = new ArrayList<>();
    public Integer limit;
    public Integer offset;
    public Aggregate aggregate;
    /** 排序被 reorder() 清空的标记,用于分页时临时去序 */
    public boolean orderCleared;

    public QueryState copy() {
        var c = new QueryState();
        c.table = table;
        c.tableAlias = tableAlias;
        c.columns = new ArrayList<>(columns);
        c.rawColumns = new ArrayList<>(rawColumns);
        c.distinct = distinct;
        c.wheres = new ArrayList<>(wheres);
        c.joins = new ArrayList<>(joins);
        c.groups = new ArrayList<>(groups);
        c.havings = new ArrayList<>(havings);
        c.orders = new ArrayList<>(orders);
        c.limit = limit;
        c.offset = offset;
        c.aggregate = aggregate;
        c.orderCleared = orderCleared;
        return c;
    }

    /**
     * 是否是一条「还没加任何东西」的查询。
     *
     * <p>按主键查询的快路径靠它判断能否生效。列得全而不是只看 wheres ——
     * 漏掉任何一项(比如 orders)都会让快路径静默丢弃用户的意图,
     * 那种 bug 查起来极痛苦。
     */
    public boolean isPristine() {
        return columns.isEmpty()
            && rawColumns.isEmpty()
            && !distinct
            && wheres.isEmpty()
            && joins.isEmpty()
            && groups.isEmpty()
            && havings.isEmpty()
            && orders.isEmpty()
            && limit == null
            && offset == null
            && aggregate == null
            && !orderCleared;
    }

    public boolean hasConditions() {
        return !wheres.isEmpty();
    }
}
