package io.jaravel.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import io.jaravel.core.context.ExecutionScope;

/**
 * 路由参数 → 已解析的对象(隐式/显式模型绑定)。
 *
 * <h2>为什么必须有一个请求级的缓存</h2>
 * <p>同一个路由参数会被**两个不同的地方**要走:
 *
 * <ul>
 *   <li>{@code middleware("can:update,post")} 在中间件阶段要 Post 实例;</li>
 *   <li>控制器方法 {@code update(Post post)} 在调用阶段要同一个实例。</li>
 * </ul>
 *
 * <p>没有缓存就是**查两次库**,而且更糟:两次查询之间数据可能变了,
 * 于是「授权时看到的那条记录」与「实际被修改的那条记录」不是同一个状态。
 * 授权检查因此可以被绕过 —— 这不是性能问题,是正确性问题。
 *
 * <h2>显式绑定</h2>
 * <pre>{@code
 * // 启动时注册(例如在一个 @Bean 的初始化里)
 * RouteBindings.bind("post", (value, request) ->
 *         Post.query().whereSlug(value).firstOrFail());
 * }</pre>
 *
 * <p>显式绑定的优先级高于隐式 —— 注册了就按注册的来,
 * 这样「这个参数到底怎么解析的」有一个唯一答案。
 */
public final class RouteBindings {

    private static final String KEY = "http.route-bindings";

    /** 参数名 → 自定义解析器。全局配置,不是请求级的。 */
    private static final Map<String, BiFunction<String, Request, Object>> EXPLICIT =
            new ConcurrentHashMap<>();

    private RouteBindings() {
    }

    // ================= 显式绑定 =================

    /**
     * 注册一个参数名的自定义解析。
     *
     * @param name     路由参数名,如 {@code post}
     * @param resolver 收到原始字符串与请求,返回解析后的对象。
     *                 查不到应当抛异常(通常是 404),而不是返回 null ——
     *                 返回 null 会让控制器拿到一个 null 参数然后在某行 NPE
     */
    public static void bind(String name, BiFunction<String, Request, Object> resolver) {
        EXPLICIT.put(name, resolver);
    }

    public static Optional<BiFunction<String, Request, Object>> explicitResolver(String name) {
        return Optional.ofNullable(EXPLICIT.get(name));
    }

    public static boolean hasExplicit(String name) {
        return EXPLICIT.containsKey(name);
    }

    /** 测试用:清掉全局注册 */
    public static void clearExplicit() {
        EXPLICIT.clear();
    }

    // ================= 请求级缓存 =================

    /**
     * 记下一个已解析的绑定。
     *
     * <p>不在请求作用域内时静默忽略 —— 直接调用控制器方法的单元测试
     * 没有作用域,而为此抛异常会让那种测试写不下去。
     */
    public static void put(String name, Object value) {
        if (value == null) return;

        ExecutionScope.tryCurrent().ifPresent(scope -> cache(scope).put(name, value));
    }

    public static Optional<Object> resolved(String name) {
        return ExecutionScope.tryCurrent()
                .map(scope -> cache(scope).get(name));
    }

    /** 按类型取,顺带做类型检查 */
    public static <T> Optional<T> resolved(String name, Class<T> type) {
        return resolved(name).filter(type::isInstance).map(type::cast);
    }

    public static Map<String, Object> all() {
        return ExecutionScope.tryCurrent()
                .map(scope -> Map.copyOf(cache(scope)))
                .orElseGet(Map::of);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cache(ExecutionScope scope) {
        var existing = (Map<String, Object>) scope.attribute(KEY).orElse(null);
        if (existing != null) return existing;

        var fresh = new LinkedHashMap<String, Object>();
        scope.put(KEY, fresh);
        return fresh;
    }
}
