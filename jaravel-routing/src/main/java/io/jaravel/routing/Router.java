package io.jaravel.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.jaravel.routing.Handler.H0;
import io.jaravel.routing.Handler.H1;
import io.jaravel.routing.Handler.H2;
import io.jaravel.routing.Handler.H3;

/**
 * 路由 DSL。
 *
 * <pre>{@code
 * r.get("/users", UserController::index).name("users.index");
 * r.get("/users/{id}", UserController::show).name("users.show");
 * r.post("/users", UserController::store);
 * r.resource("/posts", PostController.class);
 *
 * r.group(g -> g.prefix("/admin").middleware("auth"), g -> {
 *     g.get("/dashboard", DashboardController::index);
 * });
 * }</pre>
 *
 * <p>控制器用**方法引用**而非字符串,因此重命名方法时编译期就会发现断链 ——
 * 这是 Laravel 的 {@code 'C@m'} 字符串做不到的。
 */
public class Router {

    /** 方法引用注册的路由。资源路由另存于 pendingResources,compile 时展开。 */
    private final List<Route> routes = new ArrayList<>();

    /** 当前分组上下文(前缀 / 中间件 / 名称前缀)。group() 期间生效。 */
    private GroupContext group = GroupContext.root();

    /** 包可见:ResourceRegistration 在展开时需要它 */
    record GroupContext(String prefix, List<String> middleware, String namePrefix) {
        static GroupContext root() { return new GroupContext("", List.of(), ""); }

        GroupContext merge(GroupContext child) {
            var mw = new ArrayList<>(middleware);
            mw.addAll(child.middleware);
            return new GroupContext(prefix + child.prefix, List.copyOf(mw), namePrefix + child.namePrefix);
        }
    }

    // ================= HTTP 动词 × arity 重载 =================
    // V2 验证:漏一个 arity 重载会导致用户的方法引用编译失败。
    // 方法多但全是声明,写完即稳定。

    public <C>             Route get(String uri, H0<C> h)              { return add("GET", uri, h, Handler.wrap(h), 0); }
    public <C, A>          Route get(String uri, H1<C, A> h)           { return add("GET", uri, h, Handler.wrap(h), 1); }
    public <C, A, B>       Route get(String uri, H2<C, A, B> h)        { return add("GET", uri, h, Handler.wrap(h), 2); }
    public <C, A, B, D>    Route get(String uri, H3<C, A, B, D> h)     { return add("GET", uri, h, Handler.wrap(h), 3); }

    public <C>             Route post(String uri, H0<C> h)             { return add("POST", uri, h, Handler.wrap(h), 0); }
    public <C, A>          Route post(String uri, H1<C, A> h)          { return add("POST", uri, h, Handler.wrap(h), 1); }
    public <C, A, B>       Route post(String uri, H2<C, A, B> h)       { return add("POST", uri, h, Handler.wrap(h), 2); }
    public <C, A, B, D>    Route post(String uri, H3<C, A, B, D> h)    { return add("POST", uri, h, Handler.wrap(h), 3); }

    public <C>             Route put(String uri, H0<C> h)              { return add("PUT", uri, h, Handler.wrap(h), 0); }
    public <C, A>          Route put(String uri, H1<C, A> h)           { return add("PUT", uri, h, Handler.wrap(h), 1); }
    public <C, A, B>       Route put(String uri, H2<C, A, B> h)        { return add("PUT", uri, h, Handler.wrap(h), 2); }
    public <C, A, B, D>    Route put(String uri, H3<C, A, B, D> h)     { return add("PUT", uri, h, Handler.wrap(h), 3); }

    public <C>             Route patch(String uri, H0<C> h)            { return add("PATCH", uri, h, Handler.wrap(h), 0); }
    public <C, A>          Route patch(String uri, H1<C, A> h)         { return add("PATCH", uri, h, Handler.wrap(h), 1); }
    public <C, A, B>       Route patch(String uri, H2<C, A, B> h)      { return add("PATCH", uri, h, Handler.wrap(h), 2); }

    public <C>             Route delete(String uri, H0<C> h)           { return add("DELETE", uri, h, Handler.wrap(h), 0); }
    public <C, A>          Route delete(String uri, H1<C, A> h)        { return add("DELETE", uri, h, Handler.wrap(h), 1); }
    public <C, A, B>       Route delete(String uri, H2<C, A, B> h)     { return add("DELETE", uri, h, Handler.wrap(h), 2); }

    // ================= 无控制器的直出路由 =================

    /**
     * 直出视图,无需控制器。
     * {@code r.view("/about", "pages.about")}
     */
    public Route view(String uri, String viewName) {
        return view(uri, viewName, java.util.Map.of());
    }

    public Route view(String uri, String viewName, java.util.Map<String, ?> data) {
        return direct("GET", uri, "view:" + viewName,
                req -> io.jaravel.http.View.of(viewName, data));
    }

    /**
     * 直接重定向。
     * {@code r.redirect("/old", "/new", 301)}
     */
    public Route redirect(String from, String to) {
        return redirect(from, to, 302);
    }

    public Route redirect(String from, String to, int status) {
        return direct("GET", from, "redirect:" + to,
                req -> io.jaravel.http.Redirect.to(to, status));
    }

    private Route direct(String method, String uri, String label,
                         java.util.function.Function<io.jaravel.http.Request, Object> handler) {
        String fullUri = group.prefix() + Route.normalize(uri);
        var route = new Route(method, fullUri, Router.class, label, null, 0, group.namePrefix())
                        .direct(handler);
        if (!group.middleware().isEmpty()) {
            route.middleware(group.middleware().toArray(String[]::new));
        }
        routes.add(route);
        return route;
    }

    // ================= 分组 =================

    /** 分组配置器 */
    public static final class GroupBuilder {
        private String prefix = "";
        private final List<String> middleware = new ArrayList<>();
        private String namePrefix = "";

        public GroupBuilder prefix(String p)            { this.prefix = Route.normalize(p).equals("/") ? "" : Route.normalize(p); return this; }
        public GroupBuilder middleware(String... names) { this.middleware.addAll(List.of(names)); return this; }
        public GroupBuilder name(String p)              { this.namePrefix = p; return this; }

        GroupContext build() { return new GroupContext(prefix, List.copyOf(middleware), namePrefix); }
    }

    /**
     * {@code r.group(g -> g.prefix("/admin").middleware("auth"), g -> { ... })}
     */
    public Router group(Consumer<GroupBuilder> config, Consumer<Router> body) {
        var builder = new GroupBuilder();
        config.accept(builder);

        GroupContext saved = this.group;
        this.group = saved.merge(builder.build());
        try {
            body.accept(this);
        } finally {
            this.group = saved;   // 一定恢复,否则后续路由会继承错误的前缀
        }
        return this;
    }

    // ================= 资源路由 =================

    /**
     * 展开为 7 条 RESTful 路由。
     *
     * <p>V2 验证确认:APT 会在编译期校验这 7 个方法都存在于控制器上
     * (Laravel 只能运行时 404)。v0.1 在注册时校验,报错同样列出缺失方法。
     */
    public ResourceRegistration resource(String uri, Class<?> controller) {
        var reg = new ResourceRegistration(uri, controller, false, group);
        pendingResources.add(reg);
        return reg;
    }

    /** 去掉 create / edit 两个返回表单页的路由 */
    public ResourceRegistration apiResource(String uri, Class<?> controller) {
        var reg = new ResourceRegistration(uri, controller, true, group);
        pendingResources.add(reg);
        return reg;
    }

    // ================= 注册与读取 =================

    private Route add(String method, String uri, java.io.Serializable methodRef,
                      Handler.Invoker invoker, int arity) {
        var target = LambdaIntrospector.inspect(methodRef);

        String fullUri = group.prefix() + Route.normalize(uri);
        var route = new Route(method, fullUri, target.controller(), target.method(),
                              invoker, arity, group.namePrefix());

        if (!group.middleware().isEmpty()) {
            route.middleware(group.middleware().toArray(String[]::new));
        }
        routes.add(route);
        return route;
    }

    /** 资源路由的展开延迟到 compile(),这样 only/except 可以是纯 setter */
    private final List<ResourceRegistration> pendingResources = new ArrayList<>();

    /** 全部路由(含展开后的资源路由) */
    public List<Route> routes() {
        var out = new ArrayList<>(routes);
        for (var reg : pendingResources) out.addAll(reg.expand());
        return List.copyOf(out);
    }

    public RouteCollection compile() { return RouteCollection.of(routes()); }

}
