package io.jaravel.spring;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import io.jaravel.core.config.ConfigRepository;
import io.jaravel.http.Response;
import io.jaravel.routing.Route;

/**
 * 开发期调试工具栏(docs/15 §4.3)。
 *
 * <p>在 HTML 页面右下角注入一个可折叠的小面板:请求方法/路径/状态/耗时、
 * 命中的路由、渲染的视图与数据键数量、进程内存。
 *
 * <h2>★ 只在「可能是页面」的请求上缓冲响应</h2>
 * <p>注入要等整个响应写完之后才能做,所以必须把正文缓存在内存里。API 的
 * JSON、文件下载、流式响应都不该为此付代价 —— 先看 {@code Accept}:
 * 只对 {@code text/html} 或通配符(浏览器/curl)的请求包装。
 * 响应类型还要再看一次 {@code Content-Type},两个条件都满足才注入。
 *
 * <h2>不是生产功能</h2>
 * <p>默认只在 {@code app.env=local} 或 {@code app.debug=true} 时启用;
 * 生产环境要显式打开 {@code debug.toolbar=true}。工具本身也会在页面上
 * 暴露路由与内存信息。
 *
 * <h2>SQL 面板还没接</h2>
 * <p>{@code JdbcConnection} 的查询日志是**全局**的,直接拿来按请求切分会在
 * 并发时串台。正确做法是给每次请求一个 ThreadLocal 收集器 ——
 * 那是下一刀;在此之前工具栏不假装自己知道 SQL 条数。
 */
public class DebugToolbarFilter extends OncePerRequestFilter {

    static final String ID = "jaravel-debugbar";

    private final boolean enabled;

    public DebugToolbarFilter(ConfigRepository config) {
        this.enabled = config.bool("debug.toolbar",
                "local".equals(config.string("app.env", "production"))
                        || config.bool("app.debug", false));
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, java.io.IOException {

        if (!enabled || !couldBeHtml(request)) {
            chain.doFilter(request, response);
            return;
        }

        long start = System.nanoTime();
        var cached = new ContentCachingResponseWrapper(response);
        boolean injected = false;

        try {
            chain.doFilter(request, cached);
            injected = tryInject(request, cached, start);
        } finally {
            // 没注入就原样写回;注入失败也走这里,页面仍在(少一个工具栏而已)
            if (!injected) cached.copyBodyToResponse();
        }
    }

    private boolean couldBeHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) return true;

        String lower = accept.toLowerCase(Locale.ROOT);
        return lower.contains("text/html") || lower.contains("*/*");
    }

    private boolean tryInject(HttpServletRequest request,
                              ContentCachingResponseWrapper response, long startNanos) {
        try {
            if (!"GET".equalsIgnoreCase(request.getMethod())) return false;

            String contentType = response.getContentType();
            if (contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                return false;
            }

            int status = response.getStatus();
            if (status < 200 || status >= 400) return false;

            byte[] bytes = response.getContentAsByteArray();
            if (bytes.length == 0) return false;

            String html = new String(bytes, StandardCharsets.UTF_8);
            if (html.contains(ID)) return false;

            byte[] injected = injectHtml(html, toolbar(request, response, startNanos))
                    .getBytes(StandardCharsets.UTF_8);

            response.resetBuffer();
            response.getOutputStream().write(injected);
            response.setContentLength(injected.length);
            response.copyBodyToResponse();
            return true;
        } catch (Throwable ignored) {
            // 工具栏不该弄坏页面:任何失败都退回「原样写回」
            return false;
        }
    }

    private static String injectHtml(String html, String toolbar) {
        int body = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        if (body >= 0) return html.substring(0, body) + toolbar + html.substring(body);
        return html + toolbar;
    }

    private static String toolbar(HttpServletRequest request,
                                  ContentCachingResponseWrapper response, long startNanos) {
        double millis = (System.nanoTime() - startNanos) / 1_000_000.0;
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        Route route = request.getAttribute(JaravelHandlerAdapter.ROUTE_ATTRIBUTE) instanceof Route r
                ? r : null;
        String routeText = route == null
                ? "—"
                : route.method() + " " + route.uri() + " → "
                        + route.controller().getSimpleName() + "#" + route.action();

        Response jaravel = request.getAttribute(JaravelHandlerAdapter.RESPONSE_ATTRIBUTE)
                instanceof Response r ? r : null;
        String viewText = "—";
        if (jaravel != null && jaravel.body() instanceof Response.Body.ViewRef ref) {
            viewText = ref.name() + "(" + ref.data().size() + " 个数据键)";
        }

        String summary = esc(request.getMethod()) + " " + esc(request.getRequestURI())
                + " · " + response.getStatus()
                + " · " + String.format(Locale.ROOT, "%.1f", millis) + " ms";

        return """
                <div id="%s" style="position:fixed;right:12px;bottom:12px;z-index:2147483647;font:12px/1.5 ui-monospace,SFMono-Regular,Consolas,monospace;color:#e5e7eb;background:#111827;border:1px solid #374151;border-radius:8px;box-shadow:0 6px 24px rgba(0,0,0,.35);max-width:560px">
                  <details>
                    <summary style="cursor:pointer;padding:6px 10px;white-space:nowrap">Jaravel · %s</summary>
                    <div style="padding:0 10px 8px">
                      <table style="border-collapse:collapse">
                        <tr><td style="color:#9ca3af;padding-right:12px">路由</td><td>%s</td></tr>
                        <tr><td style="color:#9ca3af">视图</td><td>%s</td></tr>
                        <tr><td style="color:#9ca3af">内存</td><td>%.1f MB</td></tr>
                      </table>
                    </div>
                  </details>
                </div>
                """
                .formatted(ID, summary, esc(routeText), esc(viewText),
                        used / 1024.0 / 1024.0);
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
