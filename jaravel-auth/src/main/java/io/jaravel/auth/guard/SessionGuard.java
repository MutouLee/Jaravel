package io.jaravel.auth.guard;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.RememberToken;
import io.jaravel.auth.StatefulGuard;
import io.jaravel.auth.UserProvider;
import io.jaravel.auth.provider.AbstractUserProvider;
import io.jaravel.http.Cookie;
import io.jaravel.session.Session;
import io.jaravel.session.SessionData;

/**
 * 会话型守卫:登录状态存会话,可选 remember cookie 长期保持。
 *
 * <h2>会话里只存 id,不存用户对象</h2>
 * <p>存对象会让「用户改了名字但会话里还是旧名字」成为常态,而且 cookie
 * 驱动下会话有 4KB 上限。每个请求按 id 查一次是可接受的成本 ——
 * 而且 Eloquent 的身份映射让同一请求内只查一次。
 *
 * <h2>remember cookie 的格式与安全</h2>
 * <pre>
 * &lt;用户 id&gt;|&lt;remember_token&gt;
 * </pre>
 * <p>两段都必要:只有 token 就得全表扫描找用户;只有 id 就是「谁都能伪造」。
 * 令牌比较是定时安全的(见 {@link AbstractUserProvider#tokensMatch})。
 *
 * <p>**登录时轮换令牌**:每次主动登录都生成新 token 并写回数据库,
 * 于是旧的 remember cookie 立刻失效。这让「在新设备登录」隐含地
 * 把旧设备踢掉 —— 与 Laravel 不同(它只在 logout 时轮换),
 * 但对「cookie 被盗」这个 remember me 的主要风险更有帮助。
 * 代价是多设备用户需要重新勾选记住我,这个取舍写在这里。
 */
public class SessionGuard implements StatefulGuard {

    /** 会话里存用户 id 的键。带 guard 名,因此多 guard 可并存。 */
    private final String sessionKey;
    private final String name;
    private final UserProvider provider;
    private final Duration rememberDuration;

    private Authenticatable cachedUser;
    private boolean resolved;
    private boolean viaRemember;

    /** 本次请求要写出的 cookie(由中间件取走) */
    private Cookie pendingCookie;

    public SessionGuard(String name, UserProvider provider) {
        this(name, provider, Duration.ofDays(400));
    }

    /**
     * @param rememberDuration remember cookie 的有效期。
     *                         默认 400 天 —— 这是 Chrome 对 cookie Max-Age 的
     *                         上限,写更大的值会被浏览器截到这个数,
     *                         于是「我设了两年」与实际行为不符。
     */
    public SessionGuard(String name, UserProvider provider, Duration rememberDuration) {
        this.name = name;
        this.provider = provider;
        this.sessionKey = "login_" + name;
        this.rememberDuration = rememberDuration;
    }

    @Override public String name() { return name; }

    @Override public UserProvider provider() { return provider; }

    public String sessionKey() {
        return sessionKey;
    }

    public String rememberCookieName() {
        return "remember_" + name;
    }

    // ================= 读取当前用户 =================

    @Override
    public Optional<Authenticatable> user() {
        if (resolved) return Optional.ofNullable(cachedUser);
        resolved = true;

        var session = Session.tryCurrent();
        if (session.isEmpty()) {
            // 没有会话 → 不是「未登录」,而是「这里问不出来」。
            // 报错而不是返回空:静默返回 guest 会让「队列任务里误用 Auth」
            // 表现为「权限检查通过了因为它以为是匿名访客」——
            // 那是个安全问题,不是一个 null。
            throw new NoSessionException(name);
        }

        Optional<Authenticatable> fromSession = session.get()
                .get(sessionKey)
                .flatMap(provider::retrieveById);

        if (fromSession.isPresent()) {
            cachedUser = fromSession.get();
            return fromSession;
        }
        return Optional.empty();
    }

    /**
     * 尝试用 remember cookie 恢复登录。
     *
     * <p>单独一个方法而不是塞进 {@link #user()}:它需要 Request(读 cookie)
     * 与 Response(写回新 cookie),而 Guard 接口刻意不依赖它们。
     * 由 {@code Authenticate} 中间件在会话里没有用户时调用。
     */
    public Optional<Authenticatable> recallFromCookie(String cookieValue) {
        if (cookieValue == null || cookieValue.isBlank()) return Optional.empty();

        int separator = cookieValue.indexOf('|');
        if (separator <= 0 || separator == cookieValue.length() - 1) {
            // 格式不对 → 当作没有。不报错:cookie 可能是旧版本留下的
            return Optional.empty();
        }

        String id = cookieValue.substring(0, separator);
        String token = cookieValue.substring(separator + 1);

        var user = provider.retrieveByToken(id, token);
        if (user.isEmpty()) return Optional.empty();

        // 恢复进会话,并标记「这次是 remember 来的」
        Session.current().put(sessionKey, user.get().getAuthIdentifier());
        Session.current().regenerate();     // 会话固定防护同样适用于 remember 登录
        cachedUser = user.get();
        resolved = true;
        viaRemember = true;
        return user;
    }

    // ================= 登录 =================

    @Override
    public boolean attempt(Map<String, Object> credentials, boolean remember) {
        var user = provider.retrieveByCredentials(credentials);

        if (user.isEmpty()) {
            // 用户不存在也要空转一次哈希 —— 否则响应时间能用来枚举邮箱
            if (provider instanceof AbstractUserProvider base) {
                base.burnTimeOnMissingUser(credentials);
            }
            return false;
        }
        if (!provider.validateCredentials(user.get(), credentials)) {
            return false;
        }

        provider.rehashPasswordIfRequired(user.get(), credentials);
        login(user.get(), remember);
        return true;
    }

    @Override
    public boolean once(Map<String, Object> credentials) {
        var user = provider.retrieveByCredentials(credentials);
        if (user.isEmpty()) {
            if (provider instanceof AbstractUserProvider base) {
                base.burnTimeOnMissingUser(credentials);
            }
            return false;
        }
        if (!provider.validateCredentials(user.get(), credentials)) return false;

        // 只设内存状态,不写会话
        setUser(user.get());
        return true;
    }

    @Override
    public boolean validate(Map<String, Object> credentials) {
        var user = provider.retrieveByCredentials(credentials);
        if (user.isEmpty()) {
            if (provider instanceof AbstractUserProvider base) {
                base.burnTimeOnMissingUser(credentials);
            }
            return false;
        }
        return provider.validateCredentials(user.get(), credentials);
    }

    @Override
    public void login(Authenticatable user, boolean remember) {
        SessionData session = Session.current();
        session.put(sessionKey, user.getAuthIdentifier());

        // **必须换 session id** —— 否则攻击者可以在登录前把自己的 session id
        // 塞给受害者,受害者登录后那个 id 就携带了已认证状态(会话固定攻击)。
        // docs/08 §7.2 的检查项之一。
        session.regenerate();

        if (remember) {
            issueRememberCookie(user);
        }
        setUser(user);
    }

    @Override
    public Optional<Authenticatable> loginUsingId(Object id, boolean remember) {
        var user = provider.retrieveById(id);
        user.ifPresent(u -> login(u, remember));
        return user;
    }

    @Override
    public void setUser(Authenticatable user) {
        this.cachedUser = user;
        this.resolved = true;
    }

    // ================= 登出 =================

    @Override
    public void logout() {
        var user = resolved ? Optional.ofNullable(cachedUser) : user();

        // 清掉数据库里的 remember token —— 不清的话,被盗的 cookie 在登出后
        // 仍然可以用来登录,「登出」就只是清了本机的痕迹
        user.ifPresent(this::clearRememberToken);

        Session.current().forget(sessionKey);
        Session.current().invalidate();

        pendingCookie = Cookie.forget(rememberCookieName());
        cachedUser = null;
        resolved = true;
        viaRemember = false;
    }

    /**
     * 让其它设备下线。
     *
     * <p>做法是轮换 remember token + 换当前会话 id。其它设备的会话记录
     * 仍在存储里,但它们存的是旧 session id,而**本设备换了新 id** ——
     * 这一点要说清:这个实现让其它设备的 **remember cookie** 失效,
     * 但如果它们的会话还没过期,它们仍然是登录状态。
     *
     * <p>要真正踢掉所有会话需要会话存储支持「按 user_id 查会话」,
     * 那是 database 会话驱动的能力(docs/09 §2.2 提到的
     * {@code sessions} 表带 user_id)。当前 cache/cookie 驱动做不到,
     * 所以这里诚实地只做前者,并在文档里标明范围。
     */
    @Override
    public void logoutOtherDevices(String currentPassword) {
        Authenticatable user = user().orElseThrow(() -> new IllegalStateException(
                "logoutOtherDevices 需要已登录的用户"));

        var credentials = Map.<String, Object>of(
                user.getAuthIdentifierName(), user.getAuthIdentifier(),
                "password", currentPassword);

        if (!provider.validateCredentials(user, credentials)) {
            throw new IllegalArgumentException("""
                    当前密码不正确,拒绝执行「登出其它设备」。
                      这个操作会让其它设备的长期登录失效,后果不可逆,
                      而触发它的往往是「我怀疑账号被盗」—— 此时必须确认操作者是本人。""");
        }

        // 轮换令牌:所有旧 remember cookie 立刻失效
        issueRememberCookie(user);
        Session.current().regenerate();
    }

    @Override
    public boolean viaRemember() {
        return viaRemember;
    }

    // ================= remember cookie =================

    private void issueRememberCookie(Authenticatable user) {
        // 60 字节随机;provider 负责哈希后存库(见 RememberToken 的类注释)
        String token = RememberToken.generate();
        try {
            provider.updateRememberToken(user, token);
        } catch (UnsupportedOperationException e) {
            // 模型没有 remember_token 列。不静默忽略:用户显式要求了
            // remember=true,而实际不会生效 —— 那是个会被当成 bug 的行为。
            throw new UnsupportedOperationException(
                    "请求了 remember me,但 " + e.getMessage(), e);
        }

        pendingCookie = Cookie.make(rememberCookieName(),
                        user.getAuthIdentifier() + "|" + token)
                .withMaxAge(rememberDuration)
                .httpOnly(true)       // JS 读不到 —— XSS 偷不走长期凭据
                .secure(true)
                .sameSite(Cookie.SameSite.LAX)
                .withPath("/");
    }

    private void clearRememberToken(Authenticatable user) {
        try {
            // 换成一个无人持有的新值 —— 旧 cookie 立刻失效
            provider.updateRememberToken(user, RememberToken.generate());
        } catch (UnsupportedOperationException ignored) {
            // 模型本来就不支持 remember me,没什么要清的
        }
    }

    /** 取走本次要写出的 cookie。由中间件在响应阶段调用。 */
    public Optional<Cookie> takePendingCookie() {
        var cookie = Optional.ofNullable(pendingCookie);
        pendingCookie = null;
        return cookie;
    }

    /** 请求结束时重置请求级状态 —— guard 实例按 guard 名缓存,会跨请求复用 */
    public void reset() {
        cachedUser = null;
        resolved = false;
        viaRemember = false;
        pendingCookie = null;
    }

    public static class NoSessionException extends IllegalStateException {
        public NoSessionException(String guard) {
            super("""
                    guard "%s" 需要会话,但当前上下文没有。
                      为什么报错而不是当作「未登录」:静默返回访客身份会让
                      权限检查**以访客的规则通过** —— 在队列任务里误用 Auth 时,
                      那是个安全问题,而不是一个 null。
                      三种原因:
                        1. 路由没经过 StartSession —— 放进 "web" 中间件组;
                        2. 代码跑在队列/命令行里 —— 把 userId 作为参数传进去;
                        3. 中间件顺序错了 —— 用 Kernel.priority() 让
                           StartSession 排在认证之前。"""
                    .formatted(guard));
        }
    }
}
