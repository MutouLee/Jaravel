package io.jaravel.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ResourceSchemaIndex}(docs/12 §5 的响应 schema 索引)。
 *
 * <p>索引文件由注解处理器写,这里用临时目录模拟一个 jar:
 * 解析规则、转义、多份索引的合并。
 */
class ResourceSchemaIndexTest {

    private Path write(Path root, String content) throws Exception {
        Path file = root.resolve(ResourceSchemaIndex.RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return root;
    }

    private ResourceSchemaIndex.Index load(Path... roots) throws Exception {
        var urls = new java.net.URL[roots.length];
        for (int i = 0; i < roots.length; i++) urls[i] = roots[i].toUri().toURL();
        var loader = new URLClassLoader(urls, null);
        try (loader) {
            return ResourceSchemaIndex.load(loader);
        }
    }

    @Test
    @DisplayName("正常解析:资源、字段、类型与是否可选")
    void parsesSchema(@TempDir Path tmp) throws Exception {
        write(tmp, """
                # jaravel api resources v1
                R\tapp.PostResource
                F\tid\tinteger\t0
                F\ttitle\tstring\t0
                F\tauthor\t\t1
                """);

        var index = load(tmp);
        var schema = index.schemas().get("app.PostResource");

        assertThat(schema).isNotNull();
        assertThat(schema.properties()).containsExactly(
                new ResourceProperty("id", "integer", false),
                new ResourceProperty("title", "string", false),
                new ResourceProperty("author", null, true));
    }

    @Test
    @DisplayName("★ 生成 OpenAPI schema:知道类型才写 type,可选字段不进 required")
    void mapsToOpenApiSchema(@TempDir Path tmp) throws Exception {
        write(tmp, """
                R\tapp.PostResource
                F\tid\tinteger\t0
                F\ttitle\tstring\t0
                F\tauthor\t\t1
                """);

        Map<String, Object> schema = load(tmp).schemas().get("app.PostResource").toOpenApiSchema();

        assertThat(schema).containsEntry("type", "object");
        assertThat(schema.get("required")).isEqualTo(java.util.List.of("id", "title"));

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.get("id")).isEqualTo(Map.of("type", "integer"));
        assertThat(properties.get("author"))
                .as("不知道类型时只有空 schema —— 不猜 string")
                .isEqualTo(Map.of());
    }

    @Test
    @DisplayName("★ 第 5 列是嵌套资源类 —— 单个与数组都带 ref")
    void parsesNestedResourceRefs(@TempDir Path tmp) throws Exception {
        write(tmp, """
                R\tapp.PostResource
                F\tauthor\tobject\t1\tapp.AuthorResource
                F\tcomments\tarray\t1\tapp.CommentResource
                F\ttitle\tstring\t0
                """);

        var schema = load(tmp).schemas().get("app.PostResource");

        assertThat(schema.properties()).containsExactly(
                new ResourceProperty("author", "object", true, "app.AuthorResource"),
                new ResourceProperty("comments", "array", true, "app.CommentResource"),
                new ResourceProperty("title", "string", false, null));
    }

    @Test
    @DisplayName("★ 生成器传入 resolver 时,ref 变成真正的 $ref(单个 / 数组 items)")
    void resolvesNestedResourceRefs(@TempDir Path tmp) throws Exception {
        write(tmp, """
                R\tapp.PostResource
                F\tauthor\tobject\t1\tapp.AuthorResource
                F\tcomments\tarray\t1\tapp.CommentResource
                """);

        var schema = load(tmp).schemas().get("app.PostResource");
        var resolved = schema.toOpenApiSchema((className, array) -> array
                ? Map.of("type", "array",
                         "items", Map.of("$ref", "#/components/schemas/" + className))
                : Map.of("$ref", "#/components/schemas/" + className));

        assertThat(resolved.get("required")).isNull();

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) resolved.get("properties");
        assertThat(properties.get("author"))
                .isEqualTo(Map.of("$ref", "#/components/schemas/app.AuthorResource"));
        assertThat(properties.get("comments")).isEqualTo(Map.of(
                "type", "array",
                "items", Map.of("$ref", "#/components/schemas/app.CommentResource")));
    }

    @Test
    @DisplayName("没有 resolver 时 ref 退化成 object / array,不写假的 $ref")
    void unresolvedRefsFallBackToTypes(@TempDir Path tmp) throws Exception {
        write(tmp, """
                R\tapp.PostResource
                F\tauthor\tobject\t1\tapp.AuthorResource
                F\tcomments\tarray\t1\tapp.CommentResource
                """);

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) load(tmp).schemas()
                .get("app.PostResource").toOpenApiSchema().get("properties");

        assertThat(properties.get("author")).isEqualTo(Map.of("type", "object"));
        assertThat(properties.get("comments")).isEqualTo(Map.of("type", "array"));
    }

    @Test
    @DisplayName("多模块:多份索引合并,不是只读第一份")
    void mergesMultipleIndexes(@TempDir Path first, @TempDir Path second) throws Exception {
        write(first, """
                R\tapp.PostResource
                F\tid\tinteger\t0
                """);
        write(second, """
                R\tapp.UserResource
                F\tname\tstring\t0
                """);

        var index = load(first, second);

        assertThat(index.schemas()).containsKeys("app.PostResource", "app.UserResource");
    }

    @Test
    @DisplayName("★ 字段名里的制表符 / 换行 / 反斜杠要能原样回来")
    void escapesNames(@TempDir Path tmp) throws Exception {
        write(tmp, """
                R\tapp.WeirdResource
                F\ta\\tb\tstring\t0
                F\tc\\nd\tstring\t0
                F\te\\\\f\tstring\t0
                """);

        var schema = load(tmp).schemas().get("app.WeirdResource");

        assertThat(schema.properties()).extracting(ResourceProperty::name)
                .containsExactly("a\tb", "c\nd", "e\\f");
    }

    @Test
    @DisplayName("失败记录单独存 —— 命令要把原因报给用户")
    void keepsFailures(@TempDir Path tmp) throws Exception {
        write(tmp, """
                X\tapp.ComplexResource\ttoArray 有 3 个 return
                """);

        var index = load(tmp);

        assertThat(index.schemas()).isEmpty();
        assertThat(index.failures()).containsEntry(
                "app.ComplexResource", "toArray 有 3 个 return");
    }

    @Test
    @DisplayName("没有索引文件 → 空索引,而不是异常 —— 处理器可能没接")
    void missingIndexIsEmpty(@TempDir Path tmp) throws Exception {
        var index = load(tmp);

        assertThat(index.isEmpty()).isTrue();
        assertThat(index.schemas()).isEmpty();
        assertThat(index.failures()).isEmpty();
    }

    @Test
    @DisplayName("坏行跳过,不影响同一份文件里的其余内容")
    void skipsMalformedLines(@TempDir Path tmp) throws Exception {
        write(tmp, """
                F\torphan\tstring\t0
                R\tapp.PostResource
                F\tid
                F\tid\tinteger\t0
                """);

        var schema = load(tmp).schemas().get("app.PostResource");

        assertThat(schema.properties()).containsExactly(
                new ResourceProperty("id", "integer", false));
    }
}
