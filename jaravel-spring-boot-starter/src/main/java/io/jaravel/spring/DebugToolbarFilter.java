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
 * <h2>SQL 面板:按线程收集,不打开全局日志</h2>
 * <p>{@code JdbcConnection} 的全局查询日志会累积所有请求、无法按请求切分。
 * 工具栏用的是 {@code beginQueryCollection()} / {@code collectedQueries()} ——
 * 收集在**当前线程**上(一个请求一个虚拟线程),于是并发请求不会串台,
 * 也不需要全局打开日志。
 */
public class DebugToolbarFilter extends OncePerRequestFilter {

    static final String ID = "jaravel-debugbar";

    /** 面板里最多列出的 SQL 条数,再多就只报总数 */
    private static final int MAX_QUERIES = 100;

    private final boolean enabled;
    private final io.jaravel.database.Connection connection;

    public DebugToolbarFilter(ConfigRepository config) {
        this(config, null);
    }

    public DebugToolbarFilter(ConfigRepository config, io.jaravel.database.Connection connection) {
        this.enabled = config.bool("debug.toolbar",
                "local".equals(config.string("app.env", "production"))
                        || config.bool("app.debug", false));
        this.connection = connection;
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

        if (connection != null) connection.beginQueryCollection();
        DebugRequestStats.begin();
        io.jaravel.events.Event.beginEventCollection();

        try {
            chain.doFilter(request, cached);
            var queries = connection == null
                    ? java.util.List.<io.jaravel.database.Connection.QueryLog>of()
                    : connection.collectedQueries();
            injected = tryInject(request, cached, start, queries,
                    DebugRequestStats.current(), io.jaravel.events.Event.collectedEventNames());
        } finally {
            io.jaravel.events.Event.endEventCollection();
            DebugRequestStats.end();
            if (connection != null) connection.endQueryCollection();
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
                              ContentCachingResponseWrapper response, long startNanos,
                              java.util.List<io.jaravel.database.Connection.QueryLog> queries,
                              DebugRequestStats.Snapshot stats,
                              java.util.List<String> events) {
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

            byte[] injected = injectHtml(html,
                    toolbar(request, response, startNanos, queries, stats, events))
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
                                  ContentCachingResponseWrapper response, long startNanos,
                                  java.util.List<io.jaravel.database.Connection.QueryLog> queries,
                                  DebugRequestStats.Snapshot stats,
                                  java.util.List<String> events) {
        double millis = (System.nanoTime() - startNanos) / 1_000_000.0;
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long queryMicros = queries.stream()
                .mapToLong(io.jaravel.database.Connection.QueryLog::micros)
                .sum();

        String cacheText = stats == null
                ? "命中 0 / 未命中 0"
                : "命中 " + stats.cacheHits + " / 未命中 " + stats.cacheMisses;
        String queueText = stats == null
                ? "投递 0 · 完成 0 · 失败 0"
                : "投递 " + stats.queued + " · 完成 " + stats.processed
                        + " · 失败 " + stats.failed;

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

        String sqlText = queries.size() + " 条 · "
                + String.format(Locale.ROOT, "%.1f", queryMicros / 1000.0) + " ms";
        String eventText = events.size() + " 个";

        return """
                <div id="%s" style="position:fixed;right:12px;bottom:12px;z-index:2147483647;font:12px/1.5 ui-monospace,SFMono-Regular,Consolas,monospace;color:#e5e7eb;background:#111827;border:1px solid #374151;border-radius:8px;box-shadow:0 6px 24px rgba(0,0,0,.35);max-width:560px">
                  <details>
                    <summary style="cursor:pointer;padding:6px 10px;white-space:nowrap">Jaravel · %s</summary>
                    <div style="padding:0 10px 8px">
                      <table style="border-collapse:collapse">
                        <tr><td style="color:#9ca3af;padding-right:12px">路由</td><td>%s</td></tr>
                        <tr><td style="color:#9ca3af">视图</td><td>%s</td></tr>
                        <tr><td style="color:#9ca3af">SQL</td><td>%s</td></tr>
                        <tr><td style="color:#9ca3af">缓存</td><td>%s</td></tr>
                        <tr><td style="color:#9ca3af">队列</td><td>%s</td></tr>
                        <tr><td style="color:#9ca3af">事件</td><td>%s</td></tr>
                        <tr><td style="color:#9ca3af">内存</td><td>%.1f MB</td></tr>
                      </table>
                      %s
                      %s
                    </div>
                  </details>
                </div>
                """
                .formatted(ID, summary, esc(routeText), esc(viewText), sqlText,
                        cacheText, queueText, eventText, used / 1024.0 / 1024.0,
                        sqlPanel(queries), eventPanel(events));
    }

    private static String sqlPanel(java.util.List<io.jaravel.database.Connection.QueryLog> queries) {
        if (queries.isEmpty()) return "";

        var panel = new StringBuilder("<details style=\"margin-top:6px\"><summary style=\"cursor:pointer;color:#9ca3af\">SQL(")
                .append(queries.size())
                .append(")</summary><div style=\"max-height:220px;overflow:auto\">");

        int shown = Math.min(queries.size(), MAX_QUERIES);
        for (int i = 0; i < shown; i++) {
            var query = queries.get(i);
            panel.append("<div style=\"padding:6px 0;border-top:1px solid #374151\">")
                    .append("<div style=\"color:#9ca3af\">")
                    .append(String.format(Locale.ROOT, "%.1f", query.micros() / 1000.0))
                    .append(" ms")
                    .append(" · ")
                    .append(esc(query.caller()))
                    .append("</div><div style=\"white-space:pre-wrap;word-break:break-all\">")
                    .append(esc(query.sql()))
                    .append("</div><div style=\"color:#9ca3af\">")
                    .append(esc(String.valueOf(query.bindings())))
                    .append("</div></div>");
        }
        if (queries.size() > shown) {
            panel.append("<div style=\"color:#9ca3af;padding:6px 0\">还有 ")
                    .append(queries.size() - shown)
                    .append(" 条未列出</div>");
        }
        return panel.append("</div></details>").toString();
    }

    private static String eventPanel(java.util.List<String> events) {
        if (events.isEmpty()) return "";

        var counts = new java.util.TreeMap<String, Integer>();
        for (String event : events) counts.merge(event, 1, Integer::sum);

        var panel = new StringBuilder("<details style=\"margin-top:6px\"><summary style=\"cursor:pointer;color:#9ca3af\">事件(")
                .append(events.size())
                .append(")</summary><div style=\"max-height:160px;overflow:auto\">");

        counts.forEach((name, count) -> panel
                .append("<div style=\"padding:2px 0;color:#d1d5db\">")
                .append(esc(name))
                .append(" ×")
                .append(count)
                .append("</div>"));

        return panel.append("</div></details>").toString();
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
