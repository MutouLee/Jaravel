package io.jaravel.routing;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.jaravel.support.Str;

/**
 * 资源路由的配置与展开。{@code r.resource("/posts", PostController.class)} 展开为 7 条。
 *
 * <p><b>延迟展开:</b>构造时只记录配置,真正展开发生在 {@link Router#compile()}。
 * 这样 {@code only()/except()/names()} 就是普通 setter,不需要「撤销重注册」。
 *
 * <p>与方法引用路由不同,这里没有 lambda 可供内省,因此用 {@link MethodHandle}
 * 在**展开时**解析一次(而非每请求反射),运行时开销接近直接调用。
 *
 * <p><b>方法完备性校验:</b>控制器缺方法时在**启动时**报错并列出缺哪些、
 * 给出 {@code .except(...)} 的现成写法。Laravel 只能运行时 404 ——
 * V2 验证已确认这是 Jaravel 的真实增量价值。
 */
public final class ResourceRegistration {

    /** action → (HTTP 方法, URI 后缀) */
    private record Spec(String httpMethod, String suffix) {}

    private static final Map<String, Spec> FULL = new LinkedHashMap<>();
    static {
        FULL.put("index",   new Spec("GET",    ""));
        FULL.put("create",  new Spec("GET",    "/create"));
        FULL.put("store",   new Spec("POST",   ""));
        FULL.put("show",    new Spec("GET",    "/{id}"));
        FULL.put("edit",    new Spec("GET",    "/{id}/edit"));
        FULL.put("update",  new Spec("PUT",    "/{id}"));
        FULL.put("destroy", new Spec("DELETE", "/{id}"));
    }

    /** API 资源去掉返回表单页的两个 */
    private static final Set<String> FORM_ACTIONS = Set.of("create", "edit");

    private final String uri;
    private final Class<?> controller;
    private final boolean apiOnly;
    /** 展开时使用的分组上下文(resource() 调用时捕获) */
    private final Router.GroupContext group;

    private List<String> only = List.of();
    private List<String> except = List.of();
    private String namePrefix;
    private String paramName;
    private final List<String> middleware = new ArrayList<>();

    ResourceRegistration(String uri, Class<?> controller, boolean apiOnly, Router.GroupContext group) {
        this.uri = uri;
        this.controller = controller;
        this.apiOnly = apiOnly;
        this.group = group;

        // /posts → posts.index / posts.show …;参数名用单数:{post}
        String base = uri.replaceAll("^/+|/+$", "").replace('/', '.');
        this.namePrefix = base + ".";
        this.paramName = Str.singular(base.contains(".")
                ? base.substring(base.lastIndexOf('.') + 1) : base);
    }

    // ---------- 配置(纯 setter,展开发生在 compile 时) ----------

    public ResourceRegistration only(String... actions) {
        this.only = List.of(actions);
        return this;
    }

    public ResourceRegistration except(String... actions) {
        this.except = List.of(actions);
        return this;
    }

    public ResourceRegistration names(String prefix) {
        this.namePrefix = prefix.endsWith(".") ? prefix : prefix + ".";
        return this;
    }

    /** 覆盖路径参数名。默认是资源名的单数形式({@code /posts} → {@code {post}})。 */
    public ResourceRegistration parameter(String name) {
        this.paramName = name;
        return this;
    }

    public ResourceRegistration middleware(String... names) {
        this.middleware.addAll(List.of(names));
        return this;
    }

    // ---------- 展开 ----------

    List<Route> expand() {
        List<String> wanted = new ArrayList<>();
        for (String action : FULL.keySet()) {
            if (apiOnly && FORM_ACTIONS.contains(action)) continue;
            if (!only.isEmpty() && !only.contains(action)) continue;
            if (except.contains(action)) continue;
            wanted.add(action);
        }

        validateActionNames();

        Map<String, Method> found = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String action : wanted) {
            Method m = findPublicMethod(controller, action);
            if (m == null) missing.add(action);
            else found.put(action, m);
        }
        if (!missing.isEmpty()) {
            throw new MissingResourceActionException(buildMissingMessage(missing));
        }

        List<Route> out = new ArrayList<>();
        String fullUri = group.prefix() + Route.normalize(uri);

        for (var e : found.entrySet()) {
            String action = e.getKey();
            Method method = e.getValue();
            Spec spec = FULL.get(action);

            String routeUri = fullUri + spec.suffix().replace("{id}", "{" + paramName + "}");
            var invoker = methodHandleInvoker(method);

            Route r = new Route(spec.httpMethod(), routeUri, controller, action,
                                invoker, method.getParameterCount(), group.namePrefix());
            r.name(namePrefix + action);
            applyMiddleware(r);
            out.add(r);

            // PUT 的同时注册 PATCH,与 Laravel 一致。只给 PUT 那条命名,避免路由名重复。
            if ("update".equals(action)) {
                Route patch = new Route("PATCH", routeUri, controller, action,
                                        invoker, method.getParameterCount(), group.namePrefix());
                applyMiddleware(patch);
                out.add(patch);
            }
        }
        return out;
    }

    private void applyMiddleware(Route r) {
        if (!group.middleware().isEmpty()) r.middleware(group.middleware().toArray(String[]::new));
        if (!middleware.isEmpty())         r.middleware(middleware.toArray(String[]::new));
    }

    /** only/except 里写了不存在的 action 名(如拼成 "delete")应当立刻报错,而不是静默忽略 */
    private void validateActionNames() {
        for (String a : only) requireKnownAction(a, "only");
        for (String a : except) requireKnownAction(a, "except");
    }

    private void requireKnownAction(String action, String where) {
        if (FULL.containsKey(action)) return;
        String hint = io.jaravel.support.Arr.closest(action, FULL.keySet())
                .map(c -> " 是否想写 \"" + c + "\"?").orElse("");
        throw new IllegalArgumentException(
                "." + where + "(\"" + action + "\") 中的 \"" + action + "\" 不是资源路由的 action。"
                + hint + "\n  合法取值:" + FULL.keySet());
    }

    private String buildMissingMessage(List<String> missing) {
        String kind = apiOnly ? "apiResource" : "resource";
        return kind + "(\"" + uri + "\", " + controller.getSimpleName() + ".class) "
             + "需要以下方法,但控制器上不存在:" + missing
             + "\n  控制器已有的 public 方法:" + publicMethodNames(controller)
             + "\n  修复:实现这些方法,或排除它们:"
             + "\n    r." + kind + "(\"" + uri + "\", " + controller.getSimpleName() + ".class)"
             + ".except(\"" + String.join("\", \"", missing) + "\");";
    }

    private static Method findPublicMethod(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getDeclaringClass() != Object.class) return m;
        }
        return null;
    }

    private static List<String> publicMethodNames(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() != Object.class) out.add(m.getName());
        }
        return out.stream().distinct().sorted().toList();
    }

    /** 展开时解析一次 MethodHandle,避免每请求反射 */
    private static Handler.Invoker methodHandleInvoker(Method method) {
        MethodHandle handle;
        try {
            handle = MethodHandles.publicLookup().unreflect(method);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "无法访问控制器方法 " + method.getDeclaringClass().getSimpleName()
                    + "#" + method.getName() + "。控制器与其方法都必须是 public。", e);
        }

        return (controllerInstance, args) -> {
            Object[] all = new Object[args.length + 1];
            all[0] = controllerInstance;
            System.arraycopy(args, 0, all, 1, args.length);
            try {
                return handle.invokeWithArguments(all);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationFailure(method, t);
            }
        };
    }

    // ---------- 读取 ----------

    public String uri()            { return uri; }
    public Class<?> controller()   { return controller; }

    public static class MissingResourceActionException extends IllegalStateException {
        public MissingResourceActionException(String message) { super(message); }
    }

    public static class InvocationFailure extends RuntimeException {
        public InvocationFailure(Method m, Throwable cause) {
            super("调用 " + m.getDeclaringClass().getSimpleName() + "#" + m.getName()
                  + " 时抛出异常:" + cause, cause);
        }
    }
}
