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
