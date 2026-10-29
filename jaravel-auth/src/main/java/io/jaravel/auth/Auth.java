package io.jaravel.auth;

import java.util.Map;
import java.util.Optional;

/**
 * 认证门面。
 *
 * <pre>{@code
 * if (Auth.attempt(Map.of("email", email, "password", password), remember)) {
 *     return Redirect.intended("/dashboard");
 * }
 *
 * User me = Auth.userOrFail(User.class);
 * Auth.guard("admin").check();
 * }</pre>
 *
 * <h2>{@code user()} 与 {@code user(User.class)}</h2>
 * <p>前者返回框架内部的 {@link Authenticatable} 包装;后者解包成你的模型。
 * 日常代码几乎总是用后者。这个两层结构的理由见 {@link Authenticatable} ——
 * 它换来的是「用户模型不必实现四个样板方法」。
 *
 * <h2>请求级门面</h2>
 * <p>在队列任务或命令行里调用会抛异常而不是静默返回空 —— 这是 docs/08 §2.2
 * 列为「对 Laravel 的有意改进」的一点。静默返回访客身份会让权限检查
 * 以访客规则通过,那是安全问题而不是一个 null。
 */
public final class Auth {

    private Auth() {}

    private static volatile AuthManager manager;

    public static void use(AuthManager configured) {
        manager = configured;
    }

    public static AuthManager manager() {
        AuthManager m = manager;
        if (m == null) {
            throw new IllegalStateException("""
                    认证还没有配置。
                      Spring 环境下由 starter 从 config/auth.yml 自动装配;
                      脱离框架使用时:
                        var manager = new AuthManager()
                            .registerProvider(new EloquentUserProvider<>("users", User.class, Hash.manager()))
                            .registerGuard("web", "users", p -> new SessionGuard("web", p))
                            .defaultGuard("web");
                        Auth.use(manager);""");
        }
        return m;
    }

    // ================= guard 选择 =================

    public static Guard guard() {
        return manager().guard();
    }

    public static Guard guard(String name) {
        return manager().guard(name);
    }

    /** 本请求内切换默认 guard */
    public static void shouldUse(String name) {
        manager().shouldUse(name);
    }

    /**
     * 默认 guard 必须是有状态的才能登录/登出。
     *
     * <p>报错而不是静默失败:{@code Auth.attempt(...)} 在 JWT guard 上
     * 没有意义(JWT 无状态,凭据每次请求自带),而「调用了但什么都没发生」
     * 会让人以为是密码错了。
     */
    private static StatefulGuard stateful() {
        Guard g = guard();
        if (g instanceof StatefulGuard s) return s;

        throw new UnsupportedOperationException("""
                guard "%s"(%s)不支持登录/登出 —— 它不是有状态守卫。
                  Token / JWT / Basic 守卫的凭据由客户端每次请求自带,
                  没有「登录」这个动作:
                    · 签发 token  → 用对应的 TokenIssuer / JwtIssuer;
                    · 校验身份     → Auth.check() / Auth.user() 照常可用。
                  要在同一个应用里既有会话登录又有 API token,
                  就配两个 guard 并用 Auth.guard("web").attempt(...) 指明。"""
                .formatted(g.name(), g.getClass().getSimpleName()));
    }

    // ================= 登录 =================

    public static boolean attempt(Map<String, Object> credentials, boolean remember) {
        return stateful().attempt(credentials, remember);
    }

    public static boolean attempt(Map<String, Object> credentials) {
        return attempt(credentials, false);
    }

    public static boolean once(Map<String, Object> credentials) {
        return stateful().once(credentials);
    }

    public static void login(Authenticatable user, boolean remember) {
        stateful().login(user, remember);
    }

    /**
     * 登录一个领域对象(通常是刚保存的 Eloquent 模型)。
     *
     * <pre>{@code
     * user.save();
     * Auth.login(user);
     * }</pre>
     *
     * <p>由当前 guard 的 provider 负责包装 —— 见
     * {@link UserProvider#wrap}。比
     * {@code Auth.login(provider.retrieveById(user.id).orElseThrow())}
     * 少两行,也少一次查库。
     */
    public static void login(Object user) {
        login(user, false);
    }

    public static void login(Object user, boolean remember) {
        Guard guard = guard();
        login(guard.provider().wrap(user), remember);
    }

    public static void login(Authenticatable user) {
        login(user, false);
    }

    public static Optional<Authenticatable> loginUsingId(Object id, boolean remember) {
        return stateful().loginUsingId(id, remember);
    }

    public static Optional<Authenticatable> loginUsingId(Object id) {
        return loginUsingId(id, false);
    }

    public static void logout() {
        stateful().logout();
    }

    public static void logoutOtherDevices(String currentPassword) {
        stateful().logoutOtherDevices(currentPassword);
    }

    public static boolean viaRemember() {
        return guard() instanceof StatefulGuard s && s.viaRemember();
    }

    /** 只验证凭据,不登录 */
    public static boolean validate(Map<String, Object> credentials) {
        return guard().validate(credentials);
    }

    // ================= 读取 =================

    public static Optional<Authenticatable> user() {
        return guard().user();
    }

    /** 解包成你的模型类型 */
    public static <T> Optional<T> user(Class<T> type) {
        return guard().user().flatMap(u -> u.as(type));
    }

    /**
     * 必须已登录,否则抛 401。
     *
     * <p>用在「中间件已经保证登录了」的控制器里 —— 那里再写一次
     * {@code orElseThrow} 是噪音,而且每个人写的异常都不一样。
     */
    public static <T> T userOrFail(Class<T> type) {
        return user(type).orElseThrow(() -> new io.jaravel.http.HttpException(401, """
                需要登录才能访问。
                  若这条路由本该要求登录,给它加 middleware("auth");
                  若已经加了却走到这里,说明 guard 与中间件用的不是同一个
                  (检查 middleware("auth:admin") 与 Auth.user(User.class) 是否匹配)。"""));
    }

    public static Optional<Object> id() {
        return guard().id();
    }

    public static boolean check() {
        return guard().check();
    }

    public static boolean guest() {
        return guard().guest();
    }
}
