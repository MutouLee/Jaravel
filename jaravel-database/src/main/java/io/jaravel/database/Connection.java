package io.jaravel.database;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import io.jaravel.database.grammar.Grammar;
import io.jaravel.database.query.QueryBuilder;
import io.jaravel.support.Coll;

/**
 * 数据库连接抽象。**不含任何 Spring 类型**(ADR-001)——
 * starter 提供基于 {@code DataSource} 的实现。
 */
public interface Connection {

    Grammar grammar();

    // ---------- 查询 ----------

    Coll<Row> select(String sql, List<Object> bindings);

    <T> Coll<T> select(String sql, List<Object> bindings, Class<T> type);

    default Coll<Row> select(String sql, Object... bindings) {
        return select(sql, List.of(bindings));
    }

    // ---------- 写入 ----------

    /** 返回受影响行数 */
    int update(String sql, List<Object> bindings);

    default int update(String sql, Object... bindings) {
        return update(sql, List.of(bindings));
    }

    /** 插入并返回自增主键 */
    Optional<Long> insertGetId(String sql, List<Object> bindings);

    /** DDL 等无返回值的语句 */
    void statement(String sql);

    // ---------- 事务 ----------

    /**
     * 闭包式事务:正常返回则提交,抛异常则回滚。
     *
     * <p>这是**推荐用法** —— 手动 begin/commit 很容易在某个早返回分支上漏掉提交。
     */
    <T> T transaction(Supplier<T> body);

    default void transaction(Runnable body) {
        transaction(() -> { body.run(); return null; });
    }

    /**
     * 事务提交后执行。
     *
     * <p>解决一个经典 bug:在事务内 dispatch 队列任务,worker 可能在事务提交前
     * 就捞到任务并查不到数据。Jaravel 把它做成一等公民 API。
     */
    void afterCommit(Runnable action);

    boolean inTransaction();

    // ---------- 查询构造器入口 ----------

    default QueryBuilder table(String table) {
        return new QueryBuilder(this, grammar()).from(table);
    }

    default QueryBuilder table(String table, String alias) {
        return new QueryBuilder(this, grammar()).from(table, alias);
    }

    default QueryBuilder query() {
        return new QueryBuilder(this, grammar());
    }

    // ---------- 查询日志(开发期的 SQL 面板与 N+1 检测依赖它) ----------

    void enableQueryLog();
    void disableQueryLog();
    List<QueryLog> queryLog();
    void flushQueryLog();

    /** 一条已执行的查询 */
    record QueryLog(String sql, List<Object> bindings, long micros, String caller) {
        @Override public String toString() {
            return String.format("%.2fms  %s  %s", micros / 1000.0, sql, bindings);
        }
    }

    /** 底层原生连接的逃生口 */
    Optional<Object> raw();

    class QueryFailedException extends RuntimeException {
        private final String sql;
        private final List<Object> bindings;

        public QueryFailedException(String sql, List<Object> bindings, Throwable cause) {
            super(buildMessage(sql, bindings, cause), cause);
            this.sql = sql;
            this.bindings = bindings;
        }

        private static String buildMessage(String sql, List<Object> bindings, Throwable cause) {
            return (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage())
                 + "\n  SQL: " + sql
                 + "\n  绑定: " + bindings;
        }

        public String sql() { return sql; }
        public List<Object> bindings() { return bindings; }
    }
}
