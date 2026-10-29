package io.jaravel.core.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 一次「执行」的作用域:HTTP 请求、队列任务、控制台命令共用同一个抽象。
 *
 * <p>提供两件事:
 * <ul>
 *   <li><b>作用域内单例</b>({@link #scoped}):同一次执行内多次解析返回同一实例。
 *       数据库连接、Eloquent 的身份映射、已解析的认证用户都依赖它;
 *   <li><b>结束回调</b>({@link #onTerminate}):释放连接、flush 日志。
 * </ul>
 *
 * <p><b>为什么放在 core:</b>原先这套机制只存在于 {@code jaravel-http} 的
 * {@code RequestScope} 里,但 Eloquent 的身份映射同样需要它 ——
 * 而数据层不该依赖 HTTP(ArchUnit 规则强制)。上提到 core 后,
 * {@code RequestScope} 变成只负责「附带 Request 对象」的薄包装。
 * 这也关掉了 {@code docs/10} §7 的待决问题:RequestScope 与 JobScope 统一。
 *
 * <p>绑定由 {@link ContextHolder} 保证退出即解绑,不可能跨执行泄漏。
 */
public final class ExecutionScope {

    private static final ContextHolder<ExecutionScope> HOLDER =
            new ThreadLocalContextHolder<>("ExecutionScope");

    /** 这次执行的类型,仅用于错误消息与调试 */
    private final String kind;
    private final Map<Class<?>, Object> instances = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final Deque<Runnable> terminators = new ArrayDeque<>();

    private ExecutionScope(String kind) {
        this.kind = kind;
    }

    // ---------- 绑定 ----------

    /**
     * 在一个新作用域内执行。退出时一定解绑并执行结束回调,
     * 无论正常返回还是抛异常。
     */
    public static <R> R runIn(String kind, Callable<R> body) throws Exception {
        var scope = new ExecutionScope(kind);
        try {
            return HOLDER.runIn(scope, body);
        } finally {
            scope.terminate();
        }
    }

    public static void runIn(String kind, ContextHolder.ThrowingRunnable body) throws Exception {
        runIn(kind, () -> { body.run(); return null; });
    }

    /**
     * 复用已有作用域(嵌套场景)。若当前已在作用域内则直接执行 body,
     * 否则新建一个 —— 避免「作用域内单例」被意外重置。
     */
    public static <R> R runInOrReuse(String kind, Callable<R> body) throws Exception {
        return HOLDER.isBound() ? body.call() : runIn(kind, body);
    }

    public static ExecutionScope current() {
        return HOLDER.current().orElseThrow(() ->
                new OutsideRequestException("ExecutionScope.current()"));
    }

    public static Optional<ExecutionScope> tryCurrent() {
        return HOLDER.current();
    }

    public static boolean isActive() {
        return HOLDER.isBound();
    }

    public String kind() {
        return kind;
    }

    // ---------- 作用域内单例 ----------

    @SuppressWarnings("unchecked")
    public <T> T scoped(Class<T> type, Supplier<T> factory) {
        return (T) instances.computeIfAbsent(type, k -> factory.get());
    }

    /**
     * 作用域存在时取/建作用域内单例;不存在时直接新建一个一次性实例。
     *
     * <p>Eloquent 的身份映射用这个:在 HTTP 请求里共享,在单元测试或
     * 脱离作用域的脚本里退化为「每次新建」—— 既不报错也不泄漏。
     */
    public static <T> T scopedOrFresh(Class<T> type, Supplier<T> factory) {
        return tryCurrent().map(s -> s.scoped(type, factory)).orElseGet(factory);
    }

    public <T> Optional<T> scopedIfPresent(Class<T> type) {
        return Optional.ofNullable(type.cast(instances.get(type)));
    }

    // ---------- 自由属性 ----------

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    public Optional<Object> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> attribute(String key, Class<T> type) {
        Object v = attributes.get(key);
        return type.isInstance(v) ? Optional.of((T) v) : Optional.empty();
    }

    // ---------- 结束回调 ----------

    /** 后注册者先执行(LIFO,与资源获取顺序相反) */
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
