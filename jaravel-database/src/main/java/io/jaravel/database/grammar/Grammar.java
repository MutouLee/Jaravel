package io.jaravel.database.grammar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.jaravel.database.query.QueryState;
import io.jaravel.database.query.QueryState.Where;

/**
 * SQL 方言基类。
 *
 * <p>分层照搬 Laravel 的 {@code Illuminate\Database\Query\Grammars}:
 * 抽象基类持有 select 各组件的**固定拼接顺序**,各方言只覆写差异部分
 * (标识符包裹符、LIMIT 语法、UPSERT 语法)。这套设计经过十年多方言验证,
 * 是最省事也最可靠的选择(ADR-004)。
 *
 * <p><b>安全约束:</b>所有**值**一律走占位符绑定,唯一例外是显式的
 * {@code raw} 系列。标识符(表名/列名)走 {@link #wrap},
 * 并用白名单正则校验,拒绝可疑字符。
 */
public abstract class Grammar {

    /** 编译结果:SQL + 按位置排列的绑定值 */
    public record Compiled(String sql, List<Object> bindings) {
        @Override public String toString() { return sql + "  -- bindings: " + bindings; }
    }

    /**
     * 标识符白名单。允许:字母数字下划线、点号(表.列)、星号、
     * 空格与 as(别名)、-> 与 ->>(JSON 路径)。
     *
     * <p>这道校验是防 SQL 注入的最后一层:列名无法参数化,只能靠白名单。
     */
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("^[A-Za-z0-9_.*\\s]+(->>?[A-Za-z0-9_.$\\[\\]]+)?$");

    // ---------- 方言差异点 ----------

    /** 标识符包裹符,MySQL 是反引号,PostgreSQL/SQLite 是双引号 */
    protected abstract char openQuote();
    protected abstract char closeQuote();

    public abstract String name();

    /** PostgreSQL 支持 ILIKE,其余方言需降级 */
    protected boolean supportsIlike() { return false; }

    // ---------- select 的组件顺序(与 Laravel 一致) ----------

    public Compiled compileSelect(QueryState q) {
        requireTable(q, "SELECT");
        var bindings = new ArrayList<Object>();
        var sql = new StringBuilder();

        sql.append("select ");
        if (q.distinct) sql.append("distinct ");
        sql.append(compileColumns(q));
        sql.append(" from ").append(compileFrom(q));

        compileJoins(q, sql);
        appendWheres("where", q.wheres, sql, bindings);
        compileGroups(q, sql);
        appendWheres("having", q.havings, sql, bindings);
        compileOrders(q, sql);
        compileLimitOffset(q, sql, bindings);

        return new Compiled(sql.toString(), bindings);
    }

    protected String compileColumns(QueryState q) {
        if (q.aggregate != null) {
            String col = "*".equals(q.aggregate.column()) ? "*" : wrap(q.aggregate.column());
            return q.aggregate.function() + "(" + (q.distinct && !"*".equals(col) ? "distinct " : "") + col
                 + ") as " + wrap("aggregate");
        }
        var parts = new ArrayList<String>();
        for (String c : q.columns) parts.add(wrapColumnWithAlias(c));
        parts.addAll(q.rawColumns);
        return parts.isEmpty() ? "*" : String.join(", ", parts);
    }

    protected String compileFrom(QueryState q) {
        return q.tableAlias == null ? wrap(q.table)
                                    : wrap(q.table) + " as " + wrap(q.tableAlias);
    }

    protected void compileJoins(QueryState q, StringBuilder sql) {
        for (var j : q.joins) {
            sql.append(' ').append(j.type()).append(" join ").append(wrap(j.table()))
               .append(" on ").append(wrap(j.first())).append(' ')
               .append(j.op().sql()).append(' ').append(wrap(j.second()));
        }
    }

    protected void compileGroups(QueryState q, StringBuilder sql) {
        if (q.groups.isEmpty()) return;
        sql.append(" group by ");
        var parts = new ArrayList<String>();
        for (String g : q.groups) parts.add(wrap(g));
        sql.append(String.join(", ", parts));
    }

    protected void compileOrders(QueryState q, StringBuilder sql) {
        if (q.orders.isEmpty()) return;
        sql.append(" order by ");
        var parts = new ArrayList<String>();
        for (var o : q.orders) parts.add(wrap(o.column()) + (o.descending() ? " desc" : " asc"));
        sql.append(String.join(", ", parts));
    }

    /** MySQL/PostgreSQL/SQLite 都支持 limit/offset;SQL Server 需覆写 */
    protected void compileLimitOffset(QueryState q, StringBuilder sql, List<Object> bindings) {
        if (q.limit != null) {
            sql.append(" limit ?");
            bindings.add(q.limit);
        }
        if (q.offset != null) {
            // 多数方言要求 offset 必须跟在 limit 之后
            if (q.limit == null) {
                sql.append(" limit ?");
                bindings.add(Integer.MAX_VALUE);
            }
            sql.append(" offset ?");
            bindings.add(q.offset);
        }
    }

    // ---------- where / having ----------

    protected void appendWheres(String keyword, List<Where> wheres,
                                StringBuilder sql, List<Object> bindings) {
        if (wheres.isEmpty()) return;
        sql.append(' ').append(keyword).append(' ');
        boolean having = "having".equals(keyword);
        sql.append(compileWhereList(wheres, bindings, having));
    }

    protected String compileWhereList(List<Where> wheres, List<Object> bindings) {
        return compileWhereList(wheres, bindings, false);
    }

    protected String compileWhereList(List<Where> wheres, List<Object> bindings, boolean allowAggregate) {
        var sb = new StringBuilder();
        for (int i = 0; i < wheres.size(); i++) {
            Where w = wheres.get(i);
            if (i > 0) sb.append(w.bool() == QueryState.Bool.OR ? " or " : " and ");
            sb.append(compileWhere(w, bindings, allowAggregate));
        }
        return sb.toString();
    }

    protected String compileWhere(Where w, List<Object> bindings) {
        return compileWhere(w, bindings, false);
    }

    protected String compileWhere(Where w, List<Object> bindings, boolean allowAggregate) {
        return switch (w) {
            case Where.Basic b -> {
                bindings.add(b.value());
                String col = allowAggregate ? wrapAggregateOrColumn(b.column()) : wrap(b.column());
                yield col + " " + operatorFor(b.op()) + " ?";
            }
            case Where.Null n ->
                wrap(n.column()) + (n.negated() ? " is not null" : " is null");

            case Where.In in -> {
                if (in.values().isEmpty()) {
                    // 空集合:IN () 是语法错误。空 IN 恒假,空 NOT IN 恒真。
                    yield in.negated() ? "1 = 1" : "0 = 1";
                }
                bindings.addAll(in.values());
                yield wrap(in.column()) + (in.negated() ? " not in (" : " in (")
                    + String.join(", ", java.util.Collections.nCopies(in.values().size(), "?")) + ")";
            }
            case Where.Between bt -> {
                bindings.add(bt.low());
                bindings.add(bt.high());
                yield wrap(bt.column()) + (bt.negated() ? " not between ? and ?" : " between ? and ?");
            }
            case Where.Columns c ->
                wrap(c.left()) + " " + operatorFor(c.op()) + " " + wrap(c.right());

            case Where.Nested n -> "(" + compileWhereList(n.wheres(), bindings) + ")";

            case Where.Raw r -> {
                bindings.addAll(r.bindings());
                yield r.sql();
            }
            case Where.Exists ex -> {
                var sub = compileSelect(ex.subquery());
                bindings.addAll(sub.bindings());
                yield (ex.negated() ? "not exists (" : "exists (") + sub.sql() + ")";
            }
        };
    }

    /** ILIKE 在不支持的方言上降级为 LIKE(PostgreSQL 外都不支持) */
    protected String operatorFor(QueryState.Op op) {
        if (op == QueryState.Op.ILIKE && !supportsIlike()) return "like";
        return op.sql();
    }

    // ---------- 写入 ----------

    public Compiled compileInsert(QueryState q, List<Map<String, Object>> rows) {
        requireTable(q, "INSERT");
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("insert 需要至少一行数据");
        }
        // 以第一行的键为准,后续行必须一致 —— 否则列与值会错位
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        var bindings = new ArrayList<Object>();
        var values = new ArrayList<String>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (!row.keySet().equals(rows.get(0).keySet())) {
                throw new IllegalArgumentException(
                        "批量 insert 的各行必须有相同的列。"
                      + "\n  第 1 行:" + rows.get(0).keySet()
                      + "\n  第 " + (i + 1) + " 行:" + row.keySet()
                      + "\n  修复:给缺失的列显式传 null。");
            }
            for (String c : cols) bindings.add(row.get(c));
            values.add("(" + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")");
        }

        var wrapped = new ArrayList<String>();
        for (String c : cols) wrapped.add(wrap(c));

        String sql = "insert into " + wrap(q.table)
                   + " (" + String.join(", ", wrapped) + ") values " + String.join(", ", values);
        return new Compiled(sql, bindings);
    }

    public Compiled compileUpdate(QueryState q, Map<String, Object> values) {
        requireTable(q, "UPDATE");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("update 需要至少一个要更新的列");
        }
        var bindings = new ArrayList<Object>();
        var sets = new ArrayList<String>();
        values.forEach((k, v) -> {
            sets.add(wrap(k) + " = ?");
            bindings.add(v);
        });

        var sql = new StringBuilder("update ").append(wrap(q.table))
                .append(" set ").append(String.join(", ", sets));
        appendWheres("where", q.wheres, sql, bindings);
        return new Compiled(sql.toString(), bindings);
    }

    public Compiled compileDelete(QueryState q) {
        requireTable(q, "DELETE");
        var bindings = new ArrayList<Object>();
        var sql = new StringBuilder("delete from ").append(wrap(q.table));
        appendWheres("where", q.wheres, sql, bindings);
        return new Compiled(sql.toString(), bindings);
    }

    /** 列值自增/自减。单独编译是为了避免「读-改-写」的竞态。 */
    public Compiled compileIncrement(QueryState q, String column, Number amount, boolean decrement) {
        requireTable(q, "UPDATE");
        var bindings = new ArrayList<Object>();
        var sql = new StringBuilder("update ").append(wrap(q.table))
                .append(" set ").append(wrap(column)).append(" = ").append(wrap(column))
                .append(decrement ? " - ?" : " + ?");
        bindings.add(amount);
        appendWheres("where", q.wheres, sql, bindings);
        return new Compiled(sql.toString(), bindings);
    }

    public String compileTruncate(QueryState q) {
        requireTable(q, "TRUNCATE");
        return "truncate table " + wrap(q.table);
    }

    // ---------- 标识符处理 ----------

    /**
     * 包裹标识符,支持 {@code table.column}、{@code *}、{@code col as alias}。
     *
     * <p>不在白名单内的标识符**直接报错**,不尝试转义 ——
     * 这类输入几乎总是 bug 或注入尝试。
     */
    public String wrap(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        String id = identifier.trim();
        if ("*".equals(id)) return "*";

        if (!SAFE_IDENTIFIER.matcher(id).matches()) {
            throw new UnsafeIdentifierException(
                    "标识符 \"" + identifier + "\" 含不允许的字符。"
                  + "\n  表名与列名无法参数化,因此只接受:字母数字、下划线、点号、"
                  + "星号、as 别名、JSON 路径(->)。"
                  + "\n  若确需原生 SQL,请用 selectRaw / whereRaw 并自行确保安全。");
        }

        // col as alias
        int asIdx = indexOfIgnoreCase(id, " as ");
        if (asIdx > 0) {
            return wrap(id.substring(0, asIdx)) + " as " + wrap(id.substring(asIdx + 4));
        }

        // JSON 路径交给方言处理
        if (id.contains("->")) return wrapJsonPath(id);

        // table.column
        if (id.contains(".")) {
            var parts = new ArrayList<String>();
            for (String seg : id.split("\\.")) parts.add("*".equals(seg) ? "*" : quote(seg));
            return String.join(".", parts);
        }
        return quote(id);
    }

    private String wrapColumnWithAlias(String column) {
        return wrap(column);
    }

    /**
     * having 子句里允许聚合函数包裹,如 {@code count(*)} / {@code sum(amount)}。
     *
     * <p>这是对标识符白名单的**有界**扩展:只接受 5 个已知聚合函数,
     * 且其参数仍必须通过 {@link #wrap} 的白名单。既覆盖
     * {@code having("count(*)", ">", 2)} 这个极常见写法,又不开注入的口子。
     * 更复杂的表达式请用 {@code havingRaw}。
     */
    protected String wrapAggregateOrColumn(String expression) {
        String e = expression.trim();
        var m = AGGREGATE_CALL.matcher(e);
        if (m.matches()) {
            String fn = m.group(1).toLowerCase();
            String inner = m.group(2).trim();
            return fn + "(" + ("*".equals(inner) ? "*" : wrap(inner)) + ")";
        }
        return wrap(e);
    }

    private static final Pattern AGGREGATE_CALL =
            Pattern.compile("(?i)^(count|sum|avg|min|max)[ ]*[(]([^()]*)[)]$");

    protected String quote(String segment) {
        String s = segment.trim();
        // 包裹符本身必须被转义,否则是注入入口
        if (s.indexOf(openQuote()) >= 0 || s.indexOf(closeQuote()) >= 0) {
            throw new UnsafeIdentifierException("标识符 \"" + segment + "\" 含包裹符,已拒绝");
        }
        return openQuote() + s + closeQuote();
    }

    /** 默认不支持 JSON 路径,由支持的方言覆写 */
    protected String wrapJsonPath(String id) {
        throw new UnsupportedOperationException(
                name() + " 方言不支持 JSON 路径表达式(" + id + ")");
    }

    private static int indexOfIgnoreCase(String s, String sub) {
        return s.toLowerCase().indexOf(sub.toLowerCase());
    }

    private static void requireTable(QueryState q, String operation) {
        if (q.table == null || q.table.isBlank()) {
            throw new IllegalStateException(
                    operation + " 缺少表名。先调用 DB.table(\"...\")。");
        }
    }

    /** 把绑定值内联进 SQL,**仅用于调试输出**(toRawSql / 日志) */
    public String inlineBindings(Compiled compiled) {
        var sb = new StringBuilder();
        String sql = compiled.sql();
        int bindingIndex = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && bindingIndex < compiled.bindings().size()) {
                sb.append(literal(compiled.bindings().get(bindingIndex++)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String literal(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    public static class UnsafeIdentifierException extends IllegalArgumentException {
        public UnsafeIdentifierException(String message) { super(message); }
    }
}
