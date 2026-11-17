package io.jaravel.console.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 调 Maven(docs/13 §7)。
 *
 * <p>两个调用方,两种需求:
 * <ul>
 *   <li>{@code jaravel-cli} 的冷启动启动器 —— 它要 {@code -o}(离线),
 *       不该因为仓库不通而卡住一条用户命令;</li>
 *   <li>{@code jaravel test} —— 它**不能**默认离线,第一次跑测试时
 *       依赖和插件都可能还没下载。</li>
 * </ul>
 * 所以离线是参数,不是写死的。
 *
 * <h2>项目自带的 wrapper 优先</h2>
 * <p>{@code mvnw} 钉住了 Maven 版本;PATH 上那个没有。这也是
 * {@code AppLauncher} 一直在做的事 —— 这里把它抽出来,避免第二份实现漂移。
 */
public final class MavenInvoker {

    private MavenInvoker() {
    }

    /** 完整 argv:可执行文件 + 可选 {@code -o} + 原样参数 */
    public static List<String> argv(Path root, boolean offline, String... args) {
        return argv(root, offline, List.of(args));
    }

    public static List<String> argv(Path root, boolean offline, List<String> args) {
        var argv = new ArrayList<String>(args.size() + 2);
        argv.add(executable(root));
        if (offline) argv.add("-o");
        argv.addAll(args);
        return argv;
    }

    /** 项目根下的 {@code mvnw} / {@code mvnw.cmd},否则 PATH 上的 Maven */
    public static String executable(Path root) {
        String wrapper = isWindows() ? "mvnw.cmd" : "mvnw";
        Path local = root.resolve(wrapper);
        if (Files.isRegularFile(local)) return local.toAbsolutePath().toString();
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    public static int run(Path root, List<String> argv) {
        return run(root, argv, Map.of());
    }

    /**
     * 起一个子进程,继承 IO —— 测试输出要**边跑边出**,不是等结束后
     * 一次性倒出来。
     *
     * <p>退出码原样返回;{@code 127} 是「命令找不到」的 shell 约定,
     * {@code 130} 是 Ctrl-C。
     */
    public static int run(Path root, List<String> argv, Map<String, String> environment) {
        try {
            var builder = new ProcessBuilder(argv)
                    .directory(root.toFile())
                    .inheritIO();
            if (environment != null && !environment.isEmpty()) {
                builder.environment().putAll(environment);
            }

            Process process = builder.start();
            // Ctrl-C 时别把 Maven 留成孤儿 —— 它下面还有 Surefire fork
            Thread killer = new Thread(() -> {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
            }, "jaravel-maven-cleanup");
            Runtime.getRuntime().addShutdownHook(killer);
            try {
                return process.waitFor();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(killer);
                } catch (IllegalStateException shuttingDown) {
                    // JVM 已经在退出,钩子正在跑 —— 没有什么要清理的了
                }
            }
        } catch (IOException e) {
            System.err.printf("[jaravel] 跑不起来 %s:%s%n",
                    argv.isEmpty() ? "mvn" : argv.get(0), e.getMessage());
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
    }
}
