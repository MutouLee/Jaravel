package io.jaravel.eloquent;

import java.util.Optional;
import java.util.function.UnaryOperator;

import io.jaravel.eloquent.meta.ModelMeta;
import io.jaravel.eloquent.meta.ModelRegistry;
import io.jaravel.eloquent.query.Query;

/**
 * 按路由参数值查出模型 —— 隐式模型绑定的 Eloquent 一侧(docs/03 §3.3)。
 *
 * <pre>{@code
 * r.get("/posts/{post}", PostController::show);
 *
 * public View show(Post post) {        // 自动查出来
 *     return View.of("posts.show").with("post", post);
 * }
 * }</pre>
 *
 * <h2>为什么这个类在 jaravel-eloquent 而不是路由里</h2>
 * <p>{@code jaravel-routing} 与 {@code jaravel-eloquent} 之间**没有依赖关系**
 * (前者 → http → core,后者 → database → core)。这是刻意的:
 * 不用数据库的应用不该为了路由而拉进 Eloquent。
 *
 * <p>于是绑定被拆成两半:本类负责「给定类型与值,查出模型」,
 * 而「哪个路由参数该绑哪个类型」由 starter 的 {@code SubstituteBindings}
 * 决定 —— 它是唯一同时看得见两边的地方。
 *
 * <h2>找不到就抛,而不是返回 null</h2>
 * <p>绑定失败等价于「这个 URL 指向的资源不存在」,应该是 404。
 * 返回 null 会让控制器拿到一个 null 参数,然后在某一行 NPE ——
 * 而那一行离真正的原因很远。
 */
public final class RouteBinder {

    private RouteBinder() {
    }

    /**
     * 按路由值解析一个模型。
     *
     * @param modelType 控制器参数声明的模型类型
     * @param value     路由参数的原始字符串
     * @return 查到的模型
     * @throws ModelNotFoundException 查不到
     */
    public static <M extends Model<M>> M resolve(Class<M> modelType, String value) {
        return tryResolve(modelType, value).orElseThrow(() ->
                new ModelNotFoundException(modelType, value));
    }

    /** 查不到返回空 —— 给「可选绑定」({@code {post?}})用 */
    public static <M extends Model<M>> Optional<M> tryResolve(Class<M> modelType,
                                                              String value) {
        if (value == null || value.isBlank()) return Optional.empty();

        ModelMeta<M> meta = ModelRegistry.of(modelType);
        Query<M> query = Query.of(meta);

        // 模型可以声明「我按哪一列绑定」与「绑定时加什么作用域」。
        // 后者的用途很实际:/posts/{post} 不该能打开未发布的文章,
        // 而在每个控制器里重复写 .published() 迟早会漏掉一处
        var hooks = RouteBindingHooks.of(modelType);

        String keyColumn = hooks.keyName() != null ? hooks.keyName() : meta.keyName();

        // 钩子是反射读出来的,签名上只能是 Query<?>。这里的 cast 安全:
        // 钩子来自 modelType 自己声明的 resolveRouteBinding(Query<Post>),
        // 传进去的 query 也是同一个 modelType 的
        @SuppressWarnings("unchecked")
        Query<M> scoped = hooks.scope() == null
                ? query
                : (Query<M>) hooks.scope().apply(query);

        return scoped.whereNamed(keyColumn, coerce(meta, keyColumn, value)).first();
    }

    /**
     * 字符串 → 列的实际类型。
     *
     * <p>必须做:主键通常是 {@code bigint},而路由参数永远是字符串。
     * 不转的话 SQLite 之外的数据库会在
     * {@code where id = '42'} 上**不走索引**(类型不匹配导致隐式转换,
     * 而 MySQL 的隐式转换会放弃索引)—— 一个全表扫描的详情页。
     */
    private static <M> Object coerce(ModelMeta<M> meta, String column, String value) {
        return meta.column(column)
                .map(col -> (Object) io.jaravel.support.Cast.to(value, col.type()))
                .orElse(value);
    }

    /**
     * 模型可选的两个绑定钩子。
     *
     * <p>用反射读而不是在 {@link Model} 上定义可覆盖方法,原因是
     * {@code Model} 的子类是 APT 生成的中间类({@code PostBase}),
     * 用户的类继承它 —— 在 {@code Model} 上加方法会与生成代码的
     * 协变覆盖规则缠在一起。约定方法名更简单,且不污染所有模型的 API。
     *
     * <pre>{@code
     * public class Post extends PostBase {
     *     public static String routeKeyName() { return "slug"; }
     *     public static Query<Post> resolveRouteBinding(Query<Post> q) {
     *         return q.published();
     *     }
     * }
     * }</pre>
     */
    record RouteBindingHooks(String keyName, UnaryOperator<Query<?>> scope) {

        private static final java.util.Map<Class<?>, RouteBindingHooks> CACHE =
                new java.util.concurrent.ConcurrentHashMap<>();

        static RouteBindingHooks of(Class<?> modelType) {
            return CACHE.computeIfAbsent(modelType, RouteBindingHooks::read);
        }

        private static RouteBindingHooks read(Class<?> modelType) {
            return new RouteBindingHooks(readKeyName(modelType), readScope(modelType));
        }

        private static String readKeyName(Class<?> modelType) {
            try {
                var method = modelType.getDeclaredMethod("routeKeyName");
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) return null;
                method.setAccessible(true);
                return (String) method.invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        private static UnaryOperator<Query<?>> readScope(Class<?> modelType) {
            try {
                var method = modelType.getDeclaredMethod("resolveRouteBinding", Query.class);
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) return null;
                method.setAccessible(true);

                return query -> {
                    try {
                        return (Query<?>) method.invoke(null, query);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(
                                modelType.getSimpleName()
                              + ".resolveRouteBinding 调用失败:" + e.getMessage(), e);
                    }
                };
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * 类型擦除友好的入口。
     *
     * <p>调用方拿到的是 {@code Class<?>}(来自
     * {@code Route.parameterTypes()}),而 {@code M extends Model<M>}
     * 这个自引用边界无法由通配符捕获满足。把 unchecked 收在这里一处,
     * 而不是让每个调用点各写一个 {@code @SuppressWarnings} 的强转方法。
     *
     * <p>安全性由 {@link #isBindable} 保证:调用方必须先确认它是
     * 已注册的模型类型。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object resolveAny(Class<?> modelType, String value) {
        if (!isBindable(modelType)) {
            throw new IllegalArgumentException("""
                    %s 不是已注册的 Eloquent 模型,无法做路由绑定。
                      检查它有没有 @Model 注解,以及注解处理是否跑过
                      (target/generated-sources 下应有 %s_.java)。"""
                    .formatted(modelType.getName(), modelType.getSimpleName()));
        }
        return resolve((Class) modelType, value);
    }

    /** 这个类型能不能做隐式绑定 —— 给 starter 的参数绑定器判断 */
    public static boolean isBindable(Class<?> type) {
        return Model.class.isAssignableFrom(type) && ModelRegistry.isRegistered(type);
    }
}
