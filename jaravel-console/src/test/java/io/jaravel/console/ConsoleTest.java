package io.jaravel.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.generators.GeneratorCommand;
import io.jaravel.console.generators.MakeController;
import io.jaravel.console.generators.MakeMigration;
import io.jaravel.console.generators.MakeModel;
import io.jaravel.console.generators.Stub;

/** 控制台内核 + make:* 代码生成(docs/13) */
class ConsoleTest {

    @AfterEach
    void clearStaticState() {
        Artisan.reset();
    }

    /** 把生成目标指到临时目录,并给迁移一个确定的时间戳 */
    private CommandRegistry registry(Path projectRoot) {
        return new CommandRegistry()
                .register(MakeModel.class)
                .register(MakeController.class)
                .register(MakeMigration.class)
                .configure(command -> {
                    if (command instanceof GeneratorCommand generator) {
                        generator.inProject(projectRoot);
                    }
                    if (command instanceof MakeMigration migration) {
                        migration.at(LocalDateTime.of(2026, 11, 4, 12, 30, 0));
                    }
                });
    }

    private String read(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new AssertionError("读不了 " + file, e);
        }
    }

    // ================= 参数绑定 =================

    @Nested
    @DisplayName("参数绑定")
    class Binding {

        @Command(name = "demo", description = "绑定用的样例命令")
        static final class Demo extends ConsoleCommand {
            @Argument String target;
            @Argument(required = false) String extra;
            @Option(names = {"-f", "--force"}) boolean force;
            @Option(names = {"-m", "--migration"}) boolean migration;
            @Option(names = {"-c"}) boolean controller;
            @Option(names = "--table") String table;
            @Option(names = "--chunk", defaultValue = "500") int chunk;

            @Override public int handle() {
                line(target + "|" + extra + "|" + force + migration + controller
                        + "|" + table + "|" + chunk);
                return 0;
            }
        }

        private String run(String... args) {
            Artisan.use(new CommandRegistry().register(Demo.class));
            Artisan.call("demo", args);
            return Artisan.output().trim();
        }

        @Test
        @DisplayName("位置参数按声明顺序,选项两种写法都收")
        void positionalsAndOptions() {
            assertThat(run("Post", "x", "--table=posts")).isEqualTo("Post|x|falsefalsefalse|posts|500");
            assertThat(run("Post", "--table", "posts")).isEqualTo("Post|null|falsefalsefalse|posts|500");
        }

        @Test
        @DisplayName("★ 合并的短开关 -mcf 等价于 -m -c -f(artisan 的手感)")
        void clusteredShortFlags() {
            assertThat(run("Post", "-mcf")).isEqualTo("Post|null|truetruetrue|null|500");
        }

        @Test
        @DisplayName("defaultValue 在没给时生效")
        void defaults() {
            assertThat(run("Post")).contains("|500");
            assertThat(run("Post", "--chunk=10")).contains("|10");
        }

        @Test
        @DisplayName("★ 不认识的选项要报错 —— 静默忽略会让退出码 0 骗过脚本")
        void unknownOptionFails() {
            Artisan.use(new CommandRegistry().register(Demo.class));
            int code = Artisan.call("demo", "Post", "--migrations");

            assertThat(code).isEqualTo(1);
            assertThat(Artisan.output())
                    .contains("不认识选项 --migrations")
                    .as("顺带把可用的选项列出来")
                    .contains("--migration");
        }

        @Test
        @DisplayName("★ 缺少必填参数时,用法是从注解**生成**的")
        void missingRequiredArgument() {
            Artisan.use(new CommandRegistry().register(Demo.class));
            assertThat(Artisan.call("demo")).isEqualTo(1);

            assertThat(Artisan.output())
                    .contains("缺少参数 <target>")
                    .as("""
                            原先这行用法是手写的,与真实参数表之间没有关联 ——
                            加一个选项不会让它跟着变,而一条说错了用法的报错
                            比没有报错更费时间。""")
                    .contains("用法:demo <target> [extra] [选项]")
                    .contains("-f, --force")
                    .contains("--chunk=值")
                    .contains("(默认 500)");
        }

        @Test
        @DisplayName("多余的位置参数要报错,而不是悄悄丢掉")
        void extraArgumentsFail() {
            Artisan.use(new CommandRegistry().register(Demo.class));
            assertThat(Artisan.call("demo", "a", "b", "c")).isEqualTo(1);
            assertThat(Artisan.output()).contains("多余的参数:c");
        }

        @Test
        @DisplayName("整数选项给了非数字时,报错说清它要什么")
        void badInteger() {
            Artisan.use(new CommandRegistry().register(Demo.class));
            assertThat(Artisan.call("demo", "Post", "--chunk=many")).isEqualTo(1);
            assertThat(Artisan.output()).contains("需要一个整数");
        }

        @Test
        @DisplayName("★ 每次调用都是一个新实例 —— 上一次的 --force 不该漏过来")
        void commandsAreNotReused() {
            Artisan.use(new CommandRegistry().register(Demo.class));

            Artisan.call("demo", "Post", "-f");
            assertThat(Artisan.output()).contains("|truefalsefalse|");

            Artisan.call("demo", "Post");
            assertThat(Artisan.output())
                    .as("注册表存的是工厂而不是实例,正是为了这件事")
                    .contains("|falsefalsefalse|");
        }
    }

    // ================= 注册表 =================

    @Nested
    @DisplayName("命令注册表")
    class Registry {

        @Test
        @DisplayName("★ 没有这条命令时给出最接近的那个")
        void suggestsClosestName(@TempDir Path tmp) {
            var io = new ConsoleIo.Recording();
            int code = registry(tmp).run("make:modle", List.of(), io);

            assertThat(code).isEqualTo(1);
            assertThat(io.errors())
                    .as("一句「命令不存在」留给用户的下一步是去翻清单,而答案就在一个字母之外")
                    .contains("是否想写 \"make:model\"");
        }

        @Test
        @DisplayName("同组的命令一起列出来 —— 冒号就是分组")
        void listsSameGroup(@TempDir Path tmp) {
            var io = new ConsoleIo.Recording();
            registry(tmp).run("make:nonsense-xyz", List.of(), io);

            assertThat(io.errors()).contains("make:").contains("make:model").contains("make:controller");
        }

        @Test
        @DisplayName("名字差太远时不乱猜")
        void doesNotGuessWildly(@TempDir Path tmp) {
            var io = new ConsoleIo.Recording();
            registry(tmp).run("zzz", List.of(), io);

            assertThat(io.errors()).contains("没有命令").doesNotContain("是否想写");
        }

        @Test
        @DisplayName("没有无参构造的命令要说清该怎么注册")
        void needsFactoryForDependencies() {
            assertThatThrownBy(() -> new CommandRegistry().register(NeedsDependency.class))
                    .isInstanceOf(ConsoleException.class)
                    .hasMessageContaining("register(name, factory)");
        }

        @Command(name = "needs:dep")
        static final class NeedsDependency extends ConsoleCommand {
            NeedsDependency(String dependency) { }
            @Override public int handle() { return 0; }
        }
    }

    // ================= 输出与交互 =================

    @Nested
    @DisplayName("输出与交互")
    class Output {

        @Command(name = "io:demo")
        static final class IoDemo extends ConsoleCommand {
            @Override public int handle() {
                success("成了");
                error("炸了");
                table(List.of("列", "数量"), List.of(List.of("铁观音", 2), List.of("a", 10)));
                withProgressBar(List.of(1, 2, 3, 4), item -> { });
                return 0;
            }
        }

        private ConsoleIo.Recording run(ConsoleCommand command, String... answers) {
            var io = new ConsoleIo.Recording(answers);
            new CommandRegistry().register(() -> command).run(command.name(), List.of(), io);
            return io;
        }

        @Test
        @DisplayName("★ 错误走 stderr —— 不然 route:list --json | jq 会被报错污染")
        void errorsGoToStderr() {
            var io = run(new IoDemo());

            assertThat(io.output()).contains("成了").doesNotContain("炸了");
            assertThat(io.errors()).contains("炸了");
        }

        @Test
        @DisplayName("★ 表格的列宽按显示宽度算 —— 中文占两格,否则每行都错位")
        void tableAlignsCjk() {
            var io = run(new IoDemo());

            var lines = io.output().lines().filter(l -> l.startsWith("│")).toList();
            assertThat(lines).hasSize(3);
            assertThat(lines.stream().map(ConsoleCommand::displayWidth).distinct())
                    .as("三行(表头 + 两行数据)的显示宽度必须一致")
                    .hasSize(1);
        }

        @Test
        @DisplayName("不可交互时进度条打里程碑,而不是用回车重画")
        void progressBarInPipes() {
            var io = run(new IoDemo());

            assertThat(io.output())
                    .as("重定向到 CI 日志里,每次重画都会变成新的一行")
                    .doesNotContain("\r")
                    .contains("4/4 (100%)");
        }

        @Command(name = "ask:demo")
        static final class AskDemo extends ConsoleCommand {
            @Override public int handle() {
                line("confirm=" + confirm("要继续吗?", false));
                line("name=" + ask("项目名", "blog"));
                line("env=" + choice("目标环境", List.of("staging", "production"), "staging"));
                return 0;
            }
        }

        @Test
        @DisplayName("回答被采纳,确认提示里带默认值")
        void answersAreUsed() {
            var io = run(new AskDemo(), "y", "shop", "production");

            assertThat(io.output()).contains("confirm=true").contains("name=shop")
                                   .contains("env=production");
            assertThat(io.prompts().get(0)).contains("(y/N)");
        }

        @Test
        @DisplayName("★ 不可交互时走默认值,而不是等一个永远不会来的回车")
        void fallsBackToDefaults() {
            var io = run(new AskDemo());   // 没有预设答案 = 没有终端

            assertThat(io.output())
                    .as("在 CI 里那是一个要等到超时才失败的任务")
                    .contains("confirm=false").contains("name=blog").contains("env=staging");
        }

        @Test
        @DisplayName("choice 遇到不在选项里的回答会重问")
        void choiceRejectsUnknown() {
            var io = run(new AskDemo(), "y", "shop", "prod", "production");

            assertThat(io.output()).contains("不是可选项:prod").contains("env=production");
        }
    }

    // ================= Artisan 门面 =================

    @Nested
    @DisplayName("Artisan 门面")
    class Facade {

        @Test
        @DisplayName("Map 形式的参数:带 - 的是选项,其余是位置参数")
        void mapArguments(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            int code = Artisan.call("make:model", Map.of("name", "Post", "--table", "articles"));

            assertThat(code).isZero();
            assertThat(read(tmp.resolve("src/main/java/app/models/Post.java")))
                    .contains("table = \"articles\"");
        }

        @Test
        @DisplayName("★ Map 带多个位置参数时报错 —— Map 没有顺序")
        void mapWithTwoPositionalsFails(@TempDir Path tmp) {
            Artisan.use(registry(tmp));

            assertThatThrownBy(() -> Artisan.call("make:model", Map.of("a", "1", "b", "2")))
                    .isInstanceOf(ConsoleException.class)
                    .hasMessageContaining("只能带一个位置参数");
        }

        @Test
        @DisplayName("output() 是上一次调用的输出,含 stderr")
        void outputIsLastCall(@TempDir Path tmp) {
            Artisan.use(registry(tmp));

            Artisan.call("make:model", "Post");
            assertThat(Artisan.output()).contains("Post.java");

            Artisan.call("make:model", "Post");
            assertThat(Artisan.output())
                    .as("第二次撞上已存在 —— 报错也要进 output(),调用方拿它记日志")
                    .contains("已经存在");
        }

        @Test
        @DisplayName("没装配时的报错要说清怎么装")
        void unconfigured() {
            assertThatThrownBy(() -> Artisan.call("make:model", "Post"))
                    .isInstanceOf(ConsoleException.class)
                    .hasMessageContaining("Artisan.use");
        }
    }

    // ================= stub =================

    @Nested
    @DisplayName("stub 机制")
    class Stubs {

        @Test
        @DisplayName("★ 项目的 stubs/ 覆盖框架自带的(docs/13 §4.2)")
        void projectStubWins(@TempDir Path tmp) throws Exception {
            Files.createDirectories(tmp.resolve("stubs"));
            Files.writeString(tmp.resolve("stubs/model.stub"), """
                    // 本公司版权所有
                    package {{ pkg }};
                    public class {{ class }} { }
                    """);

            Artisan.use(registry(tmp));
            Artisan.call("make:model", "Post");

            assertThat(read(tmp.resolve("src/main/java/app/models/Post.java")))
                    .as("团队加版权头、加审计字段、换包结构约定,不该需要改框架")
                    .contains("本公司版权所有")
                    .doesNotContain("@Model");
        }

        @Test
        @DisplayName("★ 变量没给值时报错,而不是把 {{ x }} 留在 Java 文件里")
        void missingPlaceholderFails() {
            assertThatThrownBy(() -> Stub.of("model").with("pkg", "app.models").render())
                    .isInstanceOf(ConsoleException.class)
                    .as("""
                            留着那串字面量的话,生成出来的是一个编译不过的文件,
                            而 javac 的报错会指向用户的新文件。""")
                    .hasMessageContaining("没给值的变量")
                    .hasMessageContaining("class");
        }

        @Test
        @DisplayName("没有这个 stub 时列出有哪些")
        void unknownStub() {
            assertThatThrownBy(() -> Stub.of("nope").render())
                    .isInstanceOf(ConsoleException.class)
                    .hasMessageContaining("没有 stub")
                    .hasMessageContaining("model");
        }

        @Test
        @DisplayName("自带的 stub 名单与实际打包进去的资源一致")
        void bundledListMatchesResources() {
            for (String name : Stub.bundled()) {
                assertThat(Stub.class.getClassLoader().getResourceAsStream("stubs/" + name + ".stub"))
                        .as("stubs/%s.stub 在名单里但没打包进去", name)
                        .isNotNull();
            }
        }
    }

    // ================= make:* =================

    @Nested
    @DisplayName("make:model")
    class Models {

        @Test
        @DisplayName("★ 生成的模型落在 docs/16 的布局里,表名由类名推断")
        void generatesModel(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            assertThat(Artisan.call("make:model", "Post")).isZero();

            Path file = tmp.resolve("src/main/java/app/models/Post.java");
            assertThat(file).exists();
            assertThat(read(file))
                    .contains("package app.models;")
                    .contains("@Model(table = \"posts\")")
                    .as("模型继承 APT 生成的基类(examples/blog 里就是这个形态)")
                    .contains("extends PostBase");
            assertThat(Artisan.output()).contains("src/main/java/app/models/Post.java");
        }

        @Test
        @DisplayName("★ 表名只复数化最后一个词:PostCategory → post_categories")
        void tableNameInflection(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            Artisan.call("make:model", "PostCategory");

            assertThat(read(tmp.resolve("src/main/java/app/models/PostCategory.java")))
                    .as("Str.plural 作用在整串上会得到 post_categorys —— 复数规则作用在词上")
                    .contains("table = \"post_categories\"");
        }

        @Test
        @DisplayName("名字写成 post / Post.java / blog/Post 都当成 Post")
        void normalisesName(@TempDir Path tmp) {
            Artisan.use(registry(tmp));

            Artisan.call("make:model", "post");
            Artisan.call("make:model", "Post.java", "--force");
            Artisan.call("make:model", "blog/Post", "--force");

            assertThat(tmp.resolve("src/main/java/app/models/Post.java")).exists();
        }

        @Test
        @DisplayName("★ -mcr 连带生成迁移与资源控制器,而且都落在同一个项目根下")
        void withMigrationAndController(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            assertThat(Artisan.call("make:model", "Post", "-mcr")).isZero();

            assertThat(tmp.resolve("src/main/java/app/models/Post.java")).exists();
            assertThat(tmp.resolve("src/main/java/database/migrations/m2026_11_04_123000/CreatePostsTable.java"))
                    .as("子命令是注册表新建的实例 —— 项目根要靠 configure 钩子传下去")
                    .exists();
            Path controller = tmp.resolve("src/main/java/app/http/controllers/PostController.java");
            assertThat(controller).exists();
            assertThat(read(controller)).contains("public View index(").contains("/posts");
        }

        @Test
        @DisplayName("★ 已存在时拒绝覆盖,并把 --force 说出来")
        void refusesToOverwrite(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            Artisan.call("make:model", "Post");

            assertThat(Artisan.call("make:model", "Post")).isEqualTo(1);
            assertThat(Artisan.output())
                    .as("用户在那个文件里写了两小时的代码,一句 make:model 就能让它变回空模型")
                    .contains("已经存在").contains("--force");

            assertThat(Artisan.call("make:model", "Post", "--force")).isZero();
        }
    }

    @Nested
    @DisplayName("make:migration")
    class Migrations {

        @Test
        @DisplayName("★ 时间戳在包名上 —— Java 的类名不能以数字开头,也不能重名")
        void timestampIsInThePackage(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            Artisan.call("make:migration", "create_posts_table");

            Path file = tmp.resolve(
                    "src/main/java/database/migrations/m2026_11_04_123000/CreatePostsTable.java");
            assertThat(file).exists();
            assertThat(read(file))
                    .contains("package database.migrations.m2026_11_04_123000;")
                    .contains("Schema.create(\"posts\"");
        }

        @Test
        @DisplayName("add_slug_to_posts 认出是改表,用 Schema.table")
        void recognisesUpdateMigration(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            Artisan.call("make:migration", "add_slug_to_posts");

            assertThat(read(tmp.resolve(
                    "src/main/java/database/migrations/m2026_11_04_123000/AddSlugToPosts.java")))
                    .contains("Schema.table(\"posts\"")
                    .doesNotContain("Schema.create");
        }

        @Test
        @DisplayName("--table / --create 优先于从名字里猜")
        void explicitTableWins(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            Artisan.call("make:migration", "whatever", "--create=articles");

            assertThat(read(tmp.resolve(
                    "src/main/java/database/migrations/m2026_11_04_123000/Whatever.java")))
                    .contains("Schema.create(\"articles\"");
        }

        @Test
        @DisplayName("★ 猜不出表名时不拒绝生成,只提示一句")
        void unguessableNameStillGenerates(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            assertThat(Artisan.call("make:migration", "fix_the_thing")).isZero();

            assertThat(Artisan.output())
                    .as("名字是用户起的,不该为了迁就命名约定而拒绝生成文件")
                    .contains("猜不出表名").contains("--table=");
        }
    }

    @Nested
    @DisplayName("make:controller")
    class Controllers {

        @Test
        @DisplayName("默认是最小控制器,--resource 给七个方法")
        void variants(@TempDir Path tmp) {
            Artisan.use(registry(tmp));

            Artisan.call("make:controller", "HomeController");
            assertThat(read(tmp.resolve("src/main/java/app/http/controllers/HomeController.java")))
                    .contains("public Object index(").doesNotContain("destroy");

            Artisan.call("make:controller", "PostController", "--resource");
            assertThat(read(tmp.resolve("src/main/java/app/http/controllers/PostController.java")))
                    .contains("index").contains("create").contains("destroy");
        }

        @Test
        @DisplayName("★ --api 自己就意味着资源控制器 —— 不必再写 --resource")
        void apiImpliesResource(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            Artisan.call("make:controller", "PostController", "--api");

            String source = read(tmp.resolve("src/main/java/app/http/controllers/PostController.java"));
            assertThat(source)
                    .as("写了 --api 还得再写 --resource 是个一定会被漏掉的组合,"
                            + "而漏掉的结果是一个只有 index 的空壳")
                    .contains("destroy")
                    .as("API 没有返回表单页面的那两个")
                    .doesNotContain("public View create").doesNotContain("public View edit");
        }

        @Test
        @DisplayName("路由段由模型名推断,kebab 化:PostCategory → post-categories")
        void routeSegment(@TempDir Path tmp) {
            Artisan.use(registry(tmp));
            Artisan.call("make:controller", "PostCategoryController", "--resource");

            assertThat(read(tmp.resolve(
                    "src/main/java/app/http/controllers/PostCategoryController.java")))
                    .contains("/post-categories");
        }
    }
}
