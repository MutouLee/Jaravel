package io.jaravel.session;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import io.jaravel.core.context.ExecutionScope;
import io.jaravel.http.Cookie;
import io.jaravel.http.Middleware;
import io.jaravel.http.Redirect;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.session.store.CookieStore;

/**
 * 请求开始时加载会话,响应时写回。
 *
 * <p>整个会话体系只有这一个地方碰 HTTP —— {@link SessionData} 与
 * {@link SessionStore} 都不知道 cookie 的存在。
 *
 * <h2>执行顺序</h2>
 * <p>必须排在任何需要会话的中间件(认证、CSRF、错误闪存)**之前**。
 * 这件事交给 {@code Kernel.priority()} 保证,而不是指望用户在每条路由上
 * 把顺序写对。
 */
public class StartSession implements Middleware {

    private final SessionStore store;
    private final SessionConfig config;

    public StartSession(SessionStore store, SessionConfig config) {
        this.store = store;
        this.config = config.validated();
        warnIfNotShared();
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        // 线程可能被复用(虚拟线程的载体线程、或测试里的直接调用),
        // 先清掉 cookie 驱动的待写出状态,否则会把上个请求的会话写给这个请求
        CookieStore.clearPending();

        SessionData session = load(request);

        // 登记收尾动作。必要性见 SessionLifecycle 的类注释:
        // 302 / 401 / 419 / 校验失败这些「用异常表达的响应」会让
        // 下面的 finish 调用被跳过,那时由框架在最终响应确定后补上
        var pending = new SessionLifecycle.Pending(session,
                (resp, req) -> finish(resp, req, session));
        SessionLifecycle.register(pending);

        Response response;
        try {
            response = ExecutionScope.tryCurrent()
                    .map(scope -> withSession(scope, session, request, next))
                    .orElseGet(() -> next.apply(request));
        } finally {
            CookieStore.clearPending();
        }

        pending.markDone();
        return finish(response, request, session);
    }

    /**
     * 收尾:闪存入会话 → 写回存储 → 附加 cookie。
     *
     * <p>顺序不能动。{@code absorbFlash} 必须在 {@code persist} 之前 ——
     * persist 里的 ageFlash 要把本轮写入的 flash 轮转成「下个请求可读」,
     * 搬晚了这些值永远读不到(症状:{@code with("status", ...)} 之后
     * 页面上什么都没有,而代码看起来完全正确)。
     */
    private Response finish(Response response, Request request, SessionData session) {
        SessionLifecycle.absorbFlash(response, session, request);
        persist(session);
        return attachCookie(response, session);
    }

    private Response withSession(ExecutionScope scope, SessionData session,
                                 Request request, Function<Request, Response> next) {
        // scoped 的语义是「作用域内单例」;这里用一个直接返回已建会话的工厂,
        // 等于把它放进作用域
        scope.scoped(SessionData.class, () -> session);
        return next.apply(request);
    }

    // ================= 加载 =================

    private SessionData load(Request request) {
        String cookieValue = request.cookie(config.cookieName()).orElse(null);

        if (cookieValue == null || cookieValue.isBlank()) {
            return new SessionData(SessionData.generateId());
        }

        var loaded = store.read(cookieValue);
        if (loaded.isEmpty()) {
            // 会话过期/被清/解不开。新开一个,**用新的 id** ——
            // 沿用客户端给的 id 等于让攻击者能指定 session id(会话固定)
            return new SessionData(SessionData.generateId());
        }

        // cookie 驱动的「id」就是密文本身,服务端不认它作 id
        String id = store instanceof CookieStore
                ? SessionData.generateId()
                : cookieValue;

        var session = new SessionData(id);
        session.replaceAll(loaded.get());
        return session;
    }

    // ================= 写回 =================

    private void persist(SessionData session) {
        // flash 轮转必须在写回**之前** —— 反了的话本轮 flash 不会被持久化,
        // 下个请求读不到任何 flash
        session.ageFlash();

        if (store instanceof CookieStore) {
            // cookie 驱动每次都要写:载荷就是 cookie 本身
            store.write(session.id(), session.raw(), config.lifetime());
            return;
        }

        // 服务端驱动:没改过就不写。绝大多数 GET 请求属于这种,
        // 省掉的是「每个请求一次 Redis 写」
        if (session.isDirty()) {
            store.write(session.id(), session.raw(), config.lifetime());
        }
    }

    private Response attachCookie(Response response, SessionData session) {
        if (store instanceof CookieStore) {
            var payload = CookieStore.takePending();
            if (payload.isEmpty()) return response;

            String value = payload.get();
            return response.cookie(value.isEmpty()
                    ? expiredCookie()
                    : cookieWith(value));
        }
        return response.cookie(cookieWith(session.id()));
    }

    private Cookie cookieWith(String value) {
        long maxAge = config.cookieMaxAgeSeconds();
        var cookie = Cookie.make(config.cookieName(), value)
                .withPath(config.path())
                .httpOnly(config.httpOnly())
                .secure(config.secure())
                .sameSite(Cookie.SameSite.valueOf(config.sameSite().toUpperCase()));

        if (config.domain() != null && !config.domain().isBlank()) {
            cookie = cookie.withDomain(config.domain());
        }
        return maxAge < 0 ? cookie : cookie.withMaxAge(Duration.ofSeconds(maxAge));
    }

    private Cookie expiredCookie() {
        return Cookie.forget(config.cookieName()).withPath(config.path());
    }

    // ================= 启动检查 =================

    /**
     * 用进程内存储存会话时提醒。
     *
     * <p>这是一个**只在多实例部署下出现**的故障:请求打到另一个实例就读不到
     * 会话,症状是「刷新页面随机掉登录」。单机开发永远测不出来,
     * 而上线后的归因过程极其痛苦 —— 所以必须在启动时说出来。
     *
     * <p>警告而非报错:单实例部署用 memory 是完全合理的。
     */
    private void warnIfNotShared() {
        if (store.isShared()) return;
        System.err.printf("""
                [jaravel] 警告:会话驱动 "%s" 是进程内存储,不能跨实例共享。
                          单实例部署没问题;一旦多副本运行(k8s、多台机器、
                          滚动发布期间的新旧两份),请求打到另一个实例就读不到会话,
                          症状是「刷新页面随机掉登录」—— 这个现象单机永远测不出来。
                          生产请换 session.driver = redis / database / cookie。
                %n""", store.name());
    }

    public SessionStore store() {
        return store;
    }

    public SessionConfig config() {
        return config;
    }
}
