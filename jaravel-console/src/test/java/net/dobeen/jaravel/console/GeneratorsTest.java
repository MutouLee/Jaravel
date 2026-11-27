package net.dobeen.jaravel.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import net.dobeen.jaravel.console.generators.GeneratorCommand;
import net.dobeen.jaravel.console.generators.MakeComponent;
import net.dobeen.jaravel.console.generators.MakeConsoleCommand;
import net.dobeen.jaravel.console.generators.MakeController;
import net.dobeen.jaravel.console.generators.MakeEvent;
import net.dobeen.jaravel.console.generators.MakeJob;
import net.dobeen.jaravel.console.generators.MakeListener;
import net.dobeen.jaravel.console.generators.MakeMail;
import net.dobeen.jaravel.console.generators.MakeMiddleware;
import net.dobeen.jaravel.console.generators.MakeMigration;
import net.dobeen.jaravel.console.generators.MakeModel;
import net.dobeen.jaravel.console.generators.MakeFactory;
import net.dobeen.jaravel.console.generators.MakeNotification;
import net.dobeen.jaravel.console.generators.MakePolicy;
import net.dobeen.jaravel.console.generators.MakeRequest;
import net.dobeen.jaravel.console.generators.MakeSeeder;
import net.dobeen.jaravel.console.generators.MakeTest;
import net.dobeen.jaravel.console.generators.MakeView;
import net.dobeen.jaravel.console.generators.Stub;

/**
 * 第二批 make:*(docs/13 §2.2)。
 *
 * <p>每个 stub 的内容都是照仓库里**真实存在的用例**写的
 * (StorePostRequest、ArchiveInvoice、BlogSeeder、AuthAudit、NotePolicy、
 * WelcomeAboard、AuthFeatureTest),不是凭印象编的 ——
 * 一个教错 API 的 stub 比没有这条命令更糟,因为它会被照抄。
 */
class GeneratorsTest {

    @AfterEach
    void clearStaticState() {
        Artisan.reset();
    }

    private void registry(Path projectRoot) {
        var registry = new CommandRegistry();
        List.of(MakeModel.class, MakeController.class, MakeMigration.class,
                        MakeRequest.class, MakeJob.class, MakeMiddleware.class,
                        MakeEvent.class, MakeListener.class, MakePolicy.class,
                        MakeSeeder.class, MakeFactory.class, MakeMail.class, MakeNotification.class,
                        MakeConsoleCommand.class, MakeTest.class,
                        MakeView.class, MakeComponent.class)
                .forEach(registry::register);
        registry.configure(command -> {
            if (command instanceof GeneratorCommand generator) generator.inProject(projectRoot);
        });
        Artisan.use(registry);
    }

    private String read(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new AssertionError("读不了 " + file, e);
        }
    }

    private String generate(Path tmp, String command, String... args) {
        registry(tmp);
        assertThat(Artisan.call(command, args))
                .as("%s 的输出:%s", command, Artisan.output())
                .isZero();
        return Artisan.output();
    }

    // ================= 落点与内容 =================

    /** 命令 → 它该生成的文件(相对项目根) */
    static Stream<Arguments> targets() {
        return Stream.of(
                Arguments.of("make:request", "StorePostRequest",
                        "src/main/java/app/http/requests/StorePostRequest.java"),
                Arguments.of("make:job", "SendInvoice",
                        "src/main/java/app/jobs/SendInvoice.java"),
                Arguments.of("make:middleware", "EnsureSubscribed",
                        "src/main/java/app/http/middleware/EnsureSubscribed.java"),
                Arguments.of("make:event", "OrderPaid",
                        "src/main/java/app/events/OrderPaid.java"),
                Arguments.of("make:listener", "NotifyWarehouse",
                        "src/main/java/app/listeners/NotifyWarehouse.java"),
                Arguments.of("make:seeder", "PostSeeder",
                        "src/main/java/database/seeders/PostSeeder.java"),
                Arguments.of("make:factory", "PostFactory",
                        "src/main/java/database/factories/PostFactory.java"),
                Arguments.of("make:mail", "OrderShipped",
                        "src/main/java/app/mail/OrderShipped.java"),
                Arguments.of("make:notification", "InvoicePaid",
                        "src/main/java/app/notifications/InvoicePaid.java"),
                Arguments.of("make:command", "SyncInventory",
                        "src/main/java/app/console/SyncInventory.java"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("targets")
    @DisplayName("生成到 docs/16 §5 约定的那个包下,包声明与目录一致")
    void generatesToTheConventionalPackage(String command, String name, String expected,
                                           @TempDir Path tmp) {
        generate(tmp, command, name);

        Path file = tmp.resolve(expected);
        assertThat(file).exists();

        String expectedPackage = expected
                .replace("src/main/java/", "")
                .replace("/" + name + ".java", "")
                .replace('/', '.');
        assertThat(read(file))
                .as("包声明与目录对不上的话,javac 第一件事就是报错")
                .contains("package " + expectedPackage + ";");
    }

    @Test
    @DisplayName("★ 每个生成出来的文件都要过编译器的语法检查")
    void generatedSourcesParse(@TempDir Path tmp) throws Exception {
        registry(tmp);
        for (Arguments target : targets().toList()) {
            Object[] args = target.get();
            Artisan.call((String) args[0], (String) args[1]);
        }
        Artisan.call("make:model", "Post");
        Artisan.call("make:controller", "PostController", "--resource");
        Artisan.call("make:controller", "ApiController", "--api");
        Artisan.call("make:migration", "create_posts_table");
        Artisan.call("make:policy", "PostPolicy");
        Artisan.call("make:listener", "NotifyShipped", "--event=OrderPaid");
        Artisan.call("make:test", "PostTest");
        Artisan.call("make:test", "StrTest", "--unit");
        Artisan.call("make:component", "Alert");

        try (var walk = Files.walk(tmp)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(files).hasSizeGreaterThanOrEqualTo(17);

            for (Path file : files) {
                String source = read(file);
                assertThat(parses(source))
                        .as("%s 的语法不对:%n%s", file.getFileName(), source)
                        .isTrue();
            }
        }
    }

    /**
     * 只做**语法**检查,不做符号解析。
     *
     * <p>生成的代码引用应用自己的类(app.models.Post)与框架的类,
     * 在这个模块的 classpath 上一个都没有 —— 真编译会得到一堆
     * 「找不到符号」,与「stub 写得对不对」无关。
     *
     * <p>语法这一层仍然值得查:少一个括号的 stub、被替换坏的占位符、
     * 一个漏掉的 {@code {{ 变量 }}},都会在这里暴露。
     * 而 API 名字对不对由另一件事保证 —— 每个 stub 都是照仓库里
     * 真实存在的用例写的(见类注释)。
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

    // ================= 各命令自己的那点逻辑 =================

    @Nested
    @DisplayName("make:listener")
    class Listeners {

        @Test
        @DisplayName("★ 没给 --event 时生成 handle(Object) —— 不能填一个不存在的事件类")
        void withoutEvent(@TempDir Path tmp) {
            generate(tmp, "make:listener", "NotifyWarehouse");

            assertThat(read(tmp.resolve("src/main/java/app/listeners/NotifyWarehouse.java")))
                    .as("填一个 ExampleEvent 的话,生成出来的是编译不过的文件")
                    .contains("handle(Object event)")
                    .contains("--event=")
                    .doesNotContain("import app.events.");
        }

        @Test
        @DisplayName("给了 --event 时导入它并用作参数类型")
        void withEvent(@TempDir Path tmp) {
            generate(tmp, "make:listener", "NotifyWarehouse", "--event=order-paid");

            assertThat(read(tmp.resolve("src/main/java/app/listeners/NotifyWarehouse.java")))
                    .contains("import app.events.OrderPaid;")
                    .contains("handle(OrderPaid event)");
        }
    }

    @Nested
    @DisplayName("make:policy")
    class Policies {

        @Test
        @DisplayName("模型从类名推断:PostPolicy → Post")
        void inferredModel(@TempDir Path tmp) {
            generate(tmp, "make:policy", "PostPolicy");

            assertThat(read(tmp.resolve("src/main/java/app/policies/PostPolicy.java")))
                    .contains("@Policy(Post.class)")
                    .contains("import app.models.Post;")
                    .contains("public boolean update(User user, Post model)");
        }

        @Test
        @DisplayName("★ 推不出模型时报错 —— 没有模型的策略永远不会被调用")
        void withoutModelFails(@TempDir Path tmp) {
            registry(tmp);

            assertThat(Artisan.call("make:policy", "Whatever")).isEqualTo(1);
            assertThat(Artisan.output())
                    .as("症状会是「授权规则没生效」,与一个没填的注解之间没有线索")
                    .contains("--model=");
        }
    }

    @Nested
    @DisplayName("make:factory")
    class Factories {

        @Test
        @DisplayName("模型从类名推断:PostFactory → Post,并继承生成的基类")
        void inferredModel(@TempDir Path tmp) {
            generate(tmp, "make:factory", "PostFactory");

            assertThat(read(tmp.resolve("src/main/java/database/factories/PostFactory.java")))
                    .contains("import app.models.Post;")
                    .contains("extends PostFactoryBase")
                    .contains("protected void definition(Faker f, Post post)");
        }

        @Test
        @DisplayName("★ 输出里带上模型上要加的那行 —— 少了它生成的工厂编译不过")
        void tellsYouToAnnotateTheModel(@TempDir Path tmp) {
            generate(tmp, "make:factory", "PostFactory");

            assertThat(Artisan.output())
                    .as("""
                            命令不去改模型文件(往用户写过的文件里插注解会把代码改坏),
                            所以另一半必须在输出里说清,而且要能照抄。""")
                    .contains("@UseFactory(database.factories.PostFactory.class)");
        }

        @Test
        @DisplayName("推不出模型时报错,而不是生成一个编译不过的类")
        void withoutModelFails(@TempDir Path tmp) {
            registry(tmp);

            assertThat(Artisan.call("make:factory", "Whatever")).isEqualTo(1);
            assertThat(Artisan.output()).contains("--model=");
        }
    }

    @Nested
    @DisplayName("make:command")
    class Commands {

        @Test
        @DisplayName("★ 命令名默认 app:kebab-类名 —— 前缀是为了不遮住框架的命令")
        void defaultCommandName(@TempDir Path tmp) {
            generate(tmp, "make:command", "SyncInventory");

            assertThat(read(tmp.resolve("src/main/java/app/console/SyncInventory.java")))
                    .contains("name = \"app:sync-inventory\"")
                    .as("""
                            ★ 不再写 @Scope("prototype")。
                            注册表每跑一次新造一个实例(createBean),所以那个仪式
                            不再是必须的 —— 而一个生成出来就带着多余注解的模板
                            会被照抄到每一条命令上。
                            (这条测试原先断言的恰好相反,是对账时改过来的。)""")
                    .doesNotContain("Scope");
        }

        @Test
        @DisplayName("--command 覆盖默认名")
        void explicitCommandName(@TempDir Path tmp) {
            generate(tmp, "make:command", "SyncInventory", "--command=inventory:sync");

            assertThat(read(tmp.resolve("src/main/java/app/console/SyncInventory.java")))
                    .contains("name = \"inventory:sync\"");
        }
    }

    @Nested
    @DisplayName("make:test")
    class Tests {

        @Test
        @DisplayName("★ 写到 src/test/java,默认是功能测试")
        void featureByDefault(@TempDir Path tmp) {
            generate(tmp, "make:test", "PostTest");

            Path file = tmp.resolve("src/test/java/apptest/PostTest.java");
            assertThat(file).as("唯一一条换源码根的生成命令").exists();
            assertThat(read(file))
                    .contains("extends FeatureTest")
                    .contains("package apptest;");
        }

        @Test
        @DisplayName("--unit 生成不起容器的测试")
        void unitOnDemand(@TempDir Path tmp) {
            generate(tmp, "make:test", "StrTest", "--unit");

            assertThat(read(tmp.resolve("src/test/java/apptest/StrTest.java")))
                    .doesNotContain("FeatureTest")
                    .contains("单元测试");
        }
    }

    @Nested
    @DisplayName("make:view")
    class Views {

        @Test
        @DisplayName("落在 src/main/resources/views,点号是视图名里的层级")
        void generatesToViewsRoot(@TempDir Path tmp) {
            generate(tmp, "make:view", "posts.index");

            Path file = tmp.resolve("src/main/resources/views/posts/index.jblade.html");
            assertThat(file).exists();
            assertThat(read(file)).contains("{{-- posts.index --}}");
        }

        @Test
        @DisplayName("带路径与扩展名也收:posts/index.jblade.html")
        void acceptsSlashAndExtension(@TempDir Path tmp) {
            generate(tmp, "make:view", "posts/index.jblade.html");

            assertThat(tmp.resolve("src/main/resources/views/posts/index.jblade.html")).exists();
        }

        @Test
        @DisplayName("★ .. 不被静默规整 —— 那是一次路径穿越")
        void rejectsTraversal(@TempDir Path tmp) {
            registry(tmp);

            assertThat(Artisan.call("make:view", "../secrets")).isEqualTo(1);
            assertThat(Artisan.output()).contains("不合法").contains("..");
            assertThat(tmp.resolve("src/main/resources/views/secrets.jblade.html"))
                    .as("跳过空片段的话,../secrets 会被规整成一个正常的 secrets 视图")
                    .doesNotExist();
        }
    }

    @Nested
    @DisplayName("make:component")
    class Components {

        @Test
        @DisplayName("类 + 模板一起生成,@params 指向这个类")
        void generatesClassAndView(@TempDir Path tmp) {
            generate(tmp, "make:component", "Alert");

            assertThat(read(tmp.resolve("src/main/java/app/http/components/Alert.java")))
                    .contains("package app.http.components;")
                    .contains("public class Alert extends Component")
                    .contains("return \"components.alert\";");

            assertThat(read(tmp.resolve("src/main/resources/views/components/alert.jblade.html")))
                    .contains("@params(app.http.components.Alert self, gg.jte.Content slot = null)")
                    .as("模板里的 {{ slot }} 是 jblade 语法;\\{{ slot }} 只是 Stub 的转义")
                    .contains("{{ slot }}")
                    .doesNotContain("\\{{");
        }

        @Test
        @DisplayName("点号嵌套 → 类在子包里,标签是 <x-forms.input>")
        void nestedComponent(@TempDir Path tmp) {
            generate(tmp, "make:component", "Forms.Input");

            assertThat(read(tmp.resolve("src/main/java/app/http/components/forms/Input.java")))
                    .contains("package app.http.components.forms;")
                    .contains("public class Input extends Component");
            assertThat(read(tmp.resolve(
                    "src/main/resources/views/components/forms/input.jblade.html")))
                    .contains("@params(app.http.components.forms.Input self");
        }

        @Test
        @DisplayName("kebab 名字 → 类是大驼峰,文件是 kebab")
        void normalisesName(@TempDir Path tmp) {
            generate(tmp, "make:component", "user-avatar");

            assertThat(tmp.resolve("src/main/java/app/http/components/UserAvatar.java")).exists();
            assertThat(tmp.resolve(
                    "src/main/resources/views/components/user-avatar.jblade.html")).exists();
        }

        @Test
        @DisplayName("★ 只写一半就失败:两个文件要么都生成、要么都不生成")
        void refusesHalfway(@TempDir Path tmp) throws Exception {
            Path existing = tmp.resolve(
                    "src/main/resources/views/components/alert.jblade.html");
            Files.createDirectories(existing.getParent());
            Files.writeString(existing, "已有内容");

            registry(tmp);
            assertThat(Artisan.call("make:component", "Alert")).isEqualTo(1);
            assertThat(Artisan.output()).contains("已经存在").contains("--force");
            assertThat(tmp.resolve("src/main/java/app/http/components/Alert.java"))
                    .as("模板已存在时不能先把类写出来 —— 留下的是一个只有一半的组件")
                    .doesNotExist();
            assertThat(read(existing)).isEqualTo("已有内容");
        }
    }

    @Nested
    @DisplayName("共同行为")
    class Shared {

        @Test
        @DisplayName("已存在时拒绝覆盖,--force 才覆盖(与第一批一致)")
        void refusesToOverwrite(@TempDir Path tmp) {
            generate(tmp, "make:job", "SendInvoice");

            assertThat(Artisan.call("make:job", "SendInvoice")).isEqualTo(1);
            assertThat(Artisan.output()).contains("已经存在").contains("--force");
            assertThat(Artisan.call("make:job", "SendInvoice", "--force")).isZero();
        }

        @Test
        @DisplayName("名字写成 send-invoice 也当成 SendInvoice")
        void normalisesName(@TempDir Path tmp) {
            generate(tmp, "make:job", "send-invoice");

            assertThat(tmp.resolve("src/main/java/app/jobs/SendInvoice.java")).exists();
        }

        @Test
        @DisplayName("输出里带上生成的路径 —— 下一步要去哪改,不用猜")
        void reportsThePath(@TempDir Path tmp) {
            String output = generate(tmp, "make:request", "StorePostRequest");

            assertThat(output).contains("请求类")
                              .contains("src/main/java/app/http/requests/StorePostRequest.java");
        }

        @Test
        @DisplayName("stub 名单与实际打包进去的资源一致")
        void bundledStubsExist() {
            for (String name : Stub.bundled()) {
                assertThat(getClass().getClassLoader()
                        .getResourceAsStream("stubs/" + name + ".stub"))
                        .as("stubs/%s.stub 在名单里但没打包进去", name)
                        .isNotNull();
            }
        }
    }
}
