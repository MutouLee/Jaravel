package io.jaravel.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testcontainers 的开关与 Docker 29 API 版本适配(docs/14 §2.2) */
class TestcontainersSupportTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("jaravel.testcontainers");
        System.clearProperty("jaravel.testcontainers.api-version");
        System.clearProperty("api.version");
    }

    @Test
    @DisplayName("默认关闭 —— 默认套件不拉镜像")
    void disabledByDefault() {
        assertThat(TestcontainersSupport.enabled()).isFalse();

        System.setProperty("jaravel.testcontainers", "true");
        assertThat(TestcontainersSupport.enabled()).isTrue();

        System.setProperty("jaravel.testcontainers", "false");
        assertThat(TestcontainersSupport.enabled()).isFalse();
    }

    @Test
    @DisplayName("第一次连 Docker 前设置 api.version(Docker 29 需要 ≥ 1.44)")
    void setsTheApiVersion() {
        TestcontainersSupport.ensureApiVersion();
        assertThat(System.getProperty("api.version"))
                .isEqualTo(TestcontainersSupport.DEFAULT_API_VERSION);
    }

    @Test
    @DisplayName("用户显式设置的 api.version / jaravel.testcontainers.api-version 优先")
    void explicitApiVersionWins() {
        System.setProperty("api.version", "1.52");
        TestcontainersSupport.ensureApiVersion();
        assertThat(System.getProperty("api.version")).isEqualTo("1.52");

        System.clearProperty("api.version");
        System.setProperty("jaravel.testcontainers.api-version", "1.43");
        TestcontainersSupport.ensureApiVersion();
        assertThat(System.getProperty("api.version")).isEqualTo("1.43");
    }
}
