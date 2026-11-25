package io.jaravel.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认安全响应头(SECURITY-AUDIT §6 的「未做」之一)。
 *
 * <p>由 starter 注册成一个 Servlet Filter({@code SecurityHeadersFilter}),
 * 所以不写任何配置就有这三个头:
 *
 * <table border="1">
 *   <caption>默认开启的头</caption>
 *   <tr><th>头</th><th>值</th><th>它挡住什么</th></tr>
 *   <tr><td>{@code X-Content-Type-Options}</td><td>{@code nosniff}</td>
 *       <td>浏览器把一个 {@code text/plain} 的上传当成 HTML/JS 执行</td></tr>
 *   <tr><td>{@code X-Frame-Options}</td><td>{@code SAMEORIGIN}</td>
 *       <td>点击劫持:别人把你的页面嵌进 iframe 盖一层透明按钮</td></tr>
 *   <tr><td>{@code Referrer-Policy}</td><td>{@code strict-origin-when-cross-origin}</td>
 *       <td>带着含令牌的 URL 泄漏到第三方站点的 Referer 里</td></tr>
 * </table>
 *
 * <h2>★ CSP 与 HSTS 默认**不开**</h2>
 * <p>这两个与上面三个有本质区别:**配错会让站点看起来坏掉,而且坏法很隐蔽**。
 *
 * <ul>
 *   <li><b>CSP</b> —— 任何一个框架猜出来的默认值都会挡掉某些应用的内联脚本、
 *       CDN、统计代码。症状是「页面上有一块不工作」,而控制台里那条
 *       CSP 报错只有打开 devtools 才看得到。所以要开必须由应用写出策略:
 *       {@code jaravel.security.csp: "default-src 'self'"};</li>
 *   <li><b>HSTS</b> —— 它是**粘的**:浏览器记住「这个域只能用 https」,
 *       记住之后你无法让用户的浏览器忘掉(除了等 max-age 到期)。
 *       在还没全站 https 的域上开一次,代价是一部分用户再也打不开站点。
 *       所以要开必须显式配 {@code jaravel.security.hsts.max-age},
 *       而且**只在 https 请求上发**(http 上发它浏览器会忽略,
 *       但那也意味着开发环境里一次误配不会立刻暴露)。</li>
 * </ul>
 *
 * <h2>★ 为什么是 Filter 而不是中间件</h2>
 * <p>第一版写成了全局中间件,而那漏掉了**最需要这些头的那一半响应**:
 * 401 / 419 / 404 / 500 都是用异常表达的,它们的响应在中间件链
 * **unwind 之后**才由 ExceptionHandler 造出来(见
 * {@code JaravelHandlerAdapter}),于是一个中间件永远碰不到它们。
 * 静态资源与没匹配上路由的请求更是连中间件都不经过。
 *
 * <p>这条分层 {@code Middleware} 的类注释里早就写了:真正需要全局拦截的
 * 横切关注点由 starter 注册为 Servlet Filter(CORS 与请求 ID 也是
 * 这个理由)。错误页同样是一段会被浏览器解析的内容,它也需要 nosniff。
 *
 * <h2>已经设过的头不覆盖</h2>
 * <p>控制器显式设了同名头时让位。理由:一个需要
 * {@code X-Frame-Options: ALLOWALL} 的嵌入式页面,应当能在那条路由上
 * 自己说清,而不是被全局的东西静默改回去 —— 那种冲突的症状是
 * 「我明明设了,响应里却不是」。
 */
public final class SecurityHeaders {

    /** 默认值。改这里等于改全框架的默认安全姿态,所以每一条都在类注释里有理由。 */
    private static final Map<String, String> DEFAULTS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "SAMEORIGIN",
            "Referrer-Policy", "strict-origin-when-cross-origin");

    private final Map<String, String> headers = new LinkedHashMap<>(DEFAULTS);
    private String hsts;

    public SecurityHeaders() {
    }

    /**
     * 内容安全策略。空或 null = 不发这个头。
     *
     * @param policy 例如 {@code "default-src 'self'; img-src 'self' data:"}
     */
    public SecurityHeaders withCsp(String policy) {
        if (policy != null && !policy.isBlank()) {
            headers.put("Content-Security-Policy", policy.trim());
        }
        return this;
    }

    /** 只报告不拦截的 CSP —— 上线前先观察哪些资源会被挡 */
    public SecurityHeaders withCspReportOnly(String policy) {
        if (policy != null && !policy.isBlank()) {
            headers.put("Content-Security-Policy-Report-Only", policy.trim());
        }
        return this;
    }

    public SecurityHeaders withFrameOptions(String value) {
        return withHeader("X-Frame-Options", value);
    }

    public SecurityHeaders withReferrerPolicy(String value) {
        return withHeader("Referrer-Policy", value);
    }

    /** {@code Permissions-Policy}(原 Feature-Policy)。默认不发 —— 取值因应用而异。 */
    public SecurityHeaders withPermissionsPolicy(String value) {
        return withHeader("Permissions-Policy", value);
    }

    /**
     * HSTS。只在 https 请求上发(见类注释)。
     *
     * @param maxAge            浏览器记住多久。上线前先用小值(如 5 分钟)验证
     * @param includeSubDomains ⚠ 它会连子域一起锁死 —— 有 http 子域时不要开
     * @param preload           ⚠ 进浏览器预加载列表后**无法快速撤销**
     */
    public SecurityHeaders withHsts(Duration maxAge, boolean includeSubDomains, boolean preload) {
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            this.hsts = null;
            return this;
        }
        var value = new StringBuilder("max-age=").append(maxAge.toSeconds());
        if (includeSubDomains) value.append("; includeSubDomains");
        if (preload) value.append("; preload");
        this.hsts = value.toString();
        return this;
    }

    private SecurityHeaders withHeader(String name, String value) {
        if (value == null || value.isBlank()) {
            headers.remove(name);
        } else {
            headers.put(name, value.trim());
        }
        return this;
    }

    /**
     * 这个响应该加哪些头。
     *
     * @param secure 这是一次 https 请求吗 —— 决定要不要发 HSTS
     */
    public Map<String, String> headersFor(boolean secure) {
        var out = new LinkedHashMap<>(headers);
        // HSTS 在明文连接上是无意义的(浏览器按规范忽略),而发出去会让
        // 一次误配在开发环境里看不出来。只在 https 上发
        if (hsts != null && secure) out.put("Strict-Transport-Security", hsts);
        return out;
    }
}
