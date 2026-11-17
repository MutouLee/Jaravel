package io.jaravel.console.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Maven 调用的可测部分:可执行文件探测与 argv(docs/13 §7) */
class MavenInvokerTest {

    @Test
    @DisplayName("项目自带 wrapper 时优先用它 —— 它钉住了 Maven 版本")
    void wrapperWins(@TempDir Path tmp) throws Exception {
        String wrapper = MavenInvoker.isWindows() ? "mvnw.cmd" : "mvnw";
        Files.writeString(tmp.resolve(wrapper), "");

        assertThat(MavenInvoker.argv(tmp, false, "test").get(0))
                .endsWith(wrapper);
    }

    @Test
    @DisplayName("没有 wrapper 就回落 PATH 上的 mvn")
    void fallsBackToThePath(@TempDir Path tmp) {
        assertThat(MavenInvoker.argv(tmp, false, "test").get(0))
                .startsWith("mvn");
    }

    @Test
    @DisplayName("-o 只在明确要离线时出现 —— 第一次跑测试要能下载依赖")
    void offlineIsExplicit(@TempDir Path tmp) {
        assertThat(MavenInvoker.argv(tmp, false, "test"))
                .doesNotContain("-o")
                .contains("test");

        assertThat(MavenInvoker.argv(tmp, true, "test"))
                .contains("-o", "test");
    }
}
