package net.dobeen.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import net.dobeen.jaravel.core.config.MapConfigRepository;

/** Spring 属性对 config/*.yml 的显式覆盖(docs/14 §2.2) */
class ConfigOverridesTest {

    private Map<String, Object> tree() {
        var tree = new LinkedHashMap<String, Object>();
        var database = new LinkedHashMap<String, Object>();
        database.put("default", "sqlite");
        tree.put("database", database);

        var migrate = new LinkedHashMap<String, Object>();
        migrate.put("on-boot", false);
        var jaravel = new LinkedHashMap<String, Object>();
        jaravel.put("migrate", migrate);
        tree.put("jaravel", jaravel);
        return tree;
    }

    @Test
    @DisplayName("jaravel.database.default / migrate.on-boot 覆盖 yml,并参与占位符解析")
    void springPropertiesOverrideTheTree() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "jaravel.database.default", "mysql",
                "jaravel.migrate.on-boot", "true")));

        var tree = tree();
        JaravelAutoConfiguration.applyOverrides(tree, environment);
        var config = new MapConfigRepository(tree, environment::getProperty);

        assertThat(config.string("database.default")).isEqualTo("mysql");
        assertThat(config.bool("jaravel.migrate.on-boot", false)).isTrue();
    }

    @Test
    @DisplayName("没有覆盖时原树不动")
    void absentOverridesLeaveTheTreeAlone() {
        var tree = tree();
        JaravelAutoConfiguration.applyOverrides(tree, new StandardEnvironment());
        var config = new MapConfigRepository(tree);

        assertThat(config.string("database.default")).isEqualTo("sqlite");
        assertThat(config.bool("jaravel.migrate.on-boot", true)).isFalse();
    }
}
