package net.dobeen.jaravel.benchmark;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import net.dobeen.jaravel.view.JbladeEngine;
import net.dobeen.jaravel.view.ViewPrecompiler;

/**
 * 同一个模板在两条路径上的渲染成本:
 * 运行时编译(首次渲染后缓存)与构建期预编译。
 *
 * <p>预编译的收益主要在**启动**,但渲染路径也值得量 ——
 * 如果预编译的类反而更慢,说明生成的代码或引擎配置有问题。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TemplateRenderBenchmark {

    private static final String TEMPLATE = """
            @params(java.util.List<java.lang.String> items)
            <ul class="items">
            @foreach (items as item)
              <li>{{ item }}</li>
            @endforeach
            </ul>
            """;

    private static final Map<String, Object> DATA = Map.of("items", List.of(
            "alpha & beta", "<gamma>", "delta \"quoted\"", "epsilon",
            "zeta", "eta", "theta", "iota", "kappa", "lambda"));

    private JbladeEngine runtime;
    private JbladeEngine precompiled;
    private URLClassLoader loader;
    private Path tmp;
    private ClassLoader previousTccl;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        tmp = Files.createTempDirectory("jaravel-jmh-views");
        Path views = tmp.resolve("views");
        Files.createDirectories(views);
        Files.writeString(views.resolve("list.jblade.html"), TEMPLATE);

        runtime = JbladeEngine.forMap(Map.of("list", TEMPLATE),
                tmp.resolve("jte-classes"), getClass().getClassLoader());
        // 首次渲染触发编译,不计入基准
        runtime.render("list", DATA);

        Path classes = tmp.resolve("classes");
        List<Path> classpath = Arrays.stream(
                        System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(s -> !s.isBlank()).map(Path::of).toList();
        ViewPrecompiler.precompile(views, classes, classpath, getClass().getClassLoader());

        loader = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                getClass().getClassLoader());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        precompiled = JbladeEngine.forPrecompiled(loader);
        precompiled.render("list", DATA);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        Thread.currentThread().setContextClassLoader(previousTccl);
        if (loader != null) loader.close();
        if (tmp != null) {
            try (var s = Files.walk(tmp)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Benchmark
    public String runtimeCompiled() {
        return runtime.render("list", DATA);
    }

    @Benchmark
    public String precompiled() {
        return precompiled.render("list", DATA);
    }
}
