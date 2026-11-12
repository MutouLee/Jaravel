package io.jaravel.console.openapi;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

import io.jaravel.console.ConsoleException;

/**
 * 两份 OpenAPI 文档的**语义**比较(docs/12 §5 的 {@code openapi:diff})。
 *
 * <h2>★ 比较的是 API 契约,不是 JSON 文本</h2>
 * <p>文本 diff 在这里没有用:生成器一改缩进、路径换个顺序、{@code info.version}
 * 跟着发布涨一格,文本 diff 都会红,而它们与客户端能不能继续工作无关。
 * 那种噪音会训练人忽略 diff —— 于是真正的那条破坏性变更也一起被忽略。
 *
 * <p>所以这里按 {@code path → method → operation} 对齐,再逐层比较
 * 参数、请求体、响应与 schema。判定标准只有一条:
 * <b>按旧文档生成的客户端,照着旧文档发请求、读响应,还能不能工作。</b>
 *
 * <h2>三个等级</h2>
 * <ul>
 *   <li>{@link Severity#BREAKING} —— 旧客户端可能出错(默认让命令返回 1);</li>
 *   <li>{@link Severity#NON_BREAKING} —— 契约变了,但不会弄坏旧客户端;</li>
 *   <li>{@link Severity#ADDED} —— 新增的路径或接口。</li>
 * </ul>
 *
 * <h2>请求与响应方向相反</h2>
 * <p>同一个 schema 变化,在请求里与响应里的含义是反的:
 * <ul>
 *   <li><b>请求</b>描述服务端**会不会接受**。取值范围收窄 = 破坏性
 *       (旧客户端可能发一个现在不被接受的值);放宽 = 非破坏。</li>
 *   <li><b>响应</b>描述服务端**可能返回什么**。取值范围放宽 = 破坏性
 *       (旧客户端可能收到没见过的值);收窄 = 非破坏。</li>
 * </ul>
 * 例如 {@code type: integer} → {@code [integer, null]} 在请求里是放宽
 * (以前只收整数,现在也收 null),在响应里是放宽(客户端可能收到 null)。
 * 所以 {@link Direction} 贯穿整个递归 —— 不区分方向的话,
 * 这两种情况里必有一种判反。
 *
 * <h2>不认识的改动按破坏性处理</h2>
 * <p>schema 里出现本类不认识的关键字变化(例如 {@code oneOf})时,
 * 保守地报破坏性,并在消息里说明「diff 不认识它」。漏报比误报糟:
 * 误报只需要人看一眼,漏报会让一次真正的破坏性变更随发布上线。
 * 而 {@code description} / {@code example} 这类纯文档字段是明确忽略的 ——
 * 它们不进契约,报出来只是噪音。
 *
 * <h2>已知限制</h2>
 * <ul>
 *   <li>{@code $ref} 不解析:两个引用不同就报破坏性;引用相同则不深入,
 *       但 {@code components/schemas} 会单独比较 —— 资源字段的变化仍然能看到。</li>
 *   <li>路径级的共享 {@code parameters} 不单独比较 —— 生成器把它们
 *       写在每个 operation 上。</li>
 *   <li>{@code info.version} 刻意忽略:每次发布它都会变,
 *       拿它当变更等于给 diff 加一条永远为真的噪音。</li>
 * </ul>
 */
final class OpenApiDiff {

    enum Severity { BREAKING, NON_BREAKING, ADDED }

    record Change(Severity severity, String location, String message) {
    }

    private enum Direction { REQUEST, RESPONSE }

    /** OpenAPI 的 Path Item 里这些键是 HTTP 方法,其余(parameters/summary/…)不是 */
    private static final List<String> METHODS = List.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");

    /** 下界:值越大,允许的范围越窄 */
    private static final List<String> LOWER_BOUNDS = List.of(
            "minLength", "minimum", "minItems", "minProperties");

    /** 上界:值越小,允许的范围越窄 */
    private static final List<String> UPPER_BOUNDS = List.of(
            "maxLength", "maximum", "maxItems", "maxProperties");

    /** 本类会逐项比较的 schema 关键字 */
    private static final Set<String> HANDLED = Set.of(
            "type", "enum", "format", "pattern", "properties", "required",
            "additionalProperties", "items", "$ref",
            "minLength", "minimum", "minItems", "minProperties",
            "maxLength", "maximum", "maxItems", "maxProperties");

    /** 纯文档字段:它们变化不影响客户端行为 */
    private static final Set<String> IGNORED = Set.of(
            "description", "title", "example", "examples", "default",
            "deprecated", "xml", "externalDocs", "$comment", "$schema", "$id",
            "readOnly", "writeOnly");

    private OpenApiDiff() {
    }

    static List<Change> compare(JsonNode base, JsonNode current) {
        var changes = new ArrayList<Change>();

        compareDocumentInfo(base, current, changes);

        JsonNode basePaths = pathsOf(base, "基线");
        JsonNode currentPaths = pathsOf(current, "当前");

        var paths = new TreeSet<String>();
        basePaths.fieldNames().forEachRemaining(paths::add);
        currentPaths.fieldNames().forEachRemaining(paths::add);

        for (String path : paths) {
            JsonNode basePath = basePaths.get(path);
            JsonNode currentPath = currentPaths.get(path);

            if (basePath == null) {
                changes.add(new Change(Severity.ADDED, path, "新增路径。"));
            } else if (currentPath == null) {
                changes.add(new Change(Severity.BREAKING, path,
                        "路径被删除 —— 按旧文档生成的客户端会请求一个 404。"));
            } else {
                comparePath(path, basePath, currentPath, changes);
            }
        }

        compareSecuritySchemes(base, current, changes);
        compareSchemas(base, current, changes);

        // 排序后再交给调用方:输出稳定,否则同一份比较两次的顺序都可能不同,
        // 而 CI 日志里那种「这次和上次的红不一样」最难查
        changes.sort(java.util.Comparator
                .comparing(Change::location)
                .thenComparing(Change::message));
        return changes;
    }

    // ================= 文档级 =================

    private static void compareDocumentInfo(JsonNode base, JsonNode current, List<Change> out) {
        String baseVersion = text(base, "openapi");
        String currentVersion = text(current, "openapi");
        if (baseVersion != null && currentVersion != null
                && !baseVersion.equals(currentVersion)) {
            out.add(new Change(Severity.NON_BREAKING, "文档",
                    "OpenAPI 版本 " + baseVersion + " → " + currentVersion + "。"));
        }

        if (!Objects.equals(base.get("servers"), current.get("servers"))) {
            out.add(new Change(Severity.NON_BREAKING, "文档 · servers",
                    "服务器地址变了 —— 客户端默认连的地址可能不同。"));
        }
    }

    /**
     * 安全方案的定义变了。
     *
     * <p>只比较**两份文档里都有**的方案。新增的方案本身不影响旧客户端;
     * 而一个方案被删掉时,引用它的 operation 的 {@code security} 也会变,
     * 那边会报出来 —— 在这里再报一次是重复。
     */
    private static void compareSecuritySchemes(JsonNode base, JsonNode current, List<Change> out) {
        Map<String, JsonNode> baseSchemes = fieldsOf(base.path("components").path("securitySchemes"));
        Map<String, JsonNode> currentSchemes =
                fieldsOf(current.path("components").path("securitySchemes"));

        for (String name : new TreeSet<>(baseSchemes.keySet())) {
            JsonNode before = baseSchemes.get(name);
            JsonNode after = currentSchemes.get(name);
            if (after != null && !before.equals(after)) {
                out.add(new Change(Severity.BREAKING, "文档 · 安全方案 " + name,
                        "定义变了 —— 旧客户端带的凭据类型可能不再被接受。"));
            }
        }
    }

    /**
     * {@code components.schemas}。
     *
     * <p>响应体是 {@code $ref} 进来的:operation 里那个引用字符串没变,
     * 但组件里的字段可能已经删了一个。不比较这里的话,
     * 那一类破坏性变更会从门禁下面漏过去。
     */
    private static void compareSchemas(JsonNode base, JsonNode current, List<Change> out) {
        Map<String, JsonNode> before = fieldsOf(base.path("components").path("schemas"));
        Map<String, JsonNode> after = fieldsOf(current.path("components").path("schemas"));

        var names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());

        for (String name : names) {
            String location = "schema " + name;

            if (before.get(name) == null) {
                out.add(new Change(Severity.ADDED, location, "新增响应 schema。"));
            } else if (after.get(name) == null) {
                out.add(new Change(Severity.BREAKING, location,
                        "响应 schema 被删除 —— 引用它的响应在文档里失效。"));
            } else {
                compareSchema(location, before.get(name), after.get(name),
                        Direction.RESPONSE, out);
            }
        }
    }

    // ================= 路径与接口 =================

    private static void comparePath(String path, JsonNode base, JsonNode current, List<Change> out) {
        var methods = new TreeSet<String>();
        collectMethods(base, methods);
        collectMethods(current, methods);

        for (String method : methods) {
            JsonNode baseOperation = base.get(method);
            JsonNode currentOperation = current.get(method);
            String location = method.toUpperCase(Locale.ROOT) + " " + path;

            if (baseOperation == null) {
                out.add(new Change(Severity.ADDED, location, "新增接口。"));
            } else if (currentOperation == null) {
                out.add(new Change(Severity.BREAKING, location,
                        "接口被删除 —— 按旧文档生成的客户端会请求一个 404。"));
            } else {
                compareOperation(location, baseOperation, currentOperation, out);
            }
        }
    }

    private static void collectMethods(JsonNode pathItem, Set<String> out) {
        if (pathItem == null || !pathItem.isObject()) return;
        pathItem.fieldNames().forEachRemaining(name -> {
            if (METHODS.contains(name.toLowerCase(Locale.ROOT))) out.add(name.toLowerCase(Locale.ROOT));
        });
    }

    private static void compareOperation(String location, JsonNode base, JsonNode current,
                                         List<Change> out) {
        String baseId = text(base, "operationId");
        String currentId = text(current, "operationId");
        if (baseId != null && currentId != null && !baseId.equals(currentId)) {
            out.add(new Change(Severity.BREAKING, location,
                    "operationId 从 " + baseId + " 改为 " + currentId
                            + " —— 生成的客户端方法名会变。"));
        }

        if (!Objects.equals(base.get("summary"), current.get("summary"))) {
            out.add(new Change(Severity.NON_BREAKING, location, "summary 变了。"));
        }
        if (!Objects.equals(base.get("tags"), current.get("tags"))) {
            out.add(new Change(Severity.NON_BREAKING, location, "tags 变了。"));
        }

        boolean wasDeprecated = base.path("deprecated").asBoolean(false);
        boolean nowDeprecated = current.path("deprecated").asBoolean(false);
        if (!wasDeprecated && nowDeprecated) {
            out.add(new Change(Severity.NON_BREAKING, location, "接口被标记为废弃。"));
        }

        compareSecurity(location, base.get("security"), current.get("security"), out);
        compareParameters(location, base.get("parameters"), current.get("parameters"), out);
        compareRequestBody(location, base.get("requestBody"), current.get("requestBody"), out);
        compareResponses(location, base.get("responses"), current.get("responses"), out);
    }

    // ================= 安全 =================

    /**
     * 认证要求。
     *
     * <p>{@code security} 是一个数组,元素之间是 **OR** 关系 ——
     * 满足任意一个元素就能通过。所以「放宽」是新增一种可选要求,
     * 「收紧」是旧的那些要求一个都不再成立。
     *
     * <p>这里把每个元素规范化成「方案名(排序后的 scope)」的字符串再比较。
     * 规范化是必要的:JSON 对象与数组的顺序不表达语义,
     * 原样比较会把一次纯粹的重排报成认证变更。
     */
    private static void compareSecurity(String location, JsonNode base, JsonNode current,
                                        List<Change> out) {
        List<String> before = securityOf(base);
        List<String> after = securityOf(current);
        if (before.equals(after)) return;

        String authLocation = location + " · 认证";
        if (after.isEmpty()) {
            out.add(new Change(Severity.NON_BREAKING, authLocation,
                    "不再要求认证 —— 旧的匿名调用仍然可用。"));
        } else if (before.isEmpty()) {
            out.add(new Change(Severity.BREAKING, authLocation,
                    "现在要求认证 —— 旧的匿名调用会得到 401。"));
        } else if (before.containsAll(after)) {
            out.add(new Change(Severity.NON_BREAKING, authLocation,
                    "认证要求放宽了 —— 旧客户端用的那种仍然可用。"));
        } else {
            out.add(new Change(Severity.BREAKING, authLocation,
                    "认证要求变了 —— 旧客户端带的凭据可能不再被接受。"));
        }
    }

    private static List<String> securityOf(JsonNode security) {
        var requirements = new ArrayList<String>();
        if (security == null || !security.isArray()) return requirements;

        for (JsonNode requirement : security) {
            if (!requirement.isObject()) continue;

            var schemes = new ArrayList<String>();
            requirement.fieldNames().forEachRemaining(name -> {
                var scopes = new ArrayList<String>();
                requirement.get(name).forEach(scope -> scopes.add(scope.asText()));
                scopes.sort(String::compareTo);
                schemes.add(name + "(" + String.join(",", scopes) + ")");
            });
            schemes.sort(String::compareTo);
            requirements.add(String.join("+", schemes));
        }
        requirements.sort(String::compareTo);
        return requirements;
    }

    // ================= 参数 =================

    private static void compareParameters(String location, JsonNode base, JsonNode current,
                                          List<Change> out) {
        Map<String, JsonNode> before = parametersOf(base);
        Map<String, JsonNode> after = parametersOf(current);

        var keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());

        for (String key : keys) {
            JsonNode baseParameter = before.get(key);
            JsonNode currentParameter = after.get(key);
            String name = key.substring(key.indexOf(' ') + 1);
            String parameterLocation = location + " · 参数 " + name + "(" + key.substring(0, key.indexOf(' ')) + ")";

            if (baseParameter == null) {
                boolean required = currentParameter.path("required").asBoolean(false);
                out.add(new Change(required ? Severity.BREAKING : Severity.NON_BREAKING,
                        parameterLocation,
                        required
                                ? "新增必填参数 —— 旧客户端不带它会得到 422/400。"
                                : "新增可选参数。"));
                continue;
            }

            if (currentParameter == null) {
                out.add(new Change(Severity.NON_BREAKING, parameterLocation,
                        "参数被移除 —— 旧客户端仍然可以带着它,服务端会忽略未知参数。"));
                continue;
            }

            boolean wasRequired = baseParameter.path("required").asBoolean(false);
            boolean nowRequired = currentParameter.path("required").asBoolean(false);
            if (!wasRequired && nowRequired) {
                out.add(new Change(Severity.BREAKING, parameterLocation,
                        "参数由可选变为必填 —— 旧客户端可能不带它。"));
            } else if (wasRequired && !nowRequired) {
                out.add(new Change(Severity.NON_BREAKING, parameterLocation,
                        "参数由必填变为可选。"));
            }

            compareSchema(parameterLocation, baseParameter.get("schema"),
                    currentParameter.get("schema"), Direction.REQUEST, out);
        }
    }

    private static Map<String, JsonNode> parametersOf(JsonNode parameters) {
        var out = new LinkedHashMap<String, JsonNode>();
        if (parameters == null || !parameters.isArray()) return out;

        for (JsonNode parameter : parameters) {
            String name = text(parameter, "name");
            String in = text(parameter, "in");
            if (name == null || in == null) continue;
            out.put(in + " " + name, parameter);
        }
        return out;
    }

    // ================= 请求体与响应 =================

    private static void compareRequestBody(String location, JsonNode base, JsonNode current,
                                           List<Change> out) {
        String bodyLocation = location + " · 请求体";

        if (base == null && current == null) return;

        if (base == null) {
            boolean required = current.path("required").asBoolean(false);
            out.add(new Change(required ? Severity.BREAKING : Severity.NON_BREAKING, bodyLocation,
                    required ? "新增必填请求体。" : "新增可选请求体。"));
            return;
        }

        if (current == null) {
            out.add(new Change(Severity.NON_BREAKING, bodyLocation,
                    "请求体不再声明 —— 旧客户端带着它不会因此被拒。"));
            return;
        }

        if (base.has("$ref") || current.has("$ref")) {
            if (!Objects.equals(base.get("$ref"), current.get("$ref"))) {
                out.add(new Change(Severity.BREAKING, bodyLocation,
                        "$ref 指向变了(diff 不解析组件引用)。"));
            }
            return;
        }

        boolean wasRequired = base.path("required").asBoolean(false);
        boolean nowRequired = current.path("required").asBoolean(false);
        if (!wasRequired && nowRequired) {
            out.add(new Change(Severity.BREAKING, bodyLocation,
                    "请求体由可选变为必填 —— 旧客户端可能不带它。"));
        } else if (wasRequired && !nowRequired) {
            out.add(new Change(Severity.NON_BREAKING, bodyLocation,
                    "请求体由必填变为可选。"));
        }

        compareContent(bodyLocation, base.get("content"), current.get("content"),
                Direction.REQUEST, out);
    }

    private static void compareResponses(String location, JsonNode base, JsonNode current,
                                         List<Change> out) {
        Map<String, JsonNode> before = fieldsOf(base);
        Map<String, JsonNode> after = fieldsOf(current);

        var codes = new TreeSet<>(before.keySet());
        codes.addAll(after.keySet());

        for (String code : codes) {
            JsonNode baseResponse = before.get(code);
            JsonNode currentResponse = after.get(code);
            String responseLocation = location + " · 响应 " + code;

            if (baseResponse == null) {
                out.add(new Change(Severity.NON_BREAKING, responseLocation,
                        "新增响应状态码。"));
                continue;
            }
            if (currentResponse == null) {
                out.add(new Change(Severity.BREAKING, responseLocation,
                        "响应状态码被移除 —— 按旧文档生成的客户端在这个状态下没有可用的模型。"));
                continue;
            }

            if (baseResponse.has("$ref") || currentResponse.has("$ref")) {
                if (!Objects.equals(baseResponse.get("$ref"), currentResponse.get("$ref"))) {
                    out.add(new Change(Severity.BREAKING, responseLocation,
                            "$ref 指向变了(diff 不解析组件引用)。"));
                }
                continue;
            }

            compareContent(responseLocation, baseResponse.get("content"),
                    currentResponse.get("content"), Direction.RESPONSE, out);
            compareHeaders(responseLocation, baseResponse.get("headers"),
                    currentResponse.get("headers"), out);
        }
    }

    /**
     * 响应头。
     *
     * <p>删掉一个响应头是破坏性的:客户端(或它前面的那一层)可能正靠它
     * 做分页、限流或缓存判断。新增一个则不会弄坏谁。
     */
    private static void compareHeaders(String location, JsonNode base, JsonNode current,
                                       List<Change> out) {
        Map<String, JsonNode> before = fieldsOf(base);
        Map<String, JsonNode> after = fieldsOf(current);

        var names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());

        for (String name : names) {
            String headerLocation = location + " · 响应头 " + name;
            JsonNode baseHeader = before.get(name);
            JsonNode currentHeader = after.get(name);

            if (baseHeader == null) {
                out.add(new Change(Severity.NON_BREAKING, headerLocation, "新增响应头。"));
            } else if (currentHeader == null) {
                out.add(new Change(Severity.BREAKING, headerLocation,
                        "响应头被移除 —— 依赖它的客户端或代理会失效。"));
            } else {
                compareSchema(headerLocation, baseHeader.get("schema"),
                        currentHeader.get("schema"), Direction.RESPONSE, out);
            }
        }
    }

    private static void compareContent(String location, JsonNode base, JsonNode current,
                                       Direction direction, List<Change> out) {
        Map<String, JsonNode> before = fieldsOf(base);
        Map<String, JsonNode> after = fieldsOf(current);

        var mediaTypes = new TreeSet<>(before.keySet());
        mediaTypes.addAll(after.keySet());

        for (String mediaType : mediaTypes) {
            String mediaLocation = location + " · " + mediaType;
            JsonNode baseMedia = before.get(mediaType);
            JsonNode currentMedia = after.get(mediaType);

            if (baseMedia == null) {
                out.add(new Change(Severity.NON_BREAKING, mediaLocation, "新增媒体类型。"));
                continue;
            }
            if (currentMedia == null) {
                out.add(new Change(Severity.BREAKING, mediaLocation,
                        "媒体类型被移除 —— 旧客户端发的就是这个 Content-Type。"));
                continue;
            }

            compareSchema(mediaLocation, baseMedia.get("schema"),
                    currentMedia.get("schema"), direction, out);
        }
    }

    // ================= schema =================

    /**
     * JSON Schema 的递归比较。
     *
     * <p>方向贯穿全程:请求看「允许的输入有没有收窄」,响应看
     * 「可能的输出有没有放宽」(见类注释)。
     */
    private static void compareSchema(String location, JsonNode base, JsonNode current,
                                      Direction direction, List<Change> out) {
        if (base == null && current == null) return;

        if (base == null) {
            out.add(new Change(Severity.NON_BREAKING, location + " · schema",
                    "补上了 schema 声明。"));
            return;
        }
        if (current == null) {
            out.add(new Change(direction == Direction.RESPONSE
                            ? Severity.BREAKING : Severity.NON_BREAKING,
                    location + " · schema",
                    direction == Direction.RESPONSE
                            ? "响应不再声明 schema —— 生成的客户端会失去这个类型。"
                            : "schema 声明被移除。"));
            return;
        }

        if (!base.isObject() || !current.isObject()) {
            if (!base.equals(current)) {
                out.add(new Change(Severity.BREAKING, location + " · schema",
                        "schema 的形状变了。"));
            }
            return;
        }

        if (base.has("$ref") || current.has("$ref")) {
            if (!Objects.equals(base.get("$ref"), current.get("$ref"))) {
                out.add(new Change(Severity.BREAKING, location + " · schema",
                        "$ref 指向变了(diff 不解析组件引用)。"));
            }
            return;
        }

        compareTypes(location, base.get("type"), current.get("type"), direction, out);
        compareEnums(location, base.get("enum"), current.get("enum"), direction, out);
        compareFormat(location, base.get("format"), current.get("format"), out);
        comparePattern(location, base.get("pattern"), current.get("pattern"), direction, out);

        for (String key : LOWER_BOUNDS) {
            compareBound(location, key, base.get(key), current.get(key), true, direction, out);
        }
        for (String key : UPPER_BOUNDS) {
            compareBound(location, key, base.get(key), current.get(key), false, direction, out);
        }

        compareAdditionalProperties(location, base.get("additionalProperties"),
                current.get("additionalProperties"), direction, out);
        compareItems(location, base.get("items"), current.get("items"), direction, out);
        compareProperties(location, base, current, direction, out);

        compareUnknownKeys(location, base, current, out);
    }

    private static void compareTypes(String location, JsonNode base, JsonNode current,
                                     Direction direction, List<Change> out) {
        Set<String> before = typesOf(base);
        Set<String> after = typesOf(current);
        if (before.equals(after)) return;

        // 一边没写类型 —— 那是「信息变多/变少」,不是取值范围本身变了
        if (before.isEmpty() || after.isEmpty()) {
            out.add(new Change(Severity.NON_BREAKING, location + " · 类型",
                    "类型声明 " + describe(before) + " → " + describe(after) + "。"));
            return;
        }

        boolean breaking = direction == Direction.REQUEST
                ? !after.containsAll(before)   // 请求:以前能接受的值现在可能不接受了
                : !before.containsAll(after);  // 响应:以前不会出现的值现在可能出现
        out.add(new Change(breaking ? Severity.BREAKING : Severity.NON_BREAKING,
                location + " · 类型",
                "类型 " + describe(before) + " → " + describe(after) + "。"));
    }

    /**
     * ★ 保留文档里的书写顺序(`integer | null`),不是排序后的顺序。
     *
     * <p>集合语义与顺序无关,但**消息**是给人读的:把生成器写的
     * {@code [integer, null]} 显示成 {@code null | integer} 只会让人
     * 多看一眼。文档本身的顺序是稳定的,所以输出仍然可复现。
     */
    private static Set<String> typesOf(JsonNode type) {
        var out = new LinkedHashSet<String>();
        if (type == null || type.isNull()) return out;
        if (type.isTextual()) {
            out.add(type.asText());
        } else if (type.isArray()) {
            type.forEach(value -> out.add(value.asText()));
        }
        return out;
    }

    private static void compareEnums(String location, JsonNode base, JsonNode current,
                                     Direction direction, List<Change> out) {
        boolean hasBefore = base != null && base.isArray();
        boolean hasAfter = current != null && current.isArray();
        if (!hasBefore && !hasAfter) return;

        Set<String> before = enumValuesOf(base);
        Set<String> after = enumValuesOf(current);
        if (before.equals(after)) return;

        String enumLocation = location + " · 枚举";

        if (!hasBefore) {
            // 新加了枚举约束:请求的取值范围被收窄,响应的取值范围被限定
            out.add(new Change(direction == Direction.REQUEST
                            ? Severity.BREAKING : Severity.NON_BREAKING,
                    enumLocation, "新增枚举约束 " + describe(after) + "。"));
            return;
        }
        if (!hasAfter) {
            out.add(new Change(direction == Direction.REQUEST
                            ? Severity.NON_BREAKING : Severity.BREAKING,
                    enumLocation, "枚举约束被移除 —— 取值范围不再受限。"));
            return;
        }

        boolean breaking = direction == Direction.REQUEST
                ? !after.containsAll(before)
                : !before.containsAll(after);
        out.add(new Change(breaking ? Severity.BREAKING : Severity.NON_BREAKING,
                enumLocation, "枚举 " + describe(before) + " → " + describe(after) + "。"));
    }

    private static Set<String> enumValuesOf(JsonNode enumNode) {
        var out = new LinkedHashSet<String>();
        if (enumNode == null || !enumNode.isArray()) return out;
        // 保留 JSON 字面量做身份:字符串 "1" 与数字 1 是**不同的枚举值**。
        // 统一成 asText 会把这两个悄悄合并,于是它们之间的变化被漏掉
        enumNode.forEach(value -> out.add(value.toString()));
        return out;
    }

    private static void compareFormat(String location, JsonNode base, JsonNode current,
                                      List<Change> out) {
        if (Objects.equals(base, current)) return;
        if (base == null || current == null) {
            out.add(new Change(Severity.NON_BREAKING, location + " · format",
                    "format 声明变了。"));
            return;
        }
        out.add(new Change(Severity.BREAKING, location + " · format",
                "format 从 " + base.asText() + " 改为 " + current.asText()
                        + " —— 同一个类型下的语义不同了。"));
    }

    private static void comparePattern(String location, JsonNode base, JsonNode current,
                                       Direction direction, List<Change> out) {
        if (Objects.equals(base, current)) return;
        if (base == null && current == null) return;

        boolean breaking;
        if (base == null) {
            // 新增 pattern:请求收窄(破坏),响应收窄(不破坏)
            breaking = direction == Direction.REQUEST;
        } else if (current == null) {
            // 移除 pattern:请求放宽(不破坏),响应放宽(破坏)
            breaking = direction == Direction.RESPONSE;
        } else {
            // 两个不同的正则之间无法证明包含关系,保守处理
            breaking = true;
        }

        out.add(new Change(breaking ? Severity.BREAKING : Severity.NON_BREAKING,
                location + " · pattern",
                "pattern " + (base == null ? "(无)" : base.asText())
                        + " → " + (current == null ? "(无)" : current.asText()) + "。"));
    }

    /**
     * 一个数值边界。
     *
     * <p>{@code lower} 为真表示「值越大越紧」({@code minLength} 等),
     * 否则「值越小越紧」({@code maxLength} 等)。
     * 请求里收紧是破坏性,响应里收紧不是 —— 见类注释。
     */
    private static void compareBound(String location, String key, JsonNode base, JsonNode current,
                                     boolean lower, Direction direction, List<Change> out) {
        if (base == null && current == null) return;

        String boundLocation = location + " · " + key;

        if (base == null || current == null) {
            boolean added = base == null;
            boolean breaking = direction == Direction.REQUEST ? added : !added;
            out.add(new Change(breaking ? Severity.BREAKING : Severity.NON_BREAKING,
                    boundLocation,
                    (added ? "新增 " : "移除 ") + key + " "
                            + (added ? current.asText() : base.asText()) + "。"));
            return;
        }

        if (base.equals(current)) return;

        BigDecimal before = decimalOf(base);
        BigDecimal after = decimalOf(current);
        if (before == null || after == null) {
            out.add(new Change(Severity.BREAKING, boundLocation,
                    key + " " + base.asText() + " → " + current.asText()
                            + "(不是数字,diff 无法判断松紧,保守地报破坏性)。"));
            return;
        }

        boolean tightened = lower
                ? after.compareTo(before) > 0
                : after.compareTo(before) < 0;
        boolean breaking = direction == Direction.REQUEST ? tightened : !tightened;

        out.add(new Change(breaking ? Severity.BREAKING : Severity.NON_BREAKING,
                boundLocation, key + " " + base.asText() + " → " + current.asText() + "。"));
    }

    private static BigDecimal decimalOf(JsonNode node) {
        if (node == null || !node.isNumber()) return null;
        return node.decimalValue();
    }

    private static void compareAdditionalProperties(String location, JsonNode base, JsonNode current,
                                                    Direction direction, List<Change> out) {
        // JSON Schema 的默认值是 true —— 不写不等于不允许额外字段
        boolean before = base == null || base.asBoolean(true);
        boolean after = current == null || current.asBoolean(true);
        if (before == after) return;

        boolean breaking = direction == Direction.REQUEST
                ? before && !after   // 以前接受额外字段,现在拒绝
                : !before && after;  // 以前不会出现,现在可能出现
        out.add(new Change(breaking ? Severity.BREAKING : Severity.NON_BREAKING,
                location + " · additionalProperties",
                "additionalProperties " + before + " → " + after + "。"));
    }

    private static void compareItems(String location, JsonNode base, JsonNode current,
                                     Direction direction, List<Change> out) {
        if (base == null && current == null) return;
        compareSchema(location + " · items", base, current, direction, out);
    }

    /**
     * 对象字段。
     *
     * <p>{@code required} 不单独比较,而是在每个字段上判 ——
     * 否则「新增一个必填字段」会在两个地方各报一次。
     */
    private static void compareProperties(String location, JsonNode base, JsonNode current,
                                          Direction direction, List<Change> out) {
        Map<String, JsonNode> before = fieldsOf(base.get("properties"));
        Map<String, JsonNode> after = fieldsOf(current.get("properties"));

        Set<String> baseRequired = stringsOf(base.get("required"));
        Set<String> currentRequired = stringsOf(current.get("required"));

        var names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());

        for (String name : names) {
            JsonNode baseProperty = before.get(name);
            JsonNode currentProperty = after.get(name);
            String propertyLocation = location + " · 字段 " + name;

            if (baseProperty == null) {
                boolean required = currentRequired.contains(name);
                if (direction == Direction.REQUEST) {
                    out.add(new Change(required ? Severity.BREAKING : Severity.NON_BREAKING,
                            propertyLocation,
                            required
                                    ? "新增必填字段 —— 旧客户端不带它会得到 422。"
                                    : "新增可选字段。"));
                } else {
                    out.add(new Change(Severity.NON_BREAKING, propertyLocation, "响应新增字段。"));
                }
                continue;
            }

            if (currentProperty == null) {
                if (direction == Direction.REQUEST) {
                    out.add(new Change(Severity.NON_BREAKING, propertyLocation,
                            "字段被移除 —— 旧客户端带着它服务端会忽略。"));
                } else {
                    out.add(new Change(Severity.BREAKING, propertyLocation,
                            "响应不再包含这个字段 —— 客户端按旧文档会直接读它。"));
                }
                continue;
            }

            boolean wasRequired = baseRequired.contains(name);
            boolean nowRequired = currentRequired.contains(name);
            if (!wasRequired && nowRequired) {
                out.add(new Change(direction == Direction.REQUEST
                                ? Severity.BREAKING : Severity.NON_BREAKING,
                        propertyLocation,
                        direction == Direction.REQUEST
                                ? "字段由可选变为必填 —— 旧客户端可能不带它。"
                                : "响应字段变为总是存在。"));
            } else if (wasRequired && !nowRequired) {
                out.add(new Change(direction == Direction.REQUEST
                                ? Severity.NON_BREAKING : Severity.BREAKING,
                        propertyLocation,
                        direction == Direction.REQUEST
                                ? "字段由必填变为可选。"
                                : "响应字段不再保证存在 —— 客户端按旧文档会直接读它。"));
            }

            compareSchema(propertyLocation, baseProperty, currentProperty, direction, out);
        }
    }

    /**
     * 本类不认识的关键字。
     *
     * <p>明确忽略纯文档字段,其余变化保守地报破坏性 ——
     * 漏报一次真正的契约变更,比让人多看一眼误报糟得多。
     */
    private static void compareUnknownKeys(String location, JsonNode base, JsonNode current,
                                           List<Change> out) {
        var keys = new TreeSet<String>();
        base.fieldNames().forEachRemaining(keys::add);
        current.fieldNames().forEachRemaining(keys::add);

        for (String key : keys) {
            if (HANDLED.contains(key) || IGNORED.contains(key)) continue;
            if (Objects.equals(base.get(key), current.get(key))) continue;

            out.add(new Change(Severity.BREAKING, location + " · " + key,
                    "关键字 " + key + " 变了 —— diff 不认识它,保守地当作破坏性。"));
        }
    }

    // ================= 工具 =================

    private static JsonNode pathsOf(JsonNode document, String role) {
        JsonNode paths = document == null ? null : document.get("paths");
        if (paths == null || !paths.isObject()) {
            throw new ConsoleException(role + "文档里没有 paths 对象 —— 这不是一份 OpenAPI 文档。\n"
                    + "  生成一份:jaravel openapi:generate");
        }
        return paths;
    }

    private static Map<String, JsonNode> fieldsOf(JsonNode object) {
        var out = new LinkedHashMap<String, JsonNode>();
        if (object == null || !object.isObject()) return out;
        object.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }

    private static Set<String> stringsOf(JsonNode array) {
        var out = new TreeSet<String>();
        if (array != null && array.isArray()) {
            array.forEach(value -> out.add(value.asText()));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static String describe(Set<String> values) {
        if (values.isEmpty()) return "(未声明)";
        return values.stream()
                .map(OpenApiDiff::displayValue)
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    /** 枚举值存的是 JSON 字面量;显示时去掉字符串外面那对引号 */
    private static String displayValue(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
