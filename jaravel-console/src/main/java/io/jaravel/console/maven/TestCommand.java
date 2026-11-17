package io.jaravel.console.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.Option;
import io.jaravel.console.ProjectAware;

/**
 * {@code jaravel test} —— Maven 的薄代理(docs/13 §7、docs/14 §6.3)。
 *
 * <pre>
 * jaravel test                                  # = mvn test
 * jaravel test --tests Todo*                    # = mvn test -Dtest=Todo*
 * jaravel test --parallel --processes=4         # = mvn test -DforkCount=4 ...
 * jaravel test --parallel --offline             # 已缓存依赖的 CI
 * </pre>
 *
 * <h2>并行是**进程级**,不是 JUnit 线程级</h2>
 * <p>{@code --parallel} 映射到 Surefire 的 {@code forkCount}:N 个独立 JVM,
 * 各自一份 Spring 上下文缓存。JUnit 的线程级并行会被
 * {@code Facade.SWAP}、{@code Event.fake}、静态 Eloquent 连接和
 * {@code BrowserTest} 的静态浏览器互相踩 —— 那不是一个可以靠
 * 用户配置打开的开关。理由见 ADR-017。
 *
 * <h2>每进程独立测试库</h2>
 * <p>同时设置系统属性 {@code jaravel.test.parallel=true} 与环境变量
 * {@code JARAVEL_TEST_PARALLEL=1}:前者给直接跑 Maven 的人,
 * 后者保证 Surefire fork 一定能看到(不同 Surefire 配置对系统属性的
 * 传递不完全一致)。{@code jaravel-testing} 的 {@code ParallelTestDatabase}
 * 在 Spring 环境准备好之后、DataSource 创建之前改写 URL。
 */
@Command(name = "test", description = "运行测试(Maven);--parallel 进程级并行", offline = true)
public final class TestCommand extends ConsoleCommand implements ProjectAware {

    /** 未传 {@code --processes} 的哨兵;0 和负数都是非法输入 */
    private static final int UNSET = Integer.MIN_VALUE;
    /** 默认不把 CI 的 128 核变成 128 个 JVM / 128 个测试库 */
    private static final int DEFAULT_CAP = 8;
    private static final int MAX_PROCESSES = 64;

    @Option(names = {"-p", "--parallel"}, description = "进程级并行(Surefire forkCount)")
    private boolean parallel;

    @Option(names = "--processes", description = "并行进程数,默认 CPU 核数")
    private int processes = UNSET;

    @Option(names = {"--tests", "--filter"}, description = "只跑匹配的测试(Surefire -Dtest)")
    private String filter;

    @Option(names = "--offline", description = "Maven 离线模式(-o)")
    private boolean offline;

    private Path projectRoot = Path.of("").toAbsolutePath();
    private final Runner runner;

    public TestCommand() {
        this(MavenInvoker::run);
    }

    /** 测试接缝:不真的起 Maven */
    TestCommand(Runner runner) {
        this.runner = runner;
    }

    @Override
    public void inProject(Path root) {
        this.projectRoot = root;
    }

    @Override
    public int handle() {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            error("""
                    test 只支持 Maven(ADR-012):%s 下没有 pom.xml。
                      在项目根目录(或模块目录)里运行它。"""
                    .formatted(projectRoot));
            return 1;
        }

        boolean useParallel = parallel || processes != UNSET;
        int forks = 0;
        if (useParallel) {
            if (processes == UNSET) {
                forks = Math.min(DEFAULT_CAP, Math.max(1,
                        Runtime.getRuntime().availableProcessors()));
            } else if (processes < 1 || processes > MAX_PROCESSES) {
                error("--processes 需要 1.." + MAX_PROCESSES + " 之间的整数,收到 " + processes);
                return 1;
            } else {
                forks = processes;
            }
        }

        List<String> argv = new ArrayList<>(MavenInvoker.argv(projectRoot, offline, "test"));
        Map<String, String> env = new HashMap<>();

        if (useParallel) {
            argv.add("-DforkCount=" + forks);
            env.put("JARAVEL_TEST_PROCESSES", String.valueOf(forks));

            if (forks > 1) {
                // 单进程没有「并行」可言,也不必为它造一个派生库
                argv.add("-Djaravel.test.parallel=true");
                env.put("JARAVEL_TEST_PARALLEL", "1");
                info("[jaravel] 并行测试:" + forks + " 个进程(Surefire forkCount),"
                        + "每个进程独立测试库");
            } else {
                comment("[jaravel] 只开 1 个进程 —— 不会比串行更快,也不会隔离测试库");
            }
        }

        if (filter != null && !filter.isBlank()) {
            argv.add("-Dtest=" + filter);
            // 多模块项目里,没匹配到测试的模块不该让整次运行失败
            argv.add("-Dsurefire.failIfNoSpecifiedTests=false");
        }

        if (isAggregator(pom)) {
            comment("[jaravel] 检测到聚合 pom:将运行整个 reactor 的测试");
        }

        return runner.run(projectRoot, argv, env);
    }

    private static boolean isAggregator(Path pom) {
        try {
            return Files.readString(pom).contains("<modules>");
        } catch (IOException e) {
            return false;
        }
    }

    /** 测试接缝 */
    @FunctionalInterface
    interface Runner {
        int run(Path root, List<String> argv, Map<String, String> environment);
    }
}
