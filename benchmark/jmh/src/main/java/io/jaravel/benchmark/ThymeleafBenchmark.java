package io.jaravel.benchmark;

import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * 验收「模板渲染 ≥ Thymeleaf 的 3 倍」的对照面。
 *
 * <p>同一个语义的模板:一个 {@code <ul>},10 个转义后的 {@code <li>}。
 * 与 {@link TemplateRenderBenchmark.precompiled} 对比。
 * 这不是 apples-to-apples 的完整框架基准(Thymeleaf 这里只做渲染),
 * 但足以回答「jblade 的生成代码有没有慢一个数量级」。
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ThymeleafBenchmark {

    private static final String TEMPLATE = """
            <ul class="items">
              <li th:each="item : ${items}" th:text="${item}">x</li>
            </ul>
            """;

    private static final List<String> ITEMS = List.of(
            "alpha & beta", "<gamma>", "delta \"quoted\"", "epsilon",
            "zeta", "eta", "theta", "iota", "kappa", "lambda");

    private TemplateEngine engine;
    private Context context;

    @Setup(Level.Trial)
    public void setup() {
        var resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);

        context = new Context();
        context.setVariable("items", ITEMS);
        // 预热解析缓存
        render();
    }

    @Benchmark
    public String render() {
        var writer = new StringWriter(256);
        engine.process(TEMPLATE, context, writer);
        return writer.toString();
    }
}
