package io.jaravel.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.generators.GeneratorCommand;
import io.jaravel.console.generators.WrapperInstallCommand;

/**
 * {@code jaravel wrapper:install}(docs/13 §1.1)。
 *
 * <p>生成的脚本本身在 {@code examples/blog} 上端到端跑过:
 * {@code ./jaravelw list} 0.27 秒、{@code ./jaravelw migrate:status}
 * 冷启动 4.9 秒、退出码 0。这里钉住的是**内容**里那几件
 * 「错了很难查」的事。
 */
class WrapperInstallTest {

    @AfterEach
    void clearStaticState() {
        Artisan.reset();
    }

    private static final String VERSION = "9.9.9";

    private String generate(Path tmp, String... args) {
        var registry = new CommandRegistry();
        registry.register(WrapperInstallCommand.class);
        registry.configure(command -> {
            if (command instanceof GeneratorCommand generator) generator.inProject(tmp);
        });
        Artisan.use(registry);
        assertThat(Artisan.call("wrapper:install", args))
                .as("输出:%s", Artisan.output())
                .isZero();
        return Artisan.output();
    }

    private String read(Path file) throws Exception {
        return Files.readString(file);
    }

    @Test
    @DisplayName("★ jaravelw 必须是 LF —— CRLF 会让 #!/bin/sh 变成找不到的解释器")
    void posixScriptUsesUnixLineEndings(@TempDir Path tmp) throws Exception {
        generate(tmp, "--jaravel-version=" + VERSION);

        assertThat(read(tmp.resolve("jaravelw")))
                .as("""
                        CRLF 的症状是 bad interpreter: No such file or directory ——
                        那条报错指着一个明明存在的路径(/bin/sh),极难查。
                        所以这个文件的行尾不能交给平台(Windows 上生成也一样)。""")
                .doesNotContain("\r")
                .startsWith("#!/bin/sh\n");
    }

    @Test
    @DisplayName(".cmd 反过来要 CRLF —— cmd.exe 对 LF 的多行结构会出错")
    void windowsScriptUsesCrlf(@TempDir Path tmp) throws Exception {
        generate(tmp, "--jaravel-version=" + VERSION);

        assertThat(read(tmp.resolve("jaravelw.cmd")))
                .contains("\r\n")
                .doesNotContain("\n\n");   // 全是 CRLF,没有落单的 LF
    }

    @Test
    @DisplayName("★ 版本号钉进两个脚本 —— 那是 wrapper 存在的理由")
    void versionIsPinnedInBoth(@TempDir Path tmp) throws Exception {
        generate(tmp, "--jaravel-version=" + VERSION);

        assertThat(read(tmp.resolve("jaravelw")))
                .as("团队里每个人、CI 上那台机器,用的要是同一个版本")
                .contains("JARAVEL_VERSION=" + VERSION);
        assertThat(read(tmp.resolve("jaravelw.cmd")))
                .contains("JARAVEL_VERSION=" + VERSION);
    }

    @Test
    @DisplayName("★ 两份实现的一致性:主类、缓存文件名、版本号都要对上")
    void bothScriptsAgree(@TempDir Path tmp) throws Exception {
        generate(tmp, "--jaravel-version=" + VERSION);
        String posix = read(tmp.resolve("jaravelw"));
        String windows = read(tmp.resolve("jaravelw.cmd")).replace("\r\n", "\n");

        for (String shared : new String[]{
                "io.jaravel.cli.Jaravel",      // 启动哪个类
                "cli-classpath",               // 缓存文件
                "cli-version",
                "cli.pom.xml",
                "JARAVEL_VERSION=" + VERSION,
                "dependency:build-classpath",
                "JAVA_HOME"}) {
            assertThat(posix).as("jaravelw 里应当有 %s", shared).contains(shared);
            assertThat(windows).as("""
                    jaravelw.cmd 里也应当有 %s。

                    sh 版与 cmd 版是同一件事的两份实现,会各自腐坏 ——
                    包装脚本避不开这种分叉(它运行在还没有 Java 的时刻,
                    而两个平台的 shell 没有交集)。所以不假装没问题,
                    改为钉住这几样「改一边忘另一边」时真会出事的东西。""", shared)
                    .contains(shared);
        }
    }

    @Test
    @DisplayName("只有 sh 版处理输出编码 —— cmd.exe 下 JDK 自己探的才是对的")
    void onlyThePosixScriptForcesEncoding(@TempDir Path tmp) throws Exception {
        generate(tmp, "--jaravel-version=" + VERSION);

        assertThat(read(tmp.resolve("jaravelw")))
                .as("""
                        Git Bash 的终端是 UTF-8,而 JDK 探到的是 Windows 代码页 ——
                        中文会打成一串问号(实测)。""")
                .contains("stdout.encoding")
                .contains("MSYSTEM");
        // 注释里提了一句「需要时用 JARAVEL_JVM_ARGS 覆盖」,所以要看**可执行的**行
        boolean cmdForcesEncoding = read(tmp.resolve("jaravelw.cmd")).lines()
                .filter(line -> !line.stripLeading().startsWith("@rem"))
                .anyMatch(line -> line.contains("stdout.encoding"));
        assertThat(cmdForcesEncoding)
                .as("""
                        真的 cmd.exe 里 cp936 才是对的,强塞 UTF-8
                        只是把乱码换个方向。所以这一侧什么都不传。""")
                .isFalse();
    }

    @Test
    @DisplayName("已经存在时拒绝覆盖 —— 用户可能改过脚本")
    void refusesToOverwrite(@TempDir Path tmp) throws Exception {
        generate(tmp, "--jaravel-version=" + VERSION);
        Files.writeString(tmp.resolve("jaravelw"), "# 我自己改过的\n");

        var registry = new CommandRegistry();
        registry.register(WrapperInstallCommand.class);
        registry.configure(command -> {
            if (command instanceof GeneratorCommand generator) generator.inProject(tmp);
        });
        Artisan.use(registry);

        assertThat(Artisan.call("wrapper:install", "--jaravel-version=" + VERSION))
                .isEqualTo(1);
        assertThat(read(tmp.resolve("jaravelw")))
                .as("内容必须还在")
                .isEqualTo("# 我自己改过的\n");

        assertThat(Artisan.call("wrapper:install", "--jaravel-version=" + VERSION, "--force"))
                .as("--force 该覆盖")
                .isZero();
        assertThat(read(tmp.resolve("jaravelw"))).startsWith("#!/bin/sh");
    }

    @Test
    @DisplayName("★ 读不到版本号时报错,而不是猜一个写进脚本")
    void refusesWhenTheVersionIsUnknown(@TempDir Path tmp) {
        // 测试本身就是这个情形:命令从 target/classes 跑,manifest 里没有版本号
        var registry = new CommandRegistry();
        registry.register(WrapperInstallCommand.class);
        registry.configure(command -> {
            if (command instanceof GeneratorCommand generator) generator.inProject(tmp);
        });
        Artisan.use(registry);

        assertThatThrownBy(() -> {
            var io = new ConsoleIo.Recording();
            int code = registry.run("wrapper:install", java.util.List.of(), io);
            if (code != 0) throw new ConsoleException(io.errors());
        })
                .as("""
                        猜一个版本写进脚本的话,它会去解析一个不存在的 artifact,
                        而报错出现在**将来某个人**第一次跑 ./jaravelw 的时候 ——
                        离这里很远。""")
                .hasMessageContaining("--jaravel-version");

        assertThat(tmp.resolve("jaravelw")).doesNotExist();
    }
}
