package net.dobeen.jaravel.routing;

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
 *   <li>含参数路由按**首段前缀**分桶,桶内仍按注册顺序正则匹配。</li>
 * </ul>
 *
 * <p>v0.9 把线性扫描改成了首段前缀索引:路径首段未知时只查
 * 「任意前缀」桶,300 条动态路由上的 404 从线性扫描变成常数级。
 * 首段不是字面量的路由(如 {@code /{slug}})进任意前缀桶,与常规桶
 * 按注册顺序合并 —— 路由优先级仍然是注册顺序。
 * 完整的分段 trie 对万级路由才有必要,现在不做。
 */
public final class RouteCollection {

    /** "GET /users" → Route */
    private final Map<String, Route> staticRoutes;
    private final List<Route> dynamicRoutes;
    private final Map<String, Route> byName;
    /** path → 支持的方法集合,用于 405 与 Allow 头 */
    private final Map<String, Set<String>> methodsByUri;
    /**
     * "GET\0users" → 该首段下可能匹配的动态路由,已按注册顺序合并
     * 「任意前缀」桶里的路由。
     */
    private final Map<String, List<Route>> dynamicByPrefix;
    /** "GET" → 首段不是字面量的动态路由(如 /{slug}),按注册顺序 */
    private final Map<String, List<Route>> dynamicAnyPrefix;

    private RouteCollection(Map<String, Route> staticRoutes, List<Route> dynamicRoutes,
                            Map<String, Route> byName, Map<String, Set<String>> methodsByUri,
                            Map<String, List<Route>> dynamicByPrefix,
                            Map<String, List<Route>> dynamicAnyPrefix) {
        this.staticRoutes = staticRoutes;
        this.dynamicRoutes = dynamicRoutes;
        this.byName = byName;
        this.methodsByUri = methodsByUri;
        this.dynamicByPrefix = dynamicByPrefix;
        this.dynamicAnyPrefix = dynamicAnyPrefix;
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

        // ---- 动态路由的字面量前缀索引 ----
        var order = new java.util.IdentityHashMap<Route, Integer>();
        for (int i = 0; i < dynamics.size(); i++) order.put(dynamics.get(i), i);

        Map<String, List<Route>> any = new HashMap<>();
        Map<String, List<Route>> prefixed = new HashMap<>();
        for (Route r : dynamics) {
            String method = r.method().toUpperCase(java.util.Locale.ROOT);
            String prefix = routePrefix(r.uri());
            if (prefix == null) {
                any.computeIfAbsent(method, k -> new ArrayList<>()).add(r);
            } else {
                prefixed.computeIfAbsent(method + '\0' + prefix,
                        k -> new ArrayList<>()).add(r);
            }
        }

        // 每个前缀桶里合并:所有更短的字面量前缀 + 「任意前缀」桶。
        // 这样一次查找就拿到所有可能匹配的路由,且仍按注册顺序。
        Map<String, List<Route>> byPrefix = new HashMap<>();
        for (var entry : prefixed.entrySet()) {
            String key = entry.getKey();
            int sep = key.indexOf('\0');
            String method = key.substring(0, sep);
            String prefix = key.substring(sep + 1);

            var merged = new ArrayList<Route>();
            for (var other : prefixed.entrySet()) {
                String otherKey = other.getKey();
                if (!otherKey.startsWith(method + '\0')) continue;
                String otherPrefix = otherKey.substring(otherKey.indexOf('\0') + 1);
                if (prefix.equals(otherPrefix) || prefix.startsWith(otherPrefix + "/")) {
                    merged.addAll(other.getValue());
                }
            }
            merged.addAll(any.getOrDefault(method, List.of()));
            merged.sort(java.util.Comparator.comparingInt(order::get));
            byPrefix.put(key, List.copyOf(merged));
        }

        Map<String, List<Route>> anyPrefix = new HashMap<>();
        any.forEach((method, list) -> anyPrefix.put(method, List.copyOf(list)));

        return new RouteCollection(Map.copyOf(statics), List.copyOf(dynamics),
                                   Map.copyOf(named), Map.copyOf(methods),
                                   Map.copyOf(byPrefix), Map.copyOf(anyPrefix));
    }

    /** 匹配结果:路由 + 解析出的路径参数 */
    public record Match(Route route, Map<String, String> params) {}

    public Optional<Match> match(String method, String path) {
        String normalized = Route.normalize(path);
        String upper = method.toUpperCase(java.util.Locale.ROOT);

        Route exact = staticRoutes.get(upper + " " + normalized);
        if (exact != null) return Optional.of(new Match(exact, Map.of()));

        for (Route r : candidates(upper, normalized)) {
            var params = r.matchNormalized(upper, normalized);
            if (params.isPresent()) return Optional.of(new Match(r, params.get()));
        }
        return Optional.empty();
    }

    /**
     * 从最长的字面量前缀往短找;第一个命中的桶已经合并了更短的前缀与
     * 「任意前缀」桶。找不到任何桶时,只有任意前缀路由可能匹配。
     */
    private List<Route> candidates(String method, String path) {
        var prefixes = pathPrefixes(path);
        for (int i = prefixes.size() - 1; i >= 0; i--) {
            List<Route> bucket = dynamicByPrefix.get(method + '\0' + prefixes.get(i));
            if (bucket != null) return bucket;
        }
        return dynamicAnyPrefix.getOrDefault(method, List.of());
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
                if (r.method().equals(m) && r.matchNormalized(m, normalized).isPresent()) out.add(m);
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

    /**
     * 路由 URI 在第一个参数段之前的字面量前缀。
     * {@code /api/items/{id}} → {@code api/items};{@code /{slug}} → null。
     */
    private static String routePrefix(String uri) {
        String v = uri.startsWith("/") ? uri.substring(1) : uri;
        if (v.isEmpty()) return null;
        var sb = new StringBuilder();
        for (String part : v.split("/", -1)) {
            if (part.isEmpty() || part.indexOf('{') >= 0) break;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 路径的全部字面量前缀,从短到长 */
    private static List<String> pathPrefixes(String path) {
        String v = path.startsWith("/") ? path.substring(1) : path;
        if (v.isEmpty()) return List.of();
        var out = new ArrayList<String>();
        var sb = new StringBuilder();
        for (String part : v.split("/", -1)) {
            if (part.isEmpty()) break;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
            out.add(sb.toString());
        }
        return out;
    }

    public static class DuplicateRouteException extends IllegalStateException {
        public DuplicateRouteException(String message) { super(message); }
    }
}
