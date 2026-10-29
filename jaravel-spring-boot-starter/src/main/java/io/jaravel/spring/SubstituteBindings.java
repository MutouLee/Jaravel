package io.jaravel.spring;

import java.util.function.Function;

import io.jaravel.core.context.ExecutionScope;
import io.jaravel.eloquent.RouteBinder;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.http.RouteBindings;
import io.jaravel.routing.Route;

/**
 * 把路由参数替换成已解析的模型实例(docs/03 §3.3)。
 *
 * <pre>{@code
 * r.get("/posts/{post}", PostController::show)
 *  .middleware("bindings", "can:view,post");
 *
 * public View show(Post post) { ... }       // 已经是实例
 * }</pre>
 *
 * <h2>为什么必须是中间件,而不只是参数绑定的一步</h2>
 * <p>如果只在调用控制器时解析,{@code can:update,post} 就拿不到实例 ——
 * 中间件比控制器先跑。而 {@code can} 拿到字符串 {@code "42"} 去问
 * {@code PostPolicy.update(User, Post)} 的结果是类型不匹配,
 * 要么报错要么**永远拒绝**,而后者看起来像「权限配错了」。
 *
 * <p>这是 docs/08 §10 列出的待决问题之一:
 * 「中间件优先级必须让 SubstituteBindings 在 Authorize 之前」。
 * 顺序由 {@link DefaultKernel#priority()} 固定,并有测试守住。
 *
 * <h2>为什么这个类在 starter</h2>
 * <p>它需要同时看见 {@code Route}(jaravel-routing)与 {@code Model}
 * (jaravel-eloquent),而这两个模块之间没有依赖关系 —— 不用数据库的
 * 应用不该为了路由而拉进 Eloquent。starter 是唯一同时依赖两者的地方。
 *
 * <h2>解析失败是 404,不是 500</h2>
 * <p>{@code /posts/999} 查不到那条记录,语义上就是「这个 URL 不存在」。
 * {@code ModelNotFoundException} 由 {@code JaravelExceptionHandler}
 * 映射成 404 —— 这里不做额外处理,让那一处保持唯一。
 */
public class SubstituteBindings implements Middleware {

    /** 当前请求的路由。由 {@link JaravelHandlerAdapter} 放进作用域。 */
    static final String ROUTE_KEY = "routing.current-route";

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        currentRoute().ifPresent(route -> substitute(request, route));
        return next.apply(request);
    }

    /**
     * 解析这条路由上所有「能绑定」的参数。
     *
     * <p>判断依据是**控制器方法的参数类型**,不是路由参数的名字 ——
     * {@code /posts/{post}} 配 {@code show(String post)} 时不该去查库,
     * 用户显式声明了他要字符串。
     */
    private void substitute(Request request, Route route) {
        var paramNames = route.paramNames();
        var paramTypes = route.parameterTypes();

        for (int i = 0; i < paramNames.size(); i++) {
            String name = paramNames.get(i);
            String value = request.routeParam(name).orElse(null);
            if (value == null) continue;

            // 已经解析过(同一请求内重复进入,或被显式放进去过)
            if (RouteBindings.resolved(name).isPresent()) continue;

            // 显式绑定优先 —— 注册了就按注册的来,
            // 这样「这个参数怎么解析的」有唯一答案
            var explicit = RouteBindings.explicitResolver(name);
            if (explicit.isPresent()) {
                RouteBindings.put(name, explicit.get().apply(value, request));
                continue;
            }

            modelTypeFor(paramTypes, paramNames, name, i)
                    .ifPresent(type -> RouteBindings.put(name,
                            RouteBinder.resolveAny(type, value)));
        }
    }

    /**
     * 这个路由参数对应的控制器参数是不是一个可绑定的模型类型。
     *
     * <p>按**位置**对齐而不是按名字:路由参数在 URI 里的顺序与控制器
     * 方法的参数顺序一致,这是 {@code ArgumentBinder} 已有的约定。
     * 控制器方法可能还有 {@code Request} 之类的额外参数,所以这里
     * 只在位置落在范围内时才看 —— 对不上就跳过,让 ArgumentBinder
     * 用它那套更完整的规则去处理(它能按名字匹配)。
     */
    private java.util.Optional<Class<?>> modelTypeFor(
            java.util.List<Class<?>> paramTypes, java.util.List<String> paramNames,
            String name, int index) {

        // 先按位置
        if (index < paramTypes.size() && RouteBinder.isBindable(paramTypes.get(index))) {
            return java.util.Optional.of(paramTypes.get(index));
        }

        // 位置对不上(控制器签名里混了 Request):退而求其次,
        // 找唯一一个「类名(小写)等于参数名」的可绑定类型。
        // 这正是 {post} ↔ Post 的那个约定
        for (Class<?> type : paramTypes) {
            if (!RouteBinder.isBindable(type)) continue;
            if (type.getSimpleName().equalsIgnoreCase(name)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }


    private static java.util.Optional<Route> currentRoute() {
        return ExecutionScope.tryCurrent()
                .flatMap(scope -> scope.attribute(ROUTE_KEY, Route.class));
    }
}
