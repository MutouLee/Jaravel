package net.dobeen.jaravel.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL / MySQL / MariaDB 的真实建库契约测试。
 *
 * <p>默认 <b>跳过</b>。设置以下环境变量才跑(URL 指向一个已存在的库,
 * 派生库会建在同一个服务器上):
 *
 * <pre>
 * JARAVEL_TEST_PG_URL=jdbc:postgresql://127.0.0.1:5432/postgres
 * JARAVEL_TEST_PG_USER=postgres
 * JARAVEL_TEST_PG_PASSWORD=secret
 *
 * JARAVEL_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/mysql
 * JARAVEL_TEST_MYSQL_USER=root
 * JARAVEL_TEST_MYSQL_PASSWORD=secret
 *
 * JARAVEL_TEST_MARIADB_URL=jdbc:mariadb://127.0.0.1:3306/mysql
 * JARAVEL_TEST_MARIADB_USER=root
 * JARAVEL_TEST_MARIADB_PASSWORD=secret
 * </pre>
 *
 * <p>与 Redis / RabbitMQ / SQS 的契约测试同一个模式:默认套件不依赖外部服务,
 * 给了连接串才验证真实协议/权限。
 */
class ParallelDatabaseContractTest {

    private static final long PID = ProcessHandle.current().pid();

    @Test
    @DisplayName("PostgreSQL:自动 DROP/CREATE 派生库,清理后连不上")
    void postgres() throws SQLException {
        contract("JARAVEL_TEST_PG_URL", "JARAVEL_TEST_PG_USER", "JARAVEL_TEST_PG_PASSWORD");
    }

    @Test
    @DisplayName("MySQL:自动 DROP/CREATE 派生库,清理后连不上")
    void mysql() throws SQLException {
        contract("JARAVEL_TEST_MYSQL_URL", "JARAVEL_TEST_MYSQL_USER", "JARAVEL_TEST_MYSQL_PASSWORD");
    }

    @Test
    @DisplayName("MariaDB:自动 DROP/CREATE 派生库,清理后连不上")
    void mariadb() throws SQLException {
        contract("JARAVEL_TEST_MARIADB_URL",
                "JARAVEL_TEST_MARIADB_USER", "JARAVEL_TEST_MARIADB_PASSWORD");
    }

    private void contract(String urlVariable, String userVariable, String passwordVariable)
            throws SQLException {
        String url = System.getenv(urlVariable);
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "设置 " + urlVariable + " 才跑真实数据库契约测试");

        String user = System.getenv(userVariable);
        String password = System.getenv(passwordVariable);

        var plan = ForkedDatabase.plan(url, PID, Path.of("."));
        assertThat(plan).isNotNull();

        ForkedDatabase.provision(plan, user, password);
        try {
            try (Connection connection = connect(plan, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
        } finally {
            ForkedDatabase.cleanup(plan, user, password);
        }

        assertThatThrownBy(() -> connect(plan, user, password))
                .as("清理后派生库应该不存在了")
                .isInstanceOf(SQLException.class);
    }

    private Connection connect(ForkedDatabase.Plan plan, String user, String password)
            throws SQLException {
        var properties = new Properties();
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        return DriverManager.getConnection(plan.derivedUrl(), properties);
    }
}
