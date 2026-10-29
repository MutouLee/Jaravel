package io.jaravel.eloquent.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.eloquent.Eloquent;
import io.jaravel.eloquent.meta.ModelRegistry;

/**
 * 真库测试的脚手架。
 *
 * <p>**用真 SQLite 而不是 mock**:v0.1 的经验是 mock 掉 JDBC 的测试
 * 全绿而真跑起来挂在类型映射上(SQLite 的 Integer/Long、布尔存成 0/1)。
 * 这类问题只有真执行才会暴露。
 */
abstract class EloquentTestBase {

    private Connection jdbc;
    private io.jaravel.database.Connection connection;

    @BeforeEach
    void setUpDatabase() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            for (String ddl : SCHEMA) st.execute(ddl);
        }

        // 同一个内存库必须共享同一条物理连接 —— SQLite 的 :memory: 是「每连接一个库」
        DataSource shared = new SingleConnectionDataSource(jdbc);
        connection = new JdbcConnection(shared, new SqliteGrammar());
        connection.enableQueryLog();
        Eloquent.useConnection(connection);
    }

    @AfterEach
    void tearDownDatabase() throws SQLException {
        Eloquent.useConnection(null);
        ModelRegistry.clear();
        if (jdbc != null) jdbc.close();
    }

    /** 本次测试执行过的 SQL —— 用来断言「一层一条查询」 */
    protected List<String> sql() {
        return connection.queryLog().stream()
                .map(io.jaravel.database.Connection.QueryLog::sql)
                .toList();
    }

    protected void clearSql() {
        connection.flushQueryLog();
    }

    protected long selectCount() {
        return sql().stream().filter(s -> s.trim().toLowerCase().startsWith("select")).count();
    }

    protected void exec(String sql) throws SQLException {
        try (Statement st = jdbc.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * 把一条已有的 JDBC 连接包成 DataSource。
     *
     * <p>不用连接池:{@code jdbc:sqlite::memory:} 每开一条连接就是一个**新的空库**,
     * 池化会让建好表的那条连接和查询用的那条不是同一个库。
     */
    private record SingleConnectionDataSource(Connection delegate) implements DataSource {
        @Override public Connection getConnection() { return nonClosing(delegate); }
        @Override public Connection getConnection(String u, String p) { return nonClosing(delegate); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { return iface.cast(delegate); }
        @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(delegate); }

        /**
         * 吞掉 {@code close()} 的代理。
         *
         * <p>{@code JdbcConnection} 每条语句都 try-with-resources 地拿连接再关掉
         * (对连接池是正确的),而这里只有一条物理连接,第一次关就把整个内存库
         * 销毁了。所以必须让 close 变成空操作,由 {@code @AfterEach} 统一关。
         */
        private static Connection nonClosing(Connection real) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) return null;
                        if (method.getName().equals("isClosed")) return false;
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    private static final String[] SCHEMA = {
        """
        create table countries (
            id integer primary key autoincrement,
            name text not null
        )
        """,
        """
        create table users (
            id integer primary key autoincrement,
            name text not null,
            email text,
            password text,
            country_id integer,
            roles text,
            created_at text,
            updated_at text,
            deleted_at text
        )
        """,
        """
        create table profiles (
            id integer primary key autoincrement,
            user_id integer not null,
            bio text
        )
        """,
        """
        create table posts (
            id integer primary key autoincrement,
            title text not null,
            body text,
            user_id integer,
            published integer not null default 0,
            views integer not null default 0,
            created_at text,
            updated_at text
        )
        """,
        """
        create table comments (
            id integer primary key autoincrement,
            post_id integer,
            user_id integer,
            body text,
            approved integer not null default 0,
            created_at text,
            updated_at text
        )
        """,
        """
        create table tags (
            id integer primary key autoincrement,
            name text not null
        )
        """,
        """
        create table post_tag (
            post_id integer not null,
            tag_id integer not null
        )
        """,
        """
        create table drafts (
            id integer primary key autoincrement,
            title text not null,
            visible integer not null default 0,
            deleted_at text
        )
        """,
        """
        create table images (
            id integer primary key autoincrement,
            url text not null,
            imageable_id integer,
            imageable_type text
        )
        """
    };
}
