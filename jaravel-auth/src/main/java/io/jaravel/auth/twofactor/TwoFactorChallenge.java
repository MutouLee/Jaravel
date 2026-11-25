package io.jaravel.auth.twofactor;

import java.util.Optional;

import io.jaravel.auth.Auth;
import io.jaravel.auth.Authenticatable;
import io.jaravel.session.Session;

/**
 * 登录流程里的「第二步」(docs/08 §6.4)。
 *
 * <pre>{@code
 * // 控制器:密码对了,但这个账号开了二步验证
 * if (user.twoFactorEnabled()) {
 *     TwoFactorChallenge.start(user, remember);
 *     return Redirect.route("two-factor.challenge");     // 还没登录
 * }
 * Auth.login(user, remember);
 *
 * // 挑战页提交
 * var passed = TwoFactorChallenge.complete(request.input("code", ""));
 * if (passed.isEmpty()) return Redirect.back().withErrors(...);
 * return Redirect.intended("/dashboard");                // 此时才真的登录了
 * }</pre>
 *
 * <h2>★ 挑战期间**没有登录**</h2>
 * <p>待验证的只是一个用户 id 存在会话里,{@code Auth.check()} 仍然是 false。
 * 这一点是整个设计的关键:如果先登录再「要求补一个验证码」,那么任何一个
 * 忘了挂中间件的路由都是一个绕过口 —— 而「忘了挂」是一定会发生的。
 * 没登录的话,绕过的后果只是看到登录页。
 *
 * <h2>为什么状态在会话里,不在缓存里</h2>
 * <p>挑战属于**这个浏览器**。按用户 id 存缓存的版本里,A 在手机上发起的挑战
 * 可以被 B 在另一台设备上完成(只要 B 知道验证码)—— 而 TOTP 码会被钓鱼。
 * 会话让「谁发起的谁完成」成为结构上的事实。
 *
 * <h2>⚠ 挑战页必须自己挂节流</h2>
 * <p>6 位数字只有一百万种。{@code throttle:5,1} 之类的中间件是**调用方的事**,
 * 框架不替你挂 —— 但这件事必须写在这里,因为漏了之后这个接口就是一个
 * 可暴破的登录接口,而它看起来完全正常。
 */
public final class TwoFactorChallenge {

    private TwoFactorChallenge() {}

    /** 待验证的用户 id */
    public static final String PENDING_KEY = "jaravel.two-factor.pending";

    /** 通过之后要不要下发 remember cookie */
    public static final String REMEMBER_KEY = "jaravel.two-factor.remember";

    // ================= 发起 =================

    public static void start(Object user) {
        start(user, false);
    }

    /**
     * 记下「这个浏览器正在等一个验证码」。
     *
     * <p>换会话 id:挑战态是「密码已经对过」的凭据,而会话固定攻击的前提
     * 正是「攻击者提前塞给受害者一个 id」。登录那一步也会换一次 ——
     * 这里换是因为**挑战态本身**已经有价值了。
     *
     * @param user 模型或 {@link Authenticatable} 都收。
     *             模型不必实现 {@code Authenticatable}(那是 {@code @AuthModel}
     *             的设计:provider 负责包装)—— 所以这里不能只收后者,
     *             否则控制器里那句 {@code Auth.userOrFail(User.class)} 的结果
     *             压根传不进来
     */
    public static void start(Object user, boolean remember) {
        Authenticatable principal = user instanceof Authenticatable ready
                ? ready
                : Auth.guard().provider().wrap(user);

        Session.current().regenerate();
        Session.put(PENDING_KEY, principal.getAuthIdentifier());
        Session.put(REMEMBER_KEY, remember);
    }

    public static boolean pending() {
        return Session.isActive() && Session.has(PENDING_KEY);
    }

    public static Optional<Object> pendingId() {
        return Session.isActive() ? Session.get(PENDING_KEY) : Optional.empty();
    }

    /** 待验证的用户 —— 用 guard 的 provider 查,所以与登录走同一条取用户的路径 */
    public static Optional<Authenticatable> pendingUser() {
        return pendingId().flatMap(id -> Auth.guard().provider().retrieveById(id));
    }

    public static void abandon() {
        if (!Session.isActive()) return;
        Session.forget(PENDING_KEY, REMEMBER_KEY);
    }

    // ================= 完成 =================

    /**
     * 验一次 TOTP 码;通过就**真的登录**。
     *
     * @return 通过时是那个用户;失败返回 empty(调用方回退到挑战页并提示)
     */
    public static Optional<Authenticatable> complete(String code) {
        return complete(code, false);
    }

    /**
     * 用恢复码完成。
     *
     * <p>⚠ **调用方必须把用户存回库**:恢复码是一次性的,
     * {@code useRecoveryCode} 只是把那个哈希从模型的列表里删掉 ——
     * 不落库的话同一个码能用第二次,而那正是恢复码不该有的性质。
     *
     * <pre>{@code
     * var passed = TwoFactorChallenge.completeWithRecoveryCode(code);
     * passed.flatMap(u -> u.as(User.class)).ifPresent(User::save);   // ← 必须
     * }</pre>
     *
     * <p>⚠ 注意是 {@code as(User.class)} 而不是强转:guard 里的主体是
     * provider **包装**过的 {@link Authenticatable},而 {@code @AuthModel}
     * 的模型并不实现那个接口。强转会得到一个 500,而那个
     * ClassCastException 指向的是框架内部的类名 —— 示例里真写错过一次。
     */
    public static Optional<Authenticatable> completeWithRecoveryCode(String code) {
        return complete(code, true);
    }

    private static Optional<Authenticatable> complete(String code, boolean recovery) {
        if (code == null || code.isBlank()) return Optional.empty();

        var pending = pendingUser();
        if (pending.isEmpty()) return Optional.empty();

        // 模型自己实现了接口就用它,否则用 provider 包装出来的主体
        // (@AuthModel 里写列名的那条路)。of(...) 两种都认
        TwoFactorAuthenticatable user = TwoFactorAuthenticatable.of(pending.get());

        // ★ 没有密钥时**报错**,而不是返回「验证码不对」。
        //
        // 走到这里说明有人对一个压根没开二步验证的账号调了 start(...),
        // 或者 @AuthModel 里漏了那几列 —— 两种都是代码错误。
        // 静默当成验证失败的话,用户看到的是「验证码不正确」,
        // 于是没人会去看代码:那是一个登录彻底卡死、却指向用户的故障。
        String secret = user.twoFactorSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    会话里有一个待验证的二步验证挑战,但 %s 没有二步验证密钥。
                      要么这个账号压根没开(那就不该调 TwoFactorChallenge.start(...)),
                      要么 @AuthModel 里漏了 twoFactorSecret/twoFactorEnabled/
                      twoFactorRecoveryCodes 那几列 —— 也可以让模型自己实现
                      TwoFactorAuthenticatable 的七个访问器(docs/08 §6.4)。"""
                    .formatted(pending.get().unwrap().getClass().getName()));
        }

        boolean ok = recovery ? user.useRecoveryCode(code) : user.verifyTwoFactor(code);
        if (!ok) return Optional.empty();

        boolean remember = Session.get(REMEMBER_KEY, Boolean.class).orElse(false);
        abandon();

        // 真正的登录在这里 —— 它自己会换会话 id、下发 remember cookie
        Auth.login(pending.get(), remember);
        return pending;
    }
}
