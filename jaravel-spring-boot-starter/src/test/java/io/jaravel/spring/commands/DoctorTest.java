package io.jaravel.spring.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.Artisan;
import io.jaravel.console.CommandRegistry;
import io.jaravel.console.generators.GeneratorCommand;
import io.jaravel.console.generators.KeyGenerateCommand;
import io.jaravel.core.Application;
import io.jaravel.core.config.ConfigLoader;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.core.config.MapConfigRepository;
import io.jaravel.core.crypt.Encrypter;
import io.jaravel.database.Connection;
import io.jaravel.database.JdbcConnection;
import io.jaravel.database.grammar.SqliteGrammar;
import io.jaravel.migration.Migration;
import io.jaravel.migration.Migrator;
import io.jaravel.migration.schema.Schema;
import io.jaravel.scheduler.Schedule;
import io.jaravel.scheduler.Scheduler;

/**
 * {@code jaravel doctor} 与 {@code key:generate}(docs/13 §2.6 / §2.5)。
 *
 * <p>docs 称 doctor 是「Jaravel 的差异化加分项」,而那个价值完全取决于
 * **检查项是不是真的**。所以这些测试制造真实的坏配置(真 SQLite、
 * 真 Migrator、真 Scheduler),而不是喂假的检查结果。
 */
class DoctorTest {

    private java.sql.Connection jdbc;
    private Connection connection;
    private Schedule schedule;
    private final List<Migration> migrations = new ArrayList<>();
    private String yaml = """
            app:
              key: KEY_PLACEHOLDER
            cache:
              default: redis
            queue:
              default: database
            """;

    @BeforeEach
    void setUp() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection = new JdbcConnection(nonClosing(jdbc), new SqliteGrammar());
        Schema.useConnection(connection);
        Application.setEnvironment("local");
        schedule = new Schedule(null, null);
    }

    @AfterEach
    void tearDown() throws SQLException {
        Artisan.reset();
        Schema.useConnection(null);
        Application.setEnvironment("local");
        jdbc.close();
    }

    private ConfigRepository config() {
        return new MapConfigRepository(ConfigLoader.parse(
                yaml.replace("KEY_PLACEHOLDER", KeyGenerateCommand.generate()), "test"));
    }

    private String runDoctor(String... args) {
        var checks = FrameworkChecks.all(config(), () -> connection, () -> migrations,
                () -> new Scheduler(schedule));
        Artisan.use(new CommandRegistry()
                .register(DoctorCommand.class, () -> new DoctorCommand(checks))
                .register(KeyGenerateCommand.class));
        Artisan.call("doctor", args);
        return Artisan.output();
    }

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

    public static final class CreatePosts implements Migration {
        @Override public void up() { Schema.create("posts", t -> { t.id(); t.string("title"); }); }
        @Override public void down() { Schema.dropIfExists("posts"); }
    }

    /** 同名、但 DDL 不同 —— 用来模拟「迁移跑过之后被改了」 */
    public static final class CreatePostsChanged implements Migration {
        @Override public void up() {
            Schema.create("posts", t -> { t.id(); t.string("title"); t.string("slug"); });
        }
        @Override public void down() { Schema.dropIfExists("posts"); }
    }

    // ================= 全绿与退出码 =================

    @Test
    @DisplayName("★ 一切正常时全是 ✓,退出码 0")
    void allGood() {
        String out = runDoctor();

        assertThat(out).contains("已设置,格式正确")
                       .contains("连接正常").contains("sqlite")
                       .doesNotContain("✗");
        assertThat(Artisan.call("doctor")).isZero();
    }

    @Test
    @DisplayName("★ 有 ✗ 就退出码 1 —— 这让 doctor 能当 CI 的闸")
    void failureMakesItNonZero() {
        yaml = yaml.replace("key: KEY_PLACEHOLDER", "key: \"\"");

        runDoctor();   // 装配 + 跑一遍
        assertThat(Artisan.call("doctor"))
                .as("退出码恒为 0 的检查命令只能靠人去读输出,而人会读过去")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ 只有 ⚠ 时退出码还是 0 —— 否则 CI 会被训练成加 || true")
    void warningsDoNotFail() {
        // 先跑一个迁移(于是记录表存在),再加一个没跑的 → 「有待执行的迁移」= ⚠
        migrations.add(new CreatePosts());
        new Migrator(connection).migrate(List.copyOf(migrations));
        migrations.add(new CreatePostsChanged());

        String out = runDoctor();

        assertThat(out).contains("还没执行").contains("⚠");
        assertThat(Artisan.call("doctor"))
                .as("警告是「有个坑在前面」,不是「现在就不对」")
                .isZero();
    }

    @Test
    @DisplayName("★ 整份报告在**一条流**上 —— 结论与解释不能分家")
    void reportStaysOnOneStream() {
        Application.setEnvironment("production");
        Application.setDebug(true);

        var checks = FrameworkChecks.all(config(), () -> connection, () -> migrations,
                () -> new Scheduler(schedule));
        var io = new io.jaravel.console.ConsoleIo.Recording();
        new CommandRegistry()
                .register(DoctorCommand.class, () -> new DoctorCommand(checks))
                .run("doctor", List.of(), io);

        assertThat(io.output())
                .as("""
                        ✗ 用 error() 的话它走 stderr,而它下面那几行 → 说明走 stdout ——
                        于是在日志里(或任何把两条流分开收集的地方)结论与解释就分家了:
                        一串无主的「→ 修复:…」在上面,一串无因的「✗」在下面。
                        终端上两条流交错输出,所以这个缺陷在人眼前看不出来。""")
                .contains("✗ debug 模式")
                .contains("泄露连接串");
        assertThat(io.errors())
                .as("doctor 的结论由退出码表达,stderr 上不该有报告内容")
                .isEmpty();
    }

    // ================= 各检查项 =================

    @Nested
    @DisplayName("检查项")
    class Checks {

        @Test
        @DisplayName("★ 迁移跑过之后被改了 —— 这是最该被查出来的一项")
        void checksumDrift() {
            migrations.add(new CreatePosts());
            var migrator = new Migrator(connection);
            migrator.migrate(List.copyOf(migrations));

            // 同一个名字、不同的 DDL:相当于把那个迁移文件改了
            connection.update("update jaravel_migrations set migration = ? where migration = ?",
                    List.of(new Migrator(connection).nameOf(new CreatePostsChanged()),
                            new Migrator(connection).nameOf(new CreatePosts())));
            migrations.set(0, new CreatePostsChanged());

            String out = runDoctor();

            assertThat(out)
                    .as("""
                            migrate 只看名字,所以它会说「没有待执行的迁移」——
                            那个改动在本机永远不会生效,而在同事新建的库上会生效。
                            两边表结构从此不同,且两边的 migrate 都说一切正常。""")
                    .contains("执行后被改过")
                    .contains("只看名字");
        }

        @Test
        @DisplayName("APP_KEY 没设 → ✗,而且是可自动修复的")
        void missingAppKey() {
            yaml = yaml.replace("key: KEY_PLACEHOLDER", "key: \"\"");

            String out = runDoctor();

            assertThat(out).contains("APP_KEY:没有设置")
                           .contains("key:generate")
                           .contains("可以自动修复");
        }

        @Test
        @DisplayName("APP_KEY 格式不对 → ✗(而且不说它可以自动修复)")
        void invalidAppKey() {
            yaml = yaml.replace("KEY_PLACEHOLDER", "这不是 base64");

            String out = runDoctor();

            assertThat(out).contains("APP_KEY:格式不合法");
            assertThat(out)
                    .as("换掉一个「不合法但也许能解密旧数据」的 key 不该是自动的")
                    .doesNotContain("可以自动修复");
        }

        @Test
        @DisplayName("★ 生产环境开着 debug → ✗")
        void debugInProduction() {
            Application.setEnvironment("production");
            Application.setDebug(true);

            String out = runDoctor();

            assertThat(out)
                    .as("它不会让任何功能失效,所以没人会注意到 —— "
                            + "直到一个 500 页面把连接串展示给了用户")
                    .contains("生产环境开着 debug")
                    .contains("泄露连接串");
        }

        @Test
        @DisplayName("★ onOneServer + 非共享缓存 → ✗(docs §2.6 举的那个例子)")
        void scheduleLocksOnNonSharedCache() {
            yaml = yaml.replace("default: redis", "default: caffeine");
            schedule.call("对账", () -> { }).daily().onOneServer();

            String out = runDoctor();

            assertThat(out)
                    .contains("onOneServer() 会失效")
                    .as("后果要说清:症状与原因之间距离很远")
                    .contains("每个实例上各跑一遍");
        }

        @Test
        @DisplayName("声明了单实例时降级为 ⚠,并说清扩容前要换驱动")
        void singleInstanceIsAWarning() {
            yaml = yaml.replace("default: redis", "default: caffeine")
                       + """
                       schedule:
                         instances: 1
                       """;
            schedule.call("对账", () -> { }).daily().onOneServer();

            String out = runDoctor();

            assertThat(out).contains("现在是安全的").contains("扩到多实例之前");
            assertThat(Artisan.call("doctor")).isZero();
        }

        @Test
        @DisplayName("非开发环境用 sync 队列 → ✗")
        void syncQueueInProduction() {
            Application.setEnvironment("production");
            yaml = yaml.replace("default: database", "default: sync");

            assertThat(runDoctor())
                    .contains("queue.default = sync")
                    .as("削峰、重试、失败归档全都没有了,而且接口会变慢")
                    .contains("算在请求时间里");
        }

        @Test
        @DisplayName("★ 某一项检查自己炸了,不该遮住后面所有项")
        void aBrokenCheckDoesNotHideTheRest() {
            var checks = new ArrayList<DoctorCheck>();
            checks.add(new DoctorCheck() {
                @Override public String name() { return "会炸的检查"; }
                @Override public Result run() { throw new IllegalStateException("炸了"); }
            });
            checks.add(new DoctorCheck() {
                @Override public String name() { return "后面那项"; }
                @Override public Result run() { return Result.ok("没事"); }
            });

            Artisan.use(new CommandRegistry()
                    .register(DoctorCommand.class, () -> new DoctorCommand(checks)));
            Artisan.call("doctor");

            assertThat(Artisan.output())
                    .as("""
                            doctor 的用途恰恰是「有些东西不对」的时候 ——
                            那时某项检查抛异常是预期之内的(数据库连不上、调度器没装)。
                            让它冒出去的话,第一个坏掉的检查会遮住后面所有项。""")
                    .contains("检查本身失败了")
                    .contains("后面那项");
        }
    }

    // ================= --fix =================

    @Nested
    @DisplayName("doctor --fix")
    class Fix {

        @Test
        @DisplayName("★ --fix 真的把 APP_KEY 生成出来了")
        void fixesTheKey(@TempDir Path tmp) throws Exception {
            Files.writeString(tmp.resolve(".env"), "APP_NAME=测试\nAPP_KEY=\n");
            yaml = yaml.replace("key: KEY_PLACEHOLDER", "key: \"\"");

            var checks = FrameworkChecks.all(config(), () -> connection, () -> migrations,
                    () -> new Scheduler(schedule));
            Artisan.use(new CommandRegistry()
                    .register(DoctorCommand.class, () -> new DoctorCommand(checks))
                    .register(KeyGenerateCommand.class)
                    .configure(command -> {
                        if (command instanceof GeneratorCommand generator) {
                            generator.inProject(tmp);
                        }
                    }));

            Artisan.call("doctor", "--fix");

            assertThat(Files.readString(tmp.resolve(".env")))
                    .as("--fix 的那条路径是真的调了 key:generate,不是打印一句建议")
                    .contains("APP_KEY=base64:");
        }
    }

    // ================= key:generate =================

    @Nested
    @DisplayName("key:generate")
    class KeyGenerate {

        private void registry(Path projectRoot) {
            Artisan.use(new CommandRegistry()
                    .register(KeyGenerateCommand.class)
                    .configure(command -> {
                        if (command instanceof GeneratorCommand generator) {
                            generator.inProject(projectRoot);
                        }
                    }));
        }

        @Test
        @DisplayName("★ 生成的 key 必须被 Encrypter 认 —— 那份重复由这条测试钉住")
        void formatMatchesEncrypter() {
            String key = KeyGenerateCommand.generate();

            assertThat(key).startsWith("base64:");
            org.assertj.core.api.Assertions.assertThatCode(() -> Encrypter.fromBase64(key))
                    .as("""
                            key:generate 在 jaravel-console 里重写了一遍密钥生成
                            (那个模块不依赖 jaravel-core,为三行代码不值得)。
                            这条跨模块测试是那份重复的看门人:格式一旦漂掉它就红。""")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("写进 .env,原有的其它行保留")
        void writesIntoEnv(@TempDir Path tmp) throws Exception {
            Files.writeString(tmp.resolve(".env"), "APP_NAME=测试\nDB_HOST=localhost\n");
            registry(tmp);

            assertThat(Artisan.call("key:generate")).isZero();

            String env = Files.readString(tmp.resolve(".env"));
            assertThat(env).contains("APP_NAME=测试").contains("DB_HOST=localhost")
                           .contains("APP_KEY=base64:");
        }

        @Test
        @DisplayName("★ 已有 key 时拒绝覆盖 —— 换掉它等于把已加密的数据扔了")
        void refusesToReplace(@TempDir Path tmp) throws Exception {
            String original = KeyGenerateCommand.generate();
            Files.writeString(tmp.resolve(".env"), "APP_KEY=" + original + "\n");
            registry(tmp);

            assertThat(Artisan.call("key:generate")).isEqualTo(1);
            assertThat(Artisan.output())
                    .contains("已经有 APP_KEY")
                    .as("后果要说清:会话掉线、加密列永久读不出来")
                    .contains("解不开")
                    .contains("--force");
            assertThat(Files.readString(tmp.resolve(".env"))).contains(original);

            assertThat(Artisan.call("key:generate", "--force")).isZero();
            assertThat(Files.readString(tmp.resolve(".env"))).doesNotContain(original);
            assertThat(Artisan.output()).contains("旧密钥已被替换");
        }

        @Test
        @DisplayName("★ 注释掉的 APP_KEY 不算「已有」—— 改那一行等于没写")
        void commentedKeyIsNotAValue() throws Exception {
            Path tmp = Files.createTempDirectory("jaravel-key");
            Files.writeString(tmp.resolve(".env"), "# APP_KEY=base64:从 .env.example 抄来的\n");
            registry(tmp);

            assertThat(Artisan.call("key:generate")).isZero();

            String env = Files.readString(tmp.resolve(".env"));
            assertThat(env).contains("# APP_KEY=base64:从 .env.example 抄来的")
                           .contains("\nAPP_KEY=base64:");
        }

        @Test
        @DisplayName("--show 只打印,不碰文件")
        void showOnly(@TempDir Path tmp) {
            registry(tmp);

            assertThat(Artisan.call("key:generate", "--show")).isZero();
            assertThat(Artisan.output()).contains("base64:");
            assertThat(tmp.resolve(".env")).doesNotExist();
        }

        @Test
        @DisplayName("没有 .env 时说清,并指向 --show")
        void withoutEnvFile(@TempDir Path tmp) {
            registry(tmp);

            assertThat(Artisan.call("key:generate")).isEqualTo(1);
            assertThat(Artisan.output()).contains("找不到").contains("--show");
        }
    }
}
