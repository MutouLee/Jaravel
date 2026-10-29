package io.jaravel.http;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import io.jaravel.core.context.ContextHolder;
import io.jaravel.core.context.ExecutionScope;
import io.jaravel.core.context.OutsideRequestException;
import io.jaravel.core.context.ThreadLocalContextHolder;

/**
 * 请求级作用域(ADR-007 的落地)。
 *
 * <p>Laravel 能写 {@code Auth::user()} 而不传参,是因为 PHP 每请求一个独立进程。
 * Java 侧用这个作用域还原同一个心智模型。
 *
 * <p><b>与 {@link ExecutionScope} 的关系:</b>「作用域内单例 + 结束回调」这套通用机制
 * 已上提到 core 的 {@code ExecutionScope}(因为 Eloquent 的身份映射也需要它,
 * 而数据层不该依赖 HTTP)。本类只负责多附带一个 {@link Request} 对象,
 * 其余职责全部委托。
 *
 * <p>配合 {@code spring.threads.virtual.enabled=true}(starter 默认开启):
 * 每请求一个虚拟线程 → 一个作用域,零污染,创建成本接近零。
 */
public final class RequestScope {

    private static final ContextHolder<Request> REQUEST =
            new ThreadLocalContextHolder<>("Request");

    private RequestScope() {}

    /** 由 HandlerAdapter 调用,整个请求处理包在这里面 */
    public static <R> R runIn(Request request, Callable<R> body) throws Exception {
        return ExecutionScope.runIn("http", () -> REQUEST.runIn(request, body));
    }

    /**
     * 当前请求。不在请求中时抛 {@link OutsideRequestException} 而非返回 null ——
     * 这是对 Laravel 的有意改进,消除「队列任务里 auth()->user() 莫名为空」这个经典坑。
     */
    public static Request request() {
        return REQUEST.current().orElseThrow(() ->
                new OutsideRequestException("RequestScope.request()"));
    }

    public static Optional<Request> tryRequest() {
        return REQUEST.current();
    }

    public static boolean isActive() {
        return REQUEST.isBound();
    }

    // ---------- 以下全部委托给 ExecutionScope ----------

    /** 同一请求内只创建一次。用途:数据库连接、身份映射、已解析的认证用户。 */
    public static <T> T scoped(Class<T> type, Supplier<T> factory) {
        return ExecutionScope.current().scoped(type, factory);
    }

    public static <T> Optional<T> scopedIfPresent(Class<T> type) {
        return ExecutionScope.current().scopedIfPresent(type);
    }

    public static void put(String key, Object value) {
        ExecutionScope.current().put(key, value);
    }

    public static Optional<Object> attribute(String key) {
        return ExecutionScope.current().attribute(key);
    }

    public static <T> Optional<T> attribute(String key, Class<T> type) {
        return ExecutionScope.current().attribute(key, type);
    }

    /** 注册请求结束时执行的清理。后注册者先执行。 */
    public static void onTerminate(Runnable action) {
        ExecutionScope.current().onTerminate(action);
    }
}
