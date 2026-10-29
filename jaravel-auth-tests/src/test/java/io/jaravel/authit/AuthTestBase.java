package io.jaravel.authit;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.jaravel.auth.Auth;
import io.jaravel.auth.AuthManager;
import io.jaravel.auth.StatefulGuard;
import io.jaravel.auth.guard.SessionGuard;
import io.jaravel.auth.hash.Hash;
import io.jaravel.auth.hash.HashManager;
import io.jaravel.auth.provider.EloquentUserProvider;
import io.jaravel.core.context.ExecutionScope;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.eloquent.Eloquent;
import io.jaravel.eloquent.meta.ModelRegistry;
import io.jaravel.session.SessionData;
import io.jaravel.authit.models.Admin;
import io.jaravel.authit.models.User;

/**
 * 认证测试的脚手架:真 SQLite + 真会话作用域。
 *
 * <p>**不 mock Guard 或 Session** —— 认证的 bug 几乎都在「几个组件接起来」
 * 的缝里(会话没换 id、remember 令牌没轮换、guard 实例跨请求复用),
 * mock 掉任何一环就测不到。
 */
abstract class AuthTestBase {

    protected java.sql.Connection jdbc;
    protected AuthManager auth;
    protected HashManager hasher;

    @BeforeEach
    void setUpAuth() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = jdbc.createStatement()) {
            st.execute("""
                    create table users (
                        id integer primary key autoincrement,
                        email text unique,
                        password text,
                        remember_token text,
                        email_verified_at text,
                        status text default 'active',
                        created_at text,
                        updated_at text
                    )
                    """);
            st.execute("""
                    create table articles (
                        id integer primary key autoincrement,
                        title text,
                        body text,
                        author_id integer
                    )
                    """);
            st.execute("""
                    create table admins (
                        id integer primary key autoincrement,
                        email text unique,
                        password text
                    )
                    """);
        }

        var connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        Eloquent.useConnection(connection);

        // 迭代次数调低 —— 否则每个登录断言要 200ms
        hasher = HashManager.forTests();
        Hash.use(hasher);

        auth = new AuthManager()
                .registerProvider(new EloquentUserProvider<>("users", User.class, hasher))
                .registerProvider(new EloquentUserProvider<>("admins", Admin.class, hasher))
                .registerGuard("web", "users", p -> new SessionGuard("web", p))
                .registerGuard("admin", "admins", p -> new SessionGuard("admin", p))
                .defaultGuard("web");
        Auth.use(auth);
    }

    @AfterEach
    void tearDownAuth() throws SQLException {
        Eloquent.useConnection(null);
        ModelRegistry.clear();
        if (jdbc != null) jdbc.close();
    }

    // ================= 夹具 =================

    protected User createUser(String email, String password) {
        User user = new User();
        user.email = email;
        user.password = hasher.make(password);
        user.status = "active";
        user.save();
        return user;
    }

    protected Admin createAdmin(String email, String password) {
        Admin admin = new Admin();
        admin.email = email;
        admin.password = hasher.make(password);
        admin.save();
        return admin;
    }

    /**
     * 在一个带会话的「请求」里执行。
     *
     * <p>每次调用都是一个**新的执行作用域** —— 这样 guard 实例不会跨
     * 「请求」复用,正是要测的那件事。
     */
    protected <T> T inRequest(SessionData session, Callable<T> body) {
        try {
            return ExecutionScope.runIn("test-http", () -> {
                ExecutionScope.current().scoped(SessionData.class, () -> session);
                return body.call();
            });
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e);
        }
    }

    protected void inRequest(SessionData session, Runnable body) {
        inRequest(session, () -> {
            body.run();
            return null;
        });
    }

    /** 新开一个会话(模拟全新的浏览器) */
    protected SessionData newSession() {
        return new SessionData(SessionData.generateId());
    }

    protected static Map<String, Object> credentials(String email, String password) {
        return Map.of("email", email, "password", password);
    }

    protected static DataSource nonClosing(java.sql.Connection real) {
        java.sql.Connection proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (p, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("isClosed")) return false;
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new DataSource() {
            @Override public java.sql.Connection getConnection() { return proxy; }
            @Override public java.sql.Connection getConnection(String u, String p) { return proxy; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() { return Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) { return iface.cast(proxy); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
