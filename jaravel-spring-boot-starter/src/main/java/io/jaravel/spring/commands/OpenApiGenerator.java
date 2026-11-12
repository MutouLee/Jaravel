package io.jaravel.spring.commands;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import io.jaravel.api.JsonResource;
import io.jaravel.api.openapi.ApiDoc;
import io.jaravel.api.openapi.ApiResponse;
import io.jaravel.api.openapi.ResourceSchema;
import io.jaravel.api.openapi.ResourceSchemaIndex;
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
 *   <tr><td>响应 schema</td><td>APT 从 JsonResource.toArray 提取 + {@code @ApiResponse.resource}</td><td>✅(record 返回类型仍未做)</td></tr>
 *   <tr><td>描述与示例</td><td>{@code @ApiDoc} + javadoc</td><td>⚠ {@code @ApiDoc} ✅;javadoc / 示例要 APT</td></tr>
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

    /** 这一次生成用到的资源 schema(component 名 → schema) */
    private final Map<String, Object> usedSchemas = new TreeMap<>();

    /** 资源类二进制名 → component 名;同一个资源被多个接口引用时只写一份 */
    private final Map<String, String> componentNames = new LinkedHashMap<>();

    private final Set<String> takenComponentNames = new HashSet<>();

    /** 没能生成出 schema 的接口 —— 命令会报出来,而不是静默少一段 */
    private final List<String> schemaNotes = new ArrayList<>();

    private ResourceSchemaIndex.Index schemaIndex = new ResourceSchemaIndex.Index(
            Map.of(), Map.of());

    OpenApiGenerator(String title, String version, String serverUrl) {
        this.title = title;
        this.version = version;
        this.serverUrl = serverUrl;
    }

    Map<String, Object> generate(RouteCollection routes) {
        usedSchemas.clear();
        componentNames.clear();
        takenComponentNames.clear();
        schemaNotes.clear();
        skipped.clear();
        schemaIndex = ResourceSchemaIndex.load();

        var document = new LinkedHashMap<String, Object>();
        document.put("openapi", OPENAPI_VERSION);
        document.put("info", Map.of("title", title, "version", version));
        if (serverUrl != null && !serverUrl.isBlank()) {
            document.put("servers", List.of(Map.of("url", serverUrl)));
        }

        // 路径按字典序:同一份路由表两次生成的文档必须逐字节一致,
        // 否则 openapi:diff 会把顺序变化报成 API 变更。
        // 路由也按同样顺序遍历:component 名的分配依赖「谁先用到」,
        // 不排序的话同名的两个资源可能在不同次生成里拿到不同的 component 名
        var paths = new TreeMap<String, Map<String, Object>>();
        boolean anySecured = false;

        var ordered = new ArrayList<>(routes.all());
        ordered.sort(Comparator.comparing(Route::uri).thenComparing(Route::method));

        for (Route route : ordered) {
            Map<String, Object> operation = operationOf(route);
            if (operation.containsKey("security")) anySecured = true;

            paths.computeIfAbsent(route.uri(), uri -> new LinkedHashMap<>())
                    .put(route.method().toLowerCase(Locale.ROOT), operation);
        }
        document.put("paths", paths);

        var components = new LinkedHashMap<String, Object>();
        if (anySecured) {
            components.put("securitySchemes", Map.of(
                    BEARER_SCHEME, Map.of("type", "http", "scheme", "bearer")));
        }
        if (!usedSchemas.isEmpty()) {
            components.put("schemas", usedSchemas);
        }
        if (!components.isEmpty()) {
            document.put("components", components);
        }
        return document;
    }

    private Map<String, Object> operationOf(Route route) {
        var operation = new LinkedHashMap<String, Object>();
        Method action = actionMethodOf(route).orElse(null);
        ApiDoc doc = action == null ? null : action.getAnnotation(ApiDoc.class);

        operation.put("operationId", route.controller().getSimpleName() + "." + route.action());

        // 注解优先,路由/控制器名兜底 —— 没写注解的接口与这一刀之前完全一样
        operation.put("tags", doc != null && doc.tags().length > 0
                ? List.of(doc.tags())
                : List.of(tagOf(route)));

        String summary = doc != null && !doc.summary().isBlank()
                ? doc.summary()
                : route.routeName().orElse(null);
        if (summary != null) operation.put("summary", summary);

        if (doc != null && !doc.description().isBlank()) {
            operation.put("description", doc.description());
        }

        List<Map<String, Object>> parameters = parametersOf(route);
        if (!parameters.isEmpty()) operation.put("parameters", parameters);

        requestBodyOf(route).ifPresent(body -> operation.put("requestBody", body));

        boolean secured = requiresAuth(route);
        if (secured) {
            operation.put("security", List.of(Map.of(BEARER_SCHEME, List.of())));
        }

        operation.put("responses", responsesOf(doc, secured, action, route));

        return operation;
    }

    private static Optional<Method> actionMethodOf(Route route) {
        Class<?> controller = route.controller();
        Class<?>[] types = route.parameterTypes().toArray(Class<?>[]::new);

        try {
            return Optional.of(controller.getDeclaredMethod(route.action(), types));
        } catch (NoSuchMethodException ignored) {
            // 继承来的方法不在 declared 里;参数类型也可能没被内省到 —— 再找一次
        }

        for (Method method : controller.getMethods()) {
            if (method.getName().equals(route.action())
                    && Arrays.equals(method.getParameterTypes(), types)) {
                return Optional.of(method);
            }
        }

        // 参数类型对不上(比如 lambda 路由没有内省出参数)时,
        // 只有唯一同名方法才敢用 —— 多个重载里猜一个会把注解安错方法
        var byName = Arrays.stream(controller.getMethods())
                .filter(method -> method.getName().equals(route.action()))
                .toList();
        return byName.size() == 1 ? Optional.of(byName.get(0)) : Optional.empty();
    }

    /**
     * 响应状态码与响应体 schema。
     *
     * <p>没写 {@code @ApiDoc.responses} 时与之前一样:200 + (受保护时)401。
     * 写了就**以作者为准** —— 创建接口返回 201,自动补一个 200 是错的。
     * 401 仍然自动补,因为它由中间件保证;作者显式写了 401 时用作者的说明。
     *
     * <h2>响应 schema 挂在哪一个状态码上</h2>
     * <p>{@code @ApiResponse.resource} 显式指定的,挂在那个状态码上;
     * 没指定的,自动挂到**第一个 2xx(204 除外)** —— 那通常是实体返回的那条。
     * 返回类型不是 {@code JsonResource} 的接口没有可提取的形状,不挂。
     */
    private Map<String, Object> responsesOf(ApiDoc doc, boolean secured,
                                            Method action, Route route) {
        var responses = new LinkedHashMap<String, Object>();
        String location = route.method().toUpperCase(Locale.ROOT) + " " + route.uri();

        if (doc != null && doc.responses().length > 0) {
            String autoCode = null;
            for (ApiResponse declared : doc.responses()) {
                if (declared.code() >= 200 && declared.code() < 300
                        && declared.code() != 204) {
                    autoCode = String.valueOf(declared.code());
                    break;
                }
            }

            // 延迟计算:每条 2xx 都显式指了 resource 时,不该为返回类型
            // 再查一次索引 —— 那只会多出一条没人用得上的 note
            Map<String, Object> autoSchema = null;
            boolean autoComputed = false;

            for (ApiResponse declared : doc.responses()) {
                String code = String.valueOf(declared.code());
                String description = declared.description().isBlank()
                        ? defaultDescriptionOf(declared.code())
                        : declared.description();

                var response = new LinkedHashMap<String, Object>();
                response.put("description", description);

                Map<String, Object> schema;
                if (declared.resource() != Void.class) {
                    schema = responseBodySchemaOf(declared.resource(), location + " " + code);
                } else if (code.equals(autoCode)) {
                    if (!autoComputed) {
                        autoSchema = autoSchemaOf(action, location + " " + autoCode);
                        autoComputed = true;
                    }
                    schema = autoSchema;
                } else {
                    schema = null;
                }
                if (schema != null) {
                    response.put("content", Map.of("application/json",
                            Map.of("schema", schema)));
                }

                // 同一个 code 写两次时以第一次为准,不静默覆盖
                responses.putIfAbsent(code, response);
            }
        } else {
            var response = new LinkedHashMap<String, Object>();
            response.put("description", "成功");

            Map<String, Object> autoSchema = autoSchemaOf(action, location + " 200");
            if (autoSchema != null) {
                response.put("content", Map.of("application/json",
                        Map.of("schema", autoSchema)));
            }
            responses.put("200", response);
        }

        if (secured) {
            responses.putIfAbsent("401", Map.of("description", "未认证"));
        }
        return responses;
    }

    /**
     * 返回类型本身就是一个资源时,自动挂上它的 schema。
     *
     * <p>只认**具体**的 {@code JsonResource} 子类:返回类型写成
     * 抽象的 {@code JsonResource} / 基类时,索引里没有对应条目,
     * 硬挂只会得到一条「找不到 schema」的噪音。
     */
    private Map<String, Object> autoSchemaOf(Method action, String location) {
        if (action == null) return null;

        Class<?> type = action.getReturnType();
        if (!JsonResource.class.isAssignableFrom(type)
                || type == JsonResource.class
                || Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        return responseBodySchemaOf(type, location);
    }

    /**
     * 响应体的 schema:资源本体 + 根位置的 {@code data} 包裹。
     *
     * <h2>★ 为什么必须把包裹写进 schema</h2>
     * <p>{@code toArray} 的形状**不是**响应体的形状:序列化器在根位置
     * 调的是 {@code toRootBody},默认外面还有一层
     * {@code {"data": ...}}(docs/12 §1.3)。直接拿 {@code toArray}
     * 的字段当响应 schema,前端会按 {@code {"id":...}} 解析,
     * 而实际收到的是 {@code {"data":{"id":...}}} —— 每个字段都读不到。
     *
     * <p>包裹键从 {@link JsonResource#wrapKey()} 读:应用可能在启动时
     * 调了 {@code withoutWrapping()} 或 {@code wrapWith("result")}。
     * 关掉包裹时响应体就是资源本体。
     *
     * <p>⚠ {@code with(request)} 附加的根字段(比如 {@code meta})
     * 这一版**没有**提取:那需要再分析一个方法体里的 Map 表达式。
     * 文档里不写它们 —— 缺字段比写错字段好;而它们通常不参与客户端逻辑。
     */
    private Map<String, Object> responseBodySchemaOf(Class<?> resource, String location) {
        Map<String, Object> ref = schemaRefOf(resource, location);
        if (ref == null) return null;

        String wrapKey = JsonResource.wrapKey();
        if (wrapKey == null) return ref;

        var properties = new LinkedHashMap<String, Object>();
        properties.put(wrapKey, ref);

        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("type", "object");
        envelope.put("properties", properties);
        envelope.put("required", List.of(wrapKey));
        return envelope;
    }

    /**
     * 一个资源类 → {@code $ref},并把它的 schema 登记进 components。
     *
     * <p>查的是 APT 在编译期写下的索引,不是反射调用 {@code toArray} ——
     * 后者需要一个真实的模型实例,而且会执行用户的代码。
     * 索引里没有时**不编**:记一条 note,由命令报出来。
     */
    private Map<String, Object> schemaRefOf(Class<?> resource, String location) {
        if (!JsonResource.class.isAssignableFrom(resource)) {
            schemaNotes.add(location + " → " + resource.getSimpleName()
                    + ":响应 schema 只支持 JsonResource 子类");
            return null;
        }

        String className = resource.getName();
        String existing = componentNames.get(className);
        if (existing != null) return schemaRef(existing);

        Optional<ResourceSchema> schema = schemaIndex.schemaOf(resource);
        if (schema.isEmpty()) {
            String reason = schemaIndex.failureOf(resource).orElse(
                    "没有 APT 生成的 schema —— 把 jaravel-api-processor 加进 annotationProcessorPaths");
            schemaNotes.add(location + " → " + resource.getSimpleName() + ":" + reason);
            return null;
        }

        String component = componentNameFor(resource);
        componentNames.put(className, component);
        usedSchemas.put(component, schema.get().toOpenApiSchema());
        return schemaRef(component);
    }

    private static Map<String, Object> schemaRef(String component) {
        return Map.of("$ref", "#/components/schemas/" + component);
    }

    /**
     * component 名:优先简单类名;两个不同包的同名资源撞车时用限定名。
     *
     * <p>这条规则必须是确定性的:路由按 uri 排序遍历,于是
     * 「谁先拿到简单名」在同一份路由表上永远一样。
     */
    private String componentNameFor(Class<?> resource) {
        String simple = resource.getSimpleName();
        if (!simple.isEmpty() && takenComponentNames.add(simple)) return simple;

        String qualified = resource.getName().replace('$', '.');
        if (takenComponentNames.add(qualified)) return qualified;

        int suffix = 2;
        while (!takenComponentNames.add(qualified + suffix)) suffix++;
        return qualified + suffix;
    }

    /** 响应 schema 没能生成出来的接口 —— 给命令报 */
    List<String> schemaNotes() {
        return List.copyOf(schemaNotes);
    }

    /** 只给常见状态码;其余用「响应 N」—— OpenAPI 要求 description 非空 */
    private static String defaultDescriptionOf(int code) {
        return switch (code) {
            case 200 -> "成功";
            case 201 -> "创建成功";
            case 204 -> "无内容";
            case 400 -> "请求有误";
            case 401 -> "未认证";
            case 403 -> "无权限";
            case 404 -> "不存在";
            case 409 -> "冲突";
            case 422 -> "校验失败";
            case 500 -> "服务器错误";
            default -> "响应 " + code;
        };
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
