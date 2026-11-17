package io.jaravel.testing.testcontainers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

import io.jaravel.testing.UseDatabase;

/**
 * 启动容器并把连接信息放进测试上下文(docs/14 §2.2)。
 *
 * <p>{@code equals/hashCode} 必须按 (kind, image) 实现:Spring Test
 * 用 customizer 集合作为上下文缓存键的一部分;实现错了会复用错误的
 * 数据源,或者让每次运行都新建上下文。
 */
final class TestDatabaseCustomizer implements ContextCustomizer {

    static final String SOURCE_NAME = "jaravelTestcontainers";

    private final UseDatabase.Kind kind;
    private final String image;

    TestDatabaseCustomizer(UseDatabase.Kind kind, String image) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.image = image == null ? "" : image;
    }

    @Override
    public void customizeContext(ConfigurableApplicationContext context,
                                 MergedContextConfiguration config) {
        // 测试显式给了数据源就尊重它 —— 那是测试作者自己管理的库
        for (String property : config.getPropertySourceProperties()) {
            if (property.startsWith("spring.datasource.url=")
                    || property.startsWith("spring.datasource.jdbc-url=")
                    || property.startsWith("spring.datasource.hikari.jdbc-url=")) {
                return;
            }
        }

        TestDatabases.Info info = TestDatabases.start(kind, image);

        var properties = new LinkedHashMap<String, Object>();
        properties.put("spring.datasource.url", info.jdbcUrl());
        properties.put("spring.datasource.username", info.username());
        properties.put("spring.datasource.password", info.password());
        properties.put("spring.datasource.driver-class-name", info.driverClassName());

        // 只换 URL 不换 Grammar,会得到「MySQL 上跑 SQLite 语法」。
        // JaravelAutoConfiguration 会把这两个 Spring 属性覆盖进 config/*.yml。
        properties.put("jaravel.database.default", info.configName());
        boolean migrateConfigured = false;
        for (String property : config.getPropertySourceProperties()) {
            if (property.startsWith("jaravel.migrate.on-boot=")) {
                migrateConfigured = true;
                break;
            }
        }
        if (!migrateConfigured
                && context.getEnvironment().getProperty("jaravel.migrate.on-boot") == null) {
            properties.put("jaravel.migrate.on-boot", "true");
        }

        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(SOURCE_NAME, Map.copyOf(properties)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TestDatabaseCustomizer that
                && kind == that.kind
                && image.equals(that.image);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, image);
    }

    @Override
    public String toString() {
        return "UseDatabase[" + kind + (image.isBlank() ? "" : ", " + image) + "]";
    }
}
