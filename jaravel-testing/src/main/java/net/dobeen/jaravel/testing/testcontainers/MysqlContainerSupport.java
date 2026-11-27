package net.dobeen.jaravel.testing.testcontainers;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 只在 {@code @UseDatabase(MYSQL)} 时加载,避免拖入其它数据库模块 */
final class MysqlContainerSupport {

    private MysqlContainerSupport() {
    }

    static JdbcDatabaseContainer<?> create(String image) {
        return new MySQLContainer<>(DockerImageName.parse(image))
                .withDatabaseName("jaravel_test")
                .withUsername("jaravel")
                .withPassword("secret");
    }
}
