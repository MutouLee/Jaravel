package io.jaravel.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.Artisan;
import io.jaravel.console.Command;
import io.jaravel.console.ConsoleIo;

/** {@code jaravel} 入口:离线/在线分流、list / help / --version(docs/13 §1) */
class JaravelCliTest {

    @AfterEach
    void clearStaticState() {
        Artisan.reset();
    }

    private record Result(int code, String out, String err) {}

    private Result run(Path projectRoot, String... args) {
        var io = new ConsoleIo.Recording();
        int code = Jaravel.run(args, io, projectRoot);
        return new Result(code, io.output(), io.errors());
    }

    /** 装成一个项目的目录 —— 否则会多出那条「不像项目」的提示 */
    private Path project(Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        return tmp;
    }

    // ================= 离线命令 =================

    @Test
    @DisplayName("★ jaravel make:model Post 在**没有容器**的进程里跑完")
    void offlineCommandRunsInProcess(@TempDir Path tmp) throws Exception {
        var result = run(project(tmp), "make:model", "Post", "-m");

        assertThat(result.code()).isZero();
        assertThat(tmp.resolve("src/main/java/app/models/Post.java")).exists();
        assertThat(result.out())
                .as("""
                        这是整个模块存在的理由:make:model 只读写文件,
                        为它等 Spring 启动三秒是 docs/13 开头点明的那个
                        不可接受的 DX。""")
                .contains("src/main/java/app/models/Post.java");

        try (var walk = Files.walk(tmp)) {
            assertThat(walk.filter(p -> p.toString().endsWith(".java")).count())
                    .as("-m 连带生成了迁移")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("退出码就是命令的退出码 —— shell 与它唯一的契约")
    void exitCodeComesFromTheCommand(@TempDir Path tmp) throws Exception {
        Path root = project(tmp);
        assertThat(run(root, "make:model", "Post").code()).isZero();
        assertThat(run(root, "make:model", "Post").code())
                .as("第二次撞上已存在")
                .isEqualTo(1);
        assertThat(run(root, "make:model").code())
                .as("缺少参数")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("参数原样传给命令,包括合并短开关")
    void argumentsArePassedThrough(@TempDir Path tmp) throws Exception {
        Path root = project(tmp);
        run(root, "make:model", "Post", "-mcr");

        assertThat(root.resolve("src/main/java/app/http/controllers/PostController.java")).exists();
        assertThat(Files.readString(
                root.resolve("src/main/java/app/http/controllers/PostController.java")))
                .as("-r 的资源控制器有七个方法")
                .contains("destroy");
    }

    @Test
    @DisplayName("★ openapi:diff 走离线路径 —— 只读两份 JSON,不碰容器")
    void openApiDiffRunsWithoutAnApplication(@TempDir Path tmp) throws Exception {
        String json = """
                {"openapi":"3.1.0","info":{"title":"t","version":"1.0.0"},
                 "paths":{"/posts":{"get":{"operationId":"PostController.index",
                 "responses":{"200":{"description":"ok"}}}}}}""";
        Path base = Files.writeString(tmp.resolve("base.json"), json);
        Path current = Files.writeString(tmp.resolve("current.json"), json);

        var result = run(project(tmp), "openapi:diff", base.toString(), current.toString());

        assertThat(result.code()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).contains("两份文档没有差异");
    }

    @Test
    @DisplayName("★ 目录不像项目时给提示,但**不拒绝执行**")
    void hintsWhenNotAProject(@TempDir Path tmp) {
        var result = run(tmp, "make:model", "Post");

        assertThat(result.code()).as("提示不是失败").isZero();
        assertThat(result.err())
                .as("""
                        在空目录里 make:model 很可能是「忘了 cd」,
                        但也可能是故意的(还没有 pom 的新目录)——
                        拒绝执行会挡住后者。""")
                .contains("不像一个 Jaravel 项目");
        assertThat(tmp.resolve("src/main/java/app/models/Post.java")).exists();
    }

    @Test
    @DisplayName("用错了(缺参数)→ 一条消息 + 用法,不带栈帧")
    void misuseIsAMessage(@TempDir Path tmp) throws Exception {
        var result = run(project(tmp), "make:model");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err())
                .as("ConsoleException 就是「用错了」,注册表就地处理成一条消息")
                .contains("缺少参数")
                .contains("用法:make:model")
                .doesNotContain("at io.jaravel");
    }

    @Test
    @DisplayName("★ 命令**抛异常**时也只给消息,栈留给 JARAVEL_DEBUG")
    void thrownExceptionsBecomeAMessage(@TempDir Path tmp) throws Exception {
        // make:model 往一个不可能写入的路径生成 —— 用一个文件当「目录」
        Path root = project(tmp);
        java.nio.file.Files.createDirectories(root.resolve("src/main/java/app"));
        java.nio.file.Files.writeString(root.resolve("src/main/java/app/models"), "我是文件不是目录");

        var result = run(root, "make:model", "Post");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err())
                .as("""
                        栈不是没用,是**给框架开发者的** ——
                        对 jaravel 的用户来说有用的是「写不了哪个文件」那句话,
                        而它会被埋在三十行框架帧的最上面一行。""")
                .contains("写不了")
                .doesNotContain("at io.jaravel.console");
    }

    // ================= 在线命令 =================

    @Nested
    @DisplayName("在线命令")
    class Online {

        @Test
        @DisplayName("★ migrate 报一条说清状态与出路的错,而不是假装成功")
        void migrateExplainsItself(@TempDir Path tmp) throws Exception {
            var result = run(project(tmp), "migrate");

            assertThat(result.code())
                    .as("退出码 0 而什么都没做的话,脚本里它是成功的")
                    .isEqualTo(1);
            assertThat(result.err())
                    .contains("需要应用上下文")
                    .as("说清出路:把应用跑起来,它会开通道")
                    .contains("先把应用跑起来")
                    .contains(".jaravel/dev.sock")
                    .as("""
                            以及冷启动那条路为什么也没走通 —— 这个临时目录里
                            没有 pom.xml,所以启动器不知道要启动什么。
                            (有 pom.xml 的真项目上它会直接起 JVM 把命令跑掉。)""")
                    .contains("冷启动一个 JVM");
        }

        @Test
        @DisplayName("★ 不给 migrate「是否想写 make:migration ?」那种误导建议")
        void doesNotSuggestAMakeCommand(@TempDir Path tmp) throws Exception {
            var result = run(project(tmp), "migrate");

            assertThat(result.err())
                    .as("那条建议会把人带到完全错误的方向")
                    .doesNotContain("是否想写");
        }

        @Test
        @DisplayName("queue: / schedule: 下的命令按前缀认出来")
        void prefixMatching(@TempDir Path tmp) throws Exception {
            Path root = project(tmp);
            assertThat(run(root, "queue:work").err()).contains("需要应用上下文");
            assertThat(run(root, "schedule:list").err()).contains("需要应用上下文");
            assertThat(run(root, "tinker").err()).contains("需要应用上下文");
        }

        @Test
        @DisplayName("★ 应用自己定义的命令也要能到达 —— 它不在任何一张表里")
        void applicationDefinedCommandsReachTheApplication(@TempDir Path tmp) throws Exception {
            // invoice:send 既不是离线命令、也不在 ONLINE_COMMANDS 里,
            // 而且没有近似匹配 —— 那它很可能是应用里的 @Command
            var result = run(project(tmp), "invoice:send");

            assertThat(result.code()).isEqualTo(1);
            assertThat(result.err())
                    .as("""
                            此前这种名字直接得到「没有命令」,也就是说应用写的
                            artisan 命令从 CLI 上根本到不了 —— 而那是自定义命令
                            最主要的用法。(这个临时目录里没有 pom.xml,
                            所以冷启动那条路也走不通,于是落到这条说明。)""")
                    .contains("这个进程里没有应用")
                    .contains("@Command");
        }

        @Test
        @DisplayName("★ 但拼错了不该冷启动一个 JVM —— 那要几秒")
        void typosDoNotPayForAColdStart(@TempDir Path tmp) throws Exception {
            var result = run(project(tmp), "make:modle", "Post");

            assertThat(result.err())
                    .as("""
                            有近似匹配就按「敲错了」处理:那条路便宜且几乎总是对的。
                            make:modle 不值得为它启动一个应用 JVM(实测 4.9 秒)。""")
                    .contains("是否想写")
                    .doesNotContain("这个进程里没有应用");
        }

        @Test
        @DisplayName("真的拼错了还是给拼写建议")
        void typosStillGetSuggestions(@TempDir Path tmp) throws Exception {
            var result = run(project(tmp), "make:modle", "Post");

            assertThat(result.code()).isEqualTo(1);
            assertThat(result.err()).contains("是否想写 \"make:model\"");
        }
    }

    // ================= list / help / version =================

    @Nested
    @DisplayName("list / help / --version")
    class Meta {

        @Test
        @DisplayName("不带参数等于 list,命令按冒号前那段分组")
        void bareInvocationLists(@TempDir Path tmp) throws Exception {
            var result = run(project(tmp));

            assertThat(result.code()).isZero();
            assertThat(result.out())
                    .contains("make")
                    .contains("make:model")
                    .contains("新建一个 Eloquent 模型")
                    .as("list 与 help 自己也在表里")
                    .contains("list").contains("help");

            int makeGroupAt = result.out().indexOf("\nmake\n");
            assertThat(makeGroupAt).as("分组标题单独一行").isPositive();
        }

        @Test
        @DisplayName("★ help 的用法从注解生成 —— 不是手写的")
        void helpIsGeneratedFromAnnotations(@TempDir Path tmp) throws Exception {
            var result = run(project(tmp), "help", "make:model");

            assertThat(result.out())
                    .contains("用法:make:model <name> [选项]")
                    .contains("-m, --migration")
                    .as("带值的选项要显示它带值")
                    .contains("--table=值")
                    .as("说明直接来自注解")
                    .contains("同时生成建表迁移");
        }

        @Test
        @DisplayName("help 不给命令名时等于 list")
        void helpWithoutArgumentLists(@TempDir Path tmp) throws Exception {
            assertThat(run(project(tmp), "help").out()).contains("make:model");
        }

        @Test
        @DisplayName("help 一条不存在的命令 —— 给拼写建议")
        void helpForUnknownCommand(@TempDir Path tmp) throws Exception {
            var result = run(project(tmp), "help", "make:modle");

            assertThat(result.code()).isEqualTo(1);
            assertThat(result.err()).contains("是否想写 \"make:model\"");
        }

        @Test
        @DisplayName("--version 从 manifest 读,读不到就说开发版")
        void version(@TempDir Path tmp) throws Exception {
            var result = run(project(tmp), "--version");

            assertThat(result.code()).isZero();
            assertThat(result.out())
                    .as("不硬编码版本号 —— 一个报错版本号的 --version 是负资产")
                    .contains("Jaravel")
                    .contains("Java " + Runtime.version().feature());
        }

        @Test
        @DisplayName("-h 走 help")
        void dashH(@TempDir Path tmp) throws Exception {
            assertThat(run(project(tmp), "-h", "make:job").out()).contains("用法:make:job");
        }
    }

    // ================= 分流表本身 =================

    @Test
    @DisplayName("★ CLI 注册的每一条都必须标了 offline = true")
    void everyRegisteredCommandIsOffline() {
        var registry = OfflineCommands.registry(Path.of(""));

        for (var type : OfflineCommands.ALL) {
            Command annotation = type.getAnnotation(Command.class);
            assertThat(annotation)
                    .as("%s 没有 @Command", type.getSimpleName())
                    .isNotNull();
            assertThat(annotation.offline())
                    .as("""
                            %s 在 CLI 的表里但没标 offline —— 漏标的后果是
                            jaravel-cli 在一个没有容器的进程里跑一条需要容器的命令,
                            报错会是「数据库尚未装配」之类与真正原因无关的东西。""",
                            type.getSimpleName())
                    .isTrue();
            assertThat(registry.isOffline(annotation.name())).isTrue();
        }
    }

    @Test
    @DisplayName("在线命令表与离线注册表不重叠 —— 否则分流会撞车")
    void theTwoTablesDoNotOverlap() {
        var registry = OfflineCommands.registry(Path.of(""));

        for (String name : registry.names()) {
            assertThat(Jaravel.ONLINE_COMMANDS.stream().anyMatch(name::startsWith))
                    .as("%s 同时出现在离线注册表与在线命令表里", name)
                    .isFalse();
        }
    }
}
