package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.jaravel.testing.FeatureTest;

/**
 * 并行测试的每进程库(docs/14 §6.3、ADR-017)。
 *
 * <p>示例用的是文件 SQLite —— 正是需要派生路径的那种。默认套件里这条
 * 跳过;用 {@code -Djaravel.test.parallel=true} 跑时,它钉住两件事:
 * URL 被改写到 {@code target/jaravel-test-db/<pid>/} 下,且 PID 是本进程。
 */
class ParallelDatabaseTest extends FeatureTest {

    @Autowired DataSource dataSource;

    @Test
    @DisplayName("并行模式:每个 fork 用带 pid 的独立 SQLite 库")
    void eachForkGetsItsOwnDatabase() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("jaravel.test.parallel"),
                "只在 jaravel test --parallel 下验证");

        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL())
                    .contains("jaravel-test-db")
                    .contains(Long.toString(ProcessHandle.current().pid()));
        }
    }
}
