package io.jaravel.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.core.Application;
import io.jaravel.http.HttpException;
import io.jaravel.http.Request;
import io.jaravel.http.Response;

/**
 * 错误响应的两种格式与上报钩子(docs/12 §3)。
 *
 * <p>这一节此前实现了一大半(内容协商、开发/生产错误页、状态码映射),
 * 缺的是 §3.4 的 Problem JSON 与 §3.1 的三个钩子。
 */
class ErrorFormatTest {

    @AfterEach
    void resetEnvironment() {
        Application.setEnvironment("local");
    }

    /**
     * 用**真的** {@code BasicRequest},不手写替身。
     *
     * <p>第一版手写了一个 {@code Request} 的实现,结果编译就没过:
     * 那个接口比我以为的大(还有 {@code raw()}、{@code headers()} 的签名也不同)。
     * 而且手写替身会把「异常渲染只碰哪几个方法」这件事钉死在测试里 ——
     * 真实现多一个默认方法,替身就要跟着改。
     *
     * <p>{@code expectsJson()} 是接口的默认方法,靠 {@code Accept} 头判断,
     * 所以这里设的就是真实的那个条件。
     */
    private static Request jsonRequest(String path) {
        return io.jaravel.http.BasicRequest.of("GET", path)
                .header("Accept", "application/json");
    }

    private static Request htmlRequest(String path) {
        return io.jaravel.http.BasicRequest.of("GET", path)
                .header("Accept", "text/html");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(Response response) {
        var body = (Response.Body.Json) response.body();
        return (Map<String, Object>) body.value();
    }

    // ================= Laravel 格式(默认) =================

    @Nested
    @DisplayName("laravel 格式(默认)")
    class LaravelFormat {

        @Test
        @DisplayName("message + 状态码,与 Laravel 同构")
        void messageAndStatus() {
            var handler = new JaravelExceptionHandler();

            Response response = handler.render(jsonRequest("/orders/1"),
                    new HttpException(409, "库存不足"));

            assertThat(response.status()).isEqualTo(409);
            assertThat(bodyOf(response)).containsEntry("message", "库存不足");
        }

        @Test
        @DisplayName("★ 5xx 在生产环境下不泄漏异常消息")
        void productionHidesInternals() {
            Application.setEnvironment("production");
            var handler = new JaravelExceptionHandler();

            Response response = handler.render(jsonRequest("/x"),
                    new IllegalStateException("数据库 orders 表的 secret_col 读失败"));

            assertThat(response.status()).isEqualTo(500);
            assertThat(String.valueOf(bodyOf(response).get("message")))
                    .as("异常消息里可能有表名、文件路径、SQL 片段")
                    .doesNotContain("secret_col")
                    .doesNotContain("orders");
            assertThat(bodyOf(response))
                    .as("生产环境不带 exception / trace")
                    .doesNotContainKeys("exception", "trace");
        }
    }

    // ================= Problem JSON(RFC 7807) =================

    @Nested
    @DisplayName("problem-json 格式(RFC 7807)")
    class ProblemJson {

        private JaravelExceptionHandler handler() {
            var handler = new JaravelExceptionHandler();
            handler.useErrorFormat("problem-json");
            return handler;
        }

        @Test
        @DisplayName("★ 五个标准成员齐全,而且 type 是 about:blank 而不是编一个 URL")
        void standardMembers() {
            Response response = handler().render(jsonRequest("/orders/456"),
                    new HttpException(409, "SKU-123 仅剩 2 件"));

            Map<String, Object> body = bodyOf(response);
            assertThat(body)
                    .containsEntry("type", "about:blank")
                    .containsEntry("title", "Conflict")
                    .containsEntry("status", 409)
                    .containsEntry("detail", "SKU-123 仅剩 2 件")
                    .containsEntry("instance", "/orders/456");
        }

        @Test
        @DisplayName("Content-Type 是 application/problem+json —— 那是这个格式的识别方式")
        void contentType() {
            Response response = handler().render(jsonRequest("/x"), new HttpException(400, "坏请求"));

            assertThat(response.contentType())
                    .as("""
                            RFC 7807 用媒体类型声明自己。只改响应体而不改
                            Content-Type 的话,中间层与客户端都不知道这是一份
                            problem document。

                            ⚠ 断言的是 contentType() 而不是 headers() ——
                            第一版用 header("Content-Type", ...) 设的,而那是**死代码**:
                            ResponseWriter 用的是 contentType(),它那里甚至写着
                            「Tomcat 对这个头做了特判,setHeader 不管用」。
                            于是 problem+json 这个媒体类型永远到不了客户端。""")
                    .contains("application/problem+json");
        }

        @Test
        @DisplayName("自定义状态码(419)不在标准表里 —— 也不许炸")
        void unknownStatusCode() {
            Response response = handler().render(jsonRequest("/x"),
                    new HttpException(419, "CSRF token mismatch."));

            assertThat(response.status()).isEqualTo(419);
            assertThat(bodyOf(response).get("title"))
                    .as("""
                            ★ Spring 的表在 419 上会说错话:它给的是 WebDAV 的老含义
                            「Insufficient Space On Resource」,而在 Laravel 里
                            419 是 CSRF 令牌不匹配。
                            title 恰恰是给人读的那一行 —— 说错话比没有更糟。
                            (这一条是写这个测试时撞出来的:原以为 419 不在标准表里。)""")
                    .isEqualTo("CSRF Token Mismatch");
        }

        @Test
        @DisplayName("★ 切格式不改任何抛异常的代码 —— 同一个异常,两种形状")
        void sameExceptionTwoShapes() {
            var exception = new HttpException(409, "库存不足");

            Map<String, Object> laravel =
                    bodyOf(new JaravelExceptionHandler().render(jsonRequest("/o"), exception));
            Map<String, Object> problem = bodyOf(handler().render(jsonRequest("/o"), exception));

            assertThat(laravel).containsOnlyKeys("message");
            assertThat(problem).containsKeys("type", "title", "status", "detail", "instance");
            // 两边说的是同一件事,只是字段名不同
            assertThat(problem.get("detail")).isEqualTo(laravel.get("message"));
        }

        @Test
        @DisplayName("HTML 请求不受影响 —— 格式开关只管 JSON")
        void htmlIsUnaffected() {
            Response response = handler().render(htmlRequest("/x"), new HttpException(404, "没有"));

            assertThat(response.body()).isInstanceOf(Response.Body.Text.class);
            assertThat(response.status()).isEqualTo(404);
        }
    }

    // ================= 上报钩子 =================

    @Nested
    @DisplayName("上报钩子(§3.1)")
    class ReportHooks {

        @Test
        @DisplayName("★ 预期中的 4xx 默认不上报 —— 否则告警面板会被噪音埋掉")
        void expectedFailuresAreNotReported() {
            var reported = new java.util.ArrayList<Throwable>();
            var handler = new JaravelExceptionHandler() {
                @Override
                protected void report(Throwable t) {
                    reported.add(t);
                }
            };

            handler.render(jsonRequest("/x"), new HttpException(404, "没有"));
            handler.render(jsonRequest("/x"), new IllegalStateException("真的坏了"));

            assertThat(reported)
                    .as("""
                            一个每天刷出两万条「校验失败」的告警面板等于没有告警。
                            而真正的 500 必须报出来。""")
                    .hasSize(1)
                    .first().isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("dontReport 可以覆写,而覆写的是**完整那份**")
        void dontReportIsOverridable() {
            var reported = new java.util.ArrayList<Throwable>();
            var handler = new JaravelExceptionHandler() {
                @Override
                protected List<Class<? extends Throwable>> dontReport() {
                    return List.of(IllegalStateException.class);
                }

                @Override
                protected void report(Throwable t) {
                    reported.add(t);
                }
            };

            handler.render(jsonRequest("/x"), new IllegalStateException("被忽略"));
            handler.render(jsonRequest("/x"), new HttpException(404, "现在要报了"));

            assertThat(reported)
                    .as("""
                            返回 List 而不是让子类往集合里 add,是为了让
                            「我在替换什么」看得见 —— 这条测试就是那个代价:
                            覆写之后 HttpException 不再被挡,于是 404 也上报了。""")
                    .hasSize(1)
                    .first().isInstanceOf(HttpException.class);
        }

        @Test
        @DisplayName("dontFlash 列的字段在上下文里打码")
        void sensitiveContextIsRedacted() {
            var lines = new java.util.ArrayList<String>();
            var handler = new JaravelExceptionHandler() {
                @Override
                protected Map<String, Object> context(Request request) {
                    return Map.of("password", "hunter2", "tenant", "acme");
                }

                @Override
                protected void report(Throwable t) {
                    // 真实实现里上下文由上报器带走;这里只验证打码
                }
            };

            var out = new java.io.ByteArrayOutputStream();
            var original = System.err;
            System.setErr(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
            try {
                handler.render(jsonRequest("/x"), new IllegalStateException("坏了"));
            } finally {
                System.setErr(original);
            }
            lines.add(out.toString(java.nio.charset.StandardCharsets.UTF_8));

            assertThat(lines.get(0))
                    .as("""
                            把请求参数原样写进日志的上报器,会把明文密码留在
                            日志服务里 —— 而日志会被归档、检索、转发,
                            一旦进去就得当作已泄露。""")
                    .doesNotContain("hunter2")
                    .contains("********")
                    .contains("acme");
        }
    }

}
