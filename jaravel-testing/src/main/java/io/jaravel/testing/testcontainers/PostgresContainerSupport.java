package io.jaravel.testing.testcontainers;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 只在 {@code @UseDatabase(POSTGRESQL)} 时加载 */
final class PostgresContainerSupport {

    private PostgresContainerSupport() {
    }

    static JdbcDatabaseContainer<?> create(String image) {
        return new PostgreSQLContainer<>(DockerImageName.parse(image))
                .withDatabaseName("jaravel_test")
                .withUsername("jaravel")
                .withPassword("secret");
    }
}
