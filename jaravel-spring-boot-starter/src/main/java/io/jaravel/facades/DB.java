package io.jaravel.facades;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import io.jaravel.core.facade.Facade;
import io.jaravel.database.Connection;
import io.jaravel.database.Row;
import io.jaravel.database.query.QueryBuilder;
import io.jaravel.support.Coll;

/**
 * 数据库门面。
 *
 * <p>手写静态转发而非动态代理(docs/02 §2.2):保证 IDE 可跳转可重构、
 * 零反射、GraalVM native 零配置。
 */
public final class DB extends Facade {

    private DB() {}

    private static Connection conn() {
        return resolve(Connection.class);
    }

    // ---------- 查询构造器入口 ----------

    public static QueryBuilder table(String table) {
        return conn().table(table);
    }

    public static QueryBuilder table(String table, String alias) {
        return conn().table(table, alias);
    }

    // ---------- 原生 SQL(逃生口) ----------

    public static Coll<Row> select(String sql, Object... bindings) {
        return conn().select(sql, List.of(bindings));
    }

    public static <T> Coll<T> select(String sql, Class<T> type, Object... bindings) {
        return conn().select(sql, List.of(bindings), type);
    }

    public static int update(String sql, Object... bindings) {
        return conn().update(sql, List.of(bindings));
    }

    public static void statement(String sql) {
        conn().statement(sql);
    }

    // ---------- 事务 ----------

    public static <T> T transaction(Supplier<T> body) {
        return conn().transaction(body);
    }

    public static void transaction(Runnable body) {
        conn().transaction(body);
    }

    /**
     * 事务提交后执行。不在事务中则立即执行。
     *
     * <p>消除「事务未提交就发了消息 / 投了队列任务」这个经典 bug。
     */
    public static void afterCommit(Runnable action) {
        conn().afterCommit(action);
    }

    public static boolean inTransaction() {
        return conn().inTransaction();
    }

    // ---------- 查询日志 ----------

    public static void enableQueryLog()  { conn().enableQueryLog(); }
    public static void disableQueryLog() { conn().disableQueryLog(); }
    public static List<Connection.QueryLog> queryLog() { return conn().queryLog(); }
    public static void flushQueryLog()   { conn().flushQueryLog(); }

    /** 底层连接的逃生口 */
    public static Optional<Object> raw() { return conn().raw(); }

    public static Connection connection() { return conn(); }
}
