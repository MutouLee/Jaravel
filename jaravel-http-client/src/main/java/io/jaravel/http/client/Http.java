package io.jaravel.http.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * HTTP 客户端的静态门面(docs/14 §4.3)。
 *
 * <pre>{@code
 * Http.get("https://api.example.com/items");
 * Http.withToken(key).post(url, Map.of("name", "x"));
 * Http.timeout(Duration.ofSeconds(5)).retry(3, ofMillis(200)).get(url);
 * }</pre>
 *
 * <h2>关于版本:docs 自己是矛盾的</h2>
 * <p>docs/14 §4.3 说「应在 v0.4 提供」并给了理由(外部 API 调用是
 * 现代应用的常见需求);docs/17 的路线图把它列在 0.5。
 *
 * <p>按 §4.3 做了,因为有一个具体的当下需求:通知的
 * slack / webhook 渠道与调度的心跳 ping 已经各自手写了一遍
 * {@code HttpClient}(含各自的超时与错误处理)——
 * 那三处在没有这个门面之前注定会分叉。
 *
 * <h2>★ fake 下**未匹配的请求会报错**,与 Laravel 不同</h2>
 * <p>Laravel 的 {@code Http::fake([...])} 对没匹配上的 URL 返回
 * 一个默认的 200 空响应。
 *
 * <p>那个默认值有一个很具体的后果:**URL 写错时测试仍然通过**。
 * 被测代码调了 {@code /itmes},fake 里注册的是 {@code /items},
 * 于是它拿到一个 200 空响应、解析出一个空列表、然后
 * 「同步了 0 个商品」—— 而那正是这个测试要防的那个 bug。
 *
 * <p>所以这里默认**报错**,并把已注册的模式列出来。
 * Laravel 后来也加了 {@code preventStrayRequests()} 来做这件事 ——
 * 这里只是把它变成默认。
 *
 * <p>需要 Laravel 的宽松行为就调 {@link #allowStray()}。
 */
public final class Http {

    private static volatile HttpTransport transport = new HttpTransport.Jdk();
    private static volatile FakeTransport fake;

    private Http() {
    }

    /** 换掉底层实现 —— 极少需要,除了测试 */
    public static void use(HttpTransport implementation) {
        transport = implementation;
    }

    /** 测试结束后清掉 —— 静态状态不清会漏到下一个测试 */
    public static void reset() {
        transport = new HttpTransport.Jdk();
        fake = null;
    }

    private static HttpTransport active() {
        FakeTransport current = fake;
        return current != null ? current : transport;
    }

    // ================= 入口 =================

    public static PendingHttpRequest request() {
        return new PendingHttpRequest(active());
    }

    public static PendingHttpRequest withHeader(String name, String value) {
        return request().withHeader(name, value);
    }

    public static PendingHttpRequest withHeaders(Map<String, String> values) {
        return request().withHeaders(values);
    }

    public static PendingHttpRequest withToken(String token) {
        return request().withToken(token);
    }

    public static PendingHttpRequest withBasicAuth(String username, String password) {
        return request().withBasicAuth(username, password);
    }

    public static PendingHttpRequest timeout(Duration value) {
        return request().timeout(value);
    }

    public static PendingHttpRequest retry(int times, Duration delay) {
        return request().retry(times, delay);
    }

    public static PendingHttpRequest acceptJson() {
        return request().acceptJson();
    }

    public static PendingHttpRequest asForm() {
        return request().asForm();
    }

    /** 失败时抛 */
    public static PendingHttpRequest orThrow() {
        return request().orThrow();
    }

    // ================= 简写 =================

    public static HttpResponse get(String url) {
        return request().get(url);
    }

    public static HttpResponse get(String url, Map<String, ?> query) {
        return request().get(url, query);
    }

    public static HttpResponse post(String url, Object body) {
        return request().post(url, body);
    }

    public static HttpResponse put(String url, Object body) {
        return request().put(url, body);
    }

    public static HttpResponse patch(String url, Object body) {
        return request().patch(url, body);
    }

    public static HttpResponse delete(String url) {
        return request().delete(url);
    }

    // ================= fake =================

    /**
     * 所有请求都返回一个固定响应。
     *
     * <p>只在「不关心具体 URL」时用 —— 通常还是给一组模式更好,
     * 那样 URL 写错会被抓到。
     */
    public static void fake(HttpResponse response) {
        fake = new FakeTransport(Map.of("*", () -> response));
    }

    /**
     * 按 URL 模式返回不同的响应(docs/14 §4.3)。
     *
     * <pre>{@code
     * Http.fake(Map.of(
     *     "https://api.supplier.com/*", Http.response(Map.of("items", List.of()), 200),
     *     "https://api.slow.com/*",     Http.failedConnection()));
     * }</pre>
     *
     * <p>⚠ 没匹配上的请求**报错** —— 见类注释。
     */
    public static void fake(Map<String, HttpResponse> responses) {
        var suppliers = new LinkedHashMap<String, java.util.function.Supplier<HttpResponse>>();
        responses.forEach((pattern, response) -> suppliers.put(pattern, () -> response));
        fake = new FakeTransport(suppliers);
    }

    /** 一个空的 fake —— 配合 {@code assertNothingSent()} 用 */
    public static void fake() {
        fake = new FakeTransport(Map.of());
    }

    /**
     * 允许未匹配的请求返回 200 空响应(Laravel 的默认行为)。
     *
     * <p>**只在你确定不在意 URL 时用。** 它会让 URL 写错的测试通过 ——
     * 见类注释里的例子。
     */
    public static void allowStray() {
        requireFake().allowStray = true;
    }

    public static void stopFaking() {
        fake = null;
    }

    public static boolean isFaking() {
        return fake != null;
    }

    // ================= 构造假响应 =================

    /** JSON 响应 */
    public static HttpResponse response(Object body, int status) {
        String json;
        try {
            json = body instanceof String text ? text
                    : new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "假响应的 body 无法序列化成 JSON:" + e.getMessage(), e);
        }
        return HttpResponse.of(status, json,
                Map.of("content-type", List.of("application/json")), "(fake)");
    }

    public static HttpResponse response(Object body) {
        return response(body, 200);
    }

    /** 一个带状态码的空响应 */
    public static HttpResponse status(int code) {
        return HttpResponse.of(code, "", Map.of(), "(fake)");
    }

    /** 连接失败 —— 测重试与降级时用 */
    public static HttpResponse failedConnection() {
        return HttpResponse.connectionFailure("(fake)",
                new java.io.IOException("假的连接失败"));
    }

    /** 带 Retry-After 的 429 —— 测「按对方说的等」时用 */
    public static HttpResponse rateLimited(int retryAfterSeconds) {
        return HttpResponse.of(429, "",
                Map.of("retry-after", List.of(String.valueOf(retryAfterSeconds))),
                "(fake)");
    }

    // ================= 断言 =================

    public static void assertSent(Predicate<HttpRequestSpec> matching) {
        var sent = requireFake().recorded();

        if (sent.stream().noneMatch(matching)) {
            throw new AssertionError("""
                    没有找到满足条件的请求。
                      实际发出的 %d 个请求:
                    %s"""
                    .formatted(sent.size(), listRequests(sent)));
        }
    }

    /** 断言发给了某个 URL 模式 */
    public static void assertSentTo(String urlPattern) {
        assertSent(r -> r.urlMatches(urlPattern));
    }

    public static void assertSentCount(int expected) {
        var sent = requireFake().recorded();
        if (sent.size() != expected) {
            throw new AssertionError("""
                    期望发出 %d 个请求,实际 %d 个:
                    %s"""
                    .formatted(expected, sent.size(), listRequests(sent)));
        }
    }

    public static void assertNotSent(Predicate<HttpRequestSpec> matching) {
        var sent = requireFake().recorded();
        var matches = sent.stream().filter(matching).toList();

        if (!matches.isEmpty()) {
            throw new AssertionError(
                    "期望没有满足条件的请求,但有 %d 个:\n%s"
                            .formatted(matches.size(), listRequests(matches)));
        }
    }

    public static void assertNothingSent() {
        var sent = requireFake().recorded();
        if (!sent.isEmpty()) {
            throw new AssertionError(
                    "期望没有发出任何请求,实际 %d 个:\n%s"
                            .formatted(sent.size(), listRequests(sent)));
        }
    }

    /** 发出的请求 —— 复杂断言时直接看它 */
    public static List<HttpRequestSpec> recorded() {
        return requireFake().recorded();
    }

    private static String listRequests(List<HttpRequestSpec> requests) {
        if (requests.isEmpty()) return "      (没有)";
        var out = new StringBuilder();
        for (HttpRequestSpec request : requests) {
            out.append("      ").append(request).append('\n');
        }
        return out.toString();
    }

    private static FakeTransport requireFake() {
        FakeTransport current = fake;
        if (current == null) {
            throw new IllegalStateException("""
                    当前不在 fake 模式,没有记录可查。
                      在被测代码**之前**调 Http.fake(...) ——
                      之后调的话那些请求已经真的发出去了。""");
        }
        return current;
    }

    /**
     * 假的传输层。
     *
     * <p>它同时做两件事:按模式返回响应、记录所有请求。
     * 两件事放一起是因为它们要看同一批请求 ——
     * 分开的话「记录了但没匹配」与「匹配了但没记录」会出现不一致。
     */
    static final class FakeTransport implements HttpTransport {

        private final Map<String, java.util.function.Supplier<HttpResponse>> responses;
        private final List<HttpRequestSpec> requests =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        volatile boolean allowStray;

        FakeTransport(Map<String, java.util.function.Supplier<HttpResponse>> responses) {
            this.responses = new LinkedHashMap<>(responses);
        }

        @Override
        public HttpResponse send(HttpRequestSpec request, Duration connectTimeout) {
            requests.add(request);

            for (var entry : responses.entrySet()) {
                if (HttpRequestSpec.UrlPattern.matches(entry.getKey(), request.url())) {
                    HttpResponse canned = entry.getValue().get();
                    // 把 url 换成真实请求的 —— 断言与错误消息里要看到它,
                    // 而假响应构造时写的是 "(fake)"
                    return new HttpResponse(canned.status(), canned.body(),
                            canned.headers(), request.url(), canned.failure());
                }
            }

            if (allowStray) {
                return HttpResponse.of(200, "", Map.of(), request.url());
            }

            throw new AssertionError("""
                    fake 模式下发出了一个**没有匹配任何模式**的请求:
                      %s %s

                      已注册的模式:%s

                      这里报错而不是返回 200 空响应(Laravel 的默认行为),
                      因为那会让 **URL 写错的测试通过**:
                      被测代码调了 /itmes、fake 里注册的是 /items,
                      于是它拿到 200 空响应、解析出空列表、然后
                      「同步了 0 个商品」—— 而那正是这个测试要防的 bug。

                      要 Laravel 的宽松行为:Http.allowStray()。"""
                    .formatted(request.method(), request.url(),
                               responses.isEmpty() ? "(一个都没有)" : responses.keySet()));
        }

        List<HttpRequestSpec> recorded() {
            return List.copyOf(requests);
        }
    }
}
