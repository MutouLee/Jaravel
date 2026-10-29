package io.jaravel.http;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import io.jaravel.core.context.ContextHolder;
import io.jaravel.core.context.OutsideRequestException;
import io.jaravel.core.context.ThreadLocalContextHolder;

/**
 * 请求级作用域(ADR-007 的落地)。
 *
 * <p>Laravel 能写 {@code Auth::user()} 而不传参,是因为 PHP 每请求一个独立进程。
 * Java 侧用这个作用域还原同一个心智模型:
 *
 * <ul>
 *   <li>请求级单例({@link #scoped}):同一请求内多次解析返回同一实例 ——
 *       数据库连接、已加载的用户、Eloquent 的 identity map 都依赖它;
 *   <li>请求结束回调({@link #onTerminate}):释放连接、flush 日志;
 *   <li>**绑定由 {@link ContextHolder} 保证退出即解绑**,不可能跨请求泄漏。
 * </ul>
 *
 * <p>配合 {@code spring.threads.virtual.enabled=true}(starter 默认开启):
 * 每请求一个虚拟线程 → 一个作用域,零污染,创建成本接近零。
 */
public final class RequestScope {

    private static final ContextHolder<RequestScope> HOLDER =
            new ThreadLocalContextHolder<>("RequestScope");

    private final Request request;
    private final Map<Class<?>, Object> instances = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final Deque<Runnable> terminators = new ArrayDeque<>();

    private RequestScope(Request request) {
        this.request = request;
    }

    // ---------- 绑定 ----------

    /** 由 HandlerAdapter 调用,整个请求处理包在这里面 */
    public static <R> R runIn(Request request, Callable<R> body) throws Exception {
        var scope = new RequestScope(request);
        try {
            return HOLDER.runIn(scope, body);
        } finally {
            scope.terminate();   // 一定执行,一定不泄漏
        }
    }

    public static RequestScope current() {
        return HOLDER.current().orElseThrow(() -> new OutsideRequestException("RequestScope.current()"));
    }

    public static Optional<RequestScope> tryCurrent() {
        return HOLDER.current();
    }

    public static boolean isActive() {
        return HOLDER.isBound();
    }

    /** 当前请求。不在请求中时抛 {@link OutsideRequestException} 而非返回 null。 */
    public static Request request() {
        return current().request;
    }

    public static Optional<Request> tryRequest() {
        return HOLDER.current().map(s -> s.request);
    }

    // ---------- 请求级单例 ----------

    /**
     * 同一请求内只创建一次。
     * 用途:数据库连接、identity map、已解析的认证用户。
     */
    @SuppressWarnings("unchecked")
    public <T> T scoped(Class<T> type, Supplier<T> factory) {
        return (T) instances.computeIfAbsent(type, k -> factory.get());
    }

    public <T> Optional<T> scopedIfPresent(Class<T> type) {
        return Optional.ofNullable(type.cast(instances.get(type)));
    }

    // ---------- 自由属性 ----------

    public void put(String key, Object value) { attributes.put(key, value); }

    public Optional<Object> attribute(String key) { return Optional.ofNullable(attributes.get(key)); }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> attribute(String key, Class<T> type) {
        Object v = attributes.get(key);
        return type.isInstance(v) ? Optional.of((T) v) : Optional.empty();
    }

    // ---------- 结束回调 ----------

    /** 注册请求结束时执行的清理。后注册者先执行(LIFO,与资源获取顺序相反)。 */
    public void onTerminate(Runnable action) {
        terminators.push(action);
    }

    private void terminate() {
        RuntimeException failure = null;
        while (!terminators.isEmpty()) {
            try {
                terminators.pop().run();
            } catch (RuntimeException e) {
                // 一个清理失败不应阻止其他清理 —— 否则会泄漏连接
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        instances.clear();
        attributes.clear();
        if (failure != null) throw failure;
    }
}
