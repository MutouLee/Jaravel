package io.jaravel.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 覆盖率汇总的依赖表与 reactor 对得上。
 *
 * <h2>★ 为什么这条测试必须存在</h2>
 * <p>{@code report-aggregate} 读的是**依赖**里那些模块的 exec 文件。
 * 那张依赖表是手维护的,而**漏一个模块的后果是它悄悄不算进分母** ——
 * 覆盖率于是变高,而没有任何东西报错。
 *
 * <p>一个会悄悄变好的指标比没有指标更糟:它让人停止怀疑。
 * 这一轮接 jacoco 时就撞到了同一类事情的三个变种
 * (三个模块盖掉了 argLine、四个示例不继承父 pom)——
 * 结果是覆盖率从 56.1% 一路「涨」到 73.3%,而**一条测试都没加**。
 * 涨的全是「有没有在记」。
 */
class CoverageModulesTest {

    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    /** 不进汇总的两个:BOM 没有代码,汇总模块自己也没有 */
    private static final List<String> NOT_AGGREGATED = List.of("jaravel-bom", "jaravel-coverage");

    @Test
    @DisplayName("★ reactor 里的每个模块都在汇总的依赖表里 —— 漏一个就是悄悄抬高覆盖率")
    void everyReactorModuleIsAggregated() throws IOException {
        List<String> modules = matches(MODULE, read(Path.of("..", "pom.xml")));
        List<String> declared = matches(ARTIFACT, read(Path.of("pom.xml")));

        var missing = new ArrayList<String>();
        for (String module : modules) {
            // examples/blog → blog(示例的 artifactId 不带目录前缀)
            String artifact = module.contains("/")
                    ? module.substring(module.lastIndexOf('/') + 1)
                    : module;
            if (NOT_AGGREGATED.contains(artifact)) continue;
            if (!declared.contains(artifact)) missing.add(module);
        }

        assertThat(missing)
                .as("""
                        这些模块在 reactor 里构建、却没列进 jaravel-coverage 的依赖:
                        它们的类不会出现在报告里,于是覆盖率被悄悄抬高。
                        加一行 <dependency> 就行。""")
                .isEmpty();
        assertThat(modules).as("根 pom 读到了吗").hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("示例应用也要在里面 —— 它们的验收测试覆盖的是框架代码")
    void examplesAreAggregatedToo() throws IOException {
        List<String> declared = matches(ARTIFACT, read(Path.of("pom.xml")));

        assertThat(declared)
                .as("""
                        examples/auth 一个人就有 91 条真 HTTP 测试。
                        不把它算进来,框架覆盖率会被报低一大截 ——
                        而那些测试恰恰覆盖的是「类与类之间的接线」,
                        也就是这个项目里最容易出缺陷的地方。""")
                .contains("todo", "blog", "auth", "queue");
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private List<String> matches(Pattern pattern, String text) {
        var found = new ArrayList<String>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }
}
