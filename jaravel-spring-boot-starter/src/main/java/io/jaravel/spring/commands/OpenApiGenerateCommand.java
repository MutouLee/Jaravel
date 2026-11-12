package io.jaravel.spring.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleException;
import io.jaravel.console.Option;
import io.jaravel.core.config.ConfigRepository;
import io.jaravel.routing.RouteCollection;

/**
 * {@code jaravel openapi:generate}(docs/12 §5)。
 *
 * <pre>
 * jaravel openapi:generate                        → public/openapi.json
 * jaravel openapi:generate --output=docs/api.json
 * jaravel openapi:generate --stdout | jq          → 给脚本用
 * </pre>
 *
 * <p>数据来自**编译后的路由表** —— 与 {@code route:list} 同一个来源。
 * 这一版能填的字段见 {@link OpenApiGenerator} 的类注释;响应 schema
 * 只覆盖返回 {@code JsonResource} 的接口(形状来自 APT 索引),
 * 而**没有的字段不写进文档也不编**。
 */
@Command(name = "openapi:generate", description = "从路由表生成 OpenAPI 文档")
public final class OpenApiGenerateCommand extends RuntimeCommand {

    private static final String DEFAULT_OUTPUT = "public/openapi.json";

    private final Supplier<RouteCollection> routes;
    private final ConfigRepository config;

    @Option(names = "--output", description = "写到哪里(默认 public/openapi.json)")
    String output;

    @Option(names = "--stdout", description = "打到标准输出而不是写文件")
    boolean toStdout;

    public OpenApiGenerateCommand(Supplier<RouteCollection> routes, ConfigRepository config) {
        this.routes = routes;
        this.config = config;
    }

    @Override
    public int handle() {
        RouteCollection table = routes.get();
        if (table.size() == 0) {
            warn("一条路由都没有 —— 生成的文档会是空的。");
            line("  路由定义是 RouteDefinition 的 bean(见 docs/03),确认它被容器扫到了。");
        }

        var generator = new OpenApiGenerator(
                config.string("app.name", "Jaravel API"),
                config.string("api.version", "1.0.0"),
                config.string("app.url", null));

        Map<String, Object> document = generator.generate(table);
        String json = encode(document);

        // 有请求体没生成出来就说出来 —— 静默少几个字段的话,
        // 前端会照着一份不完整的文档写请求,然后在 422 上卡住
        List<String> skipped = generator.skippedRequestBodies();
        List<String> schemaNotes = generator.schemaNotes();

        if (toStdout) {
            // ★ 只有这一份 JSON 进 stdout:人看的那几行统计走 stderr。
            // 与 route:list --json 同一条规矩 —— 多一行就是 jq: parse error
            line(json);
            return 0;
        }

        Path target = Path.of(output == null || output.isBlank() ? DEFAULT_OUTPUT : output);
        write(target, json);

        success("已写入 " + target + "(" + table.size() + " 条路由)");

        if (!skipped.isEmpty()) {
            newLine();
            failure(skipped.size() + " 个接口的请求体没能生成:");
            skipped.forEach(entry -> line("  " + entry));
            line("""
                      原因通常是那个 FormRequest 的 rules() 依赖实例状态
                      (读了路由参数之类)—— 生成文档时没有请求可读。""");
        }

        if (!schemaNotes.isEmpty()) {
            newLine();
            failure(schemaNotes.size() + " 个响应 schema 没能生成:");
            schemaNotes.forEach(note -> line("  " + note));
            line("""
                      常见原因:
                        · annotationProcessorPaths 里没有 jaravel-api-processor;
                        · toArray 用了 merge / 多个 return,静态提取看不到。""");
        }

        newLine();
        line("""
                ⚠ 响应体的 schema 只覆盖**返回 JsonResource 的接口** —— 形状由
                  jaravel-api-processor 在编译期从 toArray 提取。返回
                  Response / View / Object 的接口没有可提取的形状,不会写 schema;
                  可以用 @ApiResponse(resource = XxxResource.class) 显式指一个。

                  summary / description / 状态码可以写在控制器方法的 @ApiDoc
                  上(docs/12 §5)—— 它是运行时注解,不需要 APT。

                  请求体有了(来自 FormRequest 的 rules()),但要注意:
                  exists / unique / after 这类**语义**规则在 schema 里没有
                  表达方式,所以「schema 校验通过」不等于「服务端会接受」。

                  没有的字段不写进文档也不编 —— 一个带着假 schema 的 OpenAPI
                  会让前端生成出字段名全错的模型类,而那种错要等到联调才发现。""");
        return 0;
    }

    /**
     * 序列化。
     *
     * <h2>★ 用独立的 ObjectMapper,而且**开启缩进**</h2>
     * <p>不复用应用那个:它可能配了「不输出 null 字段」之类的策略
     * (examples/blog 就配了),而那会悄悄改变文档的形状 ——
     * 同一条教训在 {@code route:list --json} 与分页的信封上各出现过一次。
     *
     * <p>缩进是为了 {@code openapi:diff}(docs/12 §5 的附带收益)与
     * code review:一份全在一行的 JSON,任何改动的 diff 都是整个文件。
     */
    private String encode(Map<String, Object> document) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ConsoleException("OpenAPI 文档序列化失败:" + e.getOriginalMessage(), e);
        }
    }

    private void write(Path target, String json) {
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, json + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConsoleException("""
                    写不了 %s:%s
                      --output 指一个可写的路径,或者用 --stdout 直接拿到内容。"""
                    .formatted(target, e.getMessage()), e);
        }
    }
}
