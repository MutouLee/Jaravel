package io.jaravel.spring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.jaravel.core.Application;
import io.jaravel.database.Connection;
import io.jaravel.http.HttpException;
import io.jaravel.http.Request;
import io.jaravel.http.Response;
import io.jaravel.view.JbladeException;

/**
 * 统一异常处理(docs/12 §3)。
 *
 * <p>按内容协商渲染:{@code Accept: application/json} 给 JSON,否则给 HTML 错误页。
 * 开发模式下 HTML 页带堆栈与源码片段;生产模式下不泄漏任何内部信息。
 *
 * <p>用户通过继承本类并覆写 {@link #renderCustom} 来定制,
 * 不需要知道 {@code @ControllerAdvice} 的存在。
 */
public class JaravelExceptionHandler {

    /** 异常 → 状态码的默认映射 */
    protected int statusFor(Throwable t) {
        if (t instanceof HttpException h) return h.status();
        // findOrFail / firstOrFail 的 404 —— 这是 Laravel 的约定,
        // 有它控制器才能直接写 Post.findOrFail(id) 而不必手写 404 分支
        if (t instanceof io.jaravel.eloquent.ModelNotFoundException) return 404;
        if (t instanceof IllegalArgumentException) return 400;
        if (t instanceof io.jaravel.core.context.OutsideRequestException) return 500;

        // 上传超限 → 413。
        //
        // 不是 400:客户端的请求本身是合法的,只是太大 ——
        // 而 413 带着「多大算太大」的语义,让前端能给出
        // 「文件不能超过 10 MB」这样的提示
        if (t instanceof org.springframework.web.multipart.MaxUploadSizeExceededException) {
            return 413;
        }
        return 500;
    }

    /** 用户覆写点:返回非空则使用该响应 */
    protected Response renderCustom(Request request, Throwable t) {
        return null;
    }

    /** 用户覆写点:上报到日志 / Sentry */
    protected void report(Throwable t) {
        if (t instanceof HttpException) return;   // 4xx 是预期的,不值得报警
        System.err.println("[jaravel] 未处理的异常:" + t);
        t.printStackTrace();
    }

    public final Response render(Request request, Throwable t) {
        Response custom = renderCustom(request, t);
        if (custom != null) return custom;

        report(t);

        // 校验失败对表单与 API 的正确响应完全不同,单独分流
        if (t instanceof io.jaravel.validation.ValidationException v) {
            return renderValidationFailure(request, v);
        }

        int status = statusFor(t);
        return request.expectsJson()
                ? renderJson(request, t, status)
                : renderHtml(request, t, status);
    }

    /**
     * 校验失败。
     *
     * <p>API 请求 → 422 + 与 Laravel 同构的 JSON;
     * 表单请求 → **302 回跳并把错误与旧输入闪存**。
     *
     * <p>后者不是锦上添花:表单提交失败时给用户一个 422 的错误页,等于让他
     * 退回去重填一遍所有字段。Laravel 之所以回跳,是因为只有回跳才能把
     * {@code @error} 与 {@code @old} 这两个模板指令变得有用。
     */
    protected Response renderValidationFailure(
            Request request, io.jaravel.validation.ValidationException e) {

        if (request.expectsJson()) {
            return Response.json(e.toJson()).status(422);
        }

        // 闪存**纯数据**而不是 ErrorBag 对象。
        //
        // 对象进会话的代价:任何需要序列化的存储(file / cookie / redis /
        // database)都写不进去,而症状是校验失败时整个请求变成 500 ——
        // 一个本该展示「邮箱格式不对」的页面变成了错误页。
        // 只有进程内的 memory/caffeine 驱动碰巧能存下它,
        // 于是这个问题在开发机上看不见,换驱动才爆。
        var redirect = io.jaravel.http.Redirect.back(request, "/")
                .with("errors", e.errors().messages())
                .with("old", formInput(request));
        return redirect;
    }

    /**
     * 回填用的旧输入。
     *
     * <p>**排除密码类字段**:它们会被写进会话存储(可能是 Redis、数据库、
     * 甚至 cookie),而「校验失败后把明文密码存进会话」是一条真实的泄露路径。
     *
     * <p>同时排除 {@code _token} —— 回填一个过期的 CSRF 令牌没有意义。
     */
    private static Map<String, Object> formInput(Request request) {
        var input = new LinkedHashMap<String, Object>(request.all());
        input.keySet().removeIf(key -> key.startsWith("password") || key.equals("_token"));
        return input;
    }

    protected Response renderJson(Request request, Throwable t, int status) {
        var body = new LinkedHashMap<String, Object>();
        body.put("message", userMessage(t, status));

        if (Application.isDebug()) {
            body.put("exception", t.getClass().getName());
            body.put("trace", topFrames(t, 10));
        }

        var response = Response.json(body).status(status);
        if (t instanceof HttpException h) h.headers().forEach(response::header);
        return response;
    }

    protected Response renderHtml(Request request, Throwable t, int status) {
        String html = Application.isDebug()
                ? DevErrorPage.render(request, t, status)
                : productionPage(status, userMessage(t, status));

        var response = Response.html(html).status(status);
        if (t instanceof HttpException h) h.headers().forEach(response::header);
        return response;
    }

    /**
     * 生产模式下给用户看的消息。
     *
     * <p>5xx **绝不**暴露异常消息 —— 那可能含表名、文件路径、SQL 片段。
     * 4xx 的消息是我们自己生成的,可以显示。
     */
    protected String userMessage(Throwable t, int status) {
        if (status >= 500 && !Application.isDebug()) return HttpException.defaultMessage(500);
        if (t instanceof HttpException h) return h.getMessage();
        if (Application.isDebug() && t.getMessage() != null) return t.getMessage();
        return HttpException.defaultMessage(status);
    }

    protected static List<String> topFrames(Throwable t, int n) {
        return java.util.Arrays.stream(t.getStackTrace())
                .limit(n)
                .map(StackTraceElement::toString)
                .toList();
    }

    private String productionPage(int status, String message) {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><title>%d</title>
            <style>
              body{font:16px/1.6 system-ui,sans-serif;display:grid;place-items:center;
                   height:100vh;margin:0;color:#334;background:#f7f8fa}
              .box{text-align:center}
              h1{font-size:72px;margin:0;color:#c7ccd6}
              p{margin:8px 0 0}
            </style></head>
            <body><div class="box"><h1>%d</h1><p>%s</p></div></body></html>
            """.formatted(status, status, escape(message));
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * 开发期错误页(docs/15 §4 的 v0.1 版本)。
     *
     * <p>v0.1 提供:异常类型与消息、模板错误的源码片段、请求信息、本次请求的 SQL。
     * v0.3 会补上「修复建议一键应用」与编辑器跳转。
     */
    static final class DevErrorPage {

        static String render(Request request, Throwable t, int status) {
            var sb = new StringBuilder();
            sb.append("""
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>%s</title>
                <style>
                  body{font:14px/1.6 ui-monospace,SFMono-Regular,Consolas,monospace;
                       margin:0;background:#1e1f26;color:#e6e6e6}
                  header{background:#c0392b;padding:20px 28px}
                  header h1{margin:0;font-size:18px;font-family:system-ui,sans-serif}
                  header p{margin:6px 0 0;opacity:.9;white-space:pre-wrap}
                  section{padding:18px 28px;border-bottom:1px solid #2e3038}
                  h2{font-size:12px;letter-spacing:.08em;text-transform:uppercase;
                     color:#8b90a0;margin:0 0 10px;font-family:system-ui,sans-serif}
                  pre{margin:0;white-space:pre-wrap;word-break:break-word}
                  table{border-collapse:collapse;width:100%%}
                  td{padding:3px 10px 3px 0;vertical-align:top}
                  td:first-child{color:#8b90a0;white-space:nowrap;width:1%%}
                  .frame{color:#9aa0b0}
                  .sql{color:#8fd19e}
                  .fix{background:#1b2a1f;border-left:3px solid #4caf50;
                       padding:12px 16px;border-radius:0 4px 4px 0}
                  .fix h3{margin:0 0 6px;font-size:13px;color:#8fd19e;
                          font-family:system-ui,sans-serif}
                  .fix p{margin:0 0 10px;color:#c6ccd4;white-space:pre-wrap}
                  .fix pre{background:#12140f;padding:10px 12px;border-radius:4px;
                           color:#e8f0e4;overflow-x:auto}
                  .fix .docs{margin:8px 0 0;font-size:12px;color:#8b90a0}
                  .code{background:#15161c;border-radius:4px;overflow-x:auto}
                  .code div{padding:1px 14px;white-space:pre}
                  .code .n{display:inline-block;width:44px;color:#5c6070;
                           text-align:right;margin-right:16px;user-select:none}
                  .code .hit{background:#3a2326}
                  .code .hit .n{color:#e0707a;font-weight:bold}
                  .jump{font-size:12px;color:#6ea8fe;text-decoration:none;
                        margin-right:14px;font-family:system-ui,sans-serif}
                </style></head><body>
                <header><h1>%s</h1><p>%s</p></header>
                """.formatted(escape(t.getClass().getSimpleName()),
                              escape(t.getClass().getName()),
                              escape(t.getMessage() == null ? "(无消息)" : t.getMessage())));

            // 出错那一行的源码。放最前面 —— 它是用户最先想看的东西,
            // 而「切回编辑器找文件数行号」这个动作本身就是摩擦
            SourceSnippet.forFirstApplicationFrame(t)
                    .ifPresent(snippet -> sb.append(section(
                            snippet.file() + ":" + snippet.highlighted(),
                            codeBlock(snippet))));

            // 修复建议。紧跟源码 —— 知道了在哪出错,下一个问题就是该改成什么
            FixSuggestion.forThrowable(t)
                    .ifPresent(fix -> sb.append(section("修复建议", fixBlock(fix))));

            // 模板错误的源码片段已在消息里,这里给出它的定位信息
            if (t instanceof JbladeException jb) {
                sb.append(section("模板", "<pre>" + escape(jb.template())
                        + (jb.line() > 0 ? " 第 " + jb.line() + " 行" : "") + "</pre>"));
            }

            sb.append(section("请求", requestTable(request)));
            sb.append(section("堆栈(应用代码优先)", stackList(t)));

            sqlPanel().ifPresent(p -> sb.append(section("本次请求的 SQL", p)));
            sessionPanel().ifPresent(p -> sb.append(section("会话", p)));
            configPanel().ifPresent(p -> sb.append(section("生效的配置(敏感值已脱敏)", p)));

            sb.append("</body></html>");
            return sb.toString();
        }

        private static String section(String title, String body) {
            return "<section><h2>" + escape(title) + "</h2>" + body + "</section>\n";
        }

        private static String requestTable(Request request) {
            var sb = new StringBuilder("<table>");
            row(sb, "方法", request.method());
            row(sb, "路径", request.fullUrl());
            request.routeName().ifPresent(n -> row(sb, "路由名", n));
            if (!request.routeParams().isEmpty()) row(sb, "路由参数", request.routeParams().toString());
            if (!request.all().isEmpty()) row(sb, "输入", request.all().toString());
            row(sb, "IP", request.ip());
            return sb.append("</table>").toString();
        }

        private static void row(StringBuilder sb, String k, String v) {
            sb.append("<tr><td>").append(escape(k)).append("</td><td>")
              .append(escape(v)).append("</td></tr>");
        }

        /** 应用代码的帧优先展示,框架帧折叠在后面 —— 与 Laravel 的做法一致 */
        private static String stackList(Throwable t) {
            var app = new StringBuilder();
            var framework = new StringBuilder();
            for (StackTraceElement f : t.getStackTrace()) {
                String cls = f.getClassName();
                boolean isFramework = cls.startsWith("io.jaravel.") || cls.startsWith("org.springframework.")
                        || cls.startsWith("java.") || cls.startsWith("jdk.")
                        || cls.startsWith("gg.jte.") || cls.startsWith("org.apache.");
                (isFramework ? framework : app)
                        .append("<div class=\"frame\">").append(escape(f.toString())).append("</div>");
            }
            var sb = new StringBuilder();
            if (!app.isEmpty()) sb.append(app);
            if (!framework.isEmpty()) {
                sb.append("<details><summary style=\"cursor:pointer;color:#8b90a0\">")
                  .append("框架与运行时的帧</summary>").append(framework).append("</details>");
            }
            return sb.isEmpty() ? "<pre>(无堆栈)</pre>" : sb.toString();
        }

        /**
         * 源码片段 + 编辑器跳转。
         *
         * <p>两个协议都给:用户装的是 IDEA 还是 VS Code 我们不知道,
         * 而探测不出来 —— 点错一个的代价只是没反应。
         */
        private static String codeBlock(SourceSnippet.Snippet snippet) {
            var sb = new StringBuilder();
            sb.append("<a class=\"jump\" href=\"").append(escape(snippet.editorUrl()))
              .append("\">在 IntelliJ 中打开</a>")
              .append("<a class=\"jump\" href=\"").append(escape(snippet.vscodeUrl()))
              .append("\">在 VS Code 中打开</a>")
              .append("<div class=\"code\" style=\"margin-top:10px\">");

            for (var line : snippet.lines()) {
                sb.append("<div").append(line.highlighted() ? " class=\"hit\"" : "").append(">")
                  .append("<span class=\"n\">").append(line.number()).append("</span>")
                  .append(escape(line.text()))
                  .append("</div>");
            }
            return sb.append("</div>").toString();
        }

        /** 修复建议 */
        private static String fixBlock(FixSuggestion fix) {
            var sb = new StringBuilder("<div class=\"fix\"><h3>💡 ")
                    .append(escape(fix.title())).append("</h3>");

            if (fix.explanation() != null && !fix.explanation().isBlank()) {
                sb.append("<p>").append(escape(fix.explanation())).append("</p>");
            }
            if (fix.hasCode()) {
                sb.append("<pre>").append(escape(fix.code())).append("</pre>");
            }
            if (fix.docs() != null) {
                sb.append("<p class=\"docs\">📖 ").append(escape(fix.docs())).append("</p>");
            }
            return sb.append("</div>").toString();
        }

        /**
         * 会话面板。
         *
         * <p>**不显示 _token** —— 错误页可能被截图发到群里,
         * 而那个值能直接用来伪造写请求。
         */
        private static java.util.Optional<String> sessionPanel() {
            try {
                if (!io.jaravel.session.Session.isActive()) return java.util.Optional.empty();

                var sb = new StringBuilder("<table>");
                row(sb, "会话 id", io.jaravel.session.Session.id());
                io.jaravel.session.Session.all().forEach((k, v) -> {
                    if (k.equals("_token")) {
                        row(sb, k, "(已隐藏 —— 错误页可能被截图)");
                    } else {
                        row(sb, k, String.valueOf(v));
                    }
                });
                return java.util.Optional.of(sb.append("</table>").toString());
            } catch (RuntimeException | NoClassDefFoundError e) {
                // 会话模块不在 classpath 上,或当前不在会话里。
                // 错误页**自己不能崩** —— 崩了用户连原始异常都看不到
                return java.util.Optional.empty();
            }
        }

        /** 配置面板。ConfigRepository.all() 已对敏感键脱敏。 */
        private static java.util.Optional<String> configPanel() {
            return Application.tryMake(io.jaravel.core.config.ConfigRepository.class)
                    .map(config -> {
                        var sb = new StringBuilder("<table>");
                        new java.util.TreeMap<>(config.all())
                                .forEach((k, v) -> row(sb, k, String.valueOf(v)));
                        return sb.append("</table>").toString();
                    });
        }

        /** SQL 面板:带 N+1 提示 */
        private static java.util.Optional<String> sqlPanel() {
            return Application.tryMake(Connection.class).map(conn -> {
                List<Connection.QueryLog> log = conn.queryLog();
                if (log.isEmpty()) return "<pre>(本次请求未执行 SQL)</pre>";

                Map<String, Integer> counts = new LinkedHashMap<>();
                for (var q : log) counts.merge(q.sql(), 1, Integer::sum);

                long totalMicros = log.stream().mapToLong(Connection.QueryLog::micros).sum();

                var sb = new StringBuilder();
                sb.append("<p style=\"margin:0 0 10px;color:#8b90a0\">")
                  .append(log.size()).append(" 条查询,合计 ")
                  .append(String.format("%.2f ms", totalMicros / 1000.0))
                  .append("</p><table>");

                for (int i = 0; i < log.size(); i++) {
                    var q = log.get(i);
                    int repeats = counts.get(q.sql());
                    boolean suspicious = repeats >= 3;

                    sb.append("<tr><td>").append(i + 1).append(".</td><td class=\"sql\">")
                      .append(String.format("%.2fms  ", q.micros() / 1000.0))
                      .append(escape(q.sql()));

                    if (suspicious) {
                        sb.append("  <b style=\"color:#f0ad4e\">⚠ 疑似 N+1(")
                          .append(repeats).append(" 次相同查询)</b>");
                    }

                    // 绑定参数:定位「为什么这条查询没命中」时必须看到实际值。
                    // 只显示语句等于只说了一半
                    if (!q.bindings().isEmpty()) {
                        sb.append("<div style=\"color:#8b90a0\">  参数 ")
                          .append(escape(String.valueOf(q.bindings()))).append("</div>");
                    }
                    // 调用点:N+1 的价值全在「是哪一行代码发出的」
                    if (q.caller() != null && !q.caller().isBlank()) {
                        sb.append("<div style=\"color:#6a6f7d\">  ← ")
                          .append(escape(q.caller())).append("</div>");
                    }
                    sb.append("</td></tr>");
                }
                sb.append("</table>");

                // 聚合视图:N+1 时逐条看是 50 行噪声,按语句聚合才看得出模式
                var repeated = counts.entrySet().stream()
                        .filter(e -> e.getValue() >= 3)
                        .toList();
                if (!repeated.isEmpty()) {
                    sb.append("<details style=\"margin-top:12px\">")
                      .append("<summary style=\"cursor:pointer;color:#f0ad4e\">")
                      .append("按语句聚合(").append(repeated.size())
                      .append(" 条疑似 N+1)</summary><table>");
                    for (var e : repeated) {
                        sb.append("<tr><td>×").append(e.getValue()).append("</td>")
                          .append("<td class=\"sql\">").append(escape(e.getKey()))
                          .append("</td></tr>");
                    }
                    sb.append("</table></details>");
                }
                return sb.toString();
            });
        }
    }
}
