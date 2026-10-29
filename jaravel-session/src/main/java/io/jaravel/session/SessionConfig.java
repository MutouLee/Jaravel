package io.jaravel.session;

import java.time.Duration;

/**
 * 会话的 cookie 与生命周期设置。
 *
 * <p>默认值按**安全优先**选:{@code secure=true}、{@code httpOnly=true}、
 * {@code sameSite=Lax}。开发环境用 http 时需要显式关掉 secure ——
 * 反过来(默认不安全、生产再开)会让忘记配置的人直接上线一个可被劫持的会话。
 */
public record SessionConfig(
        String cookieName,
        Duration lifetime,
        boolean expireOnClose,
        String path,
        String domain,
        boolean secure,
        boolean httpOnly,
        String sameSite,
        boolean partitioned
) {

    public static SessionConfig defaults() {
        return new SessionConfig(
                "jaravel_session",
                Duration.ofMinutes(120),
                false,
                "/",
                null,
                true,
                true,
                "Lax",
                false);
    }

    /** 本地 http 开发用:关掉 secure,其余不变 */
    public SessionConfig forLocalHttp() {
        return new SessionConfig(cookieName, lifetime, expireOnClose, path, domain,
                                 false, httpOnly, sameSite, partitioned);
    }

    public SessionConfig withLifetime(Duration newLifetime) {
        return new SessionConfig(cookieName, newLifetime, expireOnClose, path, domain,
                                 secure, httpOnly, sameSite, partitioned);
    }

    public SessionConfig withCookieName(String name) {
        return new SessionConfig(name, lifetime, expireOnClose, path, domain,
                                 secure, httpOnly, sameSite, partitioned);
    }

    /**
     * cookie 的 Max-Age。
     *
     * <p>{@code expireOnClose} 时返回 -1 —— 浏览器把它当会话 cookie,
     * 关窗即失效。注意这只是**客户端**行为:服务端的 TTL 仍按 lifetime 走,
     * 两者不是一回事。
     */
    public long cookieMaxAgeSeconds() {
        return expireOnClose ? -1 : lifetime.getSeconds();
    }

    public SessionConfig validated() {
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException(
                    "session.lifetime 必须为正,当前是 " + lifetime);
        }
        if (!java.util.List.of("Lax", "Strict", "None").contains(sameSite)) {
            throw new IllegalArgumentException("""
                    session.same-site 只能是 Lax / Strict / None,当前是 "%s"。
                      · Lax(默认)—— 同站请求与顶层导航携带,防住大多数 CSRF
                      · Strict    —— 任何跨站都不带;安全但从外站链接点进来会掉登录
                      · None      —— 跨站也带,**必须同时 secure=true**,否则浏览器拒收"""
                    .formatted(sameSite));
        }
        if ("None".equals(sameSite) && !secure) {
            throw new IllegalArgumentException(
                    "same-site=None 时必须 secure=true —— 浏览器会直接拒收这个 cookie,"
                  + "表现为「会话完全不工作」而没有任何报错。");
        }
        return this;
    }
}
