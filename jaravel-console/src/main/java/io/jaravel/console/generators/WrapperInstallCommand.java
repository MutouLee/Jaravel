package io.jaravel.console.generators;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;

/**
 * {@code jaravel wrapper:install}(docs/13 §1.1)—— 生成 {@code jaravelw}。
 *
 * <pre>
 * ./jaravelw make:model Post      不用全局安装 jaravel
 * </pre>
 *
 * <h2>★ 为什么需要它</h2>
 * <p>三件事,每一件单独都够烦:
 * <ul>
 *   <li><b>classpath</b>:CLI 不打 fat jar(native 镜像与 fat jar 是两条
 *       不同的分发路线,docs/13 §1.1),所以今天要跑它得手写
 *       {@code java -cp jaravel-cli.jar;jaravel-console.jar;…}。</li>
 *   <li><b>版本</b>:CLI 的版本要跟项目里的框架版本对上。包装脚本把版本号
 *       **钉在脚本里**并提交进仓库 —— 于是团队里每个人、CI 上那台机器,
 *       用的是同一个版本。这正是 {@code mvnw} 存在的理由。</li>
 *   <li><b>中文编码</b>:Git Bash 下 JDK 探到的是 Windows 代码页而终端
 *       是 UTF-8,于是输出全是问号(实测)。那个判断写在脚本里一次,
 *       而不是让每个用户每次都加 {@code -Dstdout.encoding=UTF-8}。</li>
 * </ul>
 *
 * <h2>两份实现,而且承认它会腐坏</h2>
 * <p>{@code jaravelw}(sh)与 {@code jaravelw.cmd}(cmd.exe)是同一件事
 * 的两份实现。本项目在别处(命令通道用 unix socket 而不是
 * 「unix 用 socket、Windows 用命名管道」)刻意避开了这种分叉,
 * 但包装脚本避不开:**它运行在还没有 Java 的时刻**,
 * 而两个平台的 shell 没有交集。
 *
 * <p>所以不假装没问题,改为钉住它们的一致性:一条测试核对两边的版本号、
 * 主类名与缓存文件名。那三样是「改一边忘另一边」时真正会出事的地方。
 *
 * <h2>⚠ 行尾与可执行位</h2>
 * <p>{@code jaravelw} 必须是 LF:CRLF 会让 {@code #!/bin/sh} 变成
 * {@code #!/bin/sh\r},症状是 {@code bad interpreter: No such file or directory}
 * —— 那条报错指着一个明明存在的路径,极难查。
 * {@code .cmd} 反过来要 CRLF(cmd.exe 对 LF 的多行结构会出错)。
 * 所以这里**不走** {@link GeneratorCommand#write}(它用平台行尾),
 * 两个文件各自显式写。
 */
@Command(name = "wrapper:install",
        description = "生成 jaravelw / jaravelw.cmd —— 不用全局安装 jaravel",
        offline = true)
public final class WrapperInstallCommand extends GeneratorCommand {

    static final String POSIX_SCRIPT = "jaravelw";
    static final String WINDOWS_SCRIPT = "jaravelw.cmd";

    @Option(names = "--jaravel-version", description = "要钉住的 jaravel 版本(默认用当前 CLI 的版本)")
    String version;

    @Override
    public int handle() {
        Path posix = projectRoot().resolve(POSIX_SCRIPT);
        Path windows = projectRoot().resolve(WINDOWS_SCRIPT);
        // 先看目标文件 —— 两个错都成立时,「已经存在」是更可行动的那一条
        refuseIfPresent(posix);
        refuseIfPresent(windows);

        String pinned = version != null && !version.isBlank() ? version.trim() : currentVersion();

        writeScript(posix, stub(POSIX_SCRIPT).with("version", pinned).render(), "\n");
        writeScript(windows, stub("jaravelw.cmd").with("version", pinned).render(), "\r\n");
        makeExecutable(posix);

        success("已生成 " + relative(posix) + " 与 " + relative(windows)
                + "(jaravel " + pinned + ")");
        newLine();
        line("""
                用法:
                  ./jaravelw list             (Windows 的 cmd / PowerShell 里:jaravelw list)
                  ./jaravelw make:model Post

                首次运行会解析一次 CLI 的依赖(两三秒),结果缓存在 .jaravel/。
                两个脚本都该提交进仓库 —— 它们把版本号钉给了整个团队。""");

        if (!executableBitSupported()) {
            newLine();
            warn("这台机器上设不了可执行位(Windows 的文件系统没有它)。");
            line("""
                  后果:提交之后,Linux / macOS 上 clone 下来的 ./jaravelw 不能直接跑。
                  在提交前补一句就好:
                    git update-index --chmod=+x jaravelw""");
        }
        return 0;
    }

    /**
     * 版本号从 jar 的 manifest 读 —— 与 {@code jaravel --version} 同一个来源。
     *
     * <p>读不到(从 IDE 或 {@code target/classes} 跑的开发版)时**报错而不是猜**:
     * 猜一个版本写进脚本的话,那个脚本会去解析一个不存在的 artifact,
     * 而报错会出现在**将来某个人**第一次跑 {@code ./jaravelw} 的时候 ——
     * 离这里很远。这时候要用 {@code --jaravel-version}。
     */
    private String currentVersion() {
        String implementation = getClass().getPackage().getImplementationVersion();
        if (implementation == null || implementation.isBlank()) {
            throw new ConsoleException("""
                    读不到 jaravel 的版本号(manifest 里没有)——
                    这通常意味着 CLI 是从 target/classes 直接跑的开发版。

                      包装脚本要把版本**钉死**在脚本里,所以这里不能猜:
                      猜错的报错会出现在将来某个人第一次跑 ./jaravelw 的时候。

                      显式给一个:
                        jaravel wrapper:install --jaravel-version=0.1.0-SNAPSHOT""");
        }
        return implementation;
    }

    private void refuseIfPresent(Path file) {
        if (Files.exists(file) && !force) {
            throw new ConsoleException("""
                    %s 已经存在。

                      覆盖它会丢掉你对脚本做的改动(JVM 参数、代理设置等)。
                      确实要覆盖:加 --force。""".formatted(relative(file)));
        }
    }

    /** 行尾是这里唯一不能交给平台的东西 —— 理由写在类注释上 */
    private void writeScript(Path file, String contents, String lineEnding) {
        String normalized = contents.replace("\r\n", "\n").replace("\r", "\n");
        if (!lineEnding.equals("\n")) {
            normalized = normalized.replace("\n", lineEnding);
        }
        try {
            Files.writeString(file, normalized, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConsoleException("写不了 " + relative(file) + ":" + e.getMessage(), e);
        }
    }

    private void makeExecutable(Path file) {
        if (!executableBitSupported()) return;
        try {
            Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    new java.util.HashSet<>(Files.getPosixFilePermissions(file));
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (IOException | UnsupportedOperationException e) {
            // 设不上不是错误 —— 上面那条 warn 已经给了出路
            warn("设不了可执行位:" + e.getMessage());
        }
    }

    private boolean executableBitSupported() {
        return java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix");
    }
}
