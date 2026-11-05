package io.jaravel.support;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 游标分页的一页(docs/12 §2.1 的第三种)。
 *
 * <pre>{@code
 * Cursored<Post> page = Post.query().orderByDesc(Post_.createdAt).cursorPaginate(15);
 * page.nextCursor()      // 下一页的游标,没有下一页时是 null
 * page.toMap()           // 与 Laravel 的 CursorPaginator 同构的 JSON
 * }</pre>
 *
 * <h2>★ 它与 {@link Paginated} 的区别不只是「少一个 total」</h2>
 * <p>游标分页**没有页码**,也**不可能有** `last_page`:
 * 它不知道自己在第几页(那需要数过前面所有行,正是它要避免的事)。
 *
 * <p>所以 JSON 形状也不同 —— Laravel 的 `CursorPaginator` 输出
 * {@code next_cursor} / {@code prev_cursor} 而不是 {@code links.next}。
 * 硬套成 {@link Paginated} 的形状会让前端看到一个永远是 1 的
 * {@code current_page},而那是在说谎。
 *
 * <h2>为什么没有「上一页」的数据,却有 prevCursor</h2>
 * <p>{@code prevCursor} 是**本页第一行**的位置 + 反方向。客户端带着它回来,
 * 服务端查「在这一行之前的 N 条」然后倒序 —— 这样「往回翻」不需要
 * 记住走过的路径。
 */
public final class Cursored<T> implements Iterable<T> {

    private final List<T> items;
    private final int perPage;
    private final Cursor nextCursor;
    private final Cursor prevCursor;

    private String path;
    private Map<String, String> query = Map.of();

    private Cursored(List<T> items, int perPage, Cursor nextCursor, Cursor prevCursor) {
        if (perPage <= 0) {
            throw new IllegalArgumentException("perPage 必须大于 0,收到 " + perPage);
        }
        this.items = List.copyOf(items);
        this.perPage = perPage;
        this.nextCursor = nextCursor;
        this.prevCursor = prevCursor;
    }

    public static <T> Cursored<T> of(List<T> items, int perPage,
                                     Cursor nextCursor, Cursor prevCursor) {
        return new Cursored<>(items, perPage, nextCursor, prevCursor);
    }

    public static <T> Cursored<T> empty(int perPage) {
        return new Cursored<>(List.of(), perPage, null, null);
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

    public int perPage() {
        return perPage;
    }

    public <R> Cursored<R> map(Function<? super T, ? extends R> mapper) {
        var mapped = new ArrayList<R>(items.size());
        for (T item : items) mapped.add(mapper.apply(item));

        var out = new Cursored<R>(mapped, perPage, nextCursor, prevCursor);
        out.path = path;
        out.query = query;
        return out;
    }

    // ================= 游标 =================

    /** 下一页的游标(编码后);没有下一页时 null */
    public String nextCursor() {
        return nextCursor == null ? null : nextCursor.encode();
    }

    /** 上一页的游标(编码后);本页是第一页时 null */
    public String prevCursor() {
        return prevCursor == null ? null : prevCursor.encode();
    }

    public boolean hasMorePages() {
        return nextCursor != null;
    }

    // ================= 链接 =================

    public Cursored<T> withPath(String basePath) {
        this.path = basePath;
        return this;
    }

    public Cursored<T> withQuery(Map<String, String> parameters) {
        this.query = parameters == null ? Map.of() : Map.copyOf(parameters);
        return this;
    }

    /** 带上某个游标的 URL;没有 path 或没有那个游标时是 null */
    public String url(String cursor) {
        if (path == null || cursor == null) return null;

        var sb = new StringBuilder(path);
        sb.append(path.contains("?") ? '&' : '?');
        query.forEach((k, v) -> sb.append(encode(k)).append('=').append(encode(v)).append('&'));
        return sb.append("cursor=").append(encode(cursor)).toString();
    }

    private static String encode(String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ================= JSON =================

    /**
     * 与 Laravel 的 {@code CursorPaginator} 同构。
     *
     * <p>**刻意不带 {@code total} / {@code last_page} / {@code current_page}** ——
     * 游标分页不知道这些,输出一个编出来的值会让前端渲染出
     * 一堆点不动的页码(与 {@code simplePaginate} 同一条理由)。
     */
    public Map<String, Object> toMap() {
        var out = new LinkedHashMap<String, Object>();
        out.put("data", items);
        out.put("path", path);
        out.put("per_page", perPage);
        out.put("next_cursor", nextCursor());
        out.put("next_page_url", url(nextCursor()));
        out.put("prev_cursor", prevCursor());
        out.put("prev_page_url", url(prevCursor()));
        return out;
    }

    @Override
    public String toString() {
        return "Cursored{本页 " + items.size() + " 条,每页 " + perPage
                + (hasMorePages() ? ",还有下一页}" : ",已到末页}");
    }

    // ================= resolver =================

    private static volatile java.util.function.Supplier<String> cursorResolver;

    /**
     * 「当前游标」从哪来 —— 与 {@link Paginated#pageResolver} 同一个结构。
     *
     * <p>HTTP 场景下是 {@code ?cursor=…};不在请求里时没有游标(第一页),
     * 而那在队列任务里分批处理正是对的。
     */
    public static void cursorResolver(java.util.function.Supplier<String> resolver) {
        cursorResolver = resolver;
    }

    public static void reset() {
        cursorResolver = null;
    }

    /** 当前请求里的游标;没有时返回 null */
    public static String resolveCursor() {
        var resolver = cursorResolver;
        if (resolver == null) return null;
        try {
            String value = resolver.get();
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
