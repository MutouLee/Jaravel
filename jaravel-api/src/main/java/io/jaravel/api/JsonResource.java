package io.jaravel.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.http.Request;
import io.jaravel.support.Paginated;

/**
 * 控制输出形状(docs/12 §1)。
 *
 * <pre>{@code
 * public class PostResource extends JsonResource<Post> {
 *
 *     public PostResource(Post post) { super(post); }
 *
 *     @Override
 *     public Map<String, Object> toArray(Request req) {
 *         return map()
 *             .put("id", model.id)
 *             .put("title", model.title)
 *             .putWhenLoadedOne("author", model.author(), AuthorResource::new)
 *             .putWhenLoaded("comments", model.comments(), CommentResource::collection)
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * <h2>两条输出路径,选哪条有明确指引(docs/12 §1.4)</h2>
 * <ul>
 *   <li>形状固定、没有条件字段 → 直接用 {@code record} + {@code selectInto}
 *       (只查需要的列,性能更好);</li>
 *   <li>要条件字段、关联嵌套、按权限决定输出 → 用 {@code JsonResource}。</li>
 * </ul>
 * <p>这是相对 Laravel 的一个改进:PHP 没有 record,所以 Laravel 只有
 * Resource 一条路。
 *
 * <h2>★ {@code data} 包裹只在**最外层**加一次</h2>
 * <p>默认输出 {@code {"data": {...}}}(与 Laravel 一致)。
 * 但一个资源出现在分页的 {@code data} 数组里时**不能**再包一层 ——
 * 否则会变成 {@code {"data":[{"data":{...}},…]}}。
 *
 * <p>Laravel 的做法是让分页器主动拆包。这里换了个更简单的判据:
 * 序列化器只在 **JSON 的根位置**加包裹(见 starter 里那个
 * {@code JsonResourceSerializer})。于是「谁该包」这件事不需要
 * 每个容器类型各自记得去拆。
 *
 * <p>全局关掉:{@link #withoutWrapping()}。
 */
public abstract class JsonResource<M> {

    /** 子类直接用 —— 名字短,因为它在每个 toArray 里都出现 */
    protected final M model;

    protected JsonResource(M model) {
        this.model = model;
    }

    public M model() {
        return model;
    }

    /** 这个资源输出哪些字段 */
    public abstract Map<String, Object> toArray(Request request);

    /**
     * 顶层附加信息(docs/12 §1.1 的 {@code with})。
     *
     * <p>只在最外层生效 —— 与 {@code data} 包裹同一个位置。
     * 嵌在别的资源里时它不输出:一个 {@code meta.version} 出现在
     * 数组的每个元素上毫无意义。
     */
    public Map<String, Object> with(Request request) {
        return Map.of();
    }

    /**
     * 字段构建器 —— 见 {@link Fields}。
     *
     * <p>要传 {@code request}:嵌套的资源要用它摊成纯数据
     * (理由写在 {@code Fields.resolve} 上)。
     */
    protected static Fields map(Request request) {
        return new Fields(request);
    }

    // ================= 集合 =================

    /**
     * 一批模型 → 一批资源。
     *
     * <pre>{@code
     * return PostResource.collection(posts, PostResource::new);
     * }</pre>
     *
     * <h2>为什么要显式传构造器</h2>
     * <p>docs/12 §1.2 写的是 {@code UserResource.collection(users)} ——
     * 一个**静态方法继承**过来的调用。Java 的静态方法不是多态的:
     * {@code UserResource.collection} 实际调用的是基类那个方法,
     * 而基类**不知道**要 new 哪个子类。
     *
     * <p>反射地去找子类的单参构造器可以绕过去,但代价是:
     * 构造器签名不对时要到运行时才炸,而且打 native 镜像要配反射。
     * 显式传一个方法引用多两个词,换来编译期检查 ——
     * 这是 Java 与 PHP 的一处真实差异,不假装没有。
     */
    public static <M, R extends JsonResource<M>> ResourceCollection<M, R> collection(
            Iterable<M> models, Function<M, R> factory) {

        var resources = new ArrayList<R>();
        for (M model : models) resources.add(factory.apply(model));
        return new ResourceCollection<>(resources);
    }

    /**
     * 一页模型 → 一页资源(docs/12 §2.3)。
     *
     * <p>分页信息({@code links} / {@code meta})一字不差地跟过去,
     * 只有 {@code data} 变成了资源 —— 那是 {@link Paginated#map} 保证的。
     */
    public static <M, R extends JsonResource<M>> Paginated<R> collection(
            Paginated<M> page, Function<M, R> factory) {
        return page.map(factory);
    }

    // ================= 包裹 =================

    private static volatile String wrapKey = "data";

    /** 全局关掉 {@code data} 包裹(docs/12 §1.3) */
    public static void withoutWrapping() {
        wrapKey = null;
    }

    /** 换一个包裹键 —— 有些团队用 {@code result} */
    public static void wrapWith(String key) {
        wrapKey = key;
    }

    public static void resetWrapping() {
        wrapKey = "data";
    }

    public static String wrapKey() {
        return wrapKey;
    }

    /**
     * 最外层的完整输出:包裹 + {@code with}。
     *
     * <p>由序列化器在根位置调用;嵌套位置调用的是 {@link #toArray}。
     */
    public Map<String, Object> toRootBody(Request request) {
        Map<String, Object> body = toArray(request);
        Map<String, Object> extra = with(request);

        String key = wrapKey;
        if (key == null) {
            // 不包裹时 with 的字段只能平铺进去 —— 而那可能与资源自己的
            // 字段撞名。撞了就让资源的字段赢:它是这个接口的主体
            if (extra.isEmpty()) return body;
            var merged = new LinkedHashMap<String, Object>(extra);
            merged.putAll(body);
            return merged;
        }

        var out = new LinkedHashMap<String, Object>();
        out.put(key, body);
        out.putAll(extra);
        return out;
    }

    /** 嵌套位置用的:一批资源 → 一批 Map */
    static List<Map<String, Object>> unwrapAll(List<? extends JsonResource<?>> resources,
                                               Request request) {
        var out = new ArrayList<Map<String, Object>>(resources.size());
        for (JsonResource<?> resource : resources) out.add(resource.toArray(request));
        return out;
    }
}
