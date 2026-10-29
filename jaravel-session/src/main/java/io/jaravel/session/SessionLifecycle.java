package io.jaravel.session;

import io.jaravel.core.context.ExecutionScope;
import io.jaravel.http.Redirect;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * 会话的「收尾」：闪存写入、持久化、下发 cookie。
 *
 * <h2>为什么不能全在 StartSession 里做</h2>
 * <p>{@link StartSession} 只能看到 {@code next.apply(request)} 正常返回的
 * 响应。而 Jaravel 里**大量响应是用异常表达的**:
 *
 * <ul>
 *   <li>{@code Authenticate} 未登录 → 抛 302,并要把 {@code url.intended} 写进会话;</li>
 *   <li>校验失败 → 异常处理器产出一个带 {@code errors}/{@code old} 闪存的 302;</li>
 *   <li>CSRF 不匹配 → 419。</li>
 * </ul>
 *
 * <p>这些路径上 StartSession 的 {@code next.apply} 已经抛出,它之后的
 * 持久化代码根本不执行。后果很具体:
 *
 * <ul>
 *   <li>登录后回不到原本想去的页面(url.intended 丢了);</li>
 *   <li>表单校验失败后 {@code @error} 与 {@code @old} **永远是空的** ——
 *       而这正是「回跳而不是返回 422」这个设计的全部意义所在;</li>
 *   <li>登出/登录时轮换的 CSRF 令牌没写回,下一个 POST 得到 419。</li>
 * </ul>
 *
 * <p>所以收尾动作必须发生在**最终响应已知之后** —— 也就是异常处理器
 * 跑完之后。框架在那一刻调用 {@link #finalizeResponse}。
 *
 * <h2>只做一次</h2>
 * <p>正常路径上 StartSession 自己就收尾了(这样直接 {@code new
 * StartSession(...).handle(...)} 的单元测试仍然拿得到 cookie)。
 * 于是这里必须幂等:靠 {@link Pending#done} 标记。
 *
 * <p>重复执行不是「多写一次库」那么轻 —— {@code ageFlash} 跑两遍会把
 * 本轮刚写入的 flash 直接删掉,症状是闪存消息时有时无。
 */
public final class SessionLifecycle {

    private static final String KEY = "session.pending-lifecycle";

    private SessionLifecycle() {
    }

    /** StartSession 登记本请求的会话,供框架收尾 */
    static void register(Pending pending) {
        ExecutionScope.tryCurrent().ifPresent(scope -> scope.put(KEY, pending));
    }

    static Pending current() {
        return ExecutionScope.tryCurrent()
                .flatMap(scope -> scope.attribute(KEY, Pending.class))
                .orElse(null);
    }

    /**
     * 由框架在最终响应确定后调用一次。
     *
     * <p>没有活动会话(无会话中间件的路由、api 组)时原样返回。
     */
    public static Response finalizeResponse(Response response, Request request) {
        Pending pending = current();
        if (pending == null || pending.done) return response;

        pending.done = true;
        return pending.finish(response, request);
    }

    /**
     * 把 {@link Redirect#flash()} 搬进会话的 flash 区。
     *
     * <p>{@code Redirect} 在 {@code jaravel-http} 里,那一层按 ADR-001
     * 不知道会话的存在 —— 它只能把值挂在响应对象上,真正落进 flash
     * 的动作必须发生在持有会话的这一侧。
     */
    static void absorbFlash(Response response, SessionData session, Request request) {
        if (!(response instanceof Redirect redirect)) return;

        redirect.flash().forEach(session::flash);

        if (redirect.shouldKeepInput()) {
            // 表单重填。**密码类字段必须排除** —— 它们会被写进会话存储
            // (可能是 Redis、数据库、甚至 cookie),而「校验失败后把明文
            // 密码存进会话」是一条真实的泄露路径
            var input = new java.util.LinkedHashMap<String, Object>(request.all());
            input.keySet().removeIf(key -> key.startsWith("password")
                                        || key.equals("_token"));
            session.flashInput(input);
        }
    }

    /** 本请求待收尾的会话。由 StartSession 构造。 */
    static final class Pending {

        private final SessionData session;
        private final java.util.function.BiFunction<Response, Request, Response> finisher;
        private boolean done;

        Pending(SessionData session,
                java.util.function.BiFunction<Response, Request, Response> finisher) {
            this.session = session;
            this.finisher = finisher;
        }

        SessionData session() {
            return session;
        }

        void markDone() {
            this.done = true;
        }

        Response finish(Response response, Request request) {
            return finisher.apply(response, request);
        }
    }
}
