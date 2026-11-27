package net.dobeen.jaravel.auth;

import net.dobeen.jaravel.http.Request;
import net.dobeen.jaravel.session.Session;

/**
 * 「登录前想去的地址」的读写与校验。
 *
 * <pre>{@code
 * // 登录成功后
 * return Redirect.to(Intended.pull("/dashboard"));
 * }</pre>
 *
 * <h2>为什么存相对路径</h2>
 * <p>{@link Request#fullUrl()} 含 Host 头。把用户可控的 Host 写进会话,
 * 再在登录后原样放进 {@code Location},就是一个开放重定向 ——
 * 攻击者用 {@code Host: evil.example} 访问 {@code /settings},
 * 诱导受害者登录后落到外部站点。所以中间件只记
 * {@link Request#pathWithQuery()},这里再做一次防御性校验,
 * 兼容旧版本已经存进去的绝对 URL。
 */
public final class Intended {

    public static final String SESSION_KEY = "url.intended";

    private Intended() {
    }

    /** 记住本次请求的相对地址。调用方负责判断「该不该记」(通常只记 GET)。 */
    public static void remember(Request request) {
        if (Session.isActive()) {
            Session.put(SESSION_KEY, request.pathWithQuery());
        }
    }

    /** 取出并清除;不安全的值(绝对 URL、协议相对、反斜杠、换行)回落到 fallback。 */
    public static String pull(String fallback) {
        String url = Session.pull(SESSION_KEY, String.class).orElse(null);
        return isSafe(url) ? url : fallback;
    }

    /**
     * 只接受本站相对路径。
     *
     * <p>{@code //evil.example} 是协议相对 URL,{@code /\evil.example}
     * 会被部分浏览器归一化成 {@code //evil.example};两者都必须拒绝。
     * 换行用于响应头注入,同样拒绝。
     */
    public static boolean isSafe(String url) {
        if (url == null || url.isBlank()) return false;
        if (!url.startsWith("/")) return false;
        if (url.startsWith("//")) return false;
        if (url.indexOf('\\') >= 0) return false;
        if (url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0) return false;
        return !url.regionMatches(true, 0, "/%5c", 0, 4);
    }
}
