package io.jaravel.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.relations.Rel;
import io.jaravel.eloquent.relations.Rel1;

/**
 * 资源输出的字段构建器(docs/12 §1.1)。
 *
 * <pre>{@code
 * return map(req)
 *     .put("id", model.id)
 *     .put("name", model.name)
 *     .putWhen(req.user().filter(User::isAdmin).isPresent(),
 *              "internal_note", () -> model.internalNote)
 *     .putWhenLoaded("posts", model.posts(), PostResource::collection)
 *     .build();
 * }</pre>
 *
 * <h2>★ 为什么是构建器而不是直接写 Map</h2>
 * <p>因为**条件字段**。直接写 {@code Map.of(...)} 的话,
 * 「仅管理员可见」这类字段只能靠 {@code if} 把 Map 拆成几段拼起来,
 * 而那会让一个资源的输出形状散在三四个分支里 —— 读的人没法一眼看出
 * 「这个接口到底返回哪些字段」。
 *
 * <p>另外 {@code Map.of} 不接受 null 值(直接抛 NPE),
 * 而 API 字段为 null 是完全正常的事。
 *
 * <h2>与 docs/12 的一处偏差:没有 {@code Json.map()}</h2>
 * <p>文档写的是 {@code Json.map().put(...)}。实现时改成
 * {@code JsonResource.map(request)} —— 一个**父类上的静态方法**,
 * 于是资源里直接写 {@code map(req)},不用 import。
 *
 * <p>理由不只是少一行 import:框架里已经有两个用户会碰到的 {@code Json}
 * ({@code io.jaravel.eloquent.cast.Json} 用在 {@code @Cast(Json.class)}、
 * 以及缓存里那个)。再加第三个同名类,出问题的时候
 * 「我 import 的是哪个 Json」会是个真实的困惑。
 */
public final class Fields {

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final io.jaravel.http.Request request;

    Fields(io.jaravel.http.Request request) {
        this.request = request;
    }

    /**
     * 一个字段。
     *
     * <p>用 {@code LinkedHashMap}:输出字段的顺序与声明顺序一致。
     * 哈希序会让「同一个接口两次响应的字段顺序不同」——
     * 而那会把契约测试与响应快照的 diff 变成噪音。
     */
    public Fields put(String name, Object value) {
        values.put(name, resolve(value));
        return this;
    }

    /**
     * ★ 嵌套的资源在这里就被摊成**纯数据**(Map / List / 标量)。
     *
     * <h2>为什么不把资源对象原样放进去</h2>
     * <p>原样放也能工作 —— 序列化器认识资源,到了那一层会自己展开。
     * 第一版就是那样,而测试当场撞到了它的代价:
     * {@code toArray(req)} 返回的 Map 里躺着一个 {@code UserResource},
     * 于是那个方法的返回值**不是 JSON 的形状**,只是一个中间态。
     *
     * <p>后果不只是测试难写:任何不经过 Jackson 的用途都坏了 ——
     * 把响应体写进日志、存成快照做契约测试、在命令里打印一个资源。
     * 而「这个方法返回这个接口的形状」是资源类唯一该有的契约。
     *
     * <p>代价是 {@code map()} 要知道当前 {@code Request}(嵌套资源的
     * {@code toArray} 需要它),所以它变成了 {@code map(request)}。
     * 多传一个词,换来一个自足的返回值。
     */
    private Object resolve(Object value) {
        if (value instanceof JsonResource<?> resource) {
            return resource.toArray(request);
        }
        if (value instanceof ResourceCollection<?, ?> collection) {
            return collection.toArray(request);
        }
        if (value instanceof io.jaravel.support.Paginated<?> page) {
            // 分页里的元素也可能是资源 —— data 要单独过一遍
            var shape = new LinkedHashMap<>(page.toMap());
            shape.put("data", resolve(page.items()));
            return shape;
        }
        if (value instanceof java.util.List<?> list) {
            var out = new java.util.ArrayList<Object>(list.size());
            for (Object item : list) out.add(resolve(item));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<Object, Object>();
            map.forEach((k, v) -> out.put(k, resolve(v)));
            return out;
        }
        return value;
    }

    /**
     * 条件字段。
     *
     * <p>⚠ 值是 {@code Supplier} 而不是直接的值 —— 这不是风格问题:
     * 条件字段的取值本身常常有代价或者有前提
     * ({@code model.decryptSsn()}、{@code model.owner().email}),
     * 而直接传值会让它**无论条件成不成立都被求值一次**。
     * 那种「只给管理员看」的字段,恰恰是不该为普通用户去算的。
     */
    public Fields putWhen(boolean condition, String name, Supplier<?> value) {
        if (condition) values.put(name, resolve(value.get()));
        return this;
    }

    /**
     * ★ 只在关联**已经预加载**时才输出 —— 这是整个资源层最重要的一条规则。
     *
     * <p>docs/12 §1.1:「资源层绝不触发懒加载」。配合 docs/06 §4.3 的
     * 懒加载禁止,API 层的 N+1 从架构上被消除。
     *
     * <p>不这样做的后果很具体:一个列表接口返回 50 条,而资源里写了
     * {@code .put("author", new AuthorResource(model.author().get()))} ——
     * 于是 51 条查询。那个 bug 在开发期的三条测试数据上**完全看不出来**,
     * 它只在生产的分页列表上出现。
     *
     * <p>而「关联没加载就跳过这个字段」是 Laravel 的既有约定
     * ({@code whenLoaded}):前端拿不到 {@code author} 字段,
     * 说明这个接口没有预加载它 —— 那是一个**可见的**提示,
     * 而不是一次看不见的性能事故。
     */
    public <T extends Model<T>> Fields putWhenLoaded(
            String name, Rel<T> relation, Function<java.util.List<T>, ?> mapper) {

        if (relation != null && relation.isLoaded()) {
            values.put(name, resolve(mapper.apply(relation.getLoaded())));
        }
        return this;
    }

    /**
     * 单值关联的版本({@code belongsTo} / {@code hasOne})—— 它们的访问器是 {@code Rel1}。
     *
     * <p>关联已加载但**是空的**(外键为 null)时输出 {@code null} ——
     * 而不是跳过这个字段。两件事必须分开:
     * 「没预加载」是接口的实现细节(跳过),
     * 「加载了但没有」是数据的事实(null)。
     * 前端看到 {@code author: null} 知道这篇文章没有作者;
     * 看不到 {@code author} 这个键只能去问后端。
     */
    public <T extends Model<T>> Fields putWhenLoadedOne(
            String name, Rel1<T> relation, Function<T, ?> mapper) {

        if (relation != null && relation.isLoaded()) {
            // ⚠ 用 getLoaded() 而不是 get():后者在没加载时**会去查库**,
            // 而这正是这整组方法要避免的事。这里虽然已经判过 isLoaded(),
            // 仍然用前者 —— 让「绝不触发查询」这件事在代码里是自明的,
            // 而不依赖上一行的判断还在
            values.put(name, resolve(relation.getLoaded().map(mapper).orElse(null)));
        }
        return this;
    }

    /**
     * 合并一组字段(docs/12 §1.1 的 {@code mergeWhen})。
     *
     * <p>同样用 {@code Supplier}:条件不成立时那个 Map 根本不构造。
     */
    public Fields mergeWhen(boolean condition, Supplier<Map<String, ?>> extra) {
        if (condition) extra.get().forEach((k, v) -> values.put(k, resolve(v)));
        return this;
    }

    public Fields merge(Map<String, ?> extra) {
        if (extra != null) extra.forEach((k, v) -> values.put(k, resolve(v)));
        return this;
    }

    /** 没有这个键时才放 —— 用于「默认值」语义 */
    public Fields putIfAbsent(String name, Object value) {
        values.putIfAbsent(name, resolve(value));
        return this;
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(values);
    }
}
