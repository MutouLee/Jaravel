package io.jaravel.http;

import java.time.Duration;

/**
 * Cookie 值对象。
 *
 * <p>安全默认值:{@code HttpOnly=true}、{@code SameSite=Lax}。
 * {@code Secure} 由 starter 根据环境注入(local 下关闭,否则开启)——
 * 默认安全,显式才放松。
 */
public record Cookie(
        String name,
        String value,
        String path,
        String domain,
        Duration maxAge,
        boolean httpOnly,
        boolean secure,
        SameSite sameSite
) {
    public enum SameSite { STRICT, LAX, NONE;
        public String headerValue() {
            return switch (this) { case STRICT -> "Strict"; case LAX -> "Lax"; case NONE -> "None"; };
        }
    }

    public static Cookie make(String name, String value) {
        return new Cookie(name, value, "/", null, null, true, false, SameSite.LAX);
    }

    public static Cookie make(String name, String value, Duration maxAge) {
        return new Cookie(name, value, "/", null, maxAge, true, false, SameSite.LAX);
    }

    /** 立即过期的同名 Cookie,用于删除 */
    public static Cookie forget(String name) {
        return new Cookie(name, "", "/", null, Duration.ZERO, true, false, SameSite.LAX);
    }

    public Cookie withPath(String p)         { return new Cookie(name, value, p, domain, maxAge, httpOnly, secure, sameSite); }
    public Cookie withDomain(String d)       { return new Cookie(name, value, path, d, maxAge, httpOnly, secure, sameSite); }
    public Cookie withMaxAge(Duration d)     { return new Cookie(name, value, path, domain, d, httpOnly, secure, sameSite); }
    public Cookie httpOnly(boolean b)        { return new Cookie(name, value, path, domain, maxAge, b, secure, sameSite); }
    public Cookie secure(boolean b)          { return new Cookie(name, value, path, domain, maxAge, httpOnly, b, sameSite); }
    public Cookie sameSite(SameSite s)       { return new Cookie(name, value, path, domain, maxAge, httpOnly, secure, s); }
}
