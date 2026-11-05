package io.jaravel.spring.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jaravel.console.Artisan;
import io.jaravel.console.CommandRegistry;
import io.jaravel.core.config.ConfigLoader;
import io.jaravel.core.config.MapConfigRepository;
import io.jaravel.http.Request;
import io.jaravel.routing.RouteCollection;
import io.jaravel.routing.Router;

/**
 * {@code openapi:generate}(docs/12 §5)。
 *
 * <p>数据来自**编译后的路由表** —— 所以这里也用真的 {@code Router.compile()},
 * 而不是手搭一张假表:两者不一致的地方(分组前缀、约束、中间件继承)
 * 恰恰是这条命令最容易出错的地方。
 */
class OpenApiTest {

    @AfterEach
    void clearStaticState() {
        Artisan.reset();
    }

    /** 控制器替身 —— 只需要有方法可以被方法引用指住 */
    public static class PostController {
        public Object index() {
            return "all";
        }

        public Object show(Long id) {
            return "one " + id;
        }

        public Object bySlug(String slug) {
            return "slug " + slug;
        }

        public Object store(Request request) {
            return "created";
        }
    }

    private RouteCollection routes(java.util.function.Consumer<Router> definition) {
        var router = new Router();
        definition.accept(router);
        return router.compile();
    }

    private Map<String, Object> document(RouteCollection table) {
        return new OpenApiGenerator("测试 API", "2.0.0", "https://api.example.com")
                .generate(table);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> operation(Map<String, Object> document, String uri, String verb) {
        var paths = (Map<String, Object>) document.get("paths");
        var methods = (Map<String, Object>) paths.get(uri);
        return (Map<String, Object>) methods.get(verb);
    }

    // ================= 文档骨架 =================

    @Nested
    @DisplayName("文档骨架")
    class Skeleton {

        @Test
        @DisplayName("info / servers 来自配置")
        void infoAndServers() {
            var document = document(routes(r -> r.get("/posts", PostController::index)));

            assertThat(document).containsEntry("openapi", "3.1.0");
            assertThat(document.get("info"))
                    .isEqualTo(Map.of("title", "测试 API", "version", "2.0.0"));
            assertThat(document.get("servers"))
                    .isEqualTo(List.of(Map.of("url", "https://api.example.com")));
        }

        @Test
        @DisplayName("★ 路径按字典序 —— 否则 openapi:diff 会把顺序变化报成 API 变更")
        void pathsAreSorted() {
            var document = document(routes(r -> {
                r.get("/zebra", PostController::index);
                r.get("/alpha", PostController::index);
                r.get("/middle", PostController::index);
            }));

            @SuppressWarnings("unchecked")
            var paths = (Map<String, Object>) document.get("paths");
            assertThat(paths.keySet())
                    .as("""
                            同一份路由表两次生成的文档必须逐字节一致。
                            哈希序的话,diff 里会出现一堆与 API 无关的变化,
                            而那会训练人忽略 diff。""")
                    .containsExactly("/alpha", "/middle", "/zebra");
        }

        @Test
        @DisplayName("同一路径的多个方法并列在一个条目下")
        void methodsShareAPath() {
            var document = document(routes(r -> {
                r.get("/posts", PostController::index);
                r.post("/posts", PostController::store);
            }));

            @SuppressWarnings("unchecked")
            var paths = (Map<String, Object>) document.get("paths");
            @SuppressWarnings("unchecked")
            var posts = (Map<String, Object>) paths.get("/posts");
            assertThat(posts.keySet()).containsExactlyInAnyOrder("get", "post");
        }

        @Test
        @DisplayName("operationId 与 tags 从控制器来")
        void operationIdAndTags() {
            var document = document(routes(r -> r.get("/posts", PostController::index)));

            var operation = operation(document, "/posts", "get");
            assertThat(operation.get("operationId")).isEqualTo("PostController.index");
            assertThat(operation.get("tags"))
                    .as("标签去掉 Controller 后缀 —— 那正好是 Swagger UI 的分组")
                    .isEqualTo(List.of("Post"));
        }

        @Test
        @DisplayName("路由名字成为 summary")
        void routeNameBecomesSummary() {
            var document = document(routes(r ->
                    r.get("/posts", PostController::index).name("posts.index")));

            assertThat(operation(document, "/posts", "get").get("summary"))
                    .isEqualTo("posts.index");
        }
    }

    // ================= 路径参数 =================

    @Nested
    @DisplayName("路径参数")
    class Parameters {

        @Test
        @DisplayName("★ whereNumber 的约束直接给出 integer —— 它是作者明确表达的意图")
        void numericConstraintWins() {
            var document = document(routes(r ->
                    r.get("/posts/{id}", PostController::show).whereNumber("id")));

            @SuppressWarnings("unchecked")
            var parameters = (List<Map<String, Object>>)
                    operation(document, "/posts/{id}", "get").get("parameters");

            assertThat(parameters).hasSize(1);
            assertThat(parameters.get(0))
                    .containsEntry("name", "id")
                    .containsEntry("in", "path")
                    .containsEntry("required", true);
            assertThat(parameters.get(0).get("schema")).isEqualTo(Map.of("type", "integer"));
        }

        @Test
        @DisplayName("没有约束时按方法签名推 —— Long → integer")
        void typeFromSignature() {
            var document = document(routes(r -> r.get("/posts/{id}", PostController::show)));

            @SuppressWarnings("unchecked")
            var parameters = (List<Map<String, Object>>)
                    operation(document, "/posts/{id}", "get").get("parameters");

            assertThat(parameters.get(0).get("schema")).isEqualTo(Map.of("type", "integer"));
        }

        @Test
        @DisplayName("String 参数 → string;自定义约束变成 pattern")
        void stringWithPattern() {
            var document = document(routes(r ->
                    r.get("/posts/{slug}", PostController::bySlug).whereSlug("slug")));

            @SuppressWarnings("unchecked")
            var parameters = (List<Map<String, Object>>)
                    operation(document, "/posts/{slug}", "get").get("parameters");

            @SuppressWarnings("unchecked")
            var schema = (Map<String, Object>) parameters.get(0).get("schema");
            assertThat(schema).containsEntry("type", "string");
            assertThat(schema).containsKey("pattern");
        }

        @Test
        @DisplayName("★ 签名对不上时退回 string,而不是猜 —— 文档上的假类型比没类型更糟")
        void fallsBackWhenSignatureDoesNotLineUp() {
            // 控制器只接 Request,而路径上有一个参数 —— 两者对不上
            var document = document(routes(r -> r.post("/posts/{id}", PostController::store)));

            @SuppressWarnings("unchecked")
            var parameters = (List<Map<String, Object>>)
                    operation(document, "/posts/{id}", "post").get("parameters");

            assertThat(parameters.get(0).get("schema"))
                    .as("""
                            真正的参数绑定规则在 ArgumentBinder 与 SubstituteBindings 里
                            (模型绑定、FormRequest 注入…)。在生成器里把那套规则再
                            实现一遍就是第二份会漂的实现 —— 而漂掉的后果是
                            「文档上的类型与实际接受的类型不同」。
                            路径参数在 HTTP 上本来就是字符串,这个退路不说谎。""")
                    .isEqualTo(Map.of("type", "string"));
        }

        @Test
        @DisplayName("没有路径参数时不输出 parameters 键")
        void noParametersKeyWhenThereAreNone() {
            var document = document(routes(r -> r.get("/posts", PostController::index)));

            assertThat(operation(document, "/posts", "get")).doesNotContainKey("parameters");
        }
    }

    // ================= 安全 =================

    @Nested
    @DisplayName("安全要求")
    class Security {

        @Test
        @DisplayName("★ auth 中间件 → security + 401,并声明 securityScheme")
        void authMiddlewareBecomesSecurity() {
            var document = document(routes(r ->
                    r.get("/me", PostController::index).middleware("auth:api")));

            var operation = operation(document, "/me", "get");
            assertThat(operation).containsKey("security");
            @SuppressWarnings("unchecked")
            var responses = (Map<String, Object>) operation.get("responses");
            assertThat(responses.keySet())
                    .as("受保护的接口会 401 —— 那是异常映射表的既有行为")
                    .containsExactly("200", "401");

            @SuppressWarnings("unchecked")
            var components = (Map<String, Object>) document.get("components");
            assertThat(components).isNotNull();
        }

        @Test
        @DisplayName("没有受保护的接口时不输出 components —— 一个空的安全方案是噪音")
        void noComponentsWithoutSecuredRoutes() {
            var document = document(routes(r -> r.get("/posts", PostController::index)));

            assertThat(document).doesNotContainKey("components");
        }

        @Test
        @DisplayName("★ 只写**知道**的响应:没有 404 / 422,因为那还是猜")
        void onlyKnownResponses() {
            var document = document(routes(r -> r.get("/posts", PostController::index)));

            @SuppressWarnings("unchecked")
            var responses = (Map<String, Object>) operation(document, "/posts", "get")
                    .get("responses");
            assertThat(responses.keySet())
                    .as("""
                            404 要等 findOrFail 的意图能被看出来,422 要等
                            FormRequest 的 schema —— 现在写上去只是猜,
                            而一份猜出来的 OpenAPI 会让前端照它写错误处理。""")
                    .containsExactly("200");
        }
    }

    // ================= 命令 =================

    @Nested
    @DisplayName("命令")
    class CommandBehaviour {

        private RouteCollection table;

        private String run(@TempDir Path tmp, String... args) {
            table = routes(r -> r.get("/posts/{id}", PostController::show).whereNumber("id"));
            var config = new MapConfigRepository(ConfigLoader.parse("""
                    app:
                      name: 文档测试
                    """, "test"));

            var registry = new CommandRegistry().register(OpenApiGenerateCommand.class,
                    () -> new OpenApiGenerateCommand(() -> table, config));
            Artisan.use(registry);
            Artisan.call("openapi:generate", args);
            return Artisan.output();
        }

        @Test
        @DisplayName("--stdout:标准输出上只有那一份 JSON")
        void stdoutCarriesOnlyJson(@TempDir Path tmp) throws Exception {
            String out = run(tmp, "--stdout");

            var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.trim());
            assertThat(parsed.get("openapi").asText())
                    .as("输出:%s", out)
                    .isEqualTo("3.1.0");
            assertThat(out)
                    .as("人看的那几行统计要走 stderr —— 多一行就是 jq: parse error")
                    .doesNotContain("已写入");
        }

        @Test
        @DisplayName("写文件时自动建目录,并说清还缺什么")
        void writesFileAndSaysWhatIsMissing(@TempDir Path tmp) throws Exception {
            Path target = tmp.resolve("docs/api/openapi.json");

            String out = run(tmp, "--output=" + target);

            assertThat(Files.exists(target)).isTrue();
            assertThat(Files.readString(target)).contains("\"openapi\"");
            assertThat(out)
                    .as("""
                            缺的字段要说出来。一个用户以为「文档生成好了」
                            而里面没有任何 schema 的话,他会去怀疑自己的注解
                            或者 Swagger UI 坏了。""")
                    .contains("没有");
        }

        @Test
        @DisplayName("★ 输出是缩进过的 —— 一份全在一行的 JSON,任何改动的 diff 都是整个文件")
        void outputIsPrettyPrinted(@TempDir Path tmp) {
            assertThat(run(tmp, "--stdout").lines().count())
                    .as("openapi:diff 与 code review 都要读它")
                    .isGreaterThan(10);
        }
    }
}
