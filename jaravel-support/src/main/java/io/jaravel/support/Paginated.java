package io.jaravel.support;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 一页数据 + 它的位置(docs/12 §2)。
 *
 * <pre>{@code
 * Paginated<User> page = User.query().paginate(15);
 * page.items()        // 这一页的 15 条
 * page.lastPage()     // 一共多少页
 * page.toMap()        // docs/12 §2.2 那个与 Laravel 完全一致的 JSON 形状
 * }</pre>
 *
 * <h2>★ 为什么它在 jaravel-support 而不是 http 或 eloquent</h2>
 * <p>三个地方都要用它:{@code Query.paginate} 造它、HTTP 层序列化它、
 * 视图层渲染分页链接。放在 support(零依赖)是唯一不会把这三者互相绑住的位置。
 *
 * <p>代价是它**不知道当前请求** —— 而 {@code links} 里那几个 URL 需要请求路径。
 * 解决办法与 Laravel 一样:两个**静态 resolver**,由 HTTP 层在启动时装上
 * (见 {@link #pageResolver} / {@link #pathResolver})。
 * 没装的时候 {@code paginate(15)} 仍然可用,只是当成第 1 页、
 * 并且 {@code links} 里是 null —— 在一个队列任务或测试里那正是对的。
 *
 * <h2>一个类而不是两个(Laravel 有两个)</h2>
 * <p>Laravel 分 {@code LengthAwarePaginator} 与 {@code Paginator}
 * (后者不查总数)。这里是一个类加一个**可空的 total**,
 * 因为 docs/12 §2.1 给 {@code paginate} 与 {@code simplePaginate}
 * 写的返回类型都是 {@code Paginated<User>} —— 两个类会让那段文档撒谎,
 * 而且调用方要为「少一个 total」换一次类型。
 *
 * <p>形状上的区别落在 {@link #toMap()} 里:没有 total 时
 * **不输出** {@code total} / {@code last_page} / {@code links.last} ——
 * 而不是输出一个猜的值。一个编出来的 last_page 会让前端的分页组件
 * 渲染出一堆点不动的页码。
 */
public final class Paginated<T> implements Iterable<T> {

    private static final String PAGE_PARAM = "page";

    private final List<T> items;
    private final int currentPage;
    private final int perPage;
    /** null = 没查总数(simplePaginate) */
    private final Long total;
    /** 只在没有 total 时有意义 */
    private final boolean hasMore;

    private String path;
    private Map<String, String> query = Map.of();

    private Paginated(List<T> items, int currentPage, int perPage, Long total, boolean hasMore) {
        if (perPage <= 0) {
            // 每页 0 条会让调用方的循环永远跑不完,而症状出现在很远的地方
            throw new IllegalArgumentException("perPage 必须大于 0,收到 " + perPage);
        }
        this.items = List.copyOf(items);
        this.currentPage = Math.max(1, currentPage);
        this.perPage = perPage;
        this.total = total;
        this.hasMore = hasMore;
    }

    /** 带总数的一页 */
    public static <T> Paginated<T> of(List<T> items, int currentPage, int perPage, long total) {
        return new Paginated<>(items, currentPage, perPage, total,
                (long) currentPage * perPage < total);
    }

    /**
     * 不查总数的一页。
     *
     * <p>{@code hasMore} 的来历见 {@code Query.simplePaginate}:多取一条,
     * 取到了就说明还有下一页。那是「不查总数」的全部办法 ——
     * 而 {@code count(*)} 在大表上是一次全扫。
     */
    public static <T> Paginated<T> simple(List<T> items, int currentPage, int perPage,
                                          boolean hasMore) {
        return new Paginated<>(items, currentPage, perPage, null, hasMore);
    }

    public static <T> Paginated<T> empty(int perPage) {
        return of(List.of(), 1, perPage, 0);
    }

    // ================= 数据 =================

    public List<T> items() {
        return items;
    }

    public Coll<T> collect() {
        return Coll.wrap(items);
    }

    @Override
    public Iterator<T> iterator() {
        return items.iterator();
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 换一种元素类型,保留全部分页信息。
     *
     * <p>这是 docs/12 §2.3「分页 + 资源」的接缝:
     * {@code page.map(UserResource::new)} 之后 {@code links} / {@code meta}
     * 一字不差,而 {@code data} 变成了资源。
     */
    public <R> Paginated<R> map(Function<? super T, ? extends R> mapper) {
        var mapped = new ArrayList<R>(items.size());
        for (T item : items) mapped.add(mapper.apply(item));

        var out = new Paginated<R>(mapped, currentPage, perPage, total, hasMore);
        out.path = path;
        out.query = query;
        return out;
    }

    // ================= 位置 =================

    public int currentPage() {
        return currentPage;
    }

    public int perPage() {
        return perPage;
    }

    /** 总条数 —— {@code simplePaginate} 下是 empty */
    public java.util.Optional<Long> total() {
        return java.util.Optional.ofNullable(total);
    }

    /** 总页数 —— 没查总数时是 empty */
    public java.util.Optional<Integer> lastPage() {
        if (total == null) return java.util.Optional.empty();
        return java.util.Optional.of((int) Math.max(1, Math.ceil((double) total / perPage)));
    }

    public boolean hasMorePages() {
        return hasMore;
    }

    /** 这一页第一条在全集里的序号(从 1 起);空页是 empty */
    public java.util.Optional<Integer> from() {
        return items.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of((currentPage - 1) * perPage + 1);
    }

    public java.util.Optional<Integer> to() {
        return items.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of((currentPage - 1) * perPage + items.size());
    }

    // ================= 链接 =================

    /** 分页链接的基准路径(通常是当前请求的 URL) */
    public Paginated<T> withPath(String basePath) {
        this.path = basePath;
        return this;
    }

    /** 要保留在链接里的其它查询参数(过滤条件之类)—— 丢了它们翻页会把筛选条件弄丢 */
    public Paginated<T> withQuery(Map<String, String> parameters) {
        this.query = parameters == null ? Map.of() : Map.copyOf(parameters);
        return this;
    }

    public String path() {
        return path;
    }

    /** 第 N 页的 URL;没有 path 时是 null */
    public String url(int page) {
        if (path == null) return null;

        var sb = new StringBuilder(path);
        sb.append(path.contains("?") ? '&' : '?');
        query.forEach((k, v) -> sb.append(encode(k)).append('=').append(encode(v)).append('&'));
        return sb.append(PAGE_PARAM).append('=').append(Math.max(1, page)).toString();
    }

    private static String encode(String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ================= JSON =================

    /**
     * docs/12 §2.2 的那个形状 —— **与 Laravel 逐字段一致**。
     *
     * <p>一致是目的本身:前端的分页组件(Vue/React 那些现成的)读的就是
     * {@code meta.current_page} / {@code links.next} 这几个名字。
     * 少一个下划线就等于要用户自己写适配层。
     *
     * <p>用 {@code LinkedHashMap}:字段顺序稳定,于是
     * 「把响应存下来 diff」不会全是噪音(与 {@code route:list --json} 同一个理由)。
     */
    public Map<String, Object> toMap() {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("current_page", currentPage);
        meta.put("from", from().orElse(null));
        lastPage().ifPresent(last -> meta.put("last_page", last));
        meta.put("path", path);
        meta.put("per_page", perPage);
        meta.put("to", to().orElse(null));
        if (total != null) meta.put("total", total);

        var links = new LinkedHashMap<String, Object>();
        links.put("first", url(1));
        links.put("last", lastPage().map(this::url).orElse(null));
        links.put("prev", currentPage > 1 ? url(currentPage - 1) : null);
        links.put("next", hasMore ? url(currentPage + 1) : null);

        var out = new LinkedHashMap<String, Object>();
        out.put("data", items);
        out.put("links", links);
        out.put("meta", meta);
        return out;
    }

    @Override
    public String toString() {
        return "Paginated{第 " + currentPage + " 页,每页 " + perPage
                + ",本页 " + items.size() + " 条"
                + (total == null ? ",未查总数" : ",共 " + total + " 条") + "}";
    }

    // ================= resolver(由 HTTP 层装上) =================

    private static volatile IntSupplier pageResolver;
    private static volatile Supplier<String> pathResolver;
    private static volatile Supplier<Map<String, String>> queryResolver;

    /**
     * 「当前是第几页」从哪来。
     *
     * <h2>★ 为什么是一个静态 resolver,而不是参数</h2>
     * <p>{@code User.query().paginate(15)} 这一句里没有 {@code Request} ——
     * 而页码在 HTTP 场景下必然来自 {@code ?page=N}。让它变成参数
     * ({@code paginate(15, request)})会把**每一个**分页调用点都绑上 HTTP,
     * 包括那些不在请求里的(队列任务里导出报表、命令行里分批处理)。
     *
     * <p>Laravel 用的是同一个办法({@code Paginator::currentPageResolver})。
     * 代价是一处可变的全局状态 —— 所以它只在启动时装一次,
     * 并且 {@link #reset()} 给测试留了出口。
     *
     * <p>没装的时候当成第 1 页:那在队列任务与测试里正是对的,
     * 而且**不会静默出错** —— 分批处理拿到的是第一批,不是空。
     */
    public static void pageResolver(IntSupplier resolver) {
        pageResolver = resolver;
    }

    /** 分页链接的基准路径从哪来(通常是当前请求的 URL) */
    public static void pathResolver(Supplier<String> resolver) {
        pathResolver = resolver;
    }

    /** 要带进链接的其它查询参数从哪来 */
    public static void queryResolver(Supplier<Map<String, String>> resolver) {
        queryResolver = resolver;
    }

    /** 清掉三个 resolver —— 测试之间不该互相影响 */
    public static void reset() {
        pageResolver = null;
        pathResolver = null;
        queryResolver = null;
    }

    /** 当前页;没有 resolver 或者解析不出来时是 1 */
    public static int resolveCurrentPage() {
        IntSupplier resolver = pageResolver;
        if (resolver == null) return 1;
        try {
            return Math.max(1, resolver.getAsInt());
        } catch (RuntimeException e) {
            // ?page=abc 不该让整个请求炸掉 —— 当成第 1 页
            return 1;
        }
    }

    public static String resolvePath() {
        Supplier<String> resolver = pathResolver;
        if (resolver == null) return null;
        try {
            return resolver.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Map<String, String> resolveQuery() {
        Supplier<Map<String, String>> resolver = queryResolver;
        if (resolver == null) return Map.of();
        try {
            Map<String, String> resolved = resolver.get();
            return resolved == null ? Map.of() : resolved;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
