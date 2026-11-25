package io.jaravel.console.generators;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleIo;

@DisplayName("jaravel new + install:auth / install:api / install:broadcasting / install:vite")
class StarterKitTest {

    private CommandRegistry registry(Path cwd, Class<?> command) {
        var registry = new CommandRegistry();
        registry.register((Class<? extends io.jaravel.console.ConsoleCommand>) command);
        registry.configure(c -> {
            if (c instanceof io.jaravel.console.ProjectAware aware) aware.inProject(cwd);
        });
        return registry;
    }

    @Test
    @DisplayName("new 生成可构建的项目骨架与一条烟雾测试")
    void newProject(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        int code = registry(tmp, NewProjectCommand.class).run(
                "new", List.of("demo", "--preset=web",
                        "--jaravel-version=0.1.0-SNAPSHOT"), io);

        assertThat(code).isZero();
        Path root = tmp.resolve("demo");
        assertThat(root.resolve("pom.xml")).exists();
        assertThat(root.resolve("src/main/java/app/Application.java")).exists();
        assertThat(root.resolve("src/main/java/routes/WebRoutes.java")).exists();
        assertThat(root.resolve("src/main/resources/views/home.jblade.html")).exists();
        assertThat(root.resolve(".env")).exists();
        assertThat(Files.readString(root.resolve("pom.xml")))
                .contains("jaravel-spring-boot-starter")
                .contains("jaravel-maven-plugin")
                .contains("<jaravel.version>0.1.0-SNAPSHOT</jaravel.version>");
        assertThat(Files.readString(root.resolve("src/main/resources/views/home.jblade.html")))
                .contains("{{ name }}")
                .doesNotContain("\\{{");
    }

    @Test
    @DisplayName("install:auth 生成模型、路由、控制器、模板与迁移")
    void installAuth(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        var registry = registry(tmp, NewProjectCommand.class);
        assertThat(registry.run("new", List.of("demo"), io)).isZero();

        Path root = tmp.resolve("demo");
        int code = registry(root, InstallAuthCommand.class).run("install:auth", List.of(), io);

        assertThat(code).isZero();
        assertThat(root.resolve("src/main/java/app/models/User.java")).exists();
        assertThat(root.resolve("src/main/java/app/http/controllers/AuthController.java")).exists();
        assertThat(root.resolve("src/main/java/routes/AuthRoutes.java")).exists();
        assertThat(root.resolve("src/main/resources/views/auth/login.jblade.html")).exists();
        assertThat(root.resolve("src/main/resources/views/dashboard.jblade.html")).exists();
        assertThat(root.resolve("src/main/resources/config/auth.yml")).exists();
        assertThat(Files.readString(root.resolve("src/main/java/app/models/User.java")))
                .contains("@AuthModel")
                .contains("@Fillable");
        assertThat(Files.readString(root.resolve("src/main/java/app/http/controllers/AuthController.java")))
                .contains("Auth.attempt")
                .contains("Password.sendResetLink");
        assertThat(Files.readString(root.resolve("src/main/resources/views/auth/login.jblade.html")))
                .as("生成的模板用框架指令,不再手写隐藏 input 与一堆显式参数(docs/04 §1.8)")
                .contains("@csrf")
                .contains("@error('email')")
                .contains("@old('email')")
                .doesNotContain("name=\"_token\"")
                .doesNotContain("\\{{");
    }

    /** 起一个已经跑过 new + install:auth 的项目 */
    private Path projectWithAuth(Path tmp, ConsoleIo.Recording io) {
        assertThat(registry(tmp, NewProjectCommand.class).run("new", List.of("demo"), io))
                .isZero();
        Path root = tmp.resolve("demo");
        assertThat(registry(root, InstallAuthCommand.class).run("install:auth", List.of(), io))
                .isZero();
        return root;
    }

    @Test
    @DisplayName("install:api 生成 token 表、控制器、ApiRoutes 与 api.yml")
    void installApi(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        Path root = projectWithAuth(tmp, io);

        int code = registry(root, InstallApiCommand.class).run("install:api", List.of(), io);

        assertThat(code).isZero();
        assertThat(root.resolve("src/main/java/app/http/controllers/ApiTokenController.java"))
                .exists();
        assertThat(root.resolve("src/main/java/routes/ApiRoutes.java")).exists();
        assertThat(root.resolve("src/main/resources/config/api.yml")).exists();

        try (var walk = Files.walk(root.resolve("src/main/java/database/migrations"))) {
            assertThat(walk.filter(p -> p.getFileName().toString()
                            .equals("CreateApiTokensTable.java")).findFirst())
                    .as("token 表跟着 install:api 走 —— 纯浏览器应用不需要它")
                    .isPresent();
        }

        assertThat(Files.readString(
                root.resolve("src/main/java/app/http/controllers/ApiTokenController.java")))
                .as("""
                        签发不能走 Auth.attempt —— 那是会话型 guard 的方法,
                        而 api 组刻意不含 StartSession。""")
                .contains("retrieveByCredentials")
                .contains("validateCredentials")
                .as("javadoc 里会解释「为什么不用 Auth.attempt」,但代码里不该调它")
                .doesNotContain("Auth.attempt(")
                .doesNotContain("{{");
        assertThat(Files.readString(root.resolve("src/main/java/routes/ApiRoutes.java")))
                .contains("middleware(\"api\")")
                .contains("auth:api")
                .as("签发是一个可以反复试密码的公开接口").contains("throttle:10,1");
        assertThat(Files.readString(root.resolve("src/main/resources/config/api.yml")))
                .contains("error-format")
                .contains("idempotency");
    }

    @Test
    @DisplayName("★ 没有 auth.yml 时 install:api 拒绝生成,并给出那条命令")
    void installApiRequiresAuth(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        assertThat(registry(tmp, NewProjectCommand.class).run("new", List.of("demo"), io))
                .isZero();
        Path root = tmp.resolve("demo");

        int code = registry(root, InstallApiCommand.class).run("install:api", List.of(), io);

        assertThat(code).isEqualTo(1);
        assertThat(io.errors())
                .as("半套脚手架比没有脚手架糟:用户要先读懂生成的代码才知道缺了什么")
                .contains("install:auth");
        assertThat(root.resolve("src/main/java/routes/ApiRoutes.java"))
                .as("拒绝就要一个文件都不写")
                .doesNotExist();
    }

    @Test
    @DisplayName("install:broadcasting 生成配置与频道鉴权骨架")
    void installBroadcasting(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        Path root = projectWithAuth(tmp, io);

        int code = registry(root, InstallBroadcastingCommand.class)
                .run("install:broadcasting", List.of(), io);

        assertThat(code).isZero();
        assertThat(root.resolve("src/main/resources/config/broadcasting.yml")).exists();
        assertThat(root.resolve("src/main/java/app/channels/Channels.java")).exists();

        assertThat(Files.readString(root.resolve("src/main/resources/config/broadcasting.yml")))
                .as("WebSocket 不走 CORS 那一层,所以「允许谁连」必须显式列出来")
                .contains("origins: []");
        assertThat(Files.readString(root.resolve("src/main/java/app/channels/Channels.java")))
                .contains("BroadcastChannels")
                .contains("private-users.{userId}")
                .doesNotContain("{{");
    }

    @Test
    @DisplayName("没有 User 模型时 install:broadcasting 仍然生成,但会提醒")
    void installBroadcastingWithoutUser(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        assertThat(registry(tmp, NewProjectCommand.class).run("new", List.of("demo"), io))
                .isZero();
        Path root = tmp.resolve("demo");

        int code = registry(root, InstallBroadcastingCommand.class)
                .run("install:broadcasting", List.of(), io);

        assertThat(code).isZero();
        assertThat(root.resolve("src/main/resources/config/broadcasting.yml"))
                .as("只推公开频道(大盘、公告)是成立的用法")
                .exists();
        assertThat(io.output() + io.errors()).contains("install:auth");
    }

    @Test
    @DisplayName("install:vite 生成 vite.config.js、入口文件与 config/vite.yml")
    void installVite(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        assertThat(registry(tmp, NewProjectCommand.class).run("new", List.of("demo"), io))
                .isZero();
        Path root = tmp.resolve("demo");

        int code = registry(root, InstallViteCommand.class).run("install:vite", List.of(), io);

        assertThat(code).isZero();
        assertThat(root.resolve("vite.config.js")).exists();
        assertThat(root.resolve("package.json")).exists();
        assertThat(root.resolve("resources/css/app.css")).exists();
        assertThat(root.resolve("resources/js/app.js")).exists();
        assertThat(root.resolve("src/main/resources/config/vite.yml")).exists();

        assertThat(Files.readString(root.resolve("vite.config.js")))
                .as("""
                        产物必须落在 classpath:/public/ 下 —— 放项目根的 public/
                        会让它不进 jar,症状是「线上页面没有样式」(ADR-020)""")
                .contains("outDir: 'src/main/resources/public/build'")
                .contains("manifest: true")
                .contains("'resources/css/app.css'")
                .doesNotContain("{{");

        assertThat(Files.readString(root.resolve("src/main/resources/config/vite.yml")))
                .as("空 dev-server = build 模式;开发时由 .env 覆盖")
                .contains("dev-server: ${VITE_DEV_SERVER:}");

        assertThat(io.output())
                .as("那一行要贴到布局里,不说的话用户装完什么都看不到")
                .contains("@vite(['resources/css/app.css', 'resources/js/app.js'])");
        assertThat(io.output())
                .as("mvn package 不跑前端是一个会让人踩坑的决定(ADR-020),必须说出来")
                .contains("npm run build && mvn package");
    }

    @Test
    @DisplayName("★ 老项目的 .gitignore 少那两行时 install:vite 提醒(不代劳)")
    void installViteWarnsAboutGitignore(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        assertThat(registry(tmp, NewProjectCommand.class).run("new", List.of("demo"), io))
                .isZero();
        Path root = tmp.resolve("demo");

        // jaravel new 生成的 .gitignore 里已经有那两行了(见 project.gitignore.stub)。
        // 这里把它退回到「vite 之前的项目」那个样子 —— 那才是会踩坑的人
        Files.writeString(root.resolve(".gitignore"), """
                target/
                .env
                """);

        assertThat(registry(root, InstallViteCommand.class)
                .run("install:vite", List.of(), io)).isZero();

        assertThat(io.output() + io.errors())
                .as("""
                        漏了的后果是把带哈希的产物与整个 node_modules 提交进仓库 ——
                        不报错,只是某天有人发现仓库有几百兆""")
                .contains("src/main/resources/public/build")
                .contains("node_modules");
        assertThat(Files.readString(root.resolve(".gitignore")))
                .as("这条命令不改用户已有的文件,只提示")
                .doesNotContain("node_modules");
    }

    @Test
    @DisplayName("jaravel new 的 .gitignore 里就有前端那两行 —— 提醒只是兜底")
    void newProjectAlreadyIgnoresFrontendOutput(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        assertThat(registry(tmp, NewProjectCommand.class).run("new", List.of("demo"), io))
                .isZero();

        assertThat(Files.readString(tmp.resolve("demo/.gitignore")))
                .contains("node_modules/")
                .contains("src/main/resources/public/build/");
    }

    @Test
    @DisplayName("★ install:* 生成的每个 .java 都过语法检查")
    void everyInstalledFileParses(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();
        Path root = projectWithAuth(tmp, io);
        assertThat(registry(root, InstallApiCommand.class).run("install:api", List.of(), io))
                .isZero();
        assertThat(registry(root, InstallBroadcastingCommand.class)
                .run("install:broadcasting", List.of(), io)).isZero();

        try (var walk = Files.walk(root.resolve("src/main/java"))) {
            var sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).hasSizeGreaterThanOrEqualTo(8);

            for (Path file : sources) {
                String source = Files.readString(file);
                assertThat(parses(source))
                        .as("""
                                %s 的语法不对。一个教错 API 的 stub 比没有这条命令更糟,
                                而一个**编译不过**的 stub 更糟:用户第一件事是修框架给他的报错。
                                %n%s""", file.getFileName(), source)
                        .isTrue();
            }
        }
    }

    /**
     * 只做**语法**检查,不做符号解析。
     *
     * <p>生成的代码引用应用自己的类(app.models.User)与框架的类,在这个模块的
     * classpath 上一个都没有 —— 真编译只会得到一堆与 stub 对不对无关的
     * 「找不到符号」。(与 GeneratorsTest 里同名的那个 helper 是同一招。)
     */
    private boolean parses(String source) {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        var files = compiler.getStandardFileManager(
                diagnostics, null, java.nio.charset.StandardCharsets.UTF_8);

        var unit = new javax.tools.SimpleJavaFileObject(
                java.net.URI.create("string:///Probe.java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        var task = (com.sun.source.util.JavacTask) compiler.getTask(
                java.io.Writer.nullWriter(), files, diagnostics, null, null, List.of(unit));
        try {
            task.parse();
        } catch (Exception e) {
            return false;
        }
        return diagnostics.getDiagnostics().stream()
                .noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR);
    }

    @Test
    @DisplayName("新建目录已存在且非空时拒绝覆盖")
    void refusesNonEmptyTarget(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("demo"));
        Files.writeString(tmp.resolve("demo/keep.txt"), "keep");

        var io = new ConsoleIo.Recording();
        int code = registry(tmp, NewProjectCommand.class).run("new", List.of("demo"), io);

        assertThat(code).isNotZero();
        assertThat(Files.readString(tmp.resolve("demo/keep.txt"))).isEqualTo("keep");
    }
}
