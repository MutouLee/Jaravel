package io.jaravel.spring;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTML 表单的方法伪装(docs/04 §1.8 的 {@code @method})。
 *
 * <pre>{@code
 * <form method="post" action="/notes/7">
 *   @csrf
 *   @method('DELETE')
 *   <button>删除</button>
 * </form>
 * }</pre>
 *
 * <p>浏览器的表单只会发 GET 与 POST。而 REST 风格的路由需要
 * PUT / PATCH / DELETE —— 于是约定:POST 的表单里带一个
 * {@code _method} 字段,服务端据此把方法改写掉。Laravel 用的是同一个
 * 字段名,所以照抄过来的表单直接能用。
 *
 * <h2>★ 为什么必须是 Filter 而不是中间件</h2>
 * <p>路由匹配按**方法**选路由,而匹配发生在 Spring 的 HandlerMapping 里 ——
 * 也就是在 Jaravel 的中间件管道**之前**。一个写成中间件的版本会在
 * 「已经按 POST 匹配到(或没匹配到)路由」之后才改写方法,于是
 * {@code r.delete("/notes/{id}")} 永远匹配不上,而症状是 404 / 405,
 * 与「方法伪装没生效」看不出关系。CORS 与请求 ID 是 Filter 的理由相同
 * (见 {@code Middleware} 的类注释)。
 *
 * <h2>★ 三条安全约束</h2>
 * <ol>
 *   <li><b>只改写 POST</b>。GET 不能被升级成 DELETE ——
 *       否则 {@code <a href="/notes/7?_method=DELETE">} 这样一个链接就能
 *       删数据,而链接会被浏览器预取、被爬虫抓、被聊天软件展开预览。
 *       这是方法伪装最经典的一个漏洞。</li>
 *   <li><b>目标方法必须在白名单里</b>:PUT / PATCH / DELETE。
 *       不允许改成 GET / HEAD —— 那会把一个需要 CSRF 校验的请求
 *       变成「安全方法」,等于绕过 {@code VerifyCsrfToken}。</li>
 *   <li><b>只读表单体</b>({@code application/x-www-form-urlencoded}),
 *       或显式的 {@code X-HTTP-Method-Override} 头。multipart 的
 *       {@code _method} 走头而不走参数:在这里调
 *       {@code getParameter} 会触发容器解析 multipart,而那一步本该由
 *       上传那一层(带大小上限与临时目录配置)来做。</li>
 * </ol>
 *
 * <p>改写之后 CSRF **照常校验**:{@code VerifyCsrfToken} 的安全方法只有
 * GET / HEAD / OPTIONS,PUT / PATCH / DELETE 都要令牌。所以表单里
 * {@code @csrf} 与 {@code @method} 两个都得写 —— 少写前者是 419。
 */
public class MethodOverrideFilter extends OncePerRequestFilter {

    /** 与 Laravel 一致的字段名 —— 照抄过来的表单要能直接用 */
    public static final String FIELD = "_method";

    /** 不走表单体时的头(JS 客户端常用这个) */
    public static final String HEADER = "X-HTTP-Method-Override";

    /**
     * 允许被伪装成的方法。
     *
     * <p>**不含 GET / HEAD**:见类注释的第二条。
     */
    private static final List<String> ALLOWED = List.of("PUT", "PATCH", "DELETE");

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String target = overrideFor(request);
        chain.doFilter(target == null ? request : new Overridden(request, target), response);
    }

    /** 这次请求要被改写成什么;不改写返回 null */
    private static String overrideFor(HttpServletRequest request) {
        // 第一条约束:只有 POST 能被改写
        if (!"POST".equalsIgnoreCase(request.getMethod())) return null;

        String requested = request.getHeader(HEADER);
        if (requested == null && isForm(request)) {
            // 表单体才读参数。非表单(JSON / multipart)只认上面那个头 ——
            // 见类注释第三条
            requested = request.getParameter(FIELD);
        }
        if (requested == null || requested.isBlank()) return null;

        String upper = requested.trim().toUpperCase(Locale.ROOT);
        // 第二条约束:白名单。不在表里的值**静默忽略**而不是报错 ——
        // 一个带着 _method=NONSENSE 的请求按原方法处理即可,
        // 报 400 只会把一个无害的脏输入变成一次失败的提交
        return ALLOWED.contains(upper) ? upper : null;
    }

    private static boolean isForm(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(FORM_CONTENT_TYPE);
    }

    /**
     * 只改 {@code getMethod()} 的包装。
     *
     * <p>刻意不动其它任何东西:参数、头、体都原样透过去。
     * {@code _method} 这个参数也**留在原处** —— 一个读
     * {@code request.all()} 的控制器看到它不会出错,而把它删掉需要
     * 重建参数表(并因此要处理 multipart 与重复参数),代价远大于收益。
     */
    private static final class Overridden extends HttpServletRequestWrapper {

        private final String method;

        private Overridden(HttpServletRequest request, String method) {
            super(request);
            this.method = method;
        }

        @Override
        public String getMethod() {
            return method;
        }
    }
}
