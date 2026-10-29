package io.jaravel.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.database.Connection;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.migration.fixtures.m2026_10_29_100000.CreatePosts;
import io.jaravel.migration.fixtures.m2026_10_29_100000.CreatePostsSameDdl;
import io.jaravel.migration.fixtures.m2026_10_29_110000.AddSlug;
import io.jaravel.migration.fixtures.m2026_10_29_120000.CreateTags;
import io.jaravel.migration.fixtures.m2026_10_29_130000.Failing;
import io.jaravel.migration.fixtures.misc.Irreversible;
import io.jaravel.migration.fixtures.misc.MysqlOnly;
import io.jaravel.migration.fixtures.misc.NoDown;
import io.jaravel.migration.fixtures.misc.NoTimestamp;
import io.jaravel.migration.schema.Schema;

/** 迁移执行器对真实 SQLite 的行为 */
class MigratorTest {

    private java.sql.Connection jdbc;
    private Connection connection;
    private Migrator migrator;

    @BeforeEach
    void setUp() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        Schema.useConnection(connection);
        migrator = new Migrator(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Schema.useConnection(null);
        jdbc.close();
    }

    // ================= 基本流程 =================

    @Test
    @DisplayName("migrate 建表并记账")
    void migrateCreatesTableAndLogs() {
        var executed = migrator.migrate(List.of(new CreatePosts()));

        assertThat(executed).hasSize(1);
        assertThat(Schema.hasTable("posts")).isTrue();
        assertThat(Schema.hasTable("jaravel_migrations")).isTrue();
        assertThat(migrator.ranMigrations()).containsOnlyKeys("2026_10_29_100000_CreatePosts");
    }

    @Test
    @DisplayName("再次 migrate 不重跑")
    void migrateIsIdempotent() {
        migrator.migrate(List.of(new CreatePosts()));

        var again = new Migrator(connection);
        assertThat(again.migrate(List.of(new CreatePosts()))).isEmpty();
        assertThat(again.output()).anyMatch(s -> s.contains("没有待执行的迁移"));
    }

    @Test
    @DisplayName("按包名里的时间戳排序,不受传入顺序影响")
    void ordersByTimestampNotArgumentOrder() {
        // 故意倒着传:AddSlug 依赖 posts 表存在
        var executed = migrator.migrate(List.of(new AddSlug(), new CreatePosts()));

        assertThat(executed).containsExactly(
                "2026_10_29_100000_CreatePosts",
                "2026_10_29_110000_AddSlug");
        assertThat(Schema.hasColumn("posts", "slug")).isTrue();
    }

    @Test
    @DisplayName("没有时间戳的迁移会被警告 —— 它们的顺序不确定")
    void warnsAboutMissingTimestamp() {
        migrator.migrate(List.of(new NoTimestamp()));

        assertThat(migrator.output())
                .anyMatch(s -> s.contains("没有时间戳") && s.contains("顺序不确定"));
    }

    @Test
    @DisplayName("同一次 migrate 的迁移共享一个批次;--step 则各自一批")
    void batches() {
        migrator.migrate(List.of(new CreatePosts(), new AddSlug()));
        assertThat(migrator.ranMigrations().values())
                .allMatch(r -> r.batch() == 1);

        var stepped = new Migrator(connection);
        stepped.migrate(List.of(new CreateTags()), new Migrator.Options(true, false, false));
        assertThat(stepped.ranMigrations().get("2026_10_29_120000_CreateTags").batch())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("pretend 只打印不执行")
    void pretendDoesNotTouchSchema() {
        migrator.migrate(List.of(new CreatePosts()), new Migrator.Options(false, false, true));

        assertThat(Schema.hasTable("posts")).isFalse();
        assertThat(migrator.output()).anyMatch(s -> s.startsWith("[pretend]"));
    }

    // ================= 回滚 =================

    @Test
    @DisplayName("rollback 回滚最后一批,且倒序执行")
    void rollbackLastBatchInReverseOrder() {
        var all = List.<Migration>of(new CreatePosts(), new AddSlug());
        migrator.migrate(all);

        var rolled = new Migrator(connection).rollback(all);

        // 倒序:先撤 AddSlug 再撤 CreatePosts,否则删表时列还在引用
        assertThat(rolled).containsExactly(
                "2026_10_29_110000_AddSlug",
                "2026_10_29_100000_CreatePosts");
        assertThat(Schema.hasTable("posts")).isFalse();
    }

    @Test
    @DisplayName("rollback(steps) 回滚多个批次")
    void rollbackMultipleBatches() {
        var step = new Migrator.Options(true, false, false);
        migrator.migrate(List.of(new CreatePosts(), new AddSlug(), new CreateTags()), step);

        var all = List.<Migration>of(new CreatePosts(), new AddSlug(), new CreateTags());
        var rolled = new Migrator(connection).rollback(all, 2);

        assertThat(rolled).hasSize(2);
        assertThat(Schema.hasTable("tags")).isFalse();
        assertThat(Schema.hasTable("posts")).isTrue();
    }

    @Test
    @DisplayName("reset 全部回滚")
    void reset() {
        var all = List.<Migration>of(new CreatePosts(), new AddSlug());
        migrator.migrate(all);

        new Migrator(connection).reset(all);
        assertThat(migrator.ranMigrations()).isEmpty();
    }

    @Test
    @DisplayName("没实现 down() 的迁移在回滚时报错并指出两条出路")
    void missingDownIsActionable() {
        var m = List.<Migration>of(new NoDown());
        migrator.migrate(m);

        assertThatThrownBy(() -> new Migrator(connection).rollback(m))
                .hasMessageContaining("没有实现 down()")
                .hasMessageContaining("irreversible()");
    }

    @Test
    @DisplayName("irreversible 的迁移拒绝回滚,并解释为什么")
    void irreversibleIsRejected() {
        var m = List.<Migration>of(new Irreversible());
        migrator.migrate(m);

        assertThatThrownBy(() -> new Migrator(connection).rollback(m))
                .isInstanceOf(Migrator.MigrationException.class)
                .hasMessageContaining("不能回滚")
                .hasMessageContaining("丢弃了数据");
    }

    @Test
    @DisplayName("记账表里有记录但类已被删时,给出可执行的清理 SQL")
    void missingMigrationClassIsActionable() {
        migrator.migrate(List.of(new CreatePosts()));

        // 模拟「迁移类被删掉了,记录还在」
        assertThatThrownBy(() -> new Migrator(connection).rollback(List.of()))
                .hasMessageContaining("找不到对应的迁移类")
                .hasMessageContaining("delete from");
    }

    // ================= checksum 漂移 =================

    @Test
    @DisplayName("已执行的迁移被改过时,migrate 报漂移并给出三条处理方式")
    void driftIsDetected() {
        migrator.migrate(List.of(new CreatePosts()));
        tamperChecksum();

        assertThatThrownBy(() -> new Migrator(connection).migrate(List.of(new CreatePosts())))
                .isInstanceOf(Migrator.MigrationException.class)
                .hasMessageContaining("在执行之后被修改过")
                .hasMessageContaining("已执行的迁移视为不可变历史")
                .hasMessageContaining("--allow-drift");
    }

    @Test
    @DisplayName("--allow-drift 降级为警告")
    void allowDriftDowngradesToWarning() {
        migrator.migrate(List.of(new CreatePosts()));
        tamperChecksum();

        var second = new Migrator(connection);
        second.migrate(List.of(new CreatePosts()),
                       new Migrator.Options(false, true, false));

        assertThat(second.output()).anyMatch(s -> s.contains("[警告]") && s.contains("漂移"));
    }

    /**
     * 把记账表里的 checksum 改掉,等价于「有人改了已执行的迁移文件」。
     *
     * <p>比造一个同名但内容不同的类直接 —— 后者需要两个同全限定名的类,
     * Java 做不到,而真实场景本来就是「同一个类的内容变了」。
     */
    private void tamperChecksum() {
        connection.update("update \"jaravel_migrations\" set \"checksum\" = ?",
                          List.of("0".repeat(64)));
    }

    @Test
    @DisplayName("checksum 对「只改格式」不敏感 —— 它哈希的是产生的 DDL")
    void checksumIgnoresFormatting() {
        // 两个类产生完全相同的 DDL,只是写法不同
        String a = migrator.checksumOf(new CreatePosts());
        String b = migrator.checksumOf(new CreatePostsSameDdl());

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("checksum 对结构变化敏感")
    void checksumCatchesStructuralChange() {
        assertThat(migrator.checksumOf(new CreatePosts()))
                .isNotEqualTo(migrator.checksumOf(new CreateTags()));
    }

    // ================= 方言过滤 =================

    @Test
    @DisplayName("onlyOnDialects 不匹配时跳过并说明")
    void dialectFilterSkips() {
        var executed = migrator.migrate(List.of(new MysqlOnly()));

        assertThat(executed).isEmpty();
        assertThat(migrator.output())
                .anyMatch(s -> s.contains("仅适用于") && s.contains("sqlite"));
    }

    // ================= 失败处理 =================

    @Test
    @DisplayName("迁移失败时报错包含迁移名、方向,以及事务是否已回滚")
    void failureIsDiagnosable() {
        assertThatThrownBy(() -> migrator.migrate(List.of(new Failing())))
                .isInstanceOf(Migrator.MigrationException.class)
                .hasMessageContaining("Failing")
                .hasMessageContaining("up()")
                // SQLite 的 DDL 支持事务,所以应该说「已回滚」
                .hasMessageContaining("已回滚");
    }

    @Test
    @DisplayName("失败后再次 migrate 从失败处继续,成功的不重跑")
    void resumesAfterFailure() {
        try {
            migrator.migrate(List.of(new CreatePosts(), new Failing()));
        } catch (Migrator.MigrationException ignored) {
            // 预期失败
        }

        assertThat(Schema.hasTable("posts")).isTrue();
        assertThat(migrator.ranMigrations()).containsOnlyKeys("2026_10_29_100000_CreatePosts");
    }

    // ================= 夹具 =================

    private static DataSource nonClosing(java.sql.Connection real) {
        java.sql.Connection proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (p, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("isClosed")) return false;
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new DataSource() {
            @Override public java.sql.Connection getConnection() { return proxy; }
            @Override public java.sql.Connection getConnection(String u, String p) { return proxy; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() { return Logger.getGlobal(); }
            @Override public <T> T unwrap(Class<T> iface) { return iface.cast(proxy); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
