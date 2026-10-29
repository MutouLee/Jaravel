package io.jaravel.spring;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.jaravel.core.container.Container;
import io.jaravel.http.Request;
import io.jaravel.http.annotations.Header;
import io.jaravel.http.annotations.Input;
import io.jaravel.http.annotations.Query;
import io.jaravel.routing.Route;
import io.jaravel.support.Arr;
import io.jaravel.support.Cast;

/**
 * 控制器方法的参数绑定。
 *
 * <h2>三级回落</h2>
 * <ol>
 *   <li><b>显式注解</b> —— {@code @Path("id")} / {@code @Query("page")} /
 *       {@code @Input("q")} / {@code @Header("Accept-Language")};</li>
 *   <li><b>按参数名</b> —— 编译时带 {@code -parameters} 时参数名可见,
 *       {@code Long id} 自动对上 {@code /posts/{id}};名字匹配不到路径参数时
 *       再试输入键;</li>
 *   <li><b>按位置</b> —— 参数名不可见(没开 {@code -parameters})时,
 *       按声明顺序取路径参数。</li>
 * </ol>
 *
 * <p><b>为什么要按名字</b>:v0.1 是纯按位置的,于是
 * {@code show(Long postId, Long commentId)} 对上 {@code /posts/{commentId}/c/{postId}}
 * 时会**静默交换两个值** —— 类型相同,没有任何报错,症状是「取到了别的资源」。
 * 按名字绑定让这种错误要么不发生,要么在启动时就报出来。
 *
 * <p>绑定方案按路由缓存:反射解析参数不便宜,而方案在路由注册后就固定了。
 */
final class ArgumentBinder {

    private ArgumentBinder() {}

    // ================= 绑定方案(按路由缓存) =================

    /** 一个参数的取值方式 */
    private sealed interface Source {
        record TheRequest() implements Source {}
        record FormRequestType(Class<?> type) implements Source {}
        record PathParam(String name) implements Source {}
        record BoundModel(String name, Class<?> modelType) implements Source {}
        record QueryParam(String name, String fallback, boolean required) implements Source {}
        record AnyInput(String name, String fallback, boolean required) implements Source {}
        record HeaderValue(String name, String fallback) implements Source {}
        record FromContainer(Class<?> type) implements Source {}
    }

    private record Plan(List<Source> sources, List<Class<?>> types, List<String> labels) {}

    private static final Map<Route, Plan> PLANS = new ConcurrentHashMap<>();

    static Object[] bind(Request request, Route route, Container container) {
        if (route.parameterTypes().isEmpty()) return new Object[0];

        Plan plan = PLANS.computeIfAbsent(route, ArgumentBinder::planFor);
        var args = new Object[plan.sources().size()];

        for (int i = 0; i < args.length; i++) {
            args[i] = resolve(plan.sources().get(i), plan.types().get(i),
                              plan.labels().get(i), request, route, container);
        }
        return args;
    }

    // ================= 方案推导 =================

    private static Plan planFor(Route route) {
        List<Class<?>> types = route.parameterTypes();
        Method method = findMethod(route, types);

        var sources = new ArrayList<Source>(types.size());
        var labels = new ArrayList<String>(types.size());
        List<String> pathNames = route.paramNames();

        // 已被「按名字」认领的路径参数。位置回落必须跳过它们 ——
        // 否则 show(Long id, String sort) 对 /posts/{id} 时,id 按名字拿到了,
        // 而 sort 的位置回落又指向第 0 个路径参数,于是 sort 也等于 id。
        var claimedByName = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < types.size(); i++) {
            Parameter p = method == null ? null : method.getParameters()[i];
            if (p == null || !p.isNamePresent()) continue;
            var explicit = p.getAnnotation(io.jaravel.http.annotations.Path.class);
            if (explicit != null) {
                claimedByName.add(explicit.value());
            } else if (fromAnnotation(p) == null
                    && (isSimple(types.get(i)) || isBindableModel(types.get(i)))
                    && pathNames.contains(p.getName())) {
                claimedByName.add(p.getName());
            }
        }
        var positionalQueue = new java.util.ArrayDeque<String>(
                pathNames.stream().filter(n -> !claimedByName.contains(n)).toList());

        for (int i = 0; i < types.size(); i++) {
            Class<?> type = types.get(i);
            Parameter param = method == null ? null : method.getParameters()[i];
            String label = param != null && param.isNamePresent()
                    ? param.getName() : "第 " + (i + 1) + " 个参数";
            labels.add(label);

            if (Request.class.isAssignableFrom(type)) {
                sources.add(new Source.TheRequest());
                continue;
            }
            if (io.jaravel.validation.FormRequest.class.isAssignableFrom(type)) {
                sources.add(new Source.FormRequestType(type));
                continue;
            }

            Source annotated = fromAnnotation(param);
            if (annotated != null) {
                sources.add(annotated);
                continue;
            }

            // 隐式模型绑定。必须在 FromContainer 之前判断 ——
            // 模型不是「简单类型」,不拦住它就会被当成要注入的服务,
            // 然后在容器里找不到 Post 这个 bean
            if (isBindableModel(type)) {
                String bound = bindingNameFor(type, param, pathNames, positionalQueue);
                if (bound != null) {
                    sources.add(new Source.BoundModel(bound, type));
                    continue;
                }
                // 路由上没有对应的参数:这几乎总是 URI 写漏了 {post}
                throw new IllegalStateException("""
                        %s#%s 的%s 声明为模型 %s,但路由 %s 上没有能对应的参数。
                          路径声明的参数:%s
                          隐式模型绑定按「参数名」或「模型名的小写」匹配,例如
                            r.get("/posts/{post}", PostController::show)
                            public View show(Post post) { ... }
                          不想绑定模型时把参数类型改成 Long / String。"""
                        .formatted(route.controller().getSimpleName(), route.action(),
                                   label, type.getSimpleName(), route.uri(), pathNames));
            }

            if (!isSimple(type)) {
                sources.add(new Source.FromContainer(type));
                continue;
            }

            // 按名字:参数名与路径参数名一致时直接对上
            if (param != null && param.isNamePresent() && pathNames.contains(param.getName())) {
                sources.add(new Source.PathParam(param.getName()));
                continue;
            }

            // 名字对不上路径,但还有没被认领的路径参数 → 按位置(兼容没开 -parameters)
            if (!positionalQueue.isEmpty()) {
                sources.add(new Source.PathParam(positionalQueue.poll()));
                continue;
            }

            // 路径参数用完了:当成输入项,用参数名作键
            if (param != null && param.isNamePresent()) {
                sources.add(new Source.AnyInput(param.getName(), "", false));
                continue;
            }

            throw new IllegalStateException("""
                    无法为 %s#%s 的%s(%s)确定取值来源。
                      路径 %s 声明的参数:%s
                      可能原因:路径参数个数少于方法参数,且编译时没开 -parameters
                      (没有参数名就只能按位置猜,而位置已经用完)。
                      修复二选一:
                        · 在 pom 里给 maven-compiler-plugin 加 <arg>-parameters</arg>;
                        · 或显式标注来源:@Query("name") / @Input("name") / @Path("name")。"""
                    .formatted(route.controller().getSimpleName(), route.action(),
                               label, type.getSimpleName(), route.uri(), pathNames));
        }

        // 启动期校验:参数名都在、却有路径参数没人接,通常是拼错了名字
        warnAboutUnclaimedPathParams(route, method, sources, pathNames);

        return new Plan(List.copyOf(sources), types, List.copyOf(labels));
    }

    /**
     * 这个类型是否该做隐式模型绑定。
     *
     * <p>要求它**已注册**(有 {@code @Model} 的 APT 产物)。只看
     * {@code Model.class.isAssignableFrom} 不够:一个还没编译出元模型的
     * 模型类会在这里被接下,然后在解析时报一个与根因无关的错。
     */
    private static boolean isBindableModel(Class<?> type) {
        return io.jaravel.eloquent.RouteBinder.isBindable(type);
    }

    /**
     * 这个模型参数绑哪个路由参数。
     *
     * <p>三级匹配,与简单类型的规则保持一致:
     * <ol>
     *   <li>参数名与路径参数同名({@code show(Post post)} 配 {@code {post}});</li>
     *   <li>模型名的小写与路径参数同名(编译时没开 {@code -parameters} 时的回落);</li>
     *   <li>按位置取下一个未被认领的路径参数。</li>
     * </ol>
     */
    private static String bindingNameFor(Class<?> type, Parameter param,
                                         List<String> pathNames,
                                         java.util.ArrayDeque<String> positionalQueue) {
        if (param != null && param.isNamePresent() && pathNames.contains(param.getName())) {
            return param.getName();
        }
        String byClassName = type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (pathNames.contains(byClassName)) return byClassName;

        return positionalQueue.poll();
    }

    private static Source fromAnnotation(Parameter param) {
        if (param == null) return null;

        var path = param.getAnnotation(io.jaravel.http.annotations.Path.class);
        if (path != null) return new Source.PathParam(path.value());

        var query = param.getAnnotation(Query.class);
        if (query != null) {
            return new Source.QueryParam(query.value(), query.defaultValue(), query.required());
        }
        var input = param.getAnnotation(Input.class);
        if (input != null) {
            return new Source.AnyInput(input.value(), input.defaultValue(), input.required());
        }
        var header = param.getAnnotation(Header.class);
        if (header != null) return new Source.HeaderValue(header.value(), header.defaultValue());

        return null;
    }

    /**
     * 路径里声明了参数、却没有任何方法参数去接它。
     *
     * <p>警告而不是报错:这在「只想用 {@code request.routeParam(...)} 取」时
     * 是合法的。但它同时也是「{@code {postId}} 写成 {@code {post_id}}」这类
     * 拼写错误的唯一征兆,所以必须说出来。
     */
    private static void warnAboutUnclaimedPathParams(
            Route route, Method method, List<Source> sources, List<String> pathNames) {

        if (method == null || pathNames.isEmpty()) return;
        boolean allNamesPresent = java.util.Arrays.stream(method.getParameters())
                .allMatch(Parameter::isNamePresent);
        if (!allNamesPresent) return;

        var claimed = new ArrayList<String>();
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            if (s instanceof Source.PathParam p) claimed.add(p.name());

            // ★ BoundModel 也占住一个路径参数 —— 漏了它的后果是
            // **每一条用隐式模型绑定的路由都会误报**:
            //     r.get("/invoices/{invoice}", c::show)   →  show(Invoice invoice)
            // 这里 {invoice} 明明被接住了,只是走的是模型绑定而不是
            // 字符串路径参数。
            //
            // 这个漏洞在隐式模型绑定加进来时产生,而它不会让任何测试变红 ——
            // 警告只是打到 stderr。它是靠跑一个真实示例才看见的
            if (s instanceof Source.BoundModel b) claimed.add(b.name());
        }
        var unclaimed = pathNames.stream().filter(n -> !claimed.contains(n)).toList();
        if (unclaimed.isEmpty()) return;

        // ★ 方法里没有任何「本可以接住路径参数」的参数时**不警告**。
        //
        // 典型写法:
        //     public Map<String, Object> show(Request request) {
        //         String id = request.routeParam("batch").orElseThrow();
        //     }
        //
        // 这时警告是**误报**:没有候选参数,也就不存在「{batch} 拼成了
        // {bach}」这种对应关系 —— 那条「可能的对应关系」提示会是空的。
        //
        // 而误报的代价很高:一个在正确代码上也会响的警告,会训练人
        // 忽略所有警告,于是它本来要抓的那个拼写错误也一起被忽略了。
        //
        // 仍然会警告的情况:方法里确实有一个值参数却没被接上 ——
        // 那正是拼写错误的样子。
        boolean hasBindableCandidate = sources.stream().anyMatch(s ->
                s instanceof Source.PathParam
                        || s instanceof Source.BoundModel
                        || s instanceof Source.QueryParam
                        || s instanceof Source.AnyInput);
        if (!hasBindableCandidate) return;

        var paramNames = java.util.Arrays.stream(method.getParameters())
                .map(Parameter::getName).toList();
        String hint = unclaimed.stream()
                .map(n -> Arr.closest(n, paramNames).map(c -> n + " ≈ " + c).orElse(n))
                .reduce((a, b) -> a + ", " + b).orElse("");

        System.err.printf(
                "[jaravel] 路由 %s %s 的路径参数 %s 没有对应的方法参数。%n"
              + "          %s#%s 的参数:%s%n"
              + "          可能的对应关系:%s%n"
              + "          若是刻意的(用 request.routeParam 自取),可忽略本提示。%n",
                route.method(), route.uri(), unclaimed,
                route.controller().getSimpleName(), route.action(), paramNames, hint);
    }

    /**
     * 找到控制器方法以读取参数名与注解。
     *
     * <p>按名字 + 参数个数 + 参数类型匹配。重载同名方法时类型才是决定性的 ——
     * 只按名字找会在重载时拿到错误的那一个,进而读到错误的参数名。
     */
    private static Method findMethod(Route route, List<Class<?>> types) {
        for (Method m : route.controller().getMethods()) {
            if (!m.getName().equals(route.action())) continue;
            if (m.getParameterCount() != types.size()) continue;
            if (!java.util.Arrays.asList(m.getParameterTypes()).equals(types)) continue;
            return m;
        }
        // 找不到不是致命错误:lambda 路由(r.get("/x", req -> ...))本来就没有控制器方法
        return null;
    }

    // ================= 取值 =================

    private static Object resolve(Source source, Class<?> type, String label,
                                  Request request, Route route, Container container) {
        return switch (source) {
            case Source.TheRequest ignored -> request;

            case Source.FormRequestType f -> bindFormRequest(request, f.type());

            case Source.PathParam p -> fromPath(request, route, p.name(), type, label);

            case Source.QueryParam q -> fromRaw(
                    request.query(q.name()).orElse(null),
                    q.fallback(), q.required(), "查询串参数 " + q.name(), type, label, route);

            case Source.AnyInput in -> fromRaw(
                    rawInput(request, in.name()),
                    in.fallback(), in.required(), "输入项 " + in.name(), type, label, route);

            case Source.HeaderValue h -> fromRaw(
                    request.header(h.name()).orElse(null),
                    h.fallback(), false, "请求头 " + h.name(), type, label, route);

            case Source.BoundModel b -> boundModel(request, b.name(), b.modelType());

            case Source.FromContainer c -> container.make(c.type());
        };
    }

    /**
     * 隐式模型绑定的取值。
     *
     * <p>优先用 {@code SubstituteBindings} 中间件已解析好的那一个 ——
     * **不能**在这里重新查一次库:中间件阶段的授权检查看到的是那个实例,
     * 控制器要改的必须是同一个状态。两次查询之间数据变了的话,
     * 授权检查就可以被绕过。这不是性能问题,是正确性问题。
     *
     * <p>中间件没挂(比如路由上既没有 web/api 组也没有 bindings)时
     * 在这里兜底解析 —— 控制器拿到模型总比拿到一个类型错误的参数好。
     */
    private static Object boundModel(Request request, String name, Class<?> modelType) {
        var resolved = io.jaravel.http.RouteBindings.resolved(name);
        if (resolved.isPresent()) return resolved.get();

        String value = request.routeParam(name).orElse(null);
        if (value == null) {
            throw new IllegalStateException("""
                    路由参数 "%s" 不存在,无法绑定 %s。
                      检查路由的 URI 里有没有 {%s}。"""
                    .formatted(name, modelType.getSimpleName(), name));
        }

        Object model = io.jaravel.eloquent.RouteBinder.resolveAny(modelType, value);
        io.jaravel.http.RouteBindings.put(name, model);
        return model;
    }


    private static String rawInput(Request request, String name) {
        Object value = request.all().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static Object fromPath(Request request, Route route, String name,
                                   Class<?> type, String label) {
        String raw = request.routeParam(name).orElse(null);
        if (raw == null) {
            throw new IllegalStateException("""
                    路由 %s %s 上没有名为 {%s} 的路径参数,但 %s#%s 的 %s 想绑定它。
                      该路径声明的参数:%s
                      修复:对齐名字,或用 @Query / @Input 指定别的来源。"""
                    .formatted(route.method(), route.uri(), name,
                               route.controller().getSimpleName(), route.action(),
                               label, route.paramNames()));
        }
        try {
            return Cast.to(raw, box(type));
        } catch (Cast.CastException e) {
            // 路径参数类型不符是客户端错误。用 404 而非 400:
            // 「/posts/abc」的语义是「不存在这个资源标识」,而不是「请求格式错」
            throw new io.jaravel.http.HttpException(404,
                    "路径参数 {" + name + "} 的值 \"" + raw + "\" 不是合法的 "
                    + type.getSimpleName());
        }
    }

    private static Object fromRaw(String raw, String fallback, boolean required,
                                  String what, Class<?> type, String label, Route route) {
        if (raw == null || raw.isEmpty()) {
            if (required) {
                throw new io.jaravel.http.HttpException(400, "缺少必需的" + what);
            }
            raw = fallback;
        }
        if (raw == null || raw.isEmpty()) return defaultFor(type);
        try {
            return Cast.to(raw, box(type));
        } catch (Cast.CastException e) {
            throw new io.jaravel.http.HttpException(400,
                    what + " 的值 \"" + raw + "\" 不是合法的 " + type.getSimpleName());
        }
    }

    /**
     * 构造并绑定一个 {@link io.jaravel.validation.FormRequest}。
     *
     * <p>{@code bind()} 内部会抛 403(鉴权失败)或 422(校验失败),
     * 由异常处理器转成响应 —— 控制器方法体因此只在数据合法时才会执行。
     */
    private static Object bindFormRequest(Request request, Class<?> type) {
        io.jaravel.validation.FormRequest form;
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            form = (io.jaravel.validation.FormRequest) ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("""
                    无法实例化 FormRequest %s:%s
                      FormRequest 需要一个可访问的无参构造函数 ——
                      它由框架在每次请求时新建,不能有构造依赖。
                      需要注入服务时,请在控制器里注入,再传给 FormRequest 的方法。"""
                    .formatted(type.getName(), e.getMessage()), e);
        }
        form.bind(request);
        return form;
    }

    // ================= 类型工具 =================

    private static boolean isSimple(Class<?> t) {
        return t.isPrimitive() || t == String.class || Number.class.isAssignableFrom(t)
            || t == Boolean.class || t == Character.class || t.isEnum()
            || t == java.time.Instant.class || t == java.time.LocalDate.class
            || t == java.time.LocalDateTime.class || t == java.util.UUID.class;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        return switch (t.getName()) {
            case "boolean" -> Boolean.class;
            case "byte"    -> Byte.class;
            case "short"   -> Short.class;
            case "int"     -> Integer.class;
            case "long"    -> Long.class;
            case "char"    -> Character.class;
            case "float"   -> Float.class;
            case "double"  -> Double.class;
            default -> t;
        };
    }

    private static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        return switch (t.getName()) {
            case "boolean" -> false;
            case "char"    -> '\0';
            case "float"   -> 0f;
            case "double"  -> 0d;
            case "long"    -> 0L;
            case "byte"    -> (byte) 0;
            case "short"   -> (short) 0;
            default        -> 0;
        };
    }

    /** 测试用:清掉按路由缓存的绑定方案 */
    static void clearPlans() {
        PLANS.clear();
    }
}
