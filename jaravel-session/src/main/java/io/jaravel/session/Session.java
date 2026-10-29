package io.jaravel.session;

import java.util.Map;
import java.util.Optional;

import io.jaravel.core.context.ExecutionScope;

/**
 * 会话门面。只在请求作用域内可用。
 *
 * <pre>{@code
 * Session.put("cart", cart);
 * Session.flash("status", "保存成功");
 * Session.token();          // CSRF
 * Session.regenerate();     // 登录后必做
 * }</pre>
 *
 * <p>当前会话由 {@code StartSession} 中间件放进执行作用域。
 * 作用域外调用会得到一条明确的错误,而不是 NPE 或静默返回空 ——
 * 后两者会让「队列任务里误用了 Session」变成一个难查的问题。
 */
public final class Session {

    private Session() {}

    /** 当前请求的会话。由 StartSession 设置。 */
    public static SessionData current() {
        return ExecutionScope.tryCurrent()
                .flatMap(scope -> scope.scopedIfPresent(SessionData.class))
                .orElseThrow(OutsideSessionException::new);
    }

    public static Optional<SessionData> tryCurrent() {
        try {
            return Optional.of(current());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 是否处在有会话的上下文里 —— 写「两种环境都要跑」的代码时用 */
    public static boolean isActive() {
        return tryCurrent().isPresent();
    }

    // ================= 转发 =================

    public static String id()                       { return current().id(); }
    public static Map<String, Object> all()         { return current().all(); }

    public static Optional<Object> get(String key)  { return current().get(key); }

    public static <T> Optional<T> get(String key, Class<T> type) {
        return current().get(key, type);
    }

    public static <T> T get(String key, Class<T> type, T fallback) {
        return current().get(key, type, fallback);
    }

    public static boolean has(String key)           { return current().has(key); }
    public static boolean exists(String key)        { return current().exists(key); }

    public static void put(String key, Object value) { current().put(key, value); }
    public static void putAll(Map<String, ?> values) { current().putAll(values); }

    public static <T> Optional<T> pull(String key, Class<T> type) {
        return current().pull(key, type);
    }

    public static void forget(String... keys)       { current().forget(keys); }
    public static void flush()                      { current().flush(); }

    public static void push(String key, Object value) { current().push(key, value); }
    public static long increment(String key)        { return current().increment(key); }
    public static long increment(String key, long delta) {
        return current().increment(key, delta);
    }

    // ---------- flash ----------

    public static void flash(String key, Object value) { current().flash(key, value); }
    public static void flashInput(Map<String, ?> input) { current().flashInput(input); }
    public static Optional<Object> oldInput(String key) { return current().oldInput(key); }
    public static void reflash()                    { current().reflash(); }
    public static void keep(String... keys)         { current().keep(keys); }

    // ---------- 安全 ----------

    public static String token()                    { return current().token(); }
    public static String regenerateToken()          { return current().regenerateToken(); }
    public static void regenerate()                 { current().regenerate(); }
    public static void invalidate()                 { current().invalidate(); }

    /**
     * 作用域外调用的错误。
     *
     * <p>消息要说清「为什么这里没有会话」—— 队列任务、命令行、
     * 以及没挂 StartSession 中间件的路由,是三种完全不同的原因,
     * 而修法也不同。
     */
    public static class OutsideSessionException extends IllegalStateException {
        public OutsideSessionException() {
            super("""
                    当前上下文没有会话。
                      三种常见原因:
                        1. 这条路由没有经过 StartSession 中间件 ——
                           把它放进 "web" 中间件组,或在路由上加 middleware("web");
                        2. 代码跑在队列任务 / 定时任务 / 命令行里 ——
                           那里本来就没有 HTTP 请求。需要用户身份请把 userId
                           作为任务参数传进去,而不是读会话;
                        3. 在中间件链里 StartSession 之前执行 ——
                           用 Kernel 的 priority() 调整顺序。
                      要写「两种环境都能跑」的代码,先用 Session.isActive() 判断。""");
        }
    }
}
