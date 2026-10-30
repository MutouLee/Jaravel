package io.jaravel.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Http 门面:请求构造、响应处理、重试、fake 与断言 */
class HttpTest {

    @AfterEach
    void tearDown() {
        Http.reset();
    }

    // ================= 请求构造 =================

    @Nested
    @DisplayName("请求构造")
    class Building {

        @Test
        @DisplayName("头部、token、basic auth")
        void headers() {
            Http.fake(Map.of("*", Http.response(Map.of("ok", true))));

            Http.withToken("abc123").get("https://api.example.com/x");
            Http.withBasicAuth("user", "pass").get("https://api.example.com/y");
            Http.withHeader("X-Key", "k").get("https://api.example.com/z");

            var sent = Http.recorded();
            assertThat(sent.get(0).header("Authorization")).contains("Bearer abc123");
            assertThat(sent.get(1).header("Authorization")).contains("Basic dXNlcjpwYXNz");
            assertThat(sent.get(2).header("X-Key")).contains("k");
        }

        @Test
        @DisplayName("★ 头部查询不区分大小写 —— HTTP 头本来就不敏感")
        void headersAreCaseInsensitive() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            Http.withHeader("X-Api-Key", "k").get("https://api.example.com/x");

            var request = Http.recorded().get(0);
            assertThat(request.hasHeader("x-api-key")).isTrue();
            assertThat(request.hasHeader("X-API-KEY")).isTrue();
            assertThat(request.hasHeader("X-Api-Key", "k")).isTrue();
        }

        @Test
        @DisplayName("JSON 请求体与 Content-Type")
        void jsonBody() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            Http.post("https://api.example.com/items", Map.of("name", "螺丝"));

            var request = Http.recorded().get(0);
            assertThat(request.body()).contains("\"name\"").contains("螺丝");
            assertThat(request.header("Content-Type")).get()
                    .asString().contains("application/json");
        }

        @Test
        @DisplayName("★ String 请求体原样发,不再 JSON 编码一遍")
        void stringBodyIsVerbatim() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            Http.post("https://api.example.com/x", "<xml>raw</xml>");

            assertThat(Http.recorded().get(0).body())
                    .as("有些 API 要特定格式的纯文本或 XML,"
                            + "再编码一遍会变成一个带引号的字符串")
                    .isEqualTo("<xml>raw</xml>");
        }

        @Test
        @DisplayName("表单编码")
        void formBody() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            Http.asForm().post("https://api.example.com/x",
                    Map.of("name", "a b", "n", 1));

            var request = Http.recorded().get(0);
            assertThat(request.body()).contains("name=a+b").contains("n=1");
            assertThat(request.header("Content-Type")).get()
                    .asString().contains("x-www-form-urlencoded");
        }

        @Test
        @DisplayName("asForm 的请求体不是 Map 时报错")
        void formNeedsAMap() {
            Http.fake(Map.of("*", Http.response(Map.of())));

            assertThatThrownBy(() -> Http.asForm()
                    .post("https://api.example.com/x", List.of(1, 2)))
                    .hasMessageContaining("必须是一个 Map");
        }

        @Test
        @DisplayName("查询参数被 URL 编码并追加")
        void queryParams() {
            Http.fake(Map.of("*", Http.response(Map.of())));

            Http.get("https://api.example.com/search", Map.of("q", "a b"));
            Http.get("https://api.example.com/x?已有=1", Map.of("q", "2"));

            assertThat(Http.recorded().get(0).url()).contains("?q=a+b");
            assertThat(Http.recorded().get(1).url())
                    .as("已有查询串时用 & 接")
                    .contains("&q=2");
        }

        @Test
        @DisplayName("★ 超时不接受 0 或 null —— 那等于无限")
        void timeoutMustBePositive() {
            assertThatThrownBy(() -> Http.timeout(Duration.ZERO))
                    .hasMessageContaining("没有这个选项");
            assertThatThrownBy(() -> Http.timeout(null))
                    .hasMessageContaining("占着请求线程");
        }

        @Test
        @DisplayName("超时有默认值")
        void timeoutHasADefault() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            Http.get("https://api.example.com/x");

            assertThat(Http.recorded().get(0).timeout())
                    .as("JDK 的默认超时是无限,而那个默认值"
                            + "在这个项目里已经咬过两次")
                    .isEqualTo(Duration.ofSeconds(30));
        }
    }

    // ================= 响应 =================

    @Nested
    @DisplayName("响应")
    class Responses {

        @ParameterizedTest
        @CsvSource({"200,true,false,false", "204,true,false,false",
                "301,false,false,false", "404,false,true,false",
                "500,false,false,true"})
        @DisplayName("状态分类")
        void statusCategories(int status, boolean ok, boolean clientErr, boolean serverErr) {
            Http.fake(Map.of("*", Http.status(status)));
            var response = Http.get("https://api.example.com/x");

            assertThat(response.successful()).isEqualTo(ok);
            assertThat(response.clientError()).isEqualTo(clientErr);
            assertThat(response.serverError()).isEqualTo(serverErr);
        }

        @Test
        @DisplayName("JSON 解析")
        void jsonParsing() {
            Http.fake(Map.of("*", Http.response(
                    Map.of("items", List.of("a", "b"), "total", 2))));

            var response = Http.get("https://api.example.com/x");

            assertThat(response.json().get("total").asInt()).isEqualTo(2);
            assertThat(response.json("items")).isPresent();
            assertThat(response.json("missing")).isEmpty();
        }

        @Test
        @DisplayName("解析成一个类型")
        void typedParsing() {
            record Item(String name, int qty) {}

            Http.fake(Map.of("*", Http.response(Map.of("name", "螺丝", "qty", 3))));

            assertThat(Http.get("https://api.example.com/x").json(Item.class))
                    .isEqualTo(new Item("螺丝", 3));
        }

        @Test
        @DisplayName("★ 在失败的响应上调 json() 会报错,不返回空数据")
        void jsonOnFailedResponseThrows() {
            Http.fake(Map.of("*", HttpResponse.of(500,
                    "<html>Internal Server Error</html>", Map.of(), "(fake)")));

            var response = Http.get("https://api.example.com/x");

            assertThat(response.failed()).isTrue();
            assertThatThrownBy(response::json)
                    .as("返回空数据会让调用方带着空结果继续走,"
                            + "而真正的原因(对方挂了)就丢了")
                    .isInstanceOf(HttpResponse.HttpRequestException.class)
                    .hasMessageContaining("在一个失败的响应上调用了 json()")
                    .hasMessageContaining("throwOnError");
        }

        @Test
        @DisplayName("非 JSON 的响应体报错时带上 Content-Type")
        void nonJsonBodyExplains() {
            Http.fake(Map.of("*", HttpResponse.of(200, "not json",
                    Map.of("content-type", List.of("text/plain")), "(fake)")));

            assertThatThrownBy(() -> Http.get("https://api.example.com/x").json())
                    .hasMessageContaining("不是合法的 JSON")
                    .hasMessageContaining("text/plain");
        }

        @Test
        @DisplayName("throwOnError / throwOnServerError")
        void throwing() {
            Http.fake(Map.of("*", Http.status(404)));
            var notFound = Http.get("https://api.example.com/x");

            assertThatThrownBy(notFound::throwOnError)
                    .hasMessageContaining("返回了 404");
            assertThat(notFound.throwOnServerError())
                    .as("4xx 常常是业务上合法的(404 = 不存在)")
                    .isSameAs(notFound);

            Http.fake(Map.of("*", Http.status(503)));
            assertThatThrownBy(() -> Http.get("https://api.example.com/y")
                    .throwOnServerError())
                    .hasMessageContaining("503");
        }

        @Test
        @DisplayName("orThrow() 在请求上链")
        void orThrowOnRequest() {
            Http.fake(Map.of("*", Http.status(500)));

            assertThatThrownBy(() -> Http.orThrow().get("https://api.example.com/x"))
                    .hasMessageContaining("500");
        }

        @Test
        @DisplayName("响应头不区分大小写")
        void responseHeadersAreCaseInsensitive() {
            Http.fake(Map.of("*", HttpResponse.of(200, "{}",
                    Map.of("X-Request-Id", List.of("abc")), "(fake)")));

            var response = Http.get("https://api.example.com/x");
            assertThat(response.header("x-request-id")).contains("abc");
            assertThat(response.hasHeader("X-REQUEST-ID")).isTrue();
        }

        @Test
        @DisplayName("★ Retry-After 两种格式都认")
        void retryAfterFormats() {
            Http.fake(Map.of("*", Http.rateLimited(30)));
            assertThat(Http.get("https://api.example.com/x").retryAfter())
                    .contains(Duration.ofSeconds(30));

            String httpDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .plusSeconds(120)
                    .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);

            Http.fake(Map.of("*", HttpResponse.of(429, "",
                    Map.of("retry-after", List.of(httpDate)), "(fake)")));

            assertThat(Http.get("https://api.example.com/y").retryAfter())
                    .as("只认秒数的话遇到日期格式会退回默认退避,"
                            + "而那通常比对方要求的短")
                    .isPresent();
        }

        @Test
        @DisplayName("连接失败是 status=0,与「服务器返回错误」分开")
        void connectionFailure() {
            Http.fake(Map.of("*", Http.failedConnection()));

            var response = Http.get("https://api.example.com/x");
            assertThat(response.connectionFailed()).isTrue();
            assertThat(response.failed()).isTrue();
            assertThat(response.serverError())
                    .as("连不上 ≠ 对方返回 5xx —— 前者该重试,后者看状态码")
                    .isFalse();
        }
    }

    // ================= 重试 =================

    @Nested
    @DisplayName("重试")
    class Retrying {

        /** 按顺序返回一串预设响应,并计数 */
        private static final class Sequence implements HttpTransport {
            private final List<HttpResponse> responses;
            private final AtomicInteger calls = new AtomicInteger();

            Sequence(HttpResponse... responses) {
                this.responses = List.of(responses);
            }

            @Override
            public HttpResponse send(HttpRequestSpec request, Duration connectTimeout) {
                int index = calls.getAndIncrement();
                return responses.get(Math.min(index, responses.size() - 1));
            }

            int calls() {
                return calls.get();
            }
        }

        @Test
        @DisplayName("★ 5xx 会重试,直到成功")
        void retriesServerErrors() {
            var sequence = new Sequence(Http.status(503), Http.status(503),
                    Http.response(Map.of("ok", true)));
            Http.use(sequence);

            var response = Http.retry(3, Duration.ZERO).get("https://api.example.com/x");

            assertThat(sequence.calls()).isEqualTo(3);
            assertThat(response.successful()).isTrue();
        }

        @Test
        @DisplayName("★ 连接失败会重试")
        void retriesConnectionFailures() {
            var sequence = new Sequence(Http.failedConnection(),
                    Http.response(Map.of("ok", true)));
            Http.use(sequence);

            assertThat(Http.retry(2, Duration.ZERO)
                    .get("https://api.example.com/x").successful()).isTrue();
            assertThat(sequence.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("★ 429 会重试(限流,等一下再来)")
        void retriesRateLimits() {
            var sequence = new Sequence(Http.rateLimited(0),
                    Http.response(Map.of("ok", true)));
            Http.use(sequence);

            assertThat(Http.retry(2, Duration.ZERO)
                    .get("https://api.example.com/x").successful()).isTrue();
            assertThat(sequence.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("★ 其它 4xx **不**重试")
        void doesNotRetryClientErrors() {
            for (int status : new int[] {400, 401, 403, 404, 422}) {
                var sequence = new Sequence(Http.status(status));
                Http.use(sequence);

                Http.retry(5, Duration.ZERO).get("https://api.example.com/x");

                assertThat(sequence.calls())
                        .as("一个 %d 重试一百次还是 %d,而那一百次里每一次"
                                + "都在给对方加压,并且在有速率限制的 API 上"
                                + "会把配额烧掉", status, status)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("重试耗尽后返回最后那个失败的响应")
        void returnsLastFailure() {
            var sequence = new Sequence(Http.status(503));
            Http.use(sequence);

            var response = Http.retry(3, Duration.ZERO).get("https://api.example.com/x");

            assertThat(sequence.calls()).isEqualTo(3);
            assertThat(response.status()).isEqualTo(503);
        }

        @Test
        @DisplayName("retry(1) 表示不重试")
        void retryOneMeansNoRetry() {
            var sequence = new Sequence(Http.status(500));
            Http.use(sequence);

            Http.retry(1, Duration.ZERO).get("https://api.example.com/x");
            assertThat(sequence.calls()).isEqualTo(1);
        }

        @Test
        @DisplayName("retry 次数小于 1 时报错")
        void retryCountMustBePositive() {
            assertThatThrownBy(() -> Http.retry(0, Duration.ZERO))
                    .hasMessageContaining("至少是 1");
        }
    }

    // ================= fake =================

    @Nested
    @DisplayName("Http.fake()")
    class Faking {

        @Test
        @DisplayName("按模式返回不同的响应")
        void patternMatching() {
            Http.fake(Map.of(
                    "https://api.supplier.com/*", Http.response(Map.of("items", List.of())),
                    "https://api.slow.com/*", Http.failedConnection()));

            assertThat(Http.get("https://api.supplier.com/v1/items").successful()).isTrue();
            assertThat(Http.get("https://api.slow.com/x").connectionFailed()).isTrue();
        }

        @Test
        @DisplayName("★ * 匹配任意字符,含斜杠")
        void starMatchesSlashes() {
            Http.fake(Map.of("https://api.example.com/*", Http.response(Map.of())));

            assertThat(Http.get("https://api.example.com/v1/deep/path").successful())
                    .as("glob 语义下 * 不跨 /,但这里的自然意图是"
                            + "「这个域名下的所有路径」")
                    .isTrue();
        }

        @Test
        @DisplayName("★ 未匹配的请求**报错**,并列出已注册的模式")
        void strayRequestsFail() {
            Http.fake(Map.of("https://api.example.com/items", Http.response(Map.of())));

            assertThatThrownBy(() -> Http.get("https://api.example.com/itmes"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("没有匹配任何模式")
                    .hasMessageContaining("/items")
                    .hasMessageContaining("URL 写错的测试通过");
        }

        @Test
        @DisplayName("★ allowStray() 恢复 Laravel 的宽松行为")
        void allowStray() {
            Http.fake(Map.of("https://api.example.com/items", Http.response(Map.of())));
            Http.allowStray();

            assertThat(Http.get("https://api.example.com/itmes").status()).isEqualTo(200);
        }

        @Test
        @DisplayName("假响应里的 url 被换成真实请求的 URL")
        void urlIsRewritten() {
            Http.fake(Map.of("*", Http.response(Map.of())));

            assertThat(Http.get("https://api.example.com/real").url())
                    .as("断言与错误消息里要看到真实的 URL,"
                            + "而假响应构造时写的是 (fake)")
                    .isEqualTo("https://api.example.com/real");
        }

        @Test
        @DisplayName("Http.fake(单个响应)对所有请求生效")
        void singleResponseForAll() {
            Http.fake(Http.response(Map.of("ok", true)));

            assertThat(Http.get("https://anywhere.example/x").successful()).isTrue();
            assertThat(Http.post("https://other.example/y", Map.of()).successful()).isTrue();
        }
    }

    // ================= 断言 =================

    @Nested
    @DisplayName("断言")
    class Assertions {

        @Test
        @DisplayName("assertSent 带谓词 —— docs/14 §4.3 的那个形式")
        void assertSentWithPredicate() {
            Http.fake(Map.of("*", Http.response(Map.of("items", List.of()))));

            Http.withHeader("X-Key", "k").get("https://api.example.com/items");

            Http.assertSent(r -> r.url().contains("/items") && r.hasHeader("X-Key"));

            assertThatThrownBy(() -> Http.assertSent(r -> r.url().contains("/orders")))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("实际发出的 1 个请求");
        }

        @Test
        @DisplayName("assertSentTo 按模式")
        void assertSentToPattern() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            Http.get("https://api.example.com/v1/items");

            Http.assertSentTo("https://api.example.com/*");
            assertThatThrownBy(() -> Http.assertSentTo("https://other.example/*"))
                    .isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("assertSentCount / assertNothingSent / assertNotSent")
        void countAssertions() {
            Http.fake(Map.of("*", Http.response(Map.of())));

            Http.assertNothingSent();

            Http.get("https://api.example.com/a");
            Http.get("https://api.example.com/b");

            Http.assertSentCount(2);
            Http.assertNotSent(r -> r.url().contains("/c"));

            assertThatThrownBy(() -> Http.assertSentCount(3))
                    .hasMessageContaining("实际 2 个");
            assertThatThrownBy(Http::assertNothingSent)
                    .isInstanceOf(AssertionError.class);
            // ⚠ 这里用 endsWith 而不是 contains("/a"):
            // "https://api.example.com/a" 里的 **//api** 本身就含 "/a",
            // 所以 contains("/a") 会匹配到**两个**请求。
            //
            // 这是写这个测试时当场踩到的,而用户也会踩 ——
            // 在 URL 上做 contains 时记得前缀里有 "//" 与域名。
            assertThatThrownBy(() -> Http.assertNotSent(r -> r.url().endsWith("/a")))
                    .hasMessageContaining("但有 1 个");
        }

        @Test
        @DisplayName("★ 在 URL 上做 contains 时小心 //host 这段前缀")
        void containsOnUrlsIsSubtle() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            Http.get("https://api.example.com/b");

            Http.assertSent(r -> r.url().contains("/a"));   // 匹配到 "//api" 里的 /a

            assertThat(Http.recorded().get(0).url().contains("/a"))
                    .as("//api 里的第二个 / 加上 a 就是 /a —— "
                            + "URL 断言用 endsWith 或 urlMatches 更可靠")
                    .isTrue();
        }

        @Test
        @DisplayName("★ 断言请求体 —— 「发了正确的字段」")
        void assertOnBody() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            Http.post("https://api.example.com/items", Map.of("name", "螺丝"));

            Http.assertSent(r -> r.bodyContains("螺丝"));
            Http.assertSent(r -> r.method().equals("POST") && r.hasBody());
        }

        @Test
        @DisplayName("不在 fake 模式时查记录会报错")
        void assertingWithoutFakeFails() {
            assertThatThrownBy(Http::assertNothingSent)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不在 fake 模式");
        }

        @Test
        @DisplayName("stopFaking 之后恢复真实实现")
        void stopFaking() {
            Http.fake(Map.of("*", Http.response(Map.of())));
            assertThat(Http.isFaking()).isTrue();

            Http.stopFaking();
            assertThat(Http.isFaking()).isFalse();
        }
    }

    // ================= URL 模式 =================

    @Nested
    @DisplayName("URL 模式")
    class Patterns {

        @ParameterizedTest
        @CsvSource({
                "*,https://any.example/x,true",
                "https://a.example/*,https://a.example/x/y,true",
                "https://a.example/*,https://b.example/x,false",
                "https://a.example/items,https://a.example/items,true",
                "https://a.example/items,https://a.example/items/1,false",
                "*/items,https://a.example/v1/items,true"})
        @DisplayName("匹配规则")
        void matching(String pattern, String url, boolean expected) {
            assertThat(HttpRequestSpec.UrlPattern.matches(pattern, url))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("★ URL 里的正则元字符被转义")
        void regexCharactersAreEscaped() {
            assertThat(HttpRequestSpec.UrlPattern.matches(
                    "https://a.example/x?q=1", "https://a.example/x?q=1"))
                    .as("? 与 . 在正则里有含义 —— 不转义的话"
                            + "一个带查询串的模式会匹配到意外的 URL")
                    .isTrue();
            assertThat(HttpRequestSpec.UrlPattern.matches(
                    "https://a.example/x?q=1", "https://aXexample/xZq=1"))
                    .isFalse();
        }
    }

    // ================= 真实传输层 =================

    @Test
    @DisplayName("★ 非法 URL 立刻报错,不包成一次失败的调用")
    void malformedUrlThrows() {
        var jdk = new HttpTransport.Jdk();
        var request = new HttpRequestSpec("GET", "not a url", Map.of(), null,
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> jdk.send(request, Duration.ofSeconds(1)))
                .as("包成 status=0 会让它被重试三次然后当成「对方挂了」—— "
                        + "而它是调用方的 bug")
                .isInstanceOf(HttpResponse.HttpRequestException.class)
                .hasMessageContaining("URL 不合法");
    }

    @Test
    @DisplayName("★ 连不上的地址返回 status=0,不抛")
    void unreachableHostBecomesStatusZero() {
        var jdk = new HttpTransport.Jdk();
        // 保留给文档用的 TEST-NET-1 地址段,保证不可路由
        var request = new HttpRequestSpec("GET", "http://192.0.2.1:9/x",
                Map.of(), null, Duration.ofMillis(300));

        var response = jdk.send(request, Duration.ofMillis(200));

        assertThat(response.connectionFailed())
                .as("让重试逻辑只有一条路径 —— 一个抛异常一个返回值的话"
                        + "那段代码会分叉")
                .isTrue();
        assertThat(response.failure()).isNotNull();
    }
}
