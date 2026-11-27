package net.dobeen.jaravel.testing.testcontainers;

import java.util.HashMap;
import java.util.Map;

import org.testcontainers.containers.JdbcDatabaseContainer;

import net.dobeen.jaravel.testing.TestcontainersSupport;
import net.dobeen.jaravel.testing.UseDatabase;

/**
 * 每个 JVM、每种 (kind, image) 一个容器(docs/14 §2.2)。
 *
 * <p>为什么按 JVM 而不是按测试类:Spring Test 的上下文缓存与容器生命周期
 * 都按 JVM 走;一个类一个容器会让 `@UseDatabase` 的测试启动成本翻倍,
 * 而且没有额外隔离收益(测试之间本来就有事务回滚或手动清理)。
 *
 * <p>容器在 JVM 退出时统一 stop;Testcontainers 的 Ryuk 是第二道保险。
 */
final class TestDatabases {

    private static final Map<String, JdbcDatabaseContainer<?>> CONTAINERS = new HashMap<>();
    private static boolean hookRegistered;

    private TestDatabases() {
    }

    record Info(String jdbcUrl, String username, String password,
                String driverClassName, String configName) {
    }

    static synchronized Info start(UseDatabase.Kind kind, String image) {
        TestcontainersSupport.ensureApiVersion();

        String resolvedImage = image == null || image.isBlank() ? kind.defaultImage() : image;
        String key = kind + "|" + resolvedImage;

        JdbcDatabaseContainer<?> container = CONTAINERS.get(key);
        if (container == null) {
            container = create(kind, resolvedImage);
            try {
                container.start();
            } catch (RuntimeException e) {
                throw new IllegalStateException("""
                        @UseDatabase(%s) 需要 Docker:容器启动失败。
                          镜像:%s
                          原因:%s
                          出路:启动 Docker;或移除 @UseDatabase;或用 image= 指定可用镜像。"""
                        .formatted(kind, resolvedImage, e.getMessage()), e);
            }
            CONTAINERS.put(key, container);
            registerShutdownHook();
        }

        return new Info(container.getJdbcUrl(), container.getUsername(), container.getPassword(),
                container.getDriverClassName(), kind.configName());
    }

    private static JdbcDatabaseContainer<?> create(UseDatabase.Kind kind, String image) {
        // 每个 kind 一个 helper 类:只用 MySQL 的应用不必在 classpath 上
        // 同时有 PostgreSQL/MariaDB 的 Testcontainers 模块。
        return switch (kind) {
            case MYSQL -> MysqlContainerSupport.create(image);
            case POSTGRESQL -> PostgresContainerSupport.create(image);
            case MARIADB -> MariaContainerSupport.create(image);
        };
    }

    private static void registerShutdownHook() {
        if (hookRegistered) return;
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (JdbcDatabaseContainer<?> container : CONTAINERS.values()) {
                try {
                    container.stop();
                } catch (Exception ignored) {
                    // JVM 正在退出;Ryuk 会兜底
                }
            }
        }, "jaravel-testcontainers-stop"));
    }
}
