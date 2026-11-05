package io.jaravel.spring;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import io.jaravel.core.config.ConfigRepository;

/**
 * CORS(docs/12 §6.3)。
 *
 * <pre>
 * # config/cors.yml
 * cors:
 *   paths: [ "api/*" ]
 *   allowed-origins: [ "https://app.example.com" ]
 *   allowed-origins-patterns: [ "https://*.example.com" ]
 *   allowed-methods: [ "*" ]
 *   allowed-headers: [ "*" ]
 *   exposed-headers: [ "X-RateLimit-Remaining" ]
 *   max-age: 3600
 *   supports-credentials: true
 * </pre>
 *
 * <h2>★ 为什么它是 Servlet Filter 而不是中间件</h2>
 * <p>{@code Middleware} 的类注释早就定了这条分层:「真正需要全局拦截的
 * 横切关注点(CORS、代理头、维护模式)由 starter 注册为一个 Servlet Filter」。
 * 原因是中间件跑在**路由匹配之后**,而 CORS 的预检请求必须更早被回答:
 *
 * <ul>
 *   <li>预检用的是 {@code OPTIONS},而应用通常只注册了 GET / POST ——
 *       走路由匹配的话它会撞上 405(v0.5 刚实现的那条路径),
 *       于是浏览器把整个跨域请求判为失败;</li>
 *   <li>预检**不该**经过认证、会话、CSRF:它不带 Cookie 也不带 Authorization,
 *       让它走完整管道只会在某个中间件里被拒掉。</li>
 * </ul>
 *
 * <p>⚠ 这个类此前**不存在**,而 {@code Kernel} 的文档示例里就写着
 * {@code HandleCors.class} —— 照着抄的人会得到一个编译错误。
 *
 * <h2>默认是关的</h2>
 * <p>没有 {@code config/cors.yml}(或者 {@code cors.paths} 是空)时
 * 这个 filter 直接放行,一个头都不加。
 *
 * <p>理由:CORS 是**放宽**同源策略。一个默认就带着
 * {@code Access-Control-Allow-Origin: *} 的框架,会让每一个不知道这件事的
 * 项目把自己的 API 对全世界打开 —— 而那是个安全默认值问题,
 * 不是便利性问题。
 */
public class CorsFilter extends OncePerRequestFilter {

    private final List<Pattern> paths;
    private final List<String> allowedOrigins;
    private final List<Pattern> originPatterns;
    private final List<String> allowedMethods;
    private final List<String> allowedHeaders;
    private final List<String> exposedHeaders;
    private final long maxAge;
    private final boolean credentials;

    public CorsFilter(ConfigRepository config) {
        this.paths = config.stringList("cors.paths").stream()
                .map(CorsFilter::globToPattern).toList();
        this.allowedOrigins = config.stringList("cors.allowed-origins");
        this.originPatterns = config.stringList("cors.allowed-origins-patterns").stream()
                .map(CorsFilter::globToPattern).toList();
        this.allowedMethods = config.stringList("cors.allowed-methods");
        this.allowedHeaders = config.stringList("cors.allowed-headers");
        this.exposedHeaders = config.stringList("cors.exposed-headers");
        this.maxAge = config.longValue("cors.max-age", 0);
        this.credentials = config.bool("cors.supports-credentials", false);
    }

    public boolean enabled() {
        return !paths.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String origin = request.getHeader("Origin");
        if (origin == null || !pathMatches(request) || !originAllowed(origin)) {
            chain.doFilter(request, response);
            return;
        }

        applyHeaders(response, origin);

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                && request.getHeader("Access-Control-Request-Method") != null) {
            // 预检在这里就结束 —— 不进路由、不进中间件(理由见类注释)
            applyPreflightHeaders(request, response);
            response.setStatus(204);
            return;
        }

        chain.doFilter(request, response);
    }

    private void applyHeaders(HttpServletResponse response, String origin) {
        // ★ 回显具体的 Origin 而不是写 "*"。
        //
        // 两个理由:① 带凭据的请求下浏览器**拒绝** "*";
        // ② 回显具体值才能配合 Vary,让缓存不会把 A 站的响应给 B 站。
        response.setHeader("Access-Control-Allow-Origin", origin);
        // 没有 Vary 的话,CDN 可能把第一个来源拿到的响应(含那个 Allow-Origin)
        // 缓存起来发给别的来源 —— 那时浏览器会拒绝,表现为「有时跨域失败」
        response.addHeader("Vary", "Origin");

        if (credentials) {
            response.setHeader("Access-Control-Allow-Credentials", "true");
        }
        if (!exposedHeaders.isEmpty()) {
            response.setHeader("Access-Control-Expose-Headers", String.join(", ", exposedHeaders));
        }
    }

    private void applyPreflightHeaders(HttpServletRequest request, HttpServletResponse response) {
        String requested = request.getHeader("Access-Control-Request-Method");
        response.setHeader("Access-Control-Allow-Methods",
                allowedMethods.contains("*") ? requested : String.join(", ", allowedMethods));

        String requestedHeaders = request.getHeader("Access-Control-Request-Headers");
        if (allowedHeaders.contains("*")) {
            // "*" 在带凭据时同样无效,所以回显客户端请求的那些
            if (requestedHeaders != null) {
                response.setHeader("Access-Control-Allow-Headers", requestedHeaders);
            }
        } else if (!allowedHeaders.isEmpty()) {
            response.setHeader("Access-Control-Allow-Headers", String.join(", ", allowedHeaders));
        }

        if (maxAge > 0) {
            response.setHeader("Access-Control-Max-Age", String.valueOf(maxAge));
        }
        response.addHeader("Vary", "Access-Control-Request-Method");
        response.addHeader("Vary", "Access-Control-Request-Headers");
    }

    private boolean pathMatches(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/")) path = path.substring(1);
        for (Pattern pattern : paths) {
            if (pattern.matcher(path).matches()) return true;
        }
        return false;
    }

    private boolean originAllowed(String origin) {
        if (allowedOrigins.contains("*")) return true;
        for (String allowed : allowedOrigins) {
            if (allowed.equalsIgnoreCase(origin)) return true;
        }
        for (Pattern pattern : originPatterns) {
            if (pattern.matcher(origin).matches()) return true;
        }
        return false;
    }

    /**
     * {@code api/*} / {@code https://*.example.com} 这种通配转正则。
     *
     * <p>**先转义再放开 {@code *}** —— 直接把字符串塞进 Pattern 的话,
     * {@code https://*.example.com} 里那个点会匹配任意字符,
     * 于是 {@code https://evil-example.com} 也算通过。
     * 那是一个真实的绕过,而它看起来只是「正则写松了」。
     */
    static Pattern globToPattern(String glob) {
        var regex = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (!regex.isEmpty()) regex.append(".*");
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
