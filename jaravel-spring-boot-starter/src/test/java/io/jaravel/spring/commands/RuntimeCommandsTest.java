package io.jaravel.spring.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.console.Artisan;
import io.jaravel.console.CommandRegistry;
import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigLoader;
import io.jaravel.core.config.MapConfigRepository;
import io.jaravel.database.Connection;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Migrator;
import io.jaravel.migration.Seeder;
import io.jaravel.migration.schema.Schema;
import io.jaravel.routing.Router;
import io.jaravel.routing.RouteCollection;

/**
 * 在线命令(docs/13 §2.3 / §2.5)。
 *
 * <p>**真 SQLite、真 Migrator、真路由表** —— 这些命令本身只有几十行,
 * 它们的价值全在「与运行时接得对不对」,而那恰恰是替身测不出来的
 * (docs/10 里 examples/queue 那段记的 18 个缺陷全是这一类)。
 */
class RuntimeCommandsTest {

    private java.sql.Connection jdbc;
    private Connection connection;
    private CommandRegistry registry;
    private final List<Migration> migrations = new ArrayList<>();
    private final List<Seeder> seeders = new ArrayList<>();
    private RouteCollection routes = RouteCollection.of(List.of());

    @BeforeEach
    void setUp() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        Schema.useConnection(connection);
        Application.setEnvironment("local");

        var config = new MapConfigRepository(ConfigLoader.parse("""
                app:
                  name: 测试应用
                database:
                  default: sqlite
                cache:
                  default: array
                """, "test"));

        registry = new CommandRegistry()
                .register(() -> new MigrateCommand(() -> connection, () -> migrations))
                .register(() -> new MigrateStatusCommand(() -> connection, () -> migrations))
                .register(() -> new MigrateRollbackCommand(() -> connection, () -> migrations))
                .register(() -> new MigrateFreshCommand(() -> connection, () -> migrations))
                .register(() -> new DbSeedCommand(() -> seeders))
                .register(() -> new RouteListCommand(() -> routes))
                .register(() -> new AboutCommand(config, () -> routes));
        Artisan.use(registry);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Artisan.reset();
        Schema.useConnection(null);
        Application.setEnvironment("production");
        Application.setEnvironment("local");   // 别把 production 漏给下一个测试
        jdbc.close();
    }

    /**
     * 一个不会真的关掉连接的 DataSource —— 内存库一关就没了。
     *
     * <p>与 MigratorTest 里那份同源:那边测 Migrator 本身,这边测命令壳子
     * 与运行时接得对不对。
     */
    private static javax.sql.DataSource nonClosing(java.sql.Connection real) {
        java.sql.Connection proxy = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (p, method, args) -> {
                    if ("close".equals(method.getName())) return null;
                    if ("isClosed".equals(method.getName())) return false;
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new javax.sql.DataSource() {
            @Override public java.sql.Connection getConnection() { return proxy; }
            @Override public java.sql.Connection getConnection(String u, String pw) { return proxy; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
            @Override public <T> T unwrap(Class<T> iface) { return iface.cast(proxy); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private String run(String command, String... args) {
        Artisan.call(command, args);
        return Artisan.output();
    }

    // ================= 迁移 =================

    public static final class CreatePosts implements Migration {
        @Override public void up() {
            Schema.create("posts", t -> { t.id(); t.string("title"); });
        }
        @Override public void down() { Schema.dropIfExists("posts"); }
    }

    public static final class CreateTags implements Migration {
        @Override public void up() {
            Schema.create("tags", t -> { t.id(); t.string("name"); });
        }
        @Override public void down() { Schema.dropIfExists("tags"); }
    }

    @Nested
    @DisplayName("migrate")
    class Migrate {

        @Test
        @DisplayName("★ 真的建了表,并把执行过的列出来")
        void runsMigrations() {
            migrations.add(new CreatePosts());

            assertThat(Artisan.call("migrate")).isZero();
            assertThat(Artisan.output()).contains("CreatePosts");
            assertThat(connection.select("select name from sqlite_master where name = 'posts'")
                    .isNotEmpty())
                    .as("表真的建出来了,而不只是打印了一行")
                    .isTrue();
        }

        @Test
        @DisplayName("没有迁移时说清约定位置,而不是「Nothing to migrate」")
        void noMigrations() {
            assertThat(Artisan.call("migrate")).isZero();
            assertThat(Artisan.output()).contains("database.migrations");
        }

        @Test
        @DisplayName("★ --pretend 只打印不执行")
        void pretend() {
            migrations.add(new CreatePosts());

            assertThat(Artisan.call("migrate", "--pretend")).isZero();
            assertThat(connection.select("select name from sqlite_master where name = 'posts'")
                    .isEmpty())
                    .as("空跑之后表不该存在")
                    .isTrue();
        }

        @Test
        @DisplayName("★ 失败时已经执行的那些仍在 output() 里 —— 「第几个炸的」是第一个问题")
        void outputSurvivesFailure() {
            migrations.add(new CreatePosts());
            migrations.add(new Failing());

            // Migrator 的异常会原样抛出来 —— 注册表只接 ConsoleException,
            // 真的失败要让调用方看到完整的栈(CLI 那边负责把它变成一条人话)
            assertThatThrownBy(() -> Artisan.call("migrate"))
                    .hasMessageContaining("本次已回滚");

            assertThat(Artisan.output())
                    .as("""
                            Artisan.call 在 finally 里记录输出,所以炸了也拿得到 ——
                            调用方正要用它写日志,而「第几个迁移炸的」在那段输出里,
                            异常本身只说了原因。""")
                    .contains("CreatePosts");
        }

        public static final class Failing implements Migration {
            @Override public void up() { throw new IllegalStateException("这个迁移炸了"); }
            @Override public void down() { }
        }

        @Test
        @DisplayName("--seed 跑完迁移再跑种子")
        void withSeed() {
            migrations.add(new CreatePosts());
            seeders.add(new RecordingSeeder());

            assertThat(Artisan.call("migrate", "--seed")).isZero();
            assertThat(Artisan.output()).contains("RecordingSeeder");
        }
    }

    @Nested
    @DisplayName("migrate:status")
    class Status {

        @Test
        @DisplayName("★ 已跑的打 ✓,没跑的打 ✗")
        void ranAndPending() {
            migrations.add(new CreatePosts());
            Artisan.call("migrate");
            migrations.add(new CreateTags());

            String out = run("migrate:status");

            assertThat(out).contains("CreatePosts").contains("CreateTags")
                           .contains("✓").contains("✗");
        }

        @Test
        @DisplayName("一次都没跑过时不画空表")
        void beforeAnyMigration() {
            assertThat(run("migrate:status")).contains("一次 migrate 都没跑过");
        }

        @Test
        @DisplayName("★ 库里有记录但代码里没有的迁移要标出来 —— 那通常是切了分支")
        void orphanedMigrations() {
            migrations.add(new CreatePosts());
            Artisan.call("migrate");
            migrations.clear();   // 模拟切到一个没有这个迁移的分支

            String out = run("migrate:status");

            assertThat(out)
                    .as("只看代码侧的列表看不出这件事,而它正是"
                            + "「我的库为什么和同事不一样」的常见答案")
                    .contains("?")
                    .contains("切了分支");
        }
    }

    @Nested
    @DisplayName("migrate:rollback / fresh")
    class RollbackAndFresh {

        @Test
        @DisplayName("rollback 把表删回去")
        void rollback() {
            migrations.add(new CreatePosts());
            Artisan.call("migrate");

            assertThat(Artisan.call("migrate:rollback")).isZero();
            assertThat(connection.select("select name from sqlite_master where name = 'posts'")
                    .isEmpty()).isTrue();
        }

        @Test
        @DisplayName("fresh 全删再全建")
        void fresh() {
            migrations.add(new CreatePosts());
            migrations.add(new CreateTags());
            Artisan.call("migrate");
            connection.update("insert into posts (title) values ('旧数据')", List.of());

            assertThat(Artisan.call("migrate:fresh")).isZero();
            assertThat(connection.select("select count(*) as c from posts").first()
                    .map(r -> io.jaravel.support.Cast.to(r.get("c"), Integer.class)).orElse(-1))
                    .as("fresh 会清数据 —— 那正是它的用途")
                    .isZero();
        }
    }

    // ================= 生产护栏 =================

    @Nested
    @DisplayName("生产护栏")
    class ProductionGuard {

        @Test
        @DisplayName("★ 生产环境 + 不可交互 → 拒绝,并把 --force 说出来")
        void refusesInProduction() {
            Application.setEnvironment("production");
            migrations.add(new CreatePosts());

            assertThat(Artisan.call("migrate")).isEqualTo(1);
            assertThat(Artisan.output())
                    .as("""
                            部署脚本里的 migrate 不该在生产库上静默执行 ——
                            confirm 在不可交互时返回默认值 false,于是一律拒绝。""")
                    .contains("生产环境").contains("--force");
            assertThat(connection.select("select name from sqlite_master where name = 'posts'")
                    .isEmpty()).isTrue();
        }

        @Test
        @DisplayName("--force 在生产环境下放行")
        void forceAllows() {
            Application.setEnvironment("production");
            migrations.add(new CreatePosts());

            assertThat(Artisan.call("migrate", "--force")).isZero();
        }

        @Test
        @DisplayName("★ --pretend 不需要确认 —— 它什么都不改")
        void pretendNeedsNoConfirmation() {
            Application.setEnvironment("production");
            migrations.add(new CreatePosts());

            assertThat(Artisan.call("migrate", "--pretend"))
                    .as("「想看看它会做什么」恰恰是生产环境里最该允许的操作")
                    .isZero();
        }

        @Test
        @DisplayName("fresh 与 rollback 同样受护栏")
        void destructiveCommandsAreGuarded() {
            migrations.add(new CreatePosts());
            Artisan.call("migrate");
            Application.setEnvironment("production");

            assertThat(Artisan.call("migrate:fresh")).isEqualTo(1);
            assertThat(Artisan.call("migrate:rollback")).isEqualTo(1);
            assertThat(connection.select("select name from sqlite_master where name = 'posts'")
                    .isNotEmpty())
                    .as("两条都被拦住了,表还在")
                    .isTrue();
        }
    }

    // ================= db:seed =================

    public static final class RecordingSeeder implements Seeder {
        static final List<String> RAN = new ArrayList<>();
        @Override public void run() { RAN.add("RecordingSeeder"); }
    }

    public static final class DatabaseSeeder implements Seeder {
        @Override public void run() { }
    }

    @Nested
    @DisplayName("db:seed")
    class DbSeed {

        @Test
        @DisplayName("没有 Seeder 时说清约定位置")
        void noSeeders() {
            assertThat(run("db:seed")).contains("database.seeders");
        }

        @Test
        @DisplayName("逐个报出来,而不是最后报个总数")
        void reportsEachSeeder() {
            seeders.add(new RecordingSeeder());

            assertThat(run("db:seed"))
                    .as("一个慢 seeder 在「总数」那种输出里与卡住无法区分")
                    .contains("执行 RecordingSeeder");
        }

        @Test
        @DisplayName("★ 有 DatabaseSeeder 就只跑它(尊重 Laravel 的入口约定)")
        void prefersTheEntryPoint() {
            RecordingSeeder.RAN.clear();
            seeders.add(new RecordingSeeder());
            seeders.add(new DatabaseSeeder());

            run("db:seed");

            assertThat(RecordingSeeder.RAN)
                    .as("入口 seeder 存在时,其余的应当由它 call 出来")
                    .isEmpty();
        }

        @Test
        @DisplayName("没有入口 seeder 时全跑 —— 否则「什么都没发生」")
        void runsAllWithoutEntryPoint() {
            RecordingSeeder.RAN.clear();
            seeders.add(new RecordingSeeder());

            run("db:seed");

            assertThat(RecordingSeeder.RAN).hasSize(1);
        }

        @Test
        @DisplayName("--class 指定的名字不存在时,把找到的列出来")
        void unknownClass() {
            seeders.add(new RecordingSeeder());

            assertThat(Artisan.call("db:seed", "--class=NoSuchSeeder")).isEqualTo(1);
            assertThat(Artisan.output()).contains("没有叫").contains("RecordingSeeder");
        }
    }

    // ================= route:list / about =================

    @Nested
    @DisplayName("route:list")
    class RouteList {

        /**
         * 用真的 {@code Router} 定义再 compile ——
         * Route 的构造器是包可见的,而那是对的:路由只能从 Router 来。
         * 顺带这条测试走的就是应用启动时的那条路径。
         */
        private void withRoutes() {
            var router = new Router();
            router.view("/posts", "posts.index").name("posts.index").middleware("web");
            router.view("/posts/create", "posts.form");
            routes = router.compile();
        }

        @Test
        @DisplayName("★ 数据来自编译后的路由表 —— 请求真正走的那一张")
        void listsCompiledRoutes() {
            withRoutes();

            String out = run("route:list");

            assertThat(out).contains("GET").contains("/posts")
                           .contains("posts.index").contains("web")
                           .contains("共 2 条");
        }

        @Test
        @DisplayName("没有路由时说清,而不是画一个空表")
        void noRoutes() {
            assertThat(run("route:list")).contains("没有注册任何路由");
        }

        @Test
        @DisplayName("--method / --path / --name 过滤,并报匹配了几条")
        void filters() {
            withRoutes();

            assertThat(run("route:list", "--method=get"))
                    .as("方法名不分大小写 —— 命令行上没人会去按住 shift")
                    .contains("共 2 条");
            assertThat(run("route:list", "--name=index")).contains("匹配 1 / 2 条");
            assertThat(run("route:list", "--path=create")).contains("匹配 1 / 2 条");
            assertThat(run("route:list", "--path=nope")).contains("没有匹配的路由");
        }
    }

    @Nested
    @DisplayName("about")
    class About {

        @Test
        @DisplayName("★ 报的是**解析之后**的配置值")
        void showsResolvedConfiguration() {
            String out = run("about");

            assertThat(out)
                    .as("""
                            一半的「为什么不生效」最后都是 .env 没被读到、
                            或者被另一个配置源覆盖了 —— 而那只有解析完才看得出来。""")
                    .contains("测试应用")
                    .contains("sqlite")
                    .contains("local");
        }

        @Test
        @DisplayName("没配的驱动显示「未配置」,而不是空白")
        void unconfiguredDriversAreExplicit() {
            assertThat(run("about")).contains("(未配置)");
        }
    }
}
