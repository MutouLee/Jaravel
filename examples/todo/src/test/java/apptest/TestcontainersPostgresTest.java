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

/** 真实 PostgreSQL 验收(docs/14 §2.2);没有 Docker 时跳过 */
@UseDatabase(UseDatabase.Kind.POSTGRESQL)
@EnabledIf("containersEnabled")
class TestcontainersPostgresTest extends FeatureTest {

    @Autowired DataSource dataSource;

    static boolean containersEnabled() {
        return TestcontainersSupport.enabled();
    }

    @Test
    @DisplayName("★ 真实 PostgreSQL:自动注入、迁移、写入与查询都走 Postgres")
    void runsAgainstRealPostgres() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .containsIgnoringCase("PostgreSQL");
        }

        get("/").assertOk().assertSee("Todo");

        post("/todos", Map.of("title", "Postgres 里的待办")).assertRedirect("/");
        get("/api/todos").assertOk().assertSee("Postgres 里的待办");
    }
}
