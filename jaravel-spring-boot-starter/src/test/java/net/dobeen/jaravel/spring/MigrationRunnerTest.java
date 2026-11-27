package net.dobeen.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.core.config.ConfigRepository;
import net.dobeen.jaravel.core.config.MapConfigRepository;

/** 迁移开关的优先级(docs/14 §6.3、ADR-017) */
class MigrationRunnerTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty("jaravel.migrate.on-boot");
    }

    private ConfigRepository config(String env, Boolean onBoot) {
        var tree = new LinkedHashMap<String, Object>();
        tree.put("app", Map.of("env", env));
        var jaravel = new LinkedHashMap<String, Object>();
        if (onBoot != null) jaravel.put("migrate", Map.of("on-boot", onBoot));
        tree.put("jaravel", jaravel);
        return new MapConfigRepository(tree);
    }

    @Test
    @DisplayName("默认:local 开,production 关")
    void defaultsFollowTheEnvironment() {
        assertThat(MigrationRunner.migrationsEnabled(config("local", null))).isTrue();
        assertThat(MigrationRunner.migrationsEnabled(config("production", null))).isFalse();
    }

    @Test
    @DisplayName("系统属性 true 可以强制打开 —— 并行测试的空库需要它")
    void systemPropertyForcesOn() {
        System.setProperty("jaravel.migrate.on-boot", "true");

        assertThat(MigrationRunner.migrationsEnabled(config("production", false))).isTrue();
    }

    @Test
    @DisplayName("显式的 false 也优先于 local 默认值")
    void systemPropertyCanForceOff() {
        System.setProperty("jaravel.migrate.on-boot", "false");

        assertThat(MigrationRunner.migrationsEnabled(config("local", true))).isFalse();
    }
}
