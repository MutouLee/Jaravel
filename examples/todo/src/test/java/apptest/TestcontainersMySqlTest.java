package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import io.jaravel.testing.FeatureTest;
import io.jaravel.testing.TestcontainersSupport;
import io.jaravel.testing.UseDatabase;

/**
 * 真实 MySQL 验收(docs/14 §2.2)。
 *
 * <p>{@code @UseDatabase} 会起一个 Testcontainers MySQL,并在上下文创建前
 * 注入数据源与 {@code database.default} —— 所以整条链路(迁移、Eloquent
 * 查询、页面渲染)真的跑在 MySQL 上,而不是 SQLite 冒充。
 *
 * <p>没有 Docker 时整个类跳过;注意这个判断发生在 Spring 上下文创建之前,
 * 所以不会先起容器再跳过。
 */
@UseDatabase(UseDatabase.Kind.MYSQL)
@EnabledIf("containersEnabled")
class TestcontainersMySqlTest extends FeatureTest {

    @Autowired DataSource dataSource;

    static boolean containersEnabled() {
        return TestcontainersSupport.enabled();
    }

    @Test
    @DisplayName("★ 真实 MySQL:自动注入、迁移、写入与查询都走 MySQL")
    void runsAgainstRealMysql() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .as("数据源必须是 Testcontainers 起的 MySQL,不是 SQLite")
                    .containsIgnoringCase("MySQL");
        }

        get("/").assertOk().assertSee("Todo");

        post("/todos", Map.of("title", "MySQL 里的待办")).assertRedirect("/");
        get("/api/todos").assertOk().assertSee("MySQL 里的待办");
    }
}
