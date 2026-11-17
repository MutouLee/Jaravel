package io.jaravel.console.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleIo;

/** {@code jaravel test}:选项 → Maven argv(docs/13 §7、docs/14 §6.3) */
class TestCommandTest {

    private record Invocation(Path root, List<String> argv, Map<String, String> env) {}

    private static final class RecordingIo implements ConsoleIo {
        private final StringBuilder out = new StringBuilder();
        private final StringBuilder err = new StringBuilder();

        @Override public void write(String text)      { out.append(text); }
        @Override public void writeError(String text) { err.append(text); }
        @Override public String readLine(String prompt) { return null; }
        @Override public String readSecret(String prompt) { return null; }
        @Override public boolean supportsColor()      { return false; }

        String out() { return out.toString(); }
        String err() { return err.toString(); }
    }

    private record Result(int code, String out, String err, Invocation call) {}

    private Result run(Path root, int exitCode, String... args) {
        var seen = new AtomicReference<Invocation>();
        var command = new TestCommand((r, argv, env) -> {
            seen.set(new Invocation(r, List.copyOf(argv), Map.copyOf(env)));
            return exitCode;
        });
        command.inProject(root);

        var io = new RecordingIo();
        int code = new CommandRegistry().register(() -> command)
                .run("test", List.of(args), io);
        return new Result(code, io.out(), io.err(), seen.get());
    }

    private Path project(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        return tmp;
    }

    @Test
    @DisplayName("默认就是 mvn test —— 不碰数据库、不改 forkCount")
    void defaultsToPlainMavenTest(@TempDir Path tmp) throws Exception {
        var result = run(project(tmp), 0);

        assertThat(result.code()).isZero();
        assertThat(result.call().argv()).contains("test");
        assertThat(result.call().argv())
                .noneMatch(arg -> arg.startsWith("-DforkCount"));
        assertThat(result.call().env()).isEmpty();
    }

    @Test
    @DisplayName("--parallel --processes=4 → forkCount + 独立测试库标记")
    void parallelMapsToSurefireForks(@TempDir Path tmp) throws Exception {
        var result = run(project(tmp), 0, "--parallel", "--processes=4");

        assertThat(result.code()).isZero();
        assertThat(result.call().argv())
                .contains("test", "-DforkCount=4", "-Djaravel.test.parallel=true");
        assertThat(result.call().argv()).doesNotContain("-DreuseForks=true");
        assertThat(result.call().env())
                .containsEntry("JARAVEL_TEST_PARALLEL", "1")
                .containsEntry("JARAVEL_TEST_PROCESSES", "4");
        assertThat(result.out()).contains("4 个进程");
    }

    @Test
    @DisplayName("--processes 单独出现也隐含 --parallel")
    void processesImpliesParallel(@TempDir Path tmp) throws Exception {
        var result = run(project(tmp), 0, "--processes=2");

        assertThat(result.call().argv()).contains("-DforkCount=2");
        assertThat(result.call().env()).containsEntry("JARAVEL_TEST_PARALLEL", "1");
    }

    @Test
    @DisplayName("--processes=1 仍是 forkCount=1,但不造派生库")
    void singleProcessDoesNotIsolate(@TempDir Path tmp) throws Exception {
        var result = run(project(tmp), 0, "--parallel", "--processes=1");

        assertThat(result.call().argv())
                .contains("-DforkCount=1")
                .doesNotContain("-Djaravel.test.parallel=true");
        assertThat(result.call().env()).doesNotContainKey("JARAVEL_TEST_PARALLEL");
        assertThat(result.out()).contains("只开 1 个进程");
    }

    @Test
    @DisplayName("--processes 必须是 1..64")
    void invalidProcesses(@TempDir Path tmp) throws Exception {
        var zero = run(project(tmp), 0, "--parallel", "--processes=0");
        assertThat(zero.code()).isEqualTo(1);
        assertThat(zero.call()).as("校验失败时不该起 Maven").isNull();
        assertThat(zero.err()).contains("1..64");

        var tooMany = run(project(tmp), 0, "--processes=65");
        assertThat(tooMany.code()).isEqualTo(1);
        assertThat(tooMany.call()).isNull();
    }

    @Test
    @DisplayName("--parallel 不给进程数时默认取 CPU 核数,但封顶 8")
    void defaultProcessesAreCapped(@TempDir Path tmp) throws Exception {
        var result = run(project(tmp), 0, "--parallel");

        int forks = Integer.parseInt(result.call().argv().stream()
                .filter(arg -> arg.startsWith("-DforkCount="))
                .findFirst().orElseThrow()
                .substring("-DforkCount=".length()));
        assertThat(forks)
                .isEqualTo(Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors())));
    }

    @Test
    @DisplayName("--tests 映射到 Surefire -Dtest,并容忍空模块")
    void testFilter(@TempDir Path tmp) throws Exception {
        var result = run(project(tmp), 0, "--tests=Todo*");

        assertThat(result.call().argv())
                .contains("-Dtest=Todo*", "-Dsurefire.failIfNoSpecifiedTests=false");
    }

    @Test
    @DisplayName("--offline 才给 Maven 加 -o")
    void offlineIsOptIn(@TempDir Path tmp) throws Exception {
        assertThat(run(project(tmp), 0).call().argv()).doesNotContain("-o");
        assertThat(run(project(tmp), 0, "--offline").call().argv()).contains("-o");
    }

    @Test
    @DisplayName("Maven 的退出码原样返回 —— 这是与脚本唯一的契约")
    void exitCodeIsPropagated(@TempDir Path tmp) throws Exception {
        assertThat(run(project(tmp), 3).code()).isEqualTo(3);
    }

    @Test
    @DisplayName("没有 pom.xml 就明确拒绝,而不是起一个不知道在哪跑的 Maven")
    void requiresMavenProject(@TempDir Path tmp) {
        var result = run(tmp, 0, "--parallel");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.call()).isNull();
        assertThat(result.err()).contains("pom.xml");
    }

    @Test
    @DisplayName("聚合 pom 允许运行,但提示会跑整个 reactor")
    void aggregatorIsAllowedWithANote(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project><modules><module>app</module></modules></project>");

        var result = run(tmp, 0);

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("整个 reactor");
        assertThat(result.call()).isNotNull();
    }
}
