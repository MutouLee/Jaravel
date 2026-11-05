package io.jaravel.spring.commands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.jaravel.routing.Route;
import io.jaravel.routing.RouteCollection;

/**
 * 从**编译后的路由表**生成 OpenAPI 文档(docs/12 §5)。
 *
 * <h2>★ 数据来自请求真正走的那张表</h2>
 * <p>与 {@code route:list} 同一个来源,也是同一条理由:重新解析一遍路由定义
 * 的那份会说谎(分组前缀、资源路由的展开、PATCH 的隐式注册)。
 * 一份与实际路由不一致的 OpenAPI 比没有文档更糟 ——
 * 前端照它生成客户端,然后在某个 404 上卡住。
 *
 * <h2>这一版能填上 docs/12 §5 那张表的哪几行</h2>
 *
 * <table>
 *   <tr><th>字段</th><th>来源</th><th>这一版</th></tr>
 *   <tr><td>路径与方法</td><td>路由表</td><td>✅</td></tr>
 *   <tr><td>路径参数类型</td><td>控制器方法签名 + 路由约束</td><td>✅(有限,见下)</td></tr>
 *   <tr><td>安全要求</td><td>{@code auth:*} 中间件</td><td>✅</td></tr>
 *   <tr><td>operationId / tags</td><td>控制器#方法</td><td>✅</td></tr>
 *   <tr><td>请求体 schema</td><td>FormRequest 的 rules()</td><td>✅</td></tr>
 *   <tr><td>请求体校验约束</td><td>规则 → maxLength / maximum / enum / pattern</td><td>✅</td></tr>
 *   <tr><td>响应 schema</td><td>JsonResource 的 toArray</td><td>❌(要 APT 做 AST 分析)</td></tr>
 *   <tr><td>描述与示例</td><td>javadoc + {@code @ApiDoc}</td><td>❌(javadoc 要 APT)</td></tr>
 * </table>
 *
 * <p>**没填上的行不写进文档,也不编**:一个带着假 schema 的 OpenAPI
 * 会让前端生成出字段名全错的模型类,而那种错要等到联调才发现。
 * 文档里缺一个 requestBody 至少是**明显**的缺失。
 */
final class OpenApiGenerator {

    private static final String OPENAPI_VERSION = "3.1.0";
    private static final String BEARER_SCHEME = "bearerAuth";

    private final String title;
    private final String version;
    private final String serverUrl;

    OpenApiGenerator(String title, String version, String serverUrl) {
        this.title = title;
        this.version = version;
        this.serverUrl = serverUrl;
    }

    Map<String, Object> generate(RouteCollection routes) {
        var document = new LinkedHashMap<String, Object>();
        document.put("openapi", OPENAPI_VERSION);
        document.put("info", Map.of("title", title, "version", version));
        if (serverUrl != null && !serverUrl.isBlank()) {
            document.put("servers", List.of(Map.of("url", serverUrl)));
        }

        // 路径按字典序:同一份路由表两次生成的文档必须逐字节一致,
        // 否则 openapi:diff 会把顺序变化报成 API 变更
        var paths = new TreeMap<String, Map<String, Object>>();
        boolean anySecured = false;

        for (Route route : routes.all()) {
            Map<String, Object> operation = operationOf(route);
            if (operation.containsKey("security")) anySecured = true;

            paths.computeIfAbsent(route.uri(), uri -> new LinkedHashMap<>())
                    .put(route.method().toLowerCase(java.util.Locale.ROOT), operation);
        }
        document.put("paths", paths);

        if (anySecured) {
            document.put("components", Map.of("securitySchemes", Map.of(
                    BEARER_SCHEME, Map.of("type", "http", "scheme", "bearer"))));
        }
        return document;
    }

    private Map<String, Object> operationOf(Route route) {
        var operation = new LinkedHashMap<String, Object>();

        operation.put("operationId", route.controller().getSimpleName() + "." + route.action());
        operation.put("tags", List.of(tagOf(route)));
        route.routeName().ifPresent(name -> operation.put("summary", name));

        List<Map<String, Object>> parameters = parametersOf(route);
        if (!parameters.isEmpty()) operation.put("parameters", parameters);

        requestBodyOf(route).ifPresent(body -> operation.put("requestBody", body));

        boolean secured = requiresAuth(route);
        if (secured) {
            operation.put("security", List.of(Map.of(BEARER_SCHEME, List.of())));
        }

        var responses = new LinkedHashMap<String, Object>();
        // 只写**知道**的:成功一条,受保护的加 401。
        // 404 / 422 得等 FormRequest 与返回类型的 schema 做出来 ——
        // 现在写上去只是猜
        responses.put("200", Map.of("description", "成功"));
        if (secured) responses.put("401", Map.of("description", "未认证"));
        operation.put("responses", responses);

        return operation;
    }

    /**
     * 标签 = 控制器名去掉 {@code Controller} 后缀。
     *
     * <p>{@code PostController#index} → 标签 {@code Post}。
     * 这是 Swagger UI 的分组依据,而按控制器分组恰好与「同一组接口」重合。
     */
    private String tagOf(Route route) {
        String name = route.controller().getSimpleName();
        return name.endsWith("Controller")
                ? name.substring(0, name.length() - "Controller".length())
                : name;
    }

    /**
     * 路径参数。
     *
     * <h2>★ 类型是怎么来的,以及什么时候放弃</h2>
     * <p>两个来源:
     * <ul>
     *   <li><b>路由约束</b>({@code whereNumber("id")} → 正则 {@code [0-9]+})——
     *       它直接给出 {@code type: integer} 与 {@code pattern};</li>
     *   <li><b>控制器方法签名</b> —— 但只在**能对上**的时候用。</li>
     * </ul>
     *
     * <p>「能对上」的判断很保守:先滤掉 {@code Request} 类型的参数,
     * 剩下的个数与路径参数个数相等时才按位置配对。
     *
     * <p>⚠ 为什么这么保守:真正的参数绑定规则在 {@code ArgumentBinder} 与
     * {@code SubstituteBindings} 里(模型绑定、FormRequest 注入、
     * 可选参数…)。在这里把那套规则再实现一遍,就是第二份会漂的实现 ——
     * 而漂掉的后果是**文档上的类型与实际接受的类型不同**,
     * 那比没有类型更糟。对不上就退回 {@code string}:
     * 路径参数在 HTTP 上本来就是字符串,这个退路不说谎。
     */
    private List<Map<String, Object>> parametersOf(Route route) {
        List<String> names = route.paramNames();
        if (names.isEmpty()) return List.of();

        List<Class<?>> candidates = route.parameterTypes().stream()
                .filter(type -> !io.jaravel.http.Request.class.isAssignableFrom(type))
                .toList();
        boolean typesUsable = candidates.size() == names.size();

        var out = new ArrayList<Map<String, Object>>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            var parameter = new LinkedHashMap<String, Object>();
            parameter.put("name", name);
            parameter.put("in", "path");
            parameter.put("required", true);
            parameter.put("schema", schemaFor(
                    typesUsable ? candidates.get(i) : null, route.constraints().get(name)));
            out.add(parameter);
        }
        return out;
    }

    /**
     * 一个路径参数的 schema。
     *
     * <p>约束优先于 Java 类型:{@code whereNumber} 是作者**明确表达**的意图,
     * 而 Java 类型可能因为模型绑定而是一个实体类。
     */
    private Map<String, Object> schemaFor(Class<?> type, String constraint) {
        var schema = new LinkedHashMap<String, Object>();

        if (constraint != null && constraint.equals("[0-9]+")) {
            schema.put("type", "integer");
            return schema;
        }
        schema.put("type", openApiType(type));
        if (constraint != null) {
            // 正则原样给出去 —— OpenAPI 的 pattern 就是 ECMA 正则,
            // 而路由约束也是正则,两边的常见写法是兼容的
            schema.put("pattern", constraint);
        }
        return schema;
    }

    private String openApiType(Class<?> type) {
        if (type == null) return "string";
        if (type == Long.class || type == long.class
                || type == Integer.class || type == int.class) {
            return "integer";
        }
        if (type == Double.class || type == double.class
                || type == Float.class || type == float.class
                || type == java.math.BigDecimal.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) return "boolean";
        // 其它一律 string:路径参数在 HTTP 上就是字符串,
        // 而猜一个 object schema 出来会让客户端生成器产出一个空类
        return "string";
    }

    /**
     * 请求体 —— 从控制器参数里那个 {@code FormRequest} 的 {@code rules()} 来。
     *
     * <h2>★ 这一行不需要 APT</h2>
     * <p>{@code rules()} 返回 {@code Map<String, String>},而这条命令跑在
     * 应用进程里(在线命令)—— 直接实例化那个 FormRequest 调一下就有了。
     * 用的是与 {@code ArgumentBinder} 相同的约定:**无参构造**
     * (那是 FormRequest 的既有契约,框架每次请求都这么建它)。
     *
     * <p>⚠ 不调 {@code bind(request)}:那会跑鉴权与校验,而这里没有请求。
     * 只要 {@code rules()}。
     *
     * <h2>实例化失败不让整份文档失败</h2>
     * <p>一个 {@code rules()} 依赖实例状态的 FormRequest(比如读了路由参数)
     * 在这里会抛异常。那时**跳过这一个请求体**并继续 ——
     * 因为「十个接口里有一个的请求体缺失」远好过「整份文档生成不出来」。
     */
    private java.util.Optional<Map<String, Object>> requestBodyOf(Route route) {
        for (Class<?> type : route.parameterTypes()) {
            if (!io.jaravel.validation.FormRequest.class.isAssignableFrom(type)) continue;

            Map<String, String> rules;
            try {
                var constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                var form = (io.jaravel.validation.FormRequest) constructor.newInstance();
                rules = form.rules();
            } catch (ReflectiveOperationException | RuntimeException e) {
                skipped.add(route.method() + " " + route.uri()
                        + "(" + type.getSimpleName() + ":" + e.getClass().getSimpleName() + ")");
                continue;
            }
            if (rules == null || rules.isEmpty()) continue;

            return java.util.Optional.of(Map.of(
                    "required", true,
                    "content", Map.of("application/json",
                            Map.of("schema", RuleSchema.of(rules)))));
        }
        return java.util.Optional.empty();
    }

    /** 请求体没能生成出来的那些接口 —— 命令会把它们报出来,而不是静默少几个字段 */
    private final List<String> skipped = new ArrayList<>();

    List<String> skippedRequestBodies() {
        return List.copyOf(skipped);
    }

    /**
     * 这条路由要认证吗 —— 看中间件名。
     *
     * <p>{@code auth} 与 {@code auth:api} 都算。
     *
     * <p>⚠ guard 名(api / web / sanctum)到 OpenAPI 安全方案的映射
     * 这一版是**简化的**:一律声明为 HTTP bearer。
     * 对 token / JWT guard 是对的,对 session guard 不对
     * (那应该是 cookie)。记在这里,别让读文档的人以为它精确。
     */
    private boolean requiresAuth(Route route) {
        for (String middleware : route.middlewareNames()) {
            if (middleware.equals("auth") || middleware.startsWith("auth:")) return true;
        }
        return false;
    }
}
