package io.jaravel.testing.testcontainers;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/** 只在 {@code @UseDatabase(MARIADB)} 时加载 */
final class MariaContainerSupport {

    private MariaContainerSupport() {
    }

    static JdbcDatabaseContainer<?> create(String image) {
        return new MariaDBContainer<>(DockerImageName.parse(image))
                .withDatabaseName("jaravel_test")
                .withUsername("jaravel")
                .withPassword("secret");
    }
}
