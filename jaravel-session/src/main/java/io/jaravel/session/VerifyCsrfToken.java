package io.jaravel.session;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import io.jaravel.http.HttpException;
import io.jaravel.http.Middleware;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * CSRF 校验。
 *
 * <p>检查「改状态的请求」是否带了与会话匹配的令牌。令牌可以来自:
 * 表单字段 {@code _token}、请求头 {@code X-CSRF-TOKEN}、
 * 或请求头 {@code X-XSRF-TOKEN}(前端框架常用,值是加密的)。
 *
 * <h2>为什么只查改状态的方法</h2>
 * <p>GET / HEAD / OPTIONS 按 HTTP 语义应当是安全的(不改状态),
 * 给它们加 CSRF 校验既无必要又会破坏「链接可以直接点开」。
 * 反过来说:**如果你的 GET 改了状态,那是真正的问题所在**,
 * 加 CSRF 只是把它藏起来。
 */
public class VerifyCsrfToken implements Middleware {

    private static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS");

    private final List<String> except;

    public VerifyCsrfToken() {
        this(List.of());
    }

    /**
     * @param except 豁免的路径模式(支持末尾 {@code *}),如 {@code "webhooks/*"}
     */
    public VerifyCsrfToken(List<String> except) {
        this.except = List.copyOf(except);
    }

    @Override
    public Response handle(Request request, Function<Request, Response> next) {
        if (isSafeMethod(request) || isExcepted(request) || tokensMatch(request)) {
            return next.apply(request);
        }
        throw new TokenMismatchException(request);
    }

    private boolean isSafeMethod(Request request) {
        return SAFE_METHODS.contains(request.method().toUpperCase());
    }

    private boolean isExcepted(Request request) {
        String path = request.path();
        for (String pattern : except) {
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (normalize(path).startsWith(normalize(prefix))) return true;
            } else if (normalize(path).equals(normalize(pattern))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /**
     * 比对令牌。
     *
     * <p>用 {@link MessageDigest#isEqual} 做**定时安全**比较 ——
     * 普通的 equals 会在第一个不同的字符上返回,让攻击者可以逐字符爆破令牌。
     */
    private boolean tokensMatch(Request request) {
        var session = Session.tryCurrent();
        if (session.isEmpty()) {
            // 没有会话就没有令牌可比。这通常意味着中间件顺序错了
            // (VerifyCsrfToken 跑在 StartSession 之前),而静默放过
            // 等于关掉 CSRF 防护 —— 所以报一个说明清楚的错。
            throw new IllegalStateException("""
                    VerifyCsrfToken 需要会话,但当前没有。
                      几乎总是中间件顺序问题:它必须跑在 StartSession 之后。
                      修复:在 Kernel.priority() 里把 StartSession 排在
                            VerifyCsrfToken 前面,或检查 web 组的顺序。
                      这里刻意报错而不是放过 —— 放过等于静默关闭 CSRF 防护。""");
        }

        String expected = session.get().token();
        String actual = tokenFrom(request);
        if (actual == null) return false;

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String tokenFrom(Request request) {
        var fromInput = request.input("_token");
        if (fromInput.isPresent() && !fromInput.get().isBlank()) return fromInput.get();

        var fromHeader = request.header("X-CSRF-TOKEN");
        if (fromHeader.isPresent() && !fromHeader.get().isBlank()) return fromHeader.get();

        // X-XSRF-TOKEN 来自 JS 框架(Axios 等),它读的是一个可被 JS 访问的
        // cookie。这里只接明文 —— 加密形式需要 Encrypter,而那会让本中间件
        // 多一个依赖;前端改用 X-CSRF-TOKEN 即可。
        var fromXsrf = request.header("X-XSRF-TOKEN");
        return fromXsrf.filter(s -> !s.isBlank()).orElse(null);
    }

    public List<String> except() {
        return except;
    }

    /**
     * 令牌不匹配 → 419。
     *
     * <p>419 是 Laravel 用的非标准码,专门表示「会话过期/令牌失效」。
     * 用它而不是 403 的理由:前端可以据此区分「没权限」(403,重试无用)
     * 与「页面放太久了」(419,刷新即可)—— 后者是最常见的情况,
     * 而让用户看到「没有权限」会非常困惑。
     */
    public static class TokenMismatchException extends HttpException {
        public TokenMismatchException(Request request) {
            super(419, buildMessage(request));
        }

        private static String buildMessage(Request request) {
            return """
                    CSRF 令牌缺失或不匹配(%s %s)。
                      最常见的原因是页面停留过久、会话已过期 —— 刷新页面重试即可。
                      开发时若是「表单里忘了带令牌」:
                        · jblade 模板:在 <form> 里加 @csrf
                        · fetch/axios:加请求头 X-CSRF-TOKEN: {{ csrf_token() }}
                        · webhook 回调等第三方 POST:加进 VerifyCsrfToken 的豁免列表"""
                    .formatted(request.method(), request.path());
        }
    }
}
