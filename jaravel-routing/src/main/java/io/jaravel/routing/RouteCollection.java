package io.jaravel.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 编译后的不可变路由表。启动后冻结。
 *
 * <p>匹配策略:
 * <ul>
 *   <li>静态路由(无参数)走 {@code HashMap},O(1);
 *   <li>含参数路由按注册顺序逐个正则匹配。
 * </ul>
 *
 * <p>v0.9 会把含参数路由改为前缀树以应对万级路由;v0.1 的线性匹配对
 * 几百条路由足够(单次匹配 < 10μs),过早优化没有收益。
 */
public final class RouteCollection {

    /** "GET /users" → Route */
    private final Map<String, Route> staticRoutes;
    private final List<Route> dynamicRoutes;
    private final Map<String, Route> byName;
    /** path → 支持的方法集合,用于 405 与 Allow 头 */
    private final Map<String, Set<String>> methodsByUri;

    private RouteCollection(Map<String, Route> staticRoutes, List<Route> dynamicRoutes,
                            Map<String, Route> byName, Map<String, Set<String>> methodsByUri) {
        this.staticRoutes = staticRoutes;
        this.dynamicRoutes = dynamicRoutes;
        this.byName = byName;
        this.methodsByUri = methodsByUri;
    }

    public static RouteCollection of(List<Route> routes) {
        Map<String, Route> statics = new HashMap<>();
        List<Route> dynamics = new ArrayList<>();
        Map<String, Route> named = new LinkedHashMap<>();
        Map<String, Set<String>> methods = new LinkedHashMap<>();

        for (Route r : routes) {
            if (r.isStatic()) {
                Route prev = statics.put(r.staticKey(), r);
                if (prev != null) {
                    throw new DuplicateRouteException(
                            "路由重复定义:" + r.method() + " " + r.uri()
                            + "\n  已存在:" + prev
                            + "\n  新注册:" + r);
                }
            } else {
                dynamics.add(r);
            }

            r.routeName().ifPresent(n -> {
                Route prev = named.put(n, r);
                if (prev != null) {
                    throw new DuplicateRouteException(
                            "路由名重复:\"" + n + "\""
                            + "\n  已用于:" + prev
                            + "\n  又用于:" + r);
                }
            });

            methods.computeIfAbsent(r.uri(), k -> new TreeSet<>()).add(r.method());
        }

        return new RouteCollection(Map.copyOf(statics), List.copyOf(dynamics),
                                   Map.copyOf(named), Map.copyOf(methods));
    }

    /** 匹配结果:路由 + 解析出的路径参数 */
    public record Match(Route route, Map<String, String> params) {}

    public Optional<Match> match(String method, String path) {
        String normalized = Route.normalize(path);

        Route exact = staticRoutes.get(method.toUpperCase() + " " + normalized);
        if (exact != null) return Optional.of(new Match(exact, Map.of()));

        for (Route r : dynamicRoutes) {
            var params = r.match(method, normalized);
            if (params.isPresent()) return Optional.of(new Match(r, params.get()));
        }
        return Optional.empty();
    }

    /**
     * 路径存在但方法不对 → 应返回 405 而非 404。
     * 返回该路径支持的方法集合,用于 {@code Allow} 头。
     */
    public Set<String> allowedMethods(String path) {
        String normalized = Route.normalize(path);
        Set<String> direct = methodsByUri.get(normalized);
        if (direct != null) return direct;

        // 含参数的路径需要逐个试
        Set<String> out = new TreeSet<>();
        for (Route r : dynamicRoutes) {
            for (String m : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
                if (r.method().equals(m) && r.match(m, normalized).isPresent()) out.add(m);
            }
        }
        return out;
    }

    public Optional<Route> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Route> all() {
        List<Route> out = new ArrayList<>(staticRoutes.values());
        out.addAll(dynamicRoutes);
        out.sort(java.util.Comparator.comparing(Route::uri).thenComparing(Route::method));
        return out;
    }

    public int size() { return staticRoutes.size() + dynamicRoutes.size(); }

    /** {@code jaravel route:list} 的数据源 */
    public String render() {
        var sb = new StringBuilder();
        sb.append(String.format("%-8s %-32s %-28s %s%n", "METHOD", "URI", "ACTION", "NAME"));
        sb.append("-".repeat(100)).append('\n');
        for (Route r : all()) {
            sb.append(String.format("%-8s %-32s %-28s %s%n",
                    r.method(), r.uri(),
                    r.controller().getSimpleName() + "#" + r.action(),
                    r.routeName().orElse("")));
        }
        return sb.toString();
    }

    public static class DuplicateRouteException extends IllegalStateException {
        public DuplicateRouteException(String message) { super(message); }
    }
}
