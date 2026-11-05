package io.jaravel.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.ConsoleIo;

/**
 * 冷启动那条路的三件准备工作(docs/13 §1)。
 *
 * <h2>这里**不**真的起 JVM</h2>
 * <p>起一个应用 JVM 要几秒、要一个能编译的项目、要数据库 ——
 * 那是端到端验证的事(用 {@code examples/blog} 做过:
 * {@code jaravel migrate:status} 在应用没跑的情况下输出了迁移表,
 * 退出码 0)。单元测试钉住的是**决定**:
 * 编不编译、启动哪个类、这条路走不走得通。
 */
class AppLauncherTest {

    @Test
    @DisplayName("★ 没有 pom.xml 就让调用方去打说明 —— 不在这里猜")
    void withoutAPomItDeclines(@TempDir Path tmp) {
        var io = new ConsoleIo.Recording();

        var result = AppLauncher.tryRun(tmp, "migrate", java.util.List.of(), io);

        assertThat(result)
                .as("""
                        可能是 Gradle 项目,也可能只是敲错了目录。
                        返回 empty 让 Jaravel 去打那条说明 —— 它更清楚上下文,
                        而这里要是自己瞎猜一个 mvn 命令,
                        用户看到的会是 Maven 的报错而不是一句人话。""")
                .isEmpty();
        assertThat(io.errors()).isEmpty();
    }

    @Test
    @DisplayName("★ 聚合 pom 不是应用 —— 否则会对整个 reactor 跑 mvn compile")
    void anAggregatorPomIsRefused(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"),
                "<project><modules><module>app</module></modules></project>");
        var io = new ConsoleIo.Recording();

        var result = AppLauncher.tryRun(tmp, "migrate", java.util.List.of(), io);

        assertThat(result).contains(1);
        assertThat(io.errors())
                .as("""
                        在本框架自己的仓库根目录里,那是四十个模块、好几分钟 ——
                        而触发它的只是一个敲在根目录的命令。
                        而且就算编完也没用:哪个模块是应用,这里无从得知。""")
                .contains("聚合 pom");
    }

    // ================= 主类 =================

    @Test
    @DisplayName("★ 扫出带 main 的类,而且**不加载**它")
    void findsTheMainClass(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("target/classes");
        // 真的 class 文件:Jaravel 有 main,OfflineCommands 没有。
        // ⚠ 别拿 AppLauncher 当反面样本 —— 它的常量池里**必然**有那两条,
        // 因为它们就是它自己用来匹配的字符串字面量(第一版就栽在这)
        copyClass(Jaravel.class, classes.resolve("app/Application.class"));
        copyClass(OfflineCommands.class, classes.resolve("app/support/Helper.class"));

        assertThat(AppLauncher.discoverMainClass(classes))
                .as("包名用点,不能漏 Windows 的反斜杠分隔符")
                .isEqualTo("app.Application");
    }

    @Test
    @DisplayName("不叫 Application 也能找到 —— 只是优先级低一点")
    void findsAnUnconventionallyNamedEntry(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("target/classes");
        copyClass(OfflineCommands.class, classes.resolve("app/Helper.class"));
        copyClass(Jaravel.class, classes.resolve("app/boot/Launch.class"));

        assertThat(AppLauncher.discoverMainClass(classes)).isEqualTo("app.boot.Launch");
    }

    @Test
    @DisplayName("一个带 main 的都没有 → null,由调用方给出路")
    void noEntryAtAll(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("target/classes");
        copyClass(OfflineCommands.class, classes.resolve("app/Helper.class"));

        assertThat(AppLauncher.discoverMainClass(classes)).isNull();
    }

    private void copyClass(Class<?> type, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertThat(in).as("读不到 %s 的字节码", type).isNotNull();
            Files.write(target, in.readAllBytes());
        }
    }

    // ================= 真的起一个 JVM =================

    @Test
    @DisplayName("★ spawn 真的起一个 JVM,把 -Djaravel.command 传进去,并把退出码带回来")
    void spawnPassesTheCommandAndReturnsTheExitCode(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();

        int code = AppLauncher.spawn(tmp, EchoMain.class.getName(),
                System.getProperty("java.class.path"),
                "migrate", java.util.List.of("--force"), io);

        assertThat(code)
                .as("""
                        EchoMain 收到预期的属性时退 7,否则退 8。
                        用退出码回话而不是看输出:spawn 用的是 inheritIO(),
                        子进程的输出直接进了 surefire 的控制台,测试捕不到 ——
                        而退出码恰好是启动器与 shell 之间唯一的契约,
                        所以这一条顺带也验证了它真的传得回来。""")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("参数里的空格不会在传递中散掉")
    void argumentsWithSpacesSurviveTheRoundTrip(@TempDir Path tmp) throws Exception {
        var io = new ConsoleIo.Recording();

        // EchoMain 只认 "migrate --force";带空格的参数拼错位的话它会退 8
        int code = AppLauncher.spawn(tmp, EchoMain.class.getName(),
                System.getProperty("java.class.path"),
                "migrate", java.util.List.of("--force"), io);

        assertThat(code).isEqualTo(7);
    }

    @Test
    @DisplayName("★ 用**当前这个** JVM 的 java,而不是 PATH 上那个")
    void javaComesFromThisJvm() {
        assertThat(AppLauncher.javaBinary())
                .as("""
                        PATH 上那个可能是另一个版本(机器上装着 8 和 21 很常见),
                        而症状会是 UnsupportedClassVersionError ——
                        那条报错不会让任何人想到「CLI 和应用用的不是同一个 java」。""")
                .startsWith(System.getProperty("java.home"));
    }

    @Test
    @DisplayName("输出编码透传这个进程自己的,不硬写 UTF-8")
    void encodingIsPropagatedNotGuessed() {
        assertThat(AppLauncher.outputEncoding())
                .as("""
                        真的 cmd.exe 里 cp936 才是对的,强塞 UTF-8 只是把乱码
                        换个方向。所以子进程用的是这个进程自己的编码。""")
                .isEqualTo(System.getProperty("stdout.encoding",
                        System.getProperty("native.encoding", "UTF-8")));
    }

    // ================= Maven =================

    @Test
    @DisplayName("★ 项目自带 mvnw 时优先用它 —— 它钉住了 Maven 的版本")
    void theProjectsWrapperWins(@TempDir Path tmp) throws Exception {
        String wrapper = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? "mvnw.cmd" : "mvnw";
        Files.writeString(tmp.resolve(wrapper), "#!/bin/sh\n");

        assertThat(AppLauncher.maven(tmp, "compile").get(0))
                .as("PATH 上那个 mvn 没有被项目钉住版本")
                .endsWith(wrapper);
    }

    @Test
    @DisplayName("没有 mvnw 就用 PATH 上的 mvn,而且一律离线")
    void fallsBackToMvnOnThePath(@TempDir Path tmp) {
        var argv = AppLauncher.maven(tmp, "compile");

        assertThat(argv.get(0)).startsWith("mvn");
        assertThat(argv)
                .as("""
                        -o(离线):启动器不该因为仓库不通而卡住 ——
                        它是在用户敲一条命令的路上,不是在做发布构建。""")
                .contains("-o", "compile");
    }

    // ================= classpath 缓存 =================

    @Test
    @DisplayName("★ 缓存比 pom.xml 新就直接用 —— 那两三秒是快与慢的全部差别")
    void freshCacheSkipsMavenEntirely(@TempDir Path tmp) throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Path cache = tmp.resolve(".jaravel/classpath");
        Path classes = tmp.resolve("target/classes");
        Files.createDirectories(cache.getParent());
        Files.createDirectories(classes);
        Files.writeString(pom, "<project/>");
        Files.writeString(cache, "/some/dep.jar");
        touch(pom, 1_000_000);
        touch(cache, 2_000_000);
        var io = new ConsoleIo.Recording();

        String result = AppLauncher.classpath(tmp, classes, io);

        assertThat(result)
                .as("target/classes 要在最前面 —— 依赖里可能有同名类")
                .startsWith(classes.toAbsolutePath().toString())
                .endsWith("/some/dep.jar");
        assertThat(io.errors())
                .as("""
                        没有「解析依赖…」那句话,说明根本没去跑 Maven。
                        实测那一步要 2.7 秒,比应用启动本身还慢。""")
                .isEmpty();
    }

    @Test
    @DisplayName("pom.xml 比缓存新 → 要重算(这里只验证它去跑了 Maven)")
    void staleCacheTriggersAResolve(@TempDir Path tmp) throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Path cache = tmp.resolve(".jaravel/classpath");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "/old.jar");
        Files.writeString(pom, "<project/>");
        touch(cache, 1_000_000);
        touch(pom, 2_000_000);
        var io = new ConsoleIo.Recording();

        AppLauncher.classpath(tmp, tmp.resolve("target/classes"), io);

        assertThat(io.errors())
                .as("改了 pom 却还用旧 classpath,症状是「新加的依赖找不到」")
                .contains("解析依赖");
    }

    // ================= 编译 =================

    @Test
    @DisplayName("★ 源码比 class 新 → 必须重编,否则跑的是上一次编译的结果")
    void detectsStaleClasses(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("src/main/java/app/Post.java");
        Path clazz = tmp.resolve("target/classes/app/Post.class");
        Files.createDirectories(source.getParent());
        Files.createDirectories(clazz.getParent());
        Files.writeString(clazz, "旧");
        Files.writeString(source, "新");
        touch(clazz, 1_000_000);
        touch(source, 2_000_000);

        assertThat(AppLauncher.sourcesAreNewerThanClasses(tmp, clazz.getParent().getParent()))
                .as("""
                        这是 Java 这条路独有的陷阱:用户改完迁移、重跑命令,
                        输出看起来完全正常,但跑的是旧代码 ——
                        那种失败会让人去怀疑迁移器、怀疑数据库。""")
                .isTrue();

        touch(clazz, 3_000_000);
        assertThat(AppLauncher.sourcesAreNewerThanClasses(tmp, clazz.getParent().getParent()))
                .as("编译过了就该跳过 Maven —— 那是快与慢的全部差别")
                .isFalse();
    }

    @Test
    @DisplayName("没有 src/main 的目录不会被判成「要重编」")
    void noSourcesAtAll(@TempDir Path tmp) throws Exception {
        Path classes = tmp.resolve("target/classes");
        Files.createDirectories(classes);

        assertThat(AppLauncher.sourcesAreNewerThanClasses(tmp, classes)).isFalse();
    }

    private void touch(Path path, long millis) throws Exception {
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(millis));
    }
}
