package io.jaravel.spring.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.jaravel.console.Command;
import io.jaravel.console.ConsoleCommand;
import io.jaravel.console.Option;
import io.jaravel.routing.Route;
import io.jaravel.routing.RouteCollection;

/**
 * {@code jaravel route:list}(docs/13 §2.5)。
 *
 * <pre>
 * jaravel route:list
 * jaravel route:list --method=GET --path=posts
 * jaravel route:list --name=admin
 * </pre>
 *
 * <p>数据来自**编译后的路由表**,也就是请求真正走的那一张 ——
 * 而不是重新解析一遍路由定义。两者不一致时(分组前缀、资源路由的展开、
 * PATCH 的隐式注册),重新解析那份会说谎。
 */
@Command(name = "route:list", description = "列出全部路由")
public final class RouteListCommand extends ConsoleCommand {

    @Option(names = "--method", description = "只看这个 HTTP 方法")
    String method;

    @Option(names = "--path", description = "URI 里包含这段文字")
    String path;

    @Option(names = "--name", description = "路由名里包含这段文字")
    String name;

    private final Supplier<RouteCollection> routes;

    public RouteListCommand(Supplier<RouteCollection> routes) {
        this.routes = routes;
    }

    @Override
    public int handle() {
        List<Route> all = routes.get().all();
        var rows = new ArrayList<List<Object>>();

        for (Route route : all) {
            if (!matches(route)) continue;
            rows.add(List.of(
                    route.method(),
                    route.uri(),
                    route.controller().getSimpleName() + "#" + route.action(),
                    route.routeName().orElse(""),
                    String.join(" ", route.middlewareNames())));
        }

        if (rows.isEmpty()) {
            comment(all.isEmpty() ? "没有注册任何路由。"
                    : "没有匹配的路由(共 " + all.size() + " 条)。");
            return 0;
        }

        table(List.of("方法", "URI", "处理", "名字", "中间件"), rows);
        newLine();
        line(rows.size() == all.size()
                ? "共 " + all.size() + " 条"
                : "匹配 " + rows.size() + " / " + all.size() + " 条");
        return 0;
    }

    private boolean matches(Route route) {
        if (method != null && !method.isBlank()
                && !route.method().equalsIgnoreCase(method.trim())) {
            return false;
        }
        if (path != null && !path.isBlank() && !route.uri().contains(path.trim())) {
            return false;
        }
        return name == null || name.isBlank()
                || route.routeName().orElse("").contains(name.trim());
    }
}
