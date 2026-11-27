package net.dobeen.jaravel.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import gg.jte.html.OwaspHtmlTemplateOutput;
import gg.jte.output.StringOutput;

/**
 * 上下文感知转义(jte 的 OWASP 实现)的文本上下文开销。
 *
 * <p>jblde 的模板最终把 {@code {{ ... }}} 落到 {@code writeUserContent}。
 * 这个基准量的是「已经拿到字符串之后」的纯转义成本,不含模板解析。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EscapeBenchmark {

    private static final String PLAIN =
            "The quick brown fox jumps over the lazy dog, 0123456789.";
    private static final String HOSTILE =
            "<script>alert('xss')</script> & \"quotes\" 'single' 中文内容";
    private static final String LONG =
            (PLAIN + " " + HOSTILE + " ").repeat(20);

    private final StringOutput sink = new StringOutput(1024);

    @Benchmark
    public String textPlain() {
        return escape(PLAIN);
    }

    @Benchmark
    public String textHostile() {
        return escape(HOSTILE);
    }

    @Benchmark
    public String textLong() {
        return escape(LONG);
    }

    private String escape(String value) {
        sink.reset();
        var html = new OwaspHtmlTemplateOutput(sink);
        html.writeUserContent(value);
        return sink.toString();
    }
}
