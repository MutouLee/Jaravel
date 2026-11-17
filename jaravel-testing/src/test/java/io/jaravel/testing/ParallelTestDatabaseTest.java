package io.jaravel.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.support.TestPropertySourceUtils;

/** 并行开关与 Environment 改写(docs/14 §6.3、ADR-017) */
class ParallelTestDatabaseTest {

    private final ParallelTestDatabase postProcessor = new ParallelTestDatabase();

    @AfterEach
    void clearProperties() {
        System.clearProperty("jaravel.test.parallel");
        System.clearProperty("jaravel.test.migrate");
        System.clearProperty("jaravel.migrate.on-boot");
    }

    private StandardEnvironment environmentWith(String key, String value) {
        var environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of(key, value)));
        return environment;
    }

    private void process(StandardEnvironment environment) {
        postProcessor.postProcessEnvironment(environment,
                new SpringApplication(JaravelTestApplication.class));
    }

    @Test
    @DisplayName("没有开关就是完全 no-op —— 默认测试不受影响")
    void disabledByDefault() {
        var environment = environmentWith("spring.datasource.url",
                "jdbc:sqlite:target/parallel-disabled/app.db");

        process(environment);

        assertThat(environment.getPropertySources()
                .get(ParallelTestDatabase.SOURCE_NAME)).isNull();
        assertThat(System.getProperty("jaravel.migrate.on-boot")).isNull();
    }

    @Test
    @DisplayName("开关打开:文件 SQLite 派生到 target/<pid>/,并强制每 fork 迁移")
    void rewritesRelativeSqlite() {
        System.setProperty("jaravel.test.parallel", "true");
        String url = "jdbc:sqlite:target/parallel-epp-" + System.nanoTime() + "/app.db";
        var environment = environmentWith("spring.datasource.url", url);

        process(environment);

        assertThat(environment.getPropertySources()
                .get(ParallelTestDatabase.SOURCE_NAME)).isNotNull();
        assertThat(environment.getProperty("spring.datasource.url"))
                .contains("target/jaravel-test-db/"
                        + ProcessHandle.current().pid() + "/")
                .endsWith("/app.db");
        assertThat(System.getProperty("jaravel.migrate.on-boot"))
                .as("派生库是空的,每个 fork 必须自己迁移")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("内存 SQLite 不改 URL,但仍需要迁移")
    void memoryIsNotRewrittenButStillMigrates() {
        System.setProperty("jaravel.test.parallel", "true");
        var environment = environmentWith("spring.datasource.url",
                "jdbc:sqlite:file:mem?mode=memory&cache=shared");

        process(environment);

        assertThat(environment.getPropertySources()
                .get(ParallelTestDatabase.SOURCE_NAME)).isNull();
        assertThat(System.getProperty("jaravel.migrate.on-boot")).isEqualTo("true");
    }

    @Test
    @DisplayName("测试内联指定的数据源优先 —— 那是测试作者自己管理的库")
    void inlinedTestUrlIsRespected() {
        System.setProperty("jaravel.test.parallel", "true");
        var environment = environmentWith("spring.datasource.url",
                "jdbc:sqlite:target/parallel-inlined/app.db");
        environment.getPropertySources().addFirst(new MapPropertySource(
                TestPropertySourceUtils.INLINED_PROPERTIES_PROPERTY_SOURCE_NAME,
                Map.of("spring.datasource.url", "jdbc:sqlite::memory:")));

        process(environment);

        assertThat(environment.getPropertySources()
                .get(ParallelTestDatabase.SOURCE_NAME)).isNull();
    }

    @Test
    @DisplayName("Hikari 的 jdbc-url 也要识别")
    void hikariJdbcUrl() {
        System.setProperty("jaravel.test.parallel", "true");
        var environment = environmentWith("spring.datasource.hikari.jdbc-url",
                "jdbc:sqlite:target/parallel-hikari/app.db");

        process(environment);

        assertThat(environment.getProperty("spring.datasource.hikari.jdbc-url"))
                .contains("target/jaravel-test-db/"
                        + ProcessHandle.current().pid() + "/");
    }

    @Test
    @DisplayName("不支持的 JDBC 方言直接报错,而不是共享库")
    void unsupportedDialectFails() {
        System.setProperty("jaravel.test.parallel", "true");
        var environment = environmentWith("spring.datasource.url", "jdbc:h2:mem:test");

        assertThatThrownBy(() -> process(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法隔离");
    }

    @Test
    @DisplayName("-Djaravel.test.migrate=false 可以关掉强制迁移")
    void migrationCanBeDisabled() {
        System.setProperty("jaravel.test.parallel", "true");
        System.setProperty("jaravel.test.migrate", "false");
        var environment = environmentWith("spring.datasource.url",
                "jdbc:sqlite:target/parallel-nomigrate/app.db");

        process(environment);

        assertThat(System.getProperty("jaravel.migrate.on-boot")).isNull();
    }

    @Test
    @DisplayName("没有数据源时什么都不做")
    void noDatasourceIsNoOp() {
        System.setProperty("jaravel.test.parallel", "true");
        var environment = new StandardEnvironment();

        process(environment);

        assertThat(environment.getPropertySources()
                .get(ParallelTestDatabase.SOURCE_NAME)).isNull();
        assertThat(System.getProperty("jaravel.migrate.on-boot")).isNull();
    }
}
