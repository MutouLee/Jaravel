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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.jaravel.api.JsonResource;
import io.jaravel.api.openapi.ApiDoc;
import io.jaravel.api.openapi.ApiResponse;
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
        JsonResource.resetWrapping();
    }

    /** 控制器替身 —— 只需要有方法可以被方法引用指住 */
    public static class PostController {

        /** 一个真的 FormRequest:无参构造是框架的既有契约 */
        public static class StorePostRequest extends io.jaravel.validation.FormRequest {
            @Override
            public Map<String, String> rules() {
                return Map.of(
                        "title", "required|string|max:200",
                        "age", "nullable|integer|min:18|max:99",
                        "role", "required|in:admin,user",
                        "email", "required|email",
                        "password", "required|min:8|confirmed",
                        "tags", "array|max:5",
                        "slug", "required|regex:/^[a-z0-9-]+$/");
            }
        }

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

        public Object storeValidated(StorePostRequest form) {
            return "created " + form.validated().string("title");
        }

        @ApiDoc(summary = "创建文章", description = "需要 posts:write 权限",
                tags = {"Posts", "Write"},
                responses = {
                        @ApiResponse(code = 201, description = "创建成功"),
                        @ApiResponse(code = 422)
                })
        public Object create(Request request) {
            return "created";
        }

        @ApiDoc(summary = "显式 401",
                responses = {
                        @ApiResponse(code = 200, description = "成功"),
                        @ApiResponse(code = 401, description = "要先登录")
                })
        public Object secureCreate(Request request) {
            return "ok";
        }

        @ApiDoc(summary = "只有说明", description = "说明在这里")
        public Object described(Request request) {
            return "ok";
        }

        @ApiDoc(description = "没有 summary 与方法级说明")
        public Object summaryFromRouteName() {
            return "ok";
        }

        public Object plain() {
            return "ok";
        }

        @ApiDoc(summary = "显式资源", responses = {
                @ApiResponse(code = 200, description = "成功", resource = FixtureResource.class)
        })
        public Object declaredResource(Request request) {
            return "ok";
        }

        public FixtureResource autoResource() {
            return null;
        }

        @ApiDoc(summary = "201 上的资源", responses = {
                @ApiResponse(code = 201, description = "创建成功")
        })
        public FixtureResource createResource() {
            return null;
        }

        @ApiDoc(summary = "未接入索引", responses = {
                @ApiResponse(code = 200, resource = UnindexedResource.class)
        })
        public Object unindexedResource(Request request) {
            return "ok";
        }

        @ApiDoc(summary = "非法资源类型", responses = {
                @ApiResponse(code = 200, resource = String.class)
        })
        public Object badResource(Request request) {
            return "ok";
        }

        public PostDto recordResponse() {
            return null;
        }

        @ApiDoc(summary = "嵌套 record", responses = @ApiResponse(code = 200))
        public NestedDto nestedRecord() {
            return null;
        }

        public NodeDto recursiveRecord() {
            return null;
        }

        public AnnotatedDto annotatedRecord() {
            return null;
        }

        @ApiDoc(summary = "显式 record", responses = {
                @ApiResponse(code = 200, resource = PostDto.class)
        })
        public Object explicitRecord(Request request) {
            return null;
        }

        public NestedFixtureResource nestedFixture() {
            return null;
        }

        public BrokenNestedResource brokenNested() {
            return null;
        }

        public OuterFixtureResource outerFixture() {
            return null;
        }
    }

    // ================= record 返回类型 =================

    public record AuthorDto(String name) {
    }

    public record PostDto(Long id, String title, int views, boolean published,
                          List<String> tags, AuthorDto author) {
    }

    public record NestedDto(PostDto post, int count) {
    }

    /** record 可以引用自己 —— 内联展开必须在环上停下 */
    public record NodeDto(int value, NodeDto next) {
    }

    public record AnnotatedDto(
            @JsonProperty("post_id") Long id,
            @JsonIgnore String secret,
            String title) {
    }

    /** 注解在父类方法上,注册的是子类 —— 继承来的方法也要能读到 */
    public static class BaseController {
        @ApiDoc(summary = "继承来的说明")
        public Object inherited() {
            return "ok";
        }
    }

    public static class ChildController extends BaseController {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> responses(Map<String, Object> operation) {
        return (Map<String, Object>) operation.get("responses");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(Map<String, Object> operation, String code) {
        return (Map<String, Object>) responses(operation).get(code);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> componentSchemas(Map<String, Object> document) {
        var components = (Map<String, Object>) document.get("components");
        return (Map<String, Object>) components.get("schemas");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> componentSchema(Map<String, Object> document, String name) {
        return (Map<String, Object>) componentSchemas(document).get(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    /** 响应体在根位置默认会被包成 {@code {"data": ...}} —— schema 必须如实写出来 */
    private Map<String, Object> wrappedFixtureContent(String wrapKey) {
        return wrappedContent(wrapKey, "FixtureResource");
    }

    private Map<String, Object> wrappedContent(String wrapKey, String component) {
        return Map.of("application/json", Map.of("schema", Map.of(
                "type", "object",
                "properties", Map.of(wrapKey,
                        Map.of("$ref", "#/components/schemas/" + component)),
                "required", List.of(wrapKey))));
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

    // ================= @ApiDoc =================

    @Nested
    @DisplayName("@ApiDoc")
    class Annotations {

        @Test
        @DisplayName("★ summary / description / tags 覆盖路由名与控制器名")
        void apiDocOverridesDefaults() {
            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::create))), "/posts", "get");

            assertThat(operation.get("summary")).isEqualTo("创建文章");
            assertThat(operation.get("description")).isEqualTo("需要 posts:write 权限");
            assertThat(operation.get("tags")).isEqualTo(List.of("Posts", "Write"));
        }

        @Test
        @DisplayName("★ 显式 responses 替换默认 200;空白说明按状态码补")
        void explicitResponsesReplaceTheDefault() {
            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::create))), "/posts", "get");

            assertThat(operation.get("responses"))
                    .as("""
                            创建接口返回 201,自动补一个 200 是错的。
                            写了 responses 就以作者为准 —— 这不是追加,是替换。""")
                    .isEqualTo(Map.of(
                            "201", Map.of("description", "创建成功"),
                            "422", Map.of("description", "校验失败")));
        }

        @Test
        @DisplayName("受保护的路由即使显式声明响应,仍自动补 401")
        void securedRoutesStillGet401() {
            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::create).middleware("auth:api"))), "/posts", "get");

            assertThat(response(operation, "401")).isNotNull();
            assertThat(response(operation, "401").get("description")).isEqualTo("未认证");
            assertThat(responses(operation)).containsKeys("201", "422", "401");
        }

        @Test
        @DisplayName("显式声明 401 时用作者的说明,不被自动补的那条盖掉")
        void explicit401Wins() {
            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::secureCreate).middleware("auth:api"))), "/posts", "get");

            assertThat(response(operation, "401").get("description"))
                    .as("自动补 401 只补缺,不覆盖")
                    .isEqualTo("要先登录");
            assertThat(responses(operation)).containsKey("200");
        }

        @Test
        @DisplayName("summary 留空退回路由名;tags 留空退回控制器名")
        void blanksFallBack() {
            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::summaryFromRouteName).name("posts.list"))), "/posts", "get");

            assertThat(operation.get("summary")).isEqualTo("posts.list");
            assertThat(operation.get("tags")).isEqualTo(List.of("Post"));
            assertThat(operation.get("description")).isEqualTo("没有 summary 与方法级说明");
        }

        @Test
        @DisplayName("没有注解的接口与这一刀之前完全一样")
        void unannotatedMethodKeepsDefaults() {
            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::plain))), "/posts", "get");

            assertThat(operation).doesNotContainKey("summary");
            assertThat(operation).doesNotContainKey("description");
            assertThat(operation.get("tags")).isEqualTo(List.of("Post"));
            assertThat(responses(operation)).containsOnlyKeys("200");
        }

        @Test
        @DisplayName("★ 注解写在父类方法上、注册的是子类 —— 也要读得到")
        void inheritedMethodAnnotationIsFound() {
            var operation = operation(document(routes(r ->
                    r.get("/inherited", ChildController::inherited))), "/inherited", "get");

            assertThat(operation.get("summary")).isEqualTo("继承来的说明");
        }
    }

    // ================= 响应 schema =================

    @Nested
    @DisplayName("响应 schema")
    class ResponseSchemas {

        @Test
        @DisplayName("★ @ApiResponse.resource → $ref + components.schemas")
        void explicitResource() {
            var document = document(routes(r ->
                    r.get("/posts", PostController::declaredResource)));
            var operation = operation(document, "/posts", "get");

            assertThat(response(operation, "200").get("content"))
                    .as("""
                            响应体不是 toArray 的形状 —— 序列化器在根位置还会包一层 data。
                            直接写 $ref 的话,前端会按 {"id":…} 解析而实际收到 {"data":{"id":…}}。""")
                    .isEqualTo(wrappedFixtureContent("data"));

            var fixture = componentSchema(document, "FixtureResource");
            assertThat(fixture.get("type")).isEqualTo("object");
            assertThat(fixture.get("required"))
                    .as("nickname 是可选字段,不该进 required")
                    .isEqualTo(List.of("id", "title"));
            assertThat(properties(fixture)).containsKeys("id", "title", "nickname");
            assertThat(properties(fixture).get("nickname"))
                    .as("索引里没有类型 → schema 里也不写,不猜 string")
                    .isEqualTo(Map.of());
        }

        @Test
        @DisplayName("★ 返回类型是具体 JsonResource → 自动挂 schema")
        void automaticResourceReturnType() {
            var document = document(routes(r ->
                    r.get("/posts", PostController::autoResource)));

            assertThat(response(operation(document, "/posts", "get"), "200").get("content"))
                    .isEqualTo(wrappedFixtureContent("data"));
        }

        @Test
        @DisplayName("自动 schema 挂在第一个 2xx 上 —— 201 的创建接口不塞回 200")
        void autoSchemaGoesToTheFirst2xx() {
            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::createResource))), "/posts", "get");

            assertThat(responses(operation)).containsOnlyKeys("201");
            assertThat(response(operation, "201").get("content"))
                    .isEqualTo(wrappedFixtureContent("data"));
        }

        @Test
        @DisplayName("★ 应用改了包裹键(wrapWith)→ schema 跟着用新键")
        void wrappingKeyIsReadFromTheResource() {
            JsonResource.wrapWith("result");

            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::autoResource))), "/posts", "get");

            assertThat(response(operation, "200").get("content"))
                    .isEqualTo(wrappedFixtureContent("result"));
        }

        @Test
        @DisplayName("应用关掉了包裹(withoutWrapping)→ 响应体就是资源本体")
        void wrappingCanBeDisabled() {
            JsonResource.withoutWrapping();

            var operation = operation(document(routes(r ->
                    r.get("/posts", PostController::autoResource))), "/posts", "get");

            assertThat(response(operation, "200").get("content"))
                    .isEqualTo(Map.of("application/json", Map.of("schema",
                            Map.of("$ref", "#/components/schemas/FixtureResource"))));
        }

        @Test
        @DisplayName("★ 索引里没有这个资源 → 不编 schema,记一条 note")
        void missingIndexIsReported() {
            var generator = new OpenApiGenerator("测试 API", "2.0.0", null);
            var document = generator.generate(routes(r ->
                    r.get("/posts", PostController::unindexedResource)));

            var operation = operation(document, "/posts", "get");
            assertThat(response(operation, "200")).doesNotContainKey("content");
            assertThat(document).doesNotContainKey("components");

            assertThat(generator.schemaNotes()).singleElement().asString()
                    .contains("UnindexedResource")
                    .contains("annotationProcessorPaths");
        }

        @Test
        @DisplayName("resource 指向非 JsonResource → 不写 schema,并说明原因")
        void nonResourceTypeIsReported() {
            var generator = new OpenApiGenerator("测试 API", "2.0.0", null);
            var document = generator.generate(routes(r ->
                    r.get("/posts", PostController::badResource)));

            assertThat(response(operation(document, "/posts", "get"), "200"))
                    .doesNotContainKey("content");
            assertThat(generator.schemaNotes()).singleElement().asString()
                    .contains("只支持 JsonResource 子类");
        }

        @Test
        @DisplayName("★ 嵌套资源 → $ref;自引用不会无限展开")
        void nestedResourcesBecomeRefs() {
            var document = document(routes(r ->
                    r.get("/nested", PostController::nestedFixture)));
            var operation = operation(document, "/nested", "get");

            assertThat(response(operation, "200").get("content"))
                    .isEqualTo(wrappedContent("data", "NestedFixtureResource"));

            var nested = componentSchema(document, "NestedFixtureResource");
            assertThat(properties(nested).get("owner"))
                    .isEqualTo(Map.of("$ref", "#/components/schemas/FixtureResource"));
            assertThat(properties(nested).get("items")).isEqualTo(Map.of(
                    "type", "array",
                    "items", Map.of("$ref", "#/components/schemas/FixtureResource")));
            assertThat(properties(nested).get("parent"))
                    .as("资源可以自引用 —— 名字先登记再构建,环上只会拿到 $ref")
                    .isEqualTo(Map.of("$ref", "#/components/schemas/NestedFixtureResource"));

            assertThat(nested.get("required")).isEqualTo(List.of("id"));
            assertThat(componentSchemas(document))
                    .containsKeys("NestedFixtureResource", "FixtureResource");
        }

        @Test
        @DisplayName("★ 嵌套资源类加载不到 → 退回 object,并记一条 note")
        void missingNestedResourceFallsBack() {
            var generator = new OpenApiGenerator("测试 API", "2.0.0", null);
            var document = generator.generate(routes(r ->
                    r.get("/nested", PostController::brokenNested)));

            var broken = componentSchema(document, "BrokenNestedResource");
            assertThat(properties(broken).get("owner"))
                    .as("不写一个指不到东西的 $ref")
                    .isEqualTo(Map.of("type", "object"));

            assertThat(generator.schemaNotes()).anySatisfy(note ->
                    assertThat(note).contains("app.DoesNotExist").contains("加载不到"));
        }

        @Test
        @DisplayName("★ with() 的根字段写进 envelope,且是可选属性")
        void outerFieldsAreInTheEnvelope() {
            var document = document(routes(r ->
                    r.get("/outer", PostController::outerFixture)));
            var operation = operation(document, "/outer", "get");

            assertThat(response(operation, "200").get("content")).isEqualTo(Map.of(
                    "application/json", Map.of("schema", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "data", Map.of("$ref",
                                            "#/components/schemas/OuterFixtureResource"),
                                    "meta", Map.of("type", "object"),
                                    "trace", Map.of("type", "string")),
                            "required", List.of("data")))));
        }

        @Test
        @DisplayName("withoutWrapping 时 with() 的根字段不写,并记 note")
        void outerFieldsAreNotedWhenUnwrapped() {
            JsonResource.withoutWrapping();

            var generator = new OpenApiGenerator("测试 API", "2.0.0", null);
            var document = generator.generate(routes(r ->
                    r.get("/outer", PostController::outerFixture)));
            var operation = operation(document, "/outer", "get");

            assertThat(response(operation, "200").get("content")).isEqualTo(Map.of(
                    "application/json", Map.of("schema",
                            Map.of("$ref", "#/components/schemas/OuterFixtureResource"))));

            assertThat(generator.schemaNotes()).anySatisfy(note ->
                    assertThat(note).contains("with() 的根字段"));
        }
    }

    // ================= record 响应 schema =================

    @Nested
    @DisplayName("record 响应 schema")
    class RecordSchemas {

        @Test
        @DisplayName("★ 返回 record → 自动生成 schema,而且**没有** data 包裹")
        void recordReturnTypeIsAutomatic() {
            var document = document(routes(r ->
                    r.get("/stats", PostController::recordResponse)));
            var operation = operation(document, "/stats", "get");

            assertThat(response(operation, "200").get("content"))
                    .as("record 不是 JsonResource —— Jackson 直接序列化它,根位置没有 data")
                    .isEqualTo(Map.of("application/json", Map.of("schema",
                            Map.of("$ref", "#/components/schemas/PostDto"))));

            var post = componentSchema(document, "PostDto");
            assertThat(properties(post))
                    .containsKeys("id", "title", "views", "published", "tags", "author");
            assertThat(properties(post).get("tags")).isEqualTo(Map.of("type", "array"));
            assertThat(properties(post).get("author")).isEqualTo(Map.of(
                    "type", "object",
                    "properties", Map.of("name", Map.of("type", "string"))));
            assertThat(post.get("required"))
                    .as("只把原始类型标 required —— 引用类型可能是 null,non_null 输出时会缺席")
                    .isEqualTo(List.of("views", "published"));
        }

        @Test
        @DisplayName("嵌套 record 内联展开,不单独占一个 component")
        void nestedRecordIsInlined() {
            var document = document(routes(r ->
                    r.get("/stats", PostController::nestedRecord)));
            var nested = componentSchema(document, "NestedDto");

            assertThat(properties(nested).get("count")).isEqualTo(Map.of("type", "integer"));

            @SuppressWarnings("unchecked")
            var post = (Map<String, Object>) properties(nested).get("post");
            assertThat(post.get("type")).isEqualTo("object");
            assertThat(properties(post)).containsKeys("id", "author");

            assertThat(componentSchemas(document)).containsOnlyKeys("NestedDto");
        }

        @Test
        @DisplayName("★ record 自引用 → 在环上停下,不无限展开")
        void recursiveRecordStops() {
            var document = document(routes(r ->
                    r.get("/node", PostController::recursiveRecord)));
            var node = componentSchema(document, "NodeDto");

            assertThat(properties(node).get("next"))
                    .as("环上只说是 object,不再展开")
                    .isEqualTo(Map.of("type", "object"));
            assertThat(node.get("required")).isEqualTo(List.of("value"));
        }

        @Test
        @DisplayName("record 组件上的 @JsonProperty / @JsonIgnore 要生效")
        void jacksonAnnotationsAreRespected() {
            var document = document(routes(r ->
                    r.get("/annotated", PostController::annotatedRecord)));
            var dto = componentSchema(document, "AnnotatedDto");

            assertThat(properties(dto))
                    .containsKey("post_id")
                    .containsKey("title")
                    .doesNotContainKey("secret")
                    .doesNotContainKey("id");
        }

        @Test
        @DisplayName("@ApiResponse(resource = Record.class) 也走 record schema")
        void explicitRecordResource() {
            var document = document(routes(r ->
                    r.get("/stats", PostController::explicitRecord)));
            var operation = operation(document, "/stats", "get");

            assertThat(response(operation, "200").get("content"))
                    .isEqualTo(Map.of("application/json", Map.of("schema",
                            Map.of("$ref", "#/components/schemas/PostDto"))));
            assertThat(componentSchema(document, "PostDto")).isNotNull();
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

    // ================= 请求体 =================

    @Nested
    @DisplayName("请求体(FormRequest.rules())")
    class RequestBodies {

        @SuppressWarnings("unchecked")
        private Map<String, Object> bodySchema(String uri, String verb) {
            var operation = operation(document(routes(r ->
                    r.post(uri, PostController::storeValidated))), uri, verb);
            var requestBody = (Map<String, Object>) operation.get("requestBody");
            var content = (Map<String, Object>) requestBody.get("content");
            var json = (Map<String, Object>) content.get("application/json");
            return (Map<String, Object>) json.get("schema");
        }

        @Test
        @DisplayName("★ rules() 变成 schema:类型、约束、required 都在")
        void rulesBecomeSchema() {
            var schema = bodySchema("/posts", "post");

            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("required"))
                    .as("required 来自规则串里的 required(排过序,好让 diff 逐字节稳定)")
                    .isEqualTo(List.of("email", "password", "password_confirmation",
                            "role", "slug", "title"));
            @SuppressWarnings("unchecked")
            var properties = (Map<String, Object>) schema.get("properties");

            @SuppressWarnings("unchecked")
            var title = (Map<String, Object>) properties.get("title");
            assertThat(title).containsEntry("type", "string")
                    .as("""
                            max:200 要变成 **maxLength**,不是 maximum ——
                            前者限长度,后者限大小。写反了的话前端的表单校验
                            会允许一个 200 字的标题,然后在提交时得到 422。""")
                    .containsEntry("maxLength", 200L);

            @SuppressWarnings("unchecked")
            var age = (Map<String, Object>) properties.get("age");
            assertThat(age.get("type"))
                    .as("nullable → OpenAPI 3.1 的 type 数组")
                    .isEqualTo(List.of("integer", "null"));
            assertThat(age).containsEntry("minimum", 18L).containsEntry("maximum", 99L);

            @SuppressWarnings("unchecked")
            var role = (Map<String, Object>) properties.get("role");
            assertThat(role.get("enum")).isEqualTo(List.of("admin", "user"));
        }

        @Test
        @DisplayName("格式:email / confirmed 的确认字段 / regex 的定界符剥掉")
        void formatsAndSpecials() {
            var schema = bodySchema("/posts", "post");
            @SuppressWarnings("unchecked")
            var properties = (Map<String, Object>) schema.get("properties");

            @SuppressWarnings("unchecked")
            var email = (Map<String, Object>) properties.get("email");
            assertThat(email).containsEntry("format", "email");

            @SuppressWarnings("unchecked")
            var confirmation = (Map<String, Object>) properties.get("password_confirmation");
            assertThat(confirmation)
                    .as("""
                            Laravel 的 confirmed 意味着请求里还有 password_confirmation ——
                            不写进 schema 的话,前端照文档发请求会得到 422,
                            而文档上看不出为什么。""")
                    .isNotNull();

            @SuppressWarnings("unchecked")
            var slug = (Map<String, Object>) properties.get("slug");
            assertThat(slug.get("pattern"))
                    .as("""
                            regex:/^[a-z0-9-]+$/ 里那对斜杠是 PHP 的定界符。
                            留着它的话客户端生成器会把 / 当成模式的一部分 ——
                            于是那个校验永远不通过。""")
                    .isEqualTo("^[a-z0-9-]+$");
        }

        @Test
        @DisplayName("没有类型规则时是 string —— HTTP 表单的值本来就是字符串")
        void untypedMeansString() {
            @SuppressWarnings("unchecked")
            var properties = (Map<String, Object>) bodySchema("/posts", "post")
                    .get("properties");
            @SuppressWarnings("unchecked")
            var tags = (Map<String, Object>) properties.get("tags");

            assertThat(tags.get("type")).isEqualTo("array");
            assertThat(tags).containsEntry("maxItems", 5L);
        }

        @Test
        @DisplayName("没有 FormRequest 的路由不输出 requestBody")
        void noBodyWithoutFormRequest() {
            var operation = operation(document(routes(r ->
                    r.post("/posts", PostController::store))), "/posts", "post");

            assertThat(operation).doesNotContainKey("requestBody");
        }

        @Test
        @DisplayName("★ rules() 抛异常的 FormRequest:跳过它,并把那个接口报出来")
        void brokenFormRequestIsSkippedAndReported() {
            var router = new Router();
            router.post("/weird", WeirdController::store);
            var generator = new OpenApiGenerator("t", "1", null);

            Map<String, Object> document = generator.generate(router.compile());

            @SuppressWarnings("unchecked")
            var paths = (Map<String, Object>) document.get("paths");
            @SuppressWarnings("unchecked")
            var weird = (Map<String, Object>) paths.get("/weird");
            @SuppressWarnings("unchecked")
            var post = (Map<String, Object>) weird.get("post");
            assertThat(post)
                    .as("""
                            十个接口里有一个的请求体缺失,远好过整份文档生成不出来。""")
                    .doesNotContainKey("requestBody");
            assertThat(generator.skippedRequestBodies())
                    .as("静默少一个字段会让人照不完整的文档写请求")
                    .hasSize(1)
                    .first().asString().contains("/weird");
        }
    }

    /** rules() 依赖实例状态的 FormRequest —— 生成文档时没有请求可读 */
    public static class WeirdController {
        public Object store(WeirdForm form) {
            return "x";
        }
    }

    public static class WeirdForm extends io.jaravel.validation.FormRequest {
        public WeirdForm() {
            // 没有无参构造的实质 —— 与 ArgumentBinder 的实例化约定冲突,
            // 于是 OpenAPI 生成器在反射时失败(应该被跳过并报告)
            throw new IllegalStateException("需要请求上下文");
        }

        @Override
        public Map<String, String> rules() {
            return Map.of("x", "required");
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
