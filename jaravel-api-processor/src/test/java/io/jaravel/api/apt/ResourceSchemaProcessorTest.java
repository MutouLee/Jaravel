package io.jaravel.api.apt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ResourceSchemaProcessor}(docs/12 §5 的响应 schema)。
 *
 * <p>用真的 {@code javac} 编译一组临时源码,再读产出的索引 ——
 * 索引是 {@code Filer} 写的**资源**,compile-testing 的 generatedFiles
 * 只跟踪源码/类文件,看不到它。真编译还顺带验证了「方法体里的 AST
 * 在注解处理阶段确实拿得到」这件事。
 */
class ResourceSchemaProcessorTest {

    private record Compiled(boolean ok, String index, String diagnostics) {
        List<String> lines() {
            return index.lines().filter(line -> !line.startsWith("#")).toList();
        }
    }

    private Compiled compile(Path tmp, String... sources) throws IOException {
        Path src = tmp.resolve("src");
        Path classes = tmp.resolve("classes");
        Path generated = tmp.resolve("generated");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        Files.createDirectories(generated);

        var files = new ArrayList<Path>();
        for (int i = 0; i < sources.length; i += 2) {
            Path file = src.resolve(sources[i]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, sources[i + 1], StandardCharsets.UTF_8);
            files.add(file);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();

        try (var manager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {

            var options = List.of(
                    "-proc:only",
                    "-d", classes.toString(),
                    "-s", generated.toString(),
                    "-classpath", System.getProperty("java.class.path"),
                    "-processor", "io.jaravel.api.apt.ResourceSchemaProcessor");

            boolean ok = compiler.getTask(null, manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(files)).call();

            Path indexFile = classes.resolve(ResourceSchemaProcessor.RESOURCE);
            String index = Files.exists(indexFile)
                    ? Files.readString(indexFile, StandardCharsets.UTF_8)
                    : "";

            String messages = diagnostics.getDiagnostics().stream()
                    .map(diagnostic -> diagnostic.getKind() + ": "
                            + diagnostic.getMessage(Locale.ROOT))
                    .collect(Collectors.joining("\n"));

            return new Compiled(ok, index, messages);
        }
    }

    private static final String POST = """
            package app;

            import java.time.LocalDateTime;

            public class Post {
                public Long id;
                public String title;
                public boolean published;
                public int views;
                public LocalDateTime createdAt;
            }
            """;

    private static final String SIMPLE_RESOURCE = """
            package app;

            import io.jaravel.api.JsonResource;
            import io.jaravel.http.Request;
            import java.util.Map;

            public class PostResource extends JsonResource<Post> {

                public PostResource(Post post) { super(post); }

                @Override
                public Map<String, Object> toArray(Request request) {
                    return map(request)
                            .put("id", model.id)
                            .put("title", model.title)
                            .put("published", model.published)
                            .put("views", model.views)
                            .put("created_at", model.createdAt)
                            .put("rating", 4.5)
                            .putWhen(model.published, "published_at", () -> model.createdAt)
                            .putIfAbsent("nickname", "anon")
                            .build();
                }
            }
            """;

    @Test
    @DisplayName("★ 从 put 链提取字段,并从 model 的字段类型推断 type")
    void extractsFieldsAndTypes(@TempDir Path tmp) throws IOException {
        var result = compile(tmp, "app/Post.java", POST,
                "app/PostResource.java", SIMPLE_RESOURCE);

        assertThat(result.ok()).as(result.diagnostics()).isTrue();
        assertThat(result.lines())
                .as("诊断:\n%s\n索引:\n%s", result.diagnostics(), result.index())
                .containsExactly(
                "R\tapp.PostResource",
                "F\tid\tinteger\t0",
                "F\ttitle\tstring\t0",
                "F\tpublished\tboolean\t0",
                "F\tviews\tinteger\t0",
                "F\tcreated_at\tstring\t0",
                "F\trating\tnumber\t0",
                "F\tpublished_at\tstring\t1",
                "F\tnickname\tstring\t1");
    }

    @Test
    @DisplayName("编译期常量也能当字段名 —— static final String 是常见写法")
    void resolvesConstantKeys(@TempDir Path tmp) throws IOException {
        var result = compile(tmp, "app/Post.java", POST, "app/ConstResource.java", """
                package app;

                import io.jaravel.api.JsonResource;
                import io.jaravel.http.Request;
                import java.util.Map;

                public class ConstResource extends JsonResource<Post> {

                    private static final String ID = "id";

                    public ConstResource(Post post) { super(post); }

                    @Override
                    public Map<String, Object> toArray(Request request) {
                        return map(request).put(ID, model.id).build();
                    }
                }
                """);

        assertThat(result.ok()).as(result.diagnostics()).isTrue();
        assertThat(result.lines()).contains("F\tid\tinteger\t0");
    }

    @Test
    @DisplayName("★ merge 的键是动态的 → 这份资源提取失败,而且要报出来")
    void dynamicKeysFailLoudly(@TempDir Path tmp) throws IOException {
        var result = compile(tmp, "app/Post.java", POST, "app/MergeResource.java", """
                package app;

                import io.jaravel.api.JsonResource;
                import io.jaravel.http.Request;
                import java.util.Map;

                public class MergeResource extends JsonResource<Post> {

                    public MergeResource(Post post) { super(post); }

                    private Map<String, Object> extra() { return Map.of("x", 1); }

                    @Override
                    public Map<String, Object> toArray(Request request) {
                        return map(request).put("id", model.id).merge(extra()).build();
                    }
                }
                """);

        assertThat(result.ok()).as("文档生成不了不该让编译失败").isTrue();
        assertThat(result.index())
                .contains("X\tapp.MergeResource\tmerge 的键是动态的,静态提取看不到");
        assertThat(result.diagnostics())
                .contains("MergeResource 的响应 schema 无法静态提取")
                .contains("merge 的键是动态的");
    }

    @Test
    @DisplayName("多个 return → 提取失败,而不是只取其中一支")
    void multipleReturnsFail(@TempDir Path tmp) throws IOException {
        var result = compile(tmp, "app/Post.java", POST, "app/BranchResource.java", """
                package app;

                import io.jaravel.api.JsonResource;
                import io.jaravel.http.Request;
                import java.util.Map;

                public class BranchResource extends JsonResource<Post> {

                    public BranchResource(Post post) { super(post); }

                    @Override
                    public Map<String, Object> toArray(Request request) {
                        if (request != null) {
                            return map(request).put("id", model.id).build();
                        }
                        return map(request).put("id", model.id).put("title", model.title).build();
                    }
                }
                """);

        assertThat(result.ok()).isTrue();
        assertThat(result.index())
                .contains("toArray 有 2 个 return")
                .doesNotContain("R\tapp.BranchResource");
    }

    @Test
    @DisplayName("字段名不是字面量/常量 → 提取失败")
    void nonLiteralKeysFail(@TempDir Path tmp) throws IOException {
        var result = compile(tmp, "app/Post.java", POST, "app/DynamicKeyResource.java", """
                package app;

                import io.jaravel.api.JsonResource;
                import io.jaravel.http.Request;
                import java.util.Map;

                public class DynamicKeyResource extends JsonResource<Post> {

                    public DynamicKeyResource(Post post) { super(post); }

                    private String key() { return "id"; }

                    @Override
                    public Map<String, Object> toArray(Request request) {
                        return map(request).put(key(), model.id).build();
                    }
                }
                """);

        assertThat(result.ok()).isTrue();
        assertThat(result.index()).contains("字段名不是字符串字面量/常量");
    }

    @Test
    @DisplayName("toArray 在父类里 → 子类资源也能拿到 schema")
    void inheritedToArrayIsAnalyzed(@TempDir Path tmp) throws IOException {
        var result = compile(tmp, "app/Post.java", POST, "app/BaseResource.java", """
                package app;

                import io.jaravel.api.JsonResource;
                import io.jaravel.http.Request;
                import java.util.Map;

                public abstract class BaseResource<M> extends JsonResource<M> {

                    protected BaseResource(M model) { super(model); }

                    @Override
                    public Map<String, Object> toArray(Request request) {
                        return map(request).put("base", 1).build();
                    }
                }
                """, "app/ChildResource.java", """
                package app;

                public class ChildResource extends BaseResource<Post> {
                    public ChildResource(Post post) { super(post); }
                }
                """);

        assertThat(result.ok()).as(result.diagnostics()).isTrue();
        assertThat(result.lines()).containsExactly(
                "R\tapp.ChildResource",
                "F\tbase\tinteger\t0");
    }

    @Test
    @DisplayName("★ 嵌套资源用二进制名($) —— 运行时用 Class.getName() 查,必须对得上")
    void nestedResourcesUseBinaryNames(@TempDir Path tmp) throws IOException {
        var result = compile(tmp, "app/Post.java", POST, "app/Outer.java", """
                package app;

                import io.jaravel.api.JsonResource;
                import io.jaravel.http.Request;
                import java.util.Map;

                public class Outer {
                    public static class InnerResource extends JsonResource<Post> {
                        public InnerResource(Post post) { super(post); }

                        @Override
                        public Map<String, Object> toArray(Request request) {
                            return map(request).put("inner", 1).build();
                        }
                    }
                }
                """);

        assertThat(result.ok()).as(result.diagnostics()).isTrue();
        assertThat(result.lines()).containsExactly(
                "R\tapp.Outer$InnerResource",
                "F\tinner\tinteger\t0");
    }

    @Test
    @DisplayName("★ 转义与运行时那半一致:字段名里的制表符/换行不能把索引拆成两行")
    void escapesNames() {
        assertThat(ResourceSchemaProcessor.escape("a\tb")).isEqualTo("a\\tb");
        assertThat(ResourceSchemaProcessor.escape("a\nb")).isEqualTo("a\\nb");
        assertThat(ResourceSchemaProcessor.escape("a\\b")).isEqualTo("a\\\\b");
    }
}
