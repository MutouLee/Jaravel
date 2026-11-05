package io.jaravel.api;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jaravel.http.Request;

/**
 * 一批资源(docs/12 §1.2)。
 *
 * <pre>{@code
 * // 默认:逐个包装
 * return PostResource.collection(posts, PostResource::new);
 *
 * // 要集合级元数据时继承它
 * public class PostCollection extends ResourceCollection<Post, PostResource> {
 *     public PostCollection(List<PostResource> items) { super(items); }
 *
 *     @Override public Map<String, Object> with(Request req) {
 *         return Map.of("stats", Map.of("published", countPublished()));
 *     }
 * }
 * }</pre>
 *
 * <h2>★ 它不是 {@code JsonResource<List<M>>}</h2>
 * <p>看起来可以那样复用,但两者的输出形状不同:资源的 {@code data} 是一个对象,
 * 集合的 {@code data} 是一个**数组**。硬塞进同一个类要在
 * {@code toArray} 里按「model 是不是 List」分支 —— 而那种按运行时类型
 * 分叉的代码正是泛型要消掉的东西。
 */
public class ResourceCollection<M, R extends JsonResource<M>> implements Iterable<R> {

    protected final List<R> items;

    public ResourceCollection(List<R> items) {
        this.items = List.copyOf(items);
    }

    public List<R> items() {
        return items;
    }

    @Override
    public Iterator<R> iterator() {
        return items.iterator();
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 集合级的附加信息。
     *
     * <p>与 {@link JsonResource#with} 一样只在最外层生效。
     */
    public Map<String, Object> with(Request request) {
        return Map.of();
    }

    /** 每个元素的字段(不带包裹)—— 嵌套位置用这个 */
    public List<Map<String, Object>> toArray(Request request) {
        var out = new ArrayList<Map<String, Object>>(items.size());
        for (R item : items) out.add(item.toArray(request));
        return out;
    }

    /**
     * 最外层的完整输出 —— **要么是对象,要么是数组**。
     *
     * <p>返回 {@code Object} 不是偷懒:关掉包裹({@link JsonResource#withoutWrapping})
     * 又没有集合级元数据时,这个接口的响应体就是一个裸数组
     * {@code [{...},{...}]},而那是 {@code withoutWrapping} 的全部意义。
     *
     * <p>第一版让它固定返回 {@code Map},于是裸数组那种情况只能塞进一个
     * 假键({@code __bare__})再让序列化器认出来 —— 一个会出现在
     * 公开方法返回值里的内部约定,下一个人照它写就会把那个键打进 JSON。
     * 返回类型如实说出「两种形状」更好。
     *
     * <p>包裹键取自 {@link JsonResource#wrapKey()} —— 与单个资源同一个开关,
     * 否则一个项目里会出现「列表包了、详情没包」这种不一致,
     * 而前端要为两个接口写两套解包。
     */
    public Object toRootBody(Request request) {
        List<Map<String, Object>> body = toArray(request);
        Map<String, Object> extra = with(request);

        String key = JsonResource.wrapKey();
        if (key == null) {
            if (extra.isEmpty()) return body;

            // 不包裹但有元数据:元数据得挂在某个键下,而数组挂不住键 ——
            // 这时只能退回一个对象,并且用 data 作为那个数组的键
            var merged = new LinkedHashMap<String, Object>(extra);
            merged.put("data", body);
            return merged;
        }

        var out = new LinkedHashMap<String, Object>();
        out.put(key, body);
        out.putAll(extra);
        return out;
    }
}
