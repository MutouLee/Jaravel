package net.dobeen.jaravel.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 并行测试的 URL 派生与 SQL 生成(docs/14 §6.3、ADR-017) */
class ForkedDatabaseTest {

    private static final long PID = 4242;

    @Test
    @DisplayName("SQLite 内存库不动 —— 每个 JVM 本来就不共享")
    void memoryIsAlreadyIsolated(@TempDir Path tmp) {
        assertThat(ForkedDatabase.plan("jdbc:sqlite::memory:", PID, tmp)).isNull();
        assertThat(ForkedDatabase.plan(
                "jdbc:sqlite:file:mem?mode=memory&cache=shared", PID, tmp)).isNull();
        assertThat(ForkedDatabase.plan(
                "jdbc:sqlite:file::memory:?cache=shared", PID, tmp)).isNull();
        assertThat(ForkedDatabase.plan(
                "jdbc:sqlite:file:mem?vfs=memdb", PID, tmp)).isNull();
    }

    @Test
    @DisplayName("相对文件库派到 target/<pid>/ 下,保留原相对结构")
    void relativeFileGetsAPerProcessPath(@TempDir Path tmp) {
        var plan = ForkedDatabase.plan("jdbc:sqlite:storage/todo.db", PID, tmp);

        assertThat(plan).isNotNull();
        assertThat(plan.kind()).isEqualTo(ForkedDatabase.Kind.SQLITE);
        assertThat(plan.sourceFile()).isEqualTo(tmp.resolve("storage/todo.db"));
        assertThat(plan.derivedFile()).isEqualTo(
                tmp.resolve("target/jaravel-test-db/4242/storage/todo.db"));
        assertThat(plan.derivedUrl())
                .startsWith("jdbc:sqlite:")
                .contains("target/jaravel-test-db/4242/storage/todo.db");
    }

    @Test
    @DisplayName("file: 相对路径与 query 参数都保留")
    void fileSchemeAndQuery(@TempDir Path tmp) {
        var plan = ForkedDatabase.plan(
                "jdbc:sqlite:file:data/app.db?cache=shared&busy_timeout=5000", PID, tmp);

        assertThat(plan.derivedUrl())
                .contains("target/jaravel-test-db/4242/data/app.db")
                .endsWith("?cache=shared&busy_timeout=5000");
    }

    @Test
    @DisplayName("绝对路径不动 —— 那是测试自己 @TempDir 管理的,跨 fork 已唯一")
    void absolutePathsAreLeftAlone(@TempDir Path tmp) {
        assertThat(ForkedDatabase.plan("jdbc:sqlite:/var/db/app.db", PID, tmp)).isNull();
        assertThat(ForkedDatabase.plan("jdbc:sqlite:C:/data/app.db", PID, tmp)).isNull();
        assertThat(ForkedDatabase.plan("jdbc:sqlite:file:/var/db/app.db", PID, tmp)).isNull();
    }

    @Test
    @DisplayName("只读 SQLite 明确拒绝,而不是悄悄复制成可写库")
    void readOnlySqliteIsRejected(@TempDir Path tmp) {
        assertThatThrownBy(() ->
                ForkedDatabase.plan("jdbc:sqlite:storage/app.db?mode=ro", PID, tmp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只读");
    }

    @Test
    @DisplayName("路径不能跑出 target 目录")
    void pathTraversalIsRejected(@TempDir Path tmp) {
        assertThatThrownBy(() ->
                ForkedDatabase.plan("jdbc:sqlite:../outside.db", PID, tmp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能跑出 target");
    }

    @Test
    @DisplayName("PostgreSQL:派生库名 + postgres/template1 维护连接")
    void postgresPlan() {
        var plan = ForkedDatabase.plan(
                "jdbc:postgresql://db:5432/app?sslmode=require", PID, Path.of("."));

        assertThat(plan.kind()).isEqualTo(ForkedDatabase.Kind.POSTGRES);
        assertThat(plan.derivedDatabase()).isEqualTo("app_jaravel_test_4242");
        assertThat(plan.derivedUrl())
                .isEqualTo("jdbc:postgresql://db:5432/app_jaravel_test_4242?sslmode=require");
        assertThat(plan.maintenanceUrl())
                .isEqualTo("jdbc:postgresql://db:5432/postgres?sslmode=require");
        assertThat(plan.fallbackMaintenanceUrl())
                .isEqualTo("jdbc:postgresql://db:5432/template1?sslmode=require");
    }

    @Test
    @DisplayName("MySQL:派生库名 + 无库维护连接")
    void mysqlPlan() {
        var plan = ForkedDatabase.plan(
                "jdbc:mysql://db:3306/app?useSSL=false", PID, Path.of("."));

        assertThat(plan.kind()).isEqualTo(ForkedDatabase.Kind.MYSQL);
        assertThat(plan.derivedDatabase()).isEqualTo("app_jaravel_test_4242");
        assertThat(plan.maintenanceUrl()).isEqualTo("jdbc:mysql://db:3306/?useSSL=false");
    }

    @Test
    @DisplayName("超长库名截断到方言上限,但保留进程后缀")
    void longIdentifiersAreTruncated() {
        var pg = ForkedDatabase.plan(
                "jdbc:postgresql://db/" + "x".repeat(80), PID, Path.of("."));
        assertThat(pg.derivedDatabase().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(63);
        assertThat(pg.derivedDatabase()).endsWith("_jaravel_test_4242");

        var mysql = ForkedDatabase.plan(
                "jdbc:mysql://db/" + "y".repeat(80), PID, Path.of("."));
        assertThat(mysql.derivedDatabase()).hasSizeLessThanOrEqualTo(64);
        assertThat(mysql.derivedDatabase()).endsWith("_jaravel_test_4242");
    }

    @Test
    @DisplayName("标识符转义:双引号与反引号")
    void identifierQuoting() {
        assertThat(ForkedDatabase.dropSql(ForkedDatabase.Kind.POSTGRES, "a\"b"))
                .isEqualTo("DROP DATABASE IF EXISTS \"a\"\"b\"");
        assertThat(ForkedDatabase.createSql(ForkedDatabase.Kind.MYSQL, "a`b"))
                .isEqualTo("CREATE DATABASE `a``b`");
    }

    @Test
    @DisplayName("不认识的 JDBC URL 明确报错,而不是共享一个库")
    void unknownJdbcUrlIsRejected(@TempDir Path tmp) {
        assertThatThrownBy(() -> ForkedDatabase.plan("jdbc:h2:mem:test", PID, tmp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法隔离");
    }

    @Test
    @DisplayName("SQLite 准备会复制源库,清理会删掉派生库")
    void provisionCopiesAndCleanupDeletes(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("storage/app.db");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "seeded");

        var plan = ForkedDatabase.plan("jdbc:sqlite:storage/app.db", PID, tmp);
        ForkedDatabase.provision(plan, null, null);

        assertThat(plan.derivedFile()).exists();
        assertThat(Files.readString(plan.derivedFile())).isEqualTo("seeded");

        ForkedDatabase.cleanup(plan, null, null);
        assertThat(plan.derivedFile()).doesNotExist();
    }

    @Test
    @DisplayName("启动时清掉已死进程的派生目录,Surefire 杀掉 fork 也不积累垃圾")
    void sweepRemovesDeadForkDirectories(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("jaravel-test-db");
        Path dead = root.resolve("999999999").resolve("storage");
        Files.createDirectories(dead);
        Files.writeString(dead.resolve("todo.db"), "stale");

        long current = ProcessHandle.current().pid();
        Path alive = root.resolve(Long.toString(current)).resolve("storage");
        Files.createDirectories(alive);
        Files.writeString(alive.resolve("todo.db"), "live");

        ForkedDatabase.sweepDeadProcesses(root, current);

        assertThat(dead).doesNotExist();
        assertThat(alive).exists();
    }

    @Test
    @DisplayName("诊断信息里的密码要打码")
    void credentialsAreMasked() {
        assertThat(ForkedDatabase.mask(
                "jdbc:postgresql://user:secret@db/app?password=hunter2&ssl=true"))
                .contains("user:***@", "password=***")
                .doesNotContain("secret", "hunter2");
    }
}
