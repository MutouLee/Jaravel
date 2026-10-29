package io.jaravel.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.jaravel.routing.Handler.Invoker;

/**
 * 一条路由。既是定义时的链式配置对象,也是运行时的匹配与调用单元。
 */
public final class Route {

    /** {id} / {id?} / {path*} */
    private static final Pattern PARAM_TOKEN = Pattern.compile("\\{(\\w+)(\\?)?(\\*)?}");

    private final String method;
    private final String uri;
    private final Class<?> controller;
    private final String action;
    private final Invoker invoker;
    private final int arity;
    /**
     * 控制器方法的参数类型。由方法引用内省(v0.1)或 APT(v0.2)提供。
     * 参数绑定依赖它做类型转换。
     */
    private final List<Class<?>> parameterTypes;
    /** 分组的名称前缀。name("show") 实际存为 namePrefix + "show"。 */
    private final String namePrefix;

    private String name;
    private final List<String> middleware = new ArrayList<>();
    private final List<String> withoutMiddleware = new ArrayList<>();
    private final Map<String, String> constraints = new LinkedHashMap<>();

    /** 路径参数名,按出现顺序 */
    private final List<String> paramNames = new ArrayList<>();
    /** 编译后的匹配正则;静态路由为 null(走 O(1) 哈希匹配) */
    private Pattern compiled;
    private boolean isStatic;

    /**
     * 直出路由的处理器(r.view / r.redirect)。非 null 时不解析控制器,
     * 直接调用它 —— 这类路由没有控制器可注入。
     */
    private java.util.function.Function<io.jaravel.http.Request, Object> direct;

    Route(String method, String uri, Class<?> controller, String action,
          Invoker invoker, int arity, String namePrefix) {
        this(method, uri, controller, action, invoker, arity, namePrefix, List.of());
    }

    Route(String method, String uri, Class<?> controller, String action,
          Invoker invoker, int arity, String namePrefix, List<Class<?>> parameterTypes) {
        this.method = method.toUpperCase();
        this.uri = normalize(uri);
        this.controller = controller;
        this.action = action;
        this.invoker = invoker;
        this.arity = arity;
        this.namePrefix = namePrefix == null ? "" : namePrefix;
        this.parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        parseParams();
    }

    /**
     * 只给测试用的构造入口。
     *
     * <p>正常路径是 {@code Router.get("/x", C::m)} —— 它经序列化 lambda
     * 反推出控制器与方法。但那条路径测的是「lambda 解析」,而参数绑定、
     * 中间件解析这些测试需要的只是一条**形状正确**的 Route。
     * 两者混在一起,失败时分不清坏在哪一环。
     */
    public static Route forTesting(String method, String uri, Class<?> controller,
                                   String action, java.util.List<Class<?>> parameterTypes) {
        return new Route(method, uri, controller, action,
                         (target, args) -> null, parameterTypes.size(), "", parameterTypes);
    }

    static String normalize(String uri) {
        if (uri == null || uri.isBlank()) return "/";
        String u = uri.startsWith("/") ? uri : "/" + uri;
        // 去掉尾部斜杠(根路径除外),避免 /posts 与 /posts/ 被当作两条
        if (u.length() > 1 && u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private void parseParams() {
        Matcher m = PARAM_TOKEN.matcher(uri);
        while (m.find()) paramNames.add(m.group(1));
        this.isStatic = paramNames.isEmpty();
    }

    // ---------- 链式配置 ----------

    public Route name(String n) {
        this.name = namePrefix + n;
        return this;
    }

    public Route middleware(String... names) {
        middleware.addAll(List.of(names));
        compiled = null;
        return this;
    }

    public Route withoutMiddleware(String... names) {
        withoutMiddleware.addAll(List.of(names));
        return this;
    }

    /** 正则约束。{@code .where("id", "\\d+")} */
    public Route where(String param, String regex) {
        if (!paramNames.contains(param)) {
            throw new IllegalArgumentException(
                    "路由 " + method + " " + uri + " 没有参数 {" + param + "}。"
                    + "已声明的参数:" + paramNames);
        }
        constraints.put(param, regex);
        compiled = null;     // 约束变了,需重新编译
        return this;
    }

    public Route whereNumber(String param) { return where(param, "[0-9]+"); }
    public Route whereAlpha(String param)  { return where(param, "[A-Za-z]+"); }
    public Route whereSlug(String param)   { return where(param, "[A-Za-z0-9\\-_]+"); }
    public Route whereUuid(String param) {
        return where(param, "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    Route direct(java.util.function.Function<io.jaravel.http.Request, Object> handler) {
        this.direct = handler;
        return this;
    }

    /** 是否为直出路由(无控制器) */
    public boolean isDirect() { return direct != null; }

    public java.util.function.Function<io.jaravel.http.Request, Object> directHandler() {
        return direct;
    }

    // ---------- 匹配 ----------

    public boolean isStatic() { return isStatic; }

    /** 静态路由的匹配键 */
    public String staticKey() { return method + " " + uri; }

    private Pattern pattern() {
        Pattern p = compiled;
        if (p == null) {
            p = compile();
            compiled = p;
        }
        return p;
    }

    private Pattern compile() {
        StringBuilder regex = new StringBuilder("^");
        Matcher m = PARAM_TOKEN.matcher(uri);
        int last = 0;

        while (m.find()) {
            String literal = uri.substring(last, m.start());
            String param = m.group(1);
            boolean optional = m.group(2) != null;
            boolean wildcard = m.group(3) != null;
            String body = constraints.getOrDefault(param, wildcard ? ".+" : "[^/]+");

            if (optional && literal.endsWith("/")) {
                // {id?} 必须连带前面的 / 一起可选,否则 /users 会匹配不上
                // (若只让参数可选,正则会要求末尾那个 /)
                regex.append(quote(literal.substring(0, literal.length() - 1)))
                     .append("(?:/(?<").append(param).append(">").append(body).append("))?");
            } else {
                regex.append(quote(literal))
                     .append("(?<").append(param).append(">").append(body).append(")")
                     .append(optional ? "?" : "");
            }
            last = m.end();
        }

        regex.append(quote(uri.substring(last))).append("$");
        return Pattern.compile(regex.toString());
    }

    /** 空串不能用 Pattern.quote(会产生 \Q\E 噪音,虽无害但影响可读性) */
    private static String quote(String s) {
        return s.isEmpty() ? "" : Pattern.quote(s);
    }

    /** 匹配路径;命中返回参数名→值,否则空。 */
    public Optional<Map<String, String>> match(String requestMethod, String requestPath) {
        if (!method.equalsIgnoreCase(requestMethod)) return Optional.empty();
        String path = normalize(requestPath);

        if (isStatic) {
            return uri.equals(path) ? Optional.of(Map.of()) : Optional.empty();
        }

        Matcher m = pattern().matcher(path);
        if (!m.matches()) return Optional.empty();

        Map<String, String> params = new LinkedHashMap<>();
        for (String p : paramNames) {
            String v = m.group(p);
            if (v != null) params.put(p, v);
        }
        return Optional.of(params);
    }

    // ---------- 读取 ----------

    public String method()                     { return method; }
    public String uri()                        { return uri; }
    public Class<?> controller()               { return controller; }
    public String action()                     { return action; }
    public Invoker invoker()                   { return invoker; }
    public int arity()                         { return arity; }
    public List<Class<?>> parameterTypes()     { return parameterTypes; }
    public Optional<String> routeName()        { return Optional.ofNullable(name); }
    public List<String> middlewareNames()      { return List.copyOf(middleware); }
    public List<String> excludedMiddleware()   { return List.copyOf(withoutMiddleware); }
    public List<String> paramNames()           { return List.copyOf(paramNames); }
    public Map<String, String> constraints()   { return Map.copyOf(constraints); }

    @Override public String toString() {
        return method + " " + uri + " → " + controller.getSimpleName() + "#" + action
             + (name != null ? " [" + name + "]" : "");
    }
}
