package io.jaravel.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

/**
 * 并行测试的每进程数据源(docs/14 §6.3、ADR-017)。
 *
 * <h2>为什么要在 URL 上做文章</h2>
 * <p>Surefire 的 {@code forkCount} 给出 N 个 JVM,但 Spring 配置里的
 * {@code spring.datasource.url} 是**同一个**。内存 SQLite 天然按 JVM 隔离,
 * 文件与服务器数据库不是 —— 两个 fork 连同一个库,迁移、写入、清理
 * 互相踩,症状是只在并行时出现的 flaky。
 *
 * <h2>策略</h2>
 * <ul>
 *   <li><b>SQLite 内存</b>:不动。</li>
 *   <li><b>SQLite 相对文件路径</b>:派生到
 *       {@code target/jaravel-test-db/<pid>/<原相对路径>};原文件存在就先拷贝
 *       (保留本地已迁移/已 seed 的数据),退出时删除。</li>
 *   <li><b>SQLite 绝对路径</b>:不动 —— 那是测试自己用 {@code @TempDir}
 *       管着的,跨 fork 本来就唯一。</li>
 *   <li><b>PostgreSQL / MySQL</b>:派生库名 {@code <db>_jaravel_test_<pid>},
 *       启动时 drop + create,退出时尽力 drop。需要 CREATE/DROP DATABASE 权限。</li>
 *   <li><b>其它 JDBC</b>(含 Testcontainers 的 {@code jdbc:tc:}):明确报错,
 *       而不是静默共享一个库。</li>
 * </ul>
 *
 * <p>这个类只做 URL 推导与数据库准备;何时启用由
 * {@link ParallelTestDatabase} 决定。拆开来是为了能单独测 URL 规则 ——
 * 那些规则每一条都对应一个「看起来能跑、其实共享了库」的失败模式。
 */
final class ForkedDatabase {

    private static final String SQLITE = "jdbc:sqlite:";
    private static final String POSTGRES = "jdbc:postgresql:";
    private static final String MYSQL = "jdbc:mysql:";
    private static final String MARIADB = "jdbc:mariadb:";

    static final String DATABASE_SUFFIX = "_jaravel_test_";
    static final String TARGET_DIR = "target/jaravel-test-db";

    private static final int POSTGRES_IDENTIFIER_BYTES = 63;
    private static final int MYSQL_IDENTIFIER_CHARS = 64;

    private ForkedDatabase() {
    }

    enum Kind { SQLITE, POSTGRES, MYSQL }

    /**
     * 一次派生。SQLite 用文件字段,服务端库用库名与维护连接。
     * {@code null} 表示「不需要派生」(内存库、绝对文件路径、非 JDBC URL)。
     */
    record Plan(Kind kind,
                String originalUrl,
                String derivedUrl,
                Path sourceFile,
                Path derivedFile,
                String maintenanceUrl,
                String fallbackMaintenanceUrl,
                String derivedDatabase) {
    }

    static Plan plan(String url, long pid, Path workingDir) {
        if (url == null || !url.startsWith("jdbc:")) return null;

        if (url.startsWith(SQLITE)) {
            return sqlitePlan(url, pid, workingDir);
        }
        if (url.startsWith(POSTGRES)) {
            return serverPlan(Kind.POSTGRES, url, pid);
        }
        if (url.startsWith(MYSQL) || url.startsWith(MARIADB)) {
            return serverPlan(Kind.MYSQL, url, pid);
        }
        throw new IllegalArgumentException("""
                并行测试无法隔离这个数据源:%s
                  支持:SQLite(内存/文件)、PostgreSQL、MySQL/MariaDB。
                  Testcontainers(jdbc:tc:...)尚未支持。
                  出路:关闭 --parallel,或改用受支持的方言。""".formatted(url));
    }

    // ================= SQLite =================

    private static Plan sqlitePlan(String url, long pid, Path workingDir) {
        String rest = url.substring(SQLITE.length());
        int question = rest.indexOf('?');
        String payload = question >= 0 ? rest.substring(0, question) : rest;
        String query = question >= 0 ? rest.substring(question + 1) : "";

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        if (payload.equals(":memory:")
                || payload.equals("file::memory:")
                || payload.startsWith(":resource:")
                || lowerQuery.contains("mode=memory")
                || lowerQuery.contains("vfs=memdb")) {
            return null;   // 每个 JVM 的内存库/只读 classpath 资源本来就不共享
        }
        if (lowerQuery.contains("mode=ro") || lowerQuery.contains("immutable=1")) {
            throw new IllegalArgumentException("""
                    并行测试不能隔离只读 SQLite 库:%s
                      只读库没法派生出一个可写的测试库。
                      出路:用可写路径,或关闭 --parallel。""".formatted(url));
        }

        boolean fileScheme = payload.startsWith("file:");
        String pathText = fileScheme ? payload.substring("file:".length()) : payload;
        if (fileScheme && pathText.startsWith("//") && !pathText.startsWith("///")) {
            throw new IllegalArgumentException("""
                    SQLite file: URI 带主机名,无法安全派生:%s
                      出路:改用本地绝对路径或相对路径,或关闭 --parallel。"""
                    .formatted(url));
        }
        if (pathText.isBlank() || isAbsolutePath(pathText)) {
            // 绝对路径由测试自己(@TempDir)管理,跨 fork 已经唯一
            return null;
        }

        Path source = workingDir.resolve(pathText).normalize();
        Path root = workingDir.resolve(TARGET_DIR).resolve(Long.toString(pid)).normalize();
        Path derived = root.resolve(pathText).normalize();
        if (!derived.startsWith(root)) {
            throw new IllegalArgumentException("""
                    并行测试的 SQLite 路径不能跑出 target 目录:%s
                      出路:把测试库放到项目相对路径下,或关闭 --parallel。"""
                    .formatted(pathText));
        }

        // file: 前缀会被去掉:连接串走普通路径,避免 Windows 上 file: URI 的斜杠差异
        String derivedUrl = SQLITE + slashes(derived) + (query.isEmpty() ? "" : "?" + query);
        return new Plan(Kind.SQLITE, url, derivedUrl, source, derived, null, null, null);
    }

    private static boolean isAbsolutePath(String text) {
        if (text.startsWith("/") || text.startsWith("\\\\")) return true;
        // Windows 盘符:C:/… 或 C:\…
        return text.length() >= 3
                && Character.isLetter(text.charAt(0))
                && text.charAt(1) == ':'
                && (text.charAt(2) == '/' || text.charAt(2) == '\\');
    }

    // ================= PostgreSQL / MySQL =================

    private static Plan serverPlan(Kind kind, String url, long pid) {
        String prefix = url.startsWith(POSTGRES) ? POSTGRES
                : url.startsWith(MARIADB) ? MARIADB : MYSQL;
        String rest = url.substring(prefix.length());

        if (!rest.startsWith("//")) {
            throw new IllegalArgumentException(
                    "无法解析数据库 URL(需要 jdbc:<db>://host[:port]/<dbname>):" + url);
        }
        int slash = rest.indexOf('/', 2);
        if (slash < 0) {
            throw new IllegalArgumentException(
                    "URL 里没有数据库名:" + url + "\n  并行模式需要知道要派生哪个库。");
        }

        String authority = rest.substring(0, slash);          // //host:port
        String tail = rest.substring(slash + 1);              // db[?params]
        int question = tail.indexOf('?');
        String database = question >= 0 ? tail.substring(0, question) : tail;
        String query = question >= 0 ? tail.substring(question) : "";
        if (database.isBlank()) {
            throw new IllegalArgumentException(
                    "URL 里没有数据库名:" + url + "\n  并行模式需要知道要派生哪个库。");
        }

        String derivedDatabase = fitIdentifier(kind, database, pid);
        String base = prefix + authority + "/";

        String maintenance;
        String fallback = null;
        if (kind == Kind.POSTGRES) {
            maintenance = base + "postgres" + query;
            fallback = base + "template1" + query;
        } else {
            maintenance = base + query;     // 不指定库;参数里可能有 SSL 设置
        }

        return new Plan(kind, url, base + derivedDatabase + query,
                null, null, maintenance, fallback, derivedDatabase);
    }

    private static String fitIdentifier(Kind kind, String database, long pid) {
        String suffix = DATABASE_SUFFIX + pid;
        String base = database;
        if (kind == Kind.POSTGRES) {
            while (!base.isEmpty()
                    && (base + suffix).getBytes(StandardCharsets.UTF_8).length > POSTGRES_IDENTIFIER_BYTES) {
                base = base.substring(0, base.length() - 1);
            }
        } else {
            while (!base.isEmpty() && (base + suffix).length() > MYSQL_IDENTIFIER_CHARS) {
                base = base.substring(0, base.length() - 1);
            }
        }
        if (base.isEmpty()) {
            throw new IllegalArgumentException(
                    "数据库名太短,加上并行后缀后无法形成合法标识符:" + database);
        }
        return base + suffix;
    }

    static String dropSql(Kind kind, String database) {
        return switch (kind) {
            case POSTGRES -> "DROP DATABASE IF EXISTS " + pgIdentifier(database);
            case MYSQL -> "DROP DATABASE IF EXISTS " + mysqlIdentifier(database);
            case SQLITE -> throw new IllegalArgumentException("SQLite 没有 DROP DATABASE");
        };
    }

    static String createSql(Kind kind, String database) {
        return switch (kind) {
            case POSTGRES -> "CREATE DATABASE " + pgIdentifier(database);
            case MYSQL -> "CREATE DATABASE " + mysqlIdentifier(database);
            case SQLITE -> throw new IllegalArgumentException("SQLite 没有 CREATE DATABASE");
        };
    }

    private static String pgIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String mysqlIdentifier(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    // ================= 准备与清理 =================

    static void provision(Plan plan, String username, String password) {
        if (plan.kind() == Kind.SQLITE) {
            provisionSqlite(plan);
            return;
        }
        try (Connection connection = connectMaintenance(plan, username, password)) {
            execute(connection, dropSql(plan.kind(), plan.derivedDatabase()));
            execute(connection, createSql(plan.kind(), plan.derivedDatabase()));
        } catch (SQLException e) {
            throw new IllegalStateException("""
                    并行测试需要每个进程一个独立数据库,但无法创建 %s。
                      原因:%s
                      需要 CREATE/DROP DATABASE 权限;也可以预先创建该库,
                      或关闭 --parallel 改用共享库。
                      原始数据源:%s"""
                    .formatted(plan.derivedDatabase(), e.getMessage(), mask(plan.originalUrl())), e);
        }
    }

    private static void provisionSqlite(Plan plan) {
        try {
            // Surefire 在 Windows 上可能直接杀掉 fork,JVM 的 shutdown hook
            // 不保证执行。每次启动先扫掉「进程已经不在」的旧派生库。
            sweepDeadProcesses(dbRootOf(plan.derivedFile()), ProcessHandle.current().pid());

            if (plan.derivedFile().getParent() != null) {
                Files.createDirectories(plan.derivedFile().getParent());
            }
            deleteSqlite(plan.derivedFile());   // 上一次同 PID 的残留
            if (Files.isRegularFile(plan.sourceFile())) {
                Files.copy(plan.sourceFile(), plan.derivedFile());
                copySidecar(plan.sourceFile(), plan.derivedFile(), "-wal");
                copySidecar(plan.sourceFile(), plan.derivedFile(), "-shm");
            }
        } catch (IOException e) {
            throw new IllegalStateException("""
                    并行测试无法准备 SQLite 库:%s -> %s
                      原因:%s"""
                    .formatted(plan.sourceFile(), plan.derivedFile(), e.getMessage()), e);
        }
    }

    /** {@code .../target/jaravel-test-db/<pid>/...} → {@code .../target/jaravel-test-db} */
    private static Path dbRootOf(Path derived) {
        for (Path p = derived; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("jaravel-test-db")) {
                return p;
            }
        }
        return null;
    }

    static void sweepDeadProcesses(Path dbRoot, long currentPid) {
        if (dbRoot == null || !Files.isDirectory(dbRoot)) return;
        try (var children = Files.list(dbRoot)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child)) continue;
                long pid;
                try {
                    pid = Long.parseLong(child.getFileName().toString());
                } catch (NumberFormatException notAPid) {
                    continue;
                }
                if (pid == currentPid) continue;
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) continue;
                deleteRecursively(child);
            }
        } catch (IOException e) {
            System.err.printf("[jaravel test] 清理旧测试库目录失败:%s%n", e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            System.err.printf("[jaravel test] 删不掉旧测试库目录 %s:%s%n", dir, e.getMessage());
        }
    }

    private static void copySidecar(Path source, Path target, String suffix) throws IOException {
        Path from = source.resolveSibling(source.getFileName() + suffix);
        if (Files.isRegularFile(from)) {
            Files.copy(from, target.resolveSibling(target.getFileName() + suffix));
        }
    }

    static void cleanup(Plan plan, String username, String password) {
        if (plan.kind() == Kind.SQLITE) {
            deleteSqlite(plan.derivedFile());
            return;
        }
        try (Connection connection = connectMaintenance(plan, username, password)) {
            execute(connection, dropSql(plan.kind(), plan.derivedDatabase()));
        } catch (Exception e) {
            // 退出路径不制造新失败;下次运行会先 DROP 再 CREATE
            System.err.printf("[jaravel test] 清理测试库 %s 失败(下次运行会重建):%s%n",
                    plan.derivedDatabase(), e.getMessage());
        }
    }

    private static Connection connectMaintenance(Plan plan, String username, String password)
            throws SQLException {
        Properties properties = new Properties();
        if (username != null) properties.setProperty("user", username);
        if (password != null) properties.setProperty("password", password);

        try {
            return DriverManager.getConnection(plan.maintenanceUrl(), properties);
        } catch (SQLException first) {
            if (plan.fallbackMaintenanceUrl() == null) throw first;
            try {
                return DriverManager.getConnection(plan.fallbackMaintenanceUrl(), properties);
            } catch (SQLException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void deleteSqlite(Path file) {
        delete(file);
        delete(file.resolveSibling(file.getFileName() + "-wal"));
        delete(file.resolveSibling(file.getFileName() + "-shm"));
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.printf("[jaravel test] 删不掉测试库文件 %s:%s%n", file, e.getMessage());
        }
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    /** 诊断信息里的 URL 打码 —— 连接串可能带 password= 或 user:pass@ */
    static String mask(String text) {
        if (text == null) return null;
        return text.replaceAll("(?i)(password=)[^&;\\s]*", "$1***")
                .replaceAll("(?i)(//[^/@:\\s]+:)[^/@\\s]*@", "$1***@");
    }
}
