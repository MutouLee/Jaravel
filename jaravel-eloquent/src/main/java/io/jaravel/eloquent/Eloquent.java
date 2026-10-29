package io.jaravel.eloquent;

import java.util.Optional;

import io.jaravel.core.Application;
import io.jaravel.database.Connection;

/**
 * Eloquent 的运行时接入点:拿连接、读开发期开关。
 *
 * <p>通过 {@link Application} 的容器解析,因此 {@code jaravel-eloquent}
 * 不需要 import Spring(ADR-001,由 ArchUnit 强制)。
 */
public final class Eloquent {

    private Eloquent() {}

    /** 测试或嵌入场景下可直接注入,免去启动容器 */
    private static volatile Connection override;

    /**
     * 是否禁止懒加载。
     *
     * <p>**默认跟随 debug 模式**(docs/06 §4.3):N+1 应当在开发期就无法通过,
     * 而不是上线后看慢查询日志。Laravel 8.43+ 有同等能力但默认关闭且常被忽略。
     */
    private static volatile Boolean preventLazyLoading;

    /** 单请求 SQL 条数告警阈值 */
    private static volatile int queryCountWarning = 50;

    public static Connection connection() {
        Connection c = override;
        if (c != null) return c;

        if (!Application.isBootstrapped()) {
            throw new IllegalStateException("""
                    Eloquent 还没有可用的数据库连接。
                      正常情况下 jaravel-spring-boot-starter 会在启动时装配好;
                      单元测试中请调用 Eloquent.useConnection(conn)。\
                    """);
        }
        return Application.make(Connection.class);
    }

    /** 测试用:直接指定连接 */
    public static void useConnection(Connection c) {
        override = c;
    }

    public static void reset() {
        override = null;
        preventLazyLoading = null;
        queryCountWarning = 50;
    }

    /** 懒加载是否被禁止。未显式设置时跟随 debug 模式。 */
    public static boolean isLazyLoadingPrevented() {
        Boolean explicit = preventLazyLoading;
        if (explicit != null) return explicit;
        return Application.isBootstrapped() && Application.isDebug();
    }

    public static void preventLazyLoading(boolean prevent) {
        preventLazyLoading = prevent;
    }

    /** 临时允许懒加载,用于确实需要的那一小段代码 */
    public static <T> T allowingLazyLoading(java.util.function.Supplier<T> body) {
        Boolean saved = preventLazyLoading;
        preventLazyLoading = false;
        try {
            return body.get();
        } finally {
            preventLazyLoading = saved;
        }
    }

    public static int queryCountWarningThreshold() {
        return queryCountWarning;
    }

    public static void queryCountWarningThreshold(int n) {
        queryCountWarning = n;
    }

    public static Optional<Connection> tryConnection() {
        try {
            return Optional.of(connection());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
