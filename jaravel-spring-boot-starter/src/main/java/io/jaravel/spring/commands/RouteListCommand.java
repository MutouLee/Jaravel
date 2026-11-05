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
 *
 * <h2>{@code --json}:给脚本用</h2>
 * <p>{@code route:list --json | jq} 这条管道在 {@link ConsoleCommand}
 * 的注释里被当作「报错要走 stderr」的理由反复引用,而 {@code --json}
 * 本身此前**根本没实现** —— 这是文档↔现实对账查出来的。
 *
 * <p>实现上有三条规矩,全是为了那条管道:
 * <ul>
 *   <li><b>stdout 上只有那一个 JSON 文档</b>:人看的「共 N 条」在
 *       {@code --json} 下不打。多一行就是 {@code jq: parse error}。</li>
 *   <li><b>一条都没匹配时输出 {@code []},退出码 0</b> ——
 *       而不是一句人话。一个 {@code | jq length} 的脚本要的是 0,
 *       不是解析失败。</li>
 *   <li><b>用一个**专属的** ObjectMapper,刻意不复用应用那个</b>——
 *       理由见 {@link #renderJson}。</li>
 * </ul>
 */
@Command(name = "route:list", description = "列出全部路由")
public final class RouteListCommand extends ConsoleCommand {

    @Option(names = "--method", description = "只看这个 HTTP 方法")
    String method;

    @Option(names = "--path", description = "URI 里包含这段文字")
    String path;

    @Option(names = "--name", description = "路由名里包含这段文字")
    String name;

    @Option(names = "--json", description = "输出 JSON(给脚本用)")
    boolean json;

    /** 专属 mapper —— 理由见 {@link #renderJson};默认设置就包含 null 字段 */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final Supplier<RouteCollection> routes;

    public RouteListCommand(Supplier<RouteCollection> routes) {
        this.routes = routes;
    }

    @Override
    public int handle() {
        List<Route> all = routes.get().all();
        if (json) return renderJson(all);

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

    /**
     * 匹配到的路由 → 一个 JSON 数组。
     *
     * <p>每条路由用 {@code LinkedHashMap} 而不是 {@code Map.of}:
     * 后者的键顺序是哈希序,于是**同一份路由表两次输出的字段顺序不同** ——
     * 那会让 {@code route:list --json > routes.json} 的 diff 全是噪音。
     *
     * <h2>★ 刻意**不**用应用的 ObjectMapper</h2>
     * <p>第一版用的是容器里那个(想着「转义交给它」),而第一次拿真项目
     * 跑管道就炸了:{@code examples/blog} 的 mapper 配了
     * 「不输出 null 字段」(API 响应的常见设置),于是没有名字的路由
     * **整个 name 键都消失了** —— 一个 {@code r["name"]} 的脚本直接 KeyError。
     *
     * <p>也就是说:复用应用的 mapper 等于让**应用的 HTTP 序列化策略**
     * 决定 CLI 的机器可读契约。{@code spring.jackson.default-property-inclusion}
     * 一改,所有读这份输出的脚本一起坏,而那条配置看起来与 CLI 毫无关系。
     *
     * <p>所以用一个专属的:字段永远在,值该是 null 就是 null。
     * 转义与 unicode 照样是 Jackson 的默认行为,一分不少。
     */
    private int renderJson(List<Route> all) {
        var out = new ArrayList<java.util.Map<String, Object>>();
        for (Route route : all) {
            if (!matches(route)) continue;
            var entry = new java.util.LinkedHashMap<String, Object>();
            entry.put("method", route.method());
            entry.put("uri", route.uri());
            entry.put("controller", route.controller().getName());
            entry.put("action", route.action());
            entry.put("name", route.routeName().orElse(null));
            entry.put("middleware", route.middlewareNames());
            out.add(entry);
        }

        try {
            line(MAPPER.writeValueAsString(out));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 序列化失败要走 **stderr** 并给非 0 退出码 —— 否则
            // `route:list --json | jq` 会拿到一个空的 stdout 和退出码 0,
            // 而脚本会把那当成「没有路由」
            error("路由表序列化失败:" + e.getOriginalMessage());
            return 1;
        }
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
