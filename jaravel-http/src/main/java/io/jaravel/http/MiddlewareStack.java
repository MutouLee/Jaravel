package io.jaravel.http;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.jaravel.support.Arr;

/**
 * 把一条路由声明的中间件名解析成有序的中间件实例链。
 *
 * <p>解析一次、按路由缓存 —— 中间件链在路由注册后就不再变化,
 * 每个请求重新解析一遍是纯浪费。
 *
 * <h2>解析步骤</h2>
 * <ol>
 *   <li>全局中间件(声明顺序);</li>
 *   <li>路由/分组声明的名字,逐个展开:分组名 → 整组;别名 → 单个;
 *       {@code alias:p1,p2} → 单个 + {@code configure(p1, p2)};</li>
 *   <li>去掉 {@code withoutMiddleware} 指定的;</li>
 *   <li>按 {@link Kernel#priority()} 重排。</li>
 * </ol>
 */
public final class MiddlewareStack {

    /** 用名字解析中间件实例的方式。实践中是容器。 */
    public interface Factory {
        <T extends Middleware> T create(Class<T> type);
    }

    private final Kernel kernel;
    private final Factory factory;

    /** 无参中间件可以共享实例 —— 它们是无状态的 */
    private final Map<Class<?>, Middleware> shared = new ConcurrentHashMap<>();

    public MiddlewareStack(Kernel kernel, Factory factory) {
        this.kernel = kernel;
        this.factory = factory;
    }

    /**
     * 解析一条链。
     *
     * @param declared 路由与分组上声明的名字(可含 {@code alias:args} 形式)
     * @param excluded {@code withoutMiddleware} 指定的名字或类名
     */
    public List<Middleware> resolve(List<String> declared, List<String> excluded) {
        var classes = new ArrayList<Entry>();

        for (Class<? extends Middleware> type : kernel.globalMiddleware()) {
            classes.add(new Entry(type, List.of()));
        }
        for (String name : declared) {
            classes.addAll(expand(name));
        }

        var excludedTypes = resolveExcluded(excluded);
        var kept = classes.stream()
                .filter(e -> !excludedTypes.contains(e.type()))
                .toList();

        return instantiate(sortByPriority(dedupe(kept)));
    }

    private record Entry(Class<? extends Middleware> type, List<String> params) {}

    // ================= 展开 =================

    /** {@code "throttle:60,1"} → 一个 Entry;{@code "web"} → 整组 */
    private List<Entry> expand(String declaration) {
        int colon = declaration.indexOf(':');
        String name = colon < 0 ? declaration : declaration.substring(0, colon);
        List<String> params = colon < 0
                ? List.of()
                : List.of(declaration.substring(colon + 1).split(","));

        var group = kernel.middlewareGroups().get(name);
        if (group != null) {
            if (!params.isEmpty()) {
                throw new IllegalStateException(
                        "中间件分组 \"" + name + "\" 不能带参数(写成了 \"" + declaration + "\")。"
                      + "\n  参数只对单个别名有意义。要给组里某一个传参,"
                      + "请把它从组里拿出来单独声明。");
            }
            return group.stream().map(t -> new Entry(t, List.<String>of())).toList();
        }

        var alias = kernel.middlewareAliases().get(name);
        if (alias != null) {
            return List.of(new Entry(alias, params));
        }
        throw unknownName(name, declaration);
    }

    private IllegalStateException unknownName(String name, String declaration) {
        var known = new ArrayList<String>();
        known.addAll(kernel.middlewareGroups().keySet());
        known.addAll(kernel.middlewareAliases().keySet());

        String hint = Arr.closest(name, known)
                .map(c -> "\n  是否想写 \"" + c + "\" ?").orElse("");

        return new IllegalStateException("""
                未知的中间件 "%s"(声明为 "%s")。%s
                  已注册的分组:%s
                  已注册的别名:%s
                  修复:在 Kernel 的 aliases() 里登记它,或改用已有名字。
                  (没有 Kernel 的话新建一个 app/http/HttpKernel.java extends Kernel)"""
                .formatted(name, declaration, hint,
                           kernel.middlewareGroups().keySet(),
                           kernel.middlewareAliases().keySet()));
    }

    // ================= 排除 =================

    /**
     * {@code withoutMiddleware} 可以写别名、分组名,也可以写类的简单名。
     *
     * <p>必须支持类名:常见诉求是「这条路由属于 web 组,但不要 CSRF 校验」,
     * 而 CSRF 是组里的一员、本身没有别名。只认别名的话这个诉求无法表达。
     */
    private Set<Class<?>> resolveExcluded(List<String> excluded) {
        var out = new LinkedHashSet<Class<?>>();
        for (String name : excluded) {
            var alias = kernel.middlewareAliases().get(name);
            if (alias != null) {
                out.add(alias);
                continue;
            }
            var group = kernel.middlewareGroups().get(name);
            if (group != null) {
                out.addAll(group);
                continue;
            }
            // 按类的简单名匹配
            boolean matched = false;
            for (var type : allKnownTypes()) {
                if (type.getSimpleName().equals(name) || type.getName().equals(name)) {
                    out.add(type);
                    matched = true;
                }
            }
            if (!matched) {
                throw new IllegalStateException(
                        "withoutMiddleware(\"" + name + "\") 没有匹配到任何中间件。"
                      + "\n  可以写:别名、分组名,或中间件类的简单名(如 \"VerifyCsrfToken\")。"
                      + "\n  已知的类:" + allKnownTypes().stream()
                              .map(Class::getSimpleName).sorted().toList()
                      + "\n  写错名字时静默忽略最危险 —— 那意味着你以为关掉了某个"
                      + "中间件,实际它还在跑(或反之)。所以这里直接报错。");
            }
        }
        return out;
    }

    private Set<Class<? extends Middleware>> allKnownTypes() {
        var out = new LinkedHashSet<Class<? extends Middleware>>();
        out.addAll(kernel.globalMiddleware());
        kernel.middlewareGroups().values().forEach(out::addAll);
        out.addAll(kernel.middlewareAliases().values());
        out.addAll(kernel.middlewarePriority());
        return out;
    }

    // ================= 去重与排序 =================

    /**
     * 同一个中间件出现多次只保留第一次。
     *
     * <p>重复很常见:路由同时写了 {@code "web"} 和 {@code "auth"},而 web 组里
     * 已经含 auth。跑两遍的后果取决于中间件实现 —— 会话可能被重置、
     * 限流计数翻倍 —— 总之不是用户想要的。
     *
     * <p>但**带参数的不去重**:{@code throttle:10,1} 与 {@code throttle:1000,60}
     * 是两条不同的规则,叠加是合法诉求。
     */
    private List<Entry> dedupe(List<Entry> entries) {
        var out = new ArrayList<Entry>(entries.size());
        var seen = new LinkedHashSet<Class<?>>();

        for (Entry e : entries) {
            if (!e.params().isEmpty()) {
                out.add(e);
                continue;
            }
            if (seen.add(e.type())) out.add(e);
        }
        return out;
    }

    /**
     * 按 {@link Kernel#priority()} 重排。
     *
     * <p>做法:把有优先级的挑出来按优先级排,没有的保持原顺序,
     * 然后把有优先级的**插回它们原先出现的最早位置**。这样
     * 「优先级只约束彼此的相对顺序」,不会把一个高优先级中间件
     * 莫名提到全局中间件前面去。
     */
    private List<Entry> sortByPriority(List<Entry> entries) {
        var priority = kernel.middlewarePriority();
        if (priority.isEmpty()) return entries;

        var prioritized = entries.stream()
                .filter(e -> priority.contains(e.type()))
                .sorted(java.util.Comparator.comparingInt(e -> priority.indexOf(e.type())))
                .toList();
        if (prioritized.isEmpty()) return entries;

        var out = new ArrayList<Entry>(entries.size());
        int next = 0;
        for (Entry e : entries) {
            if (priority.contains(e.type())) {
                out.add(prioritized.get(next++));
            } else {
                out.add(e);
            }
        }
        return out;
    }

    // ================= 实例化 =================

    private List<Middleware> instantiate(List<Entry> entries) {
        var out = new ArrayList<Middleware>(entries.size());
        for (Entry e : entries) {
            out.add(e.params().isEmpty() ? sharedInstance(e.type()) : configured(e));
        }
        return List.copyOf(out);
    }

    private Middleware sharedInstance(Class<? extends Middleware> type) {
        return shared.computeIfAbsent(type, this::createAny);
    }

    /** {@code Class<? extends Middleware>} 的通配符与 {@code Factory} 的 {@code <T>} 对不上,在此收口 */
    @SuppressWarnings("unchecked")
    private Middleware createAny(Class<?> type) {
        return factory.create((Class<Middleware>) type);
    }

    /**
     * 带参数的中间件**每次新建实例**。
     *
     * <p>{@code configure()} 改的是实例状态。若复用单例,
     * {@code throttle:10} 的路由会把限额写进那个共享实例,
     * 于是 {@code throttle:1000} 的路由也变成 10 —— 而且这个串台
     * 取决于两条路由谁先被访问,极难复现。
     */
    private Middleware configured(Entry e) {
        Middleware instance = createAny(e.type());
        if (!(instance instanceof Middleware.Parameterized p)) {
            throw new IllegalStateException("""
                    中间件 %s 收到了参数 %s,但它没有实现 Middleware.Parameterized。
                      要支持 "%s:参数" 的写法,请实现:
                        public class %s implements Middleware.Parameterized {
                            public void configure(String... params) { ... }
                            public Response handle(Request req, Function<Request, Response> next) { ... }
                        }"""
                    .formatted(e.type().getSimpleName(), e.params(),
                               e.type().getSimpleName(), e.type().getSimpleName()));
        }
        p.configure(e.params().toArray(String[]::new));
        return p;
    }

    /** 把容器适配成 {@link Factory} */
    public static Factory fromContainer(Function<Class<?>, Object> resolver) {
        return new Factory() {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends Middleware> T create(Class<T> type) {
                return (T) resolver.apply(type);
            }
        };
    }
}
