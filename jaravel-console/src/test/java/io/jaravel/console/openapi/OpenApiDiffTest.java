package io.jaravel.console.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.console.CommandRegistry;
import io.jaravel.console.ConsoleIo;

/**
 * {@code jaravel openapi:diff}(docs/12 §5)。
 *
 * <p>每条测试都从**两份文档**出发,断言三件事:退出码、人读输出里的判定、
 * 以及(必要时)JSON 输出。判定标准只有一条:
 * 按旧文档生成的客户端还能不能工作。
 */
class OpenApiDiffTest {

    // ================= 夹具 =================

    private static final String OK = "\"responses\":{\"200\":{\"description\":\"ok\"}}";

    private static final String TITLE = "\"title\":{\"type\":\"string\"}";
    private static final String NAME = "\"name\":{\"type\":\"string\"}";
    private static final String ID = "\"id\":{\"type\":\"integer\"}";

    private record Outcome(int code, String out, String err) {
    }

    private Outcome diff(Path tmp, String baseJson, String currentJson, String... options)
            throws IOException {
        Path base = tmp.resolve("base.json");
        Path current = tmp.resolve("current.json");
        Files.writeString(base, baseJson);
        Files.writeString(current, currentJson);
        return diffFiles(base, current, options);
    }

    private Outcome diffFiles(Path base, Path current, String... options) {
        var registry = new CommandRegistry();
        registry.register(OpenApiDiffCommand.class);

        var io = new ConsoleIo.Recording();
        var args = new ArrayList<String>();
        args.add(base.toString());
        args.add(current.toString());
        args.addAll(List.of(options));

        int code = registry.run("openapi:diff", args, io);
        return new Outcome(code, io.output(), io.errors());
    }

    private static String document(String version, String paths) {
        return """
                {"openapi":"3.1.0","info":{"title":"Test","version":"%s"},"paths":{%s}}"""
                .formatted(version, paths);
    }

    private static String doc(String path, String pathItem) {
        return document("1.0.0", "\"" + path + "\":" + pathItem);
    }

    private static String pathItem(String... operations) {
        return "{" + String.join(",", operations) + "}";
    }

    private static String operation(String operationId, String extra) {
        var members = new StringBuilder("\"operationId\":\"").append(operationId).append("\"");
        if (extra != null && !extra.isBlank()) members.append(',').append(extra);
        members.append(',').append(OK);
        return "{" + members + "}";
    }

    private static String get(String operationId, String extra) {
        return "\"get\":" + operation(operationId, extra);
    }

    private static String post(String operationId, String extra) {
        return "\"post\":" + operation(operationId, extra);
    }

    private static String operationWithResponses(String operationId, String responses) {
        return "{\"operationId\":\"" + operationId + "\",\"responses\":" + responses + "}";
    }

    private static String objectSchema(String required, String properties) {
        return "{\"type\":\"object\""
                + (required == null || required.isBlank() ? "" : ",\"required\":[" + required + "]")
                + ",\"properties\":{" + properties + "}}";
    }

    private static String requestBody(String schema) {
        return "\"requestBody\":{\"required\":true,\"content\":{\"application/json\":"
                + "{\"schema\":" + schema + "}}}";
    }

    private static String okWithSchema(String properties) {
        return "{\"200\":{\"description\":\"ok\",\"content\":{\"application/json\":"
                + "{\"schema\":" + objectSchema("", properties) + "}}}}";
    }

    private static String okWithRootSchema(String schema) {
        return "{\"200\":{\"description\":\"ok\",\"content\":{\"application/json\":"
                + "{\"schema\":" + schema + "}}}}";
    }

    private static String queryParameter(boolean required, String schema) {
        return "\"parameters\":[{\"name\":\"per_page\",\"in\":\"query\","
                + "\"required\":" + required + ",\"schema\":" + schema + "}]";
    }

    // ================= 没有变化 =================

    @Test
    @DisplayName("完全相同的两份文档 → 0,并明确说没有差异")
    void identicalDocumentsHaveNoChanges(@TempDir Path tmp) throws IOException {
        String json = doc("/posts", pathItem(get("PostController.index", "")));

        Outcome result = diff(tmp, json, json);

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("两份文档没有差异");
    }

    @Test
    @DisplayName("★ info.version 变了不算契约变更 —— 每次发布它都会变")
    void infoVersionIsIgnored(@TempDir Path tmp) throws IOException {
        String base = document("1.0.0", "\"/posts\":" + pathItem(get("PostController.index", "")));
        String current = document("2.0.0", "\"/posts\":" + pathItem(get("PostController.index", "")));

        Outcome result = diff(tmp, base, current);

        assertThat(result.code()).isZero();
        assertThat(result.out()).as("拿 info.version 当变更,等于给 diff 加一条永远为真的噪音")
                .contains("两份文档没有差异");
    }

    // ================= 路径与接口 =================

    @Test
    @DisplayName("删除路径 → 破坏性,退出码 1")
    void removedPathIsBreaking(@TempDir Path tmp) throws IOException {
        String base = doc("/posts", pathItem(get("PostController.index", "")));
        String current = doc("/comments", pathItem(get("CommentController.index", "")));

        Outcome result = diff(tmp, base, current);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("路径被删除").contains("/posts");
    }

    @Test
    @DisplayName("新增路径 → 0;--fail-on=any 时 → 1")
    void addedPathIsNotBreakingByDefault(@TempDir Path tmp) throws IOException {
        String base = doc("/posts", pathItem(get("PostController.index", "")));
        String current = document("1.0.0",
                "\"/posts\":" + pathItem(get("PostController.index", ""))
                        + ",\"/comments\":" + pathItem(get("CommentController.index", "")));

        assertThat(diff(tmp, base, current).code()).isZero();

        Outcome strict = diff(tmp, base, current, "--fail-on=any");
        assertThat(strict.code()).isEqualTo(1);
        assertThat(strict.out()).contains("新增路径");
    }

    @Test
    @DisplayName("删除一个方法 → 破坏性")
    void removedMethodIsBreaking(@TempDir Path tmp) throws IOException {
        String base = doc("/posts", pathItem(
                get("PostController.index", ""), post("PostController.store", "")));
        String current = doc("/posts", pathItem(get("PostController.index", "")));

        Outcome result = diff(tmp, base, current);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("接口被删除").contains("POST /posts");
    }

    @Test
    @DisplayName("★ operationId 改名 → 破坏性 —— 生成的客户端方法名会变")
    void renamedOperationIdIsBreaking(@TempDir Path tmp) throws IOException {
        String base = doc("/posts", pathItem(get("PostController.index", "")));
        String current = doc("/posts", pathItem(get("PostController.list", "")));

        Outcome result = diff(tmp, base, current);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("operationId 从 PostController.index 改为 PostController.list");
    }

    // ================= 参数 =================

    @Test
    @DisplayName("新增必填参数 → 破坏性;新增可选参数 → 非破坏")
    void addedParameterDependsOnRequired(@TempDir Path tmp) throws IOException {
        String base = doc("/posts", pathItem(get("PostController.index", "")));
        String required = doc("/posts", pathItem(get("PostController.index",
                queryParameter(true, "{\"type\":\"integer\"}"))));
        String optional = doc("/posts", pathItem(get("PostController.index",
                queryParameter(false, "{\"type\":\"integer\"}"))));

        Outcome breaking = diff(tmp, base, required);
        assertThat(breaking.code()).isEqualTo(1);
        assertThat(breaking.out()).contains("新增必填参数").contains("per_page(query)");

        Outcome nonBreaking = diff(tmp, base, optional);
        assertThat(nonBreaking.code()).isZero();
        assertThat(nonBreaking.out()).contains("新增可选参数");
    }

    @Test
    @DisplayName("参数由可选变为必填 → 破坏性;反过来 → 非破坏")
    void requiredFlagChange(@TempDir Path tmp) throws IOException {
        String optional = doc("/posts", pathItem(get("PostController.index",
                queryParameter(false, "{\"type\":\"integer\"}"))));
        String required = doc("/posts", pathItem(get("PostController.index",
                queryParameter(true, "{\"type\":\"integer\"}"))));

        Outcome tightened = diff(tmp, optional, required);
        assertThat(tightened.code()).isEqualTo(1);
        assertThat(tightened.out()).contains("参数由可选变为必填");

        Outcome loosened = diff(tmp, required, optional);
        assertThat(loosened.code()).isZero();
        assertThat(loosened.out()).contains("参数由必填变为可选");
    }

    @Test
    @DisplayName("参数类型变了 → 破坏性")
    void parameterTypeChangeIsBreaking(@TempDir Path tmp) throws IOException {
        String integer = doc("/posts", pathItem(get("PostController.index",
                queryParameter(false, "{\"type\":\"integer\"}"))));
        String string = doc("/posts", pathItem(get("PostController.index",
                queryParameter(false, "{\"type\":\"string\"}"))));

        Outcome result = diff(tmp, integer, string);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("类型 integer → string");
    }

    @Test
    @DisplayName("★ 请求里的可空放宽(integer → [integer, null])不是破坏性")
    void nullableWideningInRequestIsNotBreaking(@TempDir Path tmp) throws IOException {
        String required = doc("/posts", pathItem(get("PostController.index",
                queryParameter(false, "{\"type\":\"integer\"}"))));
        String nullable = doc("/posts", pathItem(get("PostController.index",
                queryParameter(false, "{\"type\":[\"integer\",\"null\"]}"))));

        Outcome result = diff(tmp, required, nullable);

        assertThat(result.code()).as("服务端接受的范围变大了,旧客户端不受影响").isZero();
        assertThat(result.out()).contains("类型 integer → integer | null");
    }

    // ================= 请求体 =================

    @Test
    @DisplayName("请求体新增必填字段 → 破坏性;新增可选字段 → 非破坏")
    void newRequestBodyPropertyDependsOnRequired(@TempDir Path tmp) throws IOException {
        String base = doc("/posts", pathItem(post("PostController.store",
                requestBody(objectSchema("\"title\"", TITLE)))));
        String required = doc("/posts", pathItem(post("PostController.store",
                requestBody(objectSchema("\"title\",\"name\"", TITLE + "," + NAME)))));
        String optional = doc("/posts", pathItem(post("PostController.store",
                requestBody(objectSchema("\"title\"", TITLE + "," + NAME)))));

        Outcome breaking = diff(tmp, base, required);
        assertThat(breaking.code()).isEqualTo(1);
        assertThat(breaking.out()).contains("新增必填字段").contains("name");

        Outcome nonBreaking = diff(tmp, base, optional);
        assertThat(nonBreaking.code()).isZero();
        assertThat(nonBreaking.out()).contains("新增可选字段");
    }

    @Test
    @DisplayName("请求里的枚举收窄 → 破坏性;放宽 → 非破坏")
    void enumNarrowingInRequestIsBreaking(@TempDir Path tmp) throws IOException {
        String wide = doc("/posts", pathItem(get("PostController.index",
                queryParameter(false, "{\"type\":\"string\",\"enum\":[\"admin\",\"user\"]}"))));
        String narrow = doc("/posts", pathItem(get("PostController.index",
                queryParameter(false, "{\"type\":\"string\",\"enum\":[\"admin\"]}"))));

        Outcome breaking = diff(tmp, wide, narrow);
        assertThat(breaking.code()).isEqualTo(1);
        assertThat(breaking.out()).contains("枚举 admin | user → admin");

        Outcome nonBreaking = diff(tmp, narrow, wide);
        assertThat(nonBreaking.code()).isZero();
    }

    // ================= 认证 =================

    @Test
    @DisplayName("接口开始要求认证 → 破坏性;取消认证 → 非破坏")
    void securityChangesAreDirectional(@TempDir Path tmp) throws IOException {
        String open = doc("/posts", pathItem(get("PostController.index", "")));
        String secured = doc("/posts", pathItem(get("PostController.index",
                "\"security\":[{\"bearerAuth\":[]}]")));

        Outcome tightened = diff(tmp, open, secured);
        assertThat(tightened.code()).isEqualTo(1);
        assertThat(tightened.out()).contains("现在要求认证");

        Outcome loosened = diff(tmp, secured, open);
        assertThat(loosened.code()).isZero();
        assertThat(loosened.out()).contains("不再要求认证");
    }

    // ================= 响应 =================

    @Test
    @DisplayName("删除响应状态码 → 破坏性;新增 → 非破坏")
    void removedResponseCodeIsBreaking(@TempDir Path tmp) throws IOException {
        String twoCodes = doc("/posts", pathItem("\"get\":" + operationWithResponses(
                "PostController.index",
                "{\"200\":{\"description\":\"ok\"},"
                        + "\"404\":{\"description\":\"missing\"}}")));
        String oneCode = doc("/posts", pathItem(get("PostController.index", "")));

        Outcome breaking = diff(tmp, twoCodes, oneCode);
        assertThat(breaking.code()).isEqualTo(1);
        assertThat(breaking.out()).as("stderr:%s", breaking.err())
                .contains("响应状态码被移除").contains("404");

        Outcome nonBreaking = diff(tmp, oneCode, twoCodes);
        assertThat(nonBreaking.code()).isZero();
        assertThat(nonBreaking.out()).contains("新增响应状态码");
    }

    @Test
    @DisplayName("响应删字段 → 破坏性;加字段 → 非破坏")
    void removedResponsePropertyIsBreaking(@TempDir Path tmp) throws IOException {
        String rich = doc("/posts", pathItem("\"get\":"
                + operationWithResponses("PostController.index", okWithSchema(ID + "," + TITLE))));
        String poor = doc("/posts", pathItem("\"get\":"
                + operationWithResponses("PostController.index", okWithSchema(ID))));

        Outcome breaking = diff(tmp, rich, poor);
        assertThat(breaking.code()).isEqualTo(1);
        assertThat(breaking.out()).contains("响应不再包含这个字段").contains("title");

        Outcome nonBreaking = diff(tmp, poor, rich);
        assertThat(nonBreaking.code()).isZero();
        assertThat(nonBreaking.out()).contains("响应新增字段");
    }

    @Test
    @DisplayName("★ 响应里的可空放宽是破坏性 —— 客户端可能收到没见过的 null")
    void nullableWideningInResponseIsBreaking(@TempDir Path tmp) throws IOException {
        String strict = doc("/posts", pathItem("\"get\":"
                + operationWithResponses("PostController.index",
                        okWithRootSchema("{\"type\":\"string\"}"))));
        String nullable = doc("/posts", pathItem("\"get\":"
                + operationWithResponses("PostController.index",
                        okWithRootSchema("{\"type\":[\"string\",\"null\"]}"))));

        Outcome result = diff(tmp, strict, nullable);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out()).contains("类型 string → string | null");
    }

    // ================= 门槛与输出 =================

    @Test
    @DisplayName("--fail-on=none:破坏性变更照样报告,但返回 0")
    void failOnNoneDoesNotFail(@TempDir Path tmp) throws IOException {
        String base = doc("/posts", pathItem(get("PostController.index", "")));
        String current = doc("/comments", pathItem(get("CommentController.index", "")));

        Outcome result = diff(tmp, base, current, "--fail-on=none");

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("破坏性变更").contains("fail-on=none");
    }

    @Test
    @DisplayName("--fail-on 的非法值要说清允许哪些")
    void invalidFailOnExplainsChoices(@TempDir Path tmp) throws IOException {
        String json = doc("/posts", pathItem(get("PostController.index", "")));

        Outcome result = diff(tmp, json, json, "--fail-on=kaboom");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("只认 breaking / any / none");
    }

    @Test
    @DisplayName("★ --json:stdout 只有 JSON,stdout/stderr 都不掺人读文字")
    void jsonOutputIsTheOnlyThingOnStdout(@TempDir Path tmp) throws IOException {
        String base = doc("/posts", pathItem(get("PostController.index", "")));
        String current = doc("/comments", pathItem(get("CommentController.index", "")));

        Outcome result = diff(tmp, base, current, "--json");

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).isEmpty();

        var tree = new ObjectMapper().readTree(result.out());
        assertThat(tree.path("summary").path("breaking").asInt()).isEqualTo(1);
        assertThat(tree.path("summary").path("added").asInt()).isEqualTo(1);
        assertThat(tree.path("breaking").get(0).path("location").asText()).isNotBlank();
    }

    @Test
    @DisplayName("输出顺序稳定 —— 路径按字典序,不随 JSON 里的顺序漂")
    void outputOrderIsDeterministic(@TempDir Path tmp) throws IOException {
        String base = document("1.0.0",
                "\"/b\":" + pathItem(get("BController.index", ""))
                        + ",\"/a\":" + pathItem(get("AController.index", "")));
        String current = doc("/z", pathItem(get("ZController.index", "")));

        Outcome result = diff(tmp, base, current);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.out().indexOf("/a"))
                .as("哈希序会让 diff 每次都不一样,而那种红最难查")
                .isLessThan(result.out().indexOf("/b"));
    }

    @Test
    @DisplayName("★ openapi:diff 必须是离线命令 —— 它只读两份 JSON,不需要容器")
    void isOffline() {
        var registry = new CommandRegistry();
        registry.register(OpenApiDiffCommand.class);

        assertThat(registry.isOffline("openapi:diff")).isTrue();
    }

    // ================= 坏输入 =================

    @Test
    @DisplayName("文件不存在 → 1,并给出生成基线的两步")
    void missingFileExplainsHowToCreateOne(@TempDir Path tmp) throws IOException {
        Path missing = tmp.resolve("missing.json");
        Path current = tmp.resolve("current.json");
        Files.writeString(current, doc("/posts", pathItem(get("PostController.index", ""))));

        Outcome result = diffFiles(missing, current);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("文件不存在").contains("openapi:generate");
    }

    @Test
    @DisplayName("不是合法 JSON → 1,说清是哪个文件")
    void invalidJsonNamesTheFile(@TempDir Path tmp) throws IOException {
        Path base = tmp.resolve("base.json");
        Path current = tmp.resolve("current.json");
        Files.writeString(base, "{ this is not json");
        Files.writeString(current, doc("/posts", pathItem(get("PostController.index", ""))));

        Outcome result = diffFiles(base, current);

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("不是合法的 JSON").contains("base.json");
    }

    @Test
    @DisplayName("没有 paths 的 JSON 不是 OpenAPI 文档 → 1")
    void jsonWithoutPathsIsRejected(@TempDir Path tmp) throws IOException {
        Outcome result = diff(tmp, "{\"hello\":\"world\"}",
                doc("/posts", pathItem(get("PostController.index", ""))));

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.err()).contains("没有 paths");
    }
}
