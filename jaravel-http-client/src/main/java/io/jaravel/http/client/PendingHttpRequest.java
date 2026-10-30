package io.jaravel.http.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 一次待发出的请求(docs/14 §4.3)。
 *
 * <pre>{@code
 * Http.withToken(apiKey)
 *     .timeout(Duration.ofSeconds(5))
 *     .retry(3, Duration.ofMillis(200))
 *     .post("https://api.example.com/items", Map.of("name", "x"))
 *     .throwOnError();
 * }</pre>
 *
 * <h2>★ 超时有默认值,而且不是「无限」</h2>
 * <p>JDK 的 {@code HttpClient} 默认超时是**无限**。
 *
 * <p>这个默认值在这个项目里已经咬过两次(Jakarta Mail、通知的
 * slack/webhook 渠道),所以这里一律带默认超时:
 * 连接 10 秒、整体 30 秒。
 *
 * <p>整体 30 秒而不是更短:有些 API 的正常响应就要十几秒
 * (报表导出、批量查询)。而无论多长,**有一个上限**才是关键 ——
 * 一个挂住的外部调用会占着请求线程或 worker 的并发额度不放。
 *
 * <h2>retry 只重试值得重试的</h2>
 * <p>连接失败、5xx、429 才重试;**4xx 不重试** ——
 * 一个 404 重试一百次还是 404,而那一百次里每一次都在给对方加压。
 *
 * <p>429 与 503 带 {@code Retry-After} 时**按对方说的等** ——
 * 用自己的退避时间会在对方明确要求等 60 秒时只等 200 毫秒,
 * 于是被继续限流甚至封禁。
 */
public final class PendingHttpRequest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpTransport transport;
    private final Map<String, String> headers = new LinkedHashMap<>();

    private Duration timeout = Duration.ofSeconds(30);
    private Duration connectTimeout = Duration.ofSeconds(10);
    private int retries = 1;
    private Duration retryDelay = Duration.ofMillis(200);
    private boolean throwOnError;
    private String contentType = "application/json; charset=utf-8";

    PendingHttpRequest(HttpTransport transport) {
        this.transport = transport;
    }

    // ================= 配置 =================

    public PendingHttpRequest withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public PendingHttpRequest withHeaders(Map<String, String> values) {
        headers.putAll(values);
        return this;
    }

    /** Bearer token */
    public PendingHttpRequest withToken(String token) {
        return withHeader("Authorization", "Bearer " + token);
    }

    /** 自定义认证方案({@code withToken(t, "Basic")}) */
    public PendingHttpRequest withToken(String token, String scheme) {
        return withHeader("Authorization", scheme + " " + token);
    }

    public PendingHttpRequest withBasicAuth(String username, String password) {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return withHeader("Authorization", "Basic " + encoded);
    }

    public PendingHttpRequest accept(String mediaType) {
        return withHeader("Accept", mediaType);
    }

    public PendingHttpRequest acceptJson() {
        return accept("application/json");
    }

    /** 请求体的 Content-Type。默认 JSON */
    public PendingHttpRequest asForm() {
        this.contentType = "application/x-www-form-urlencoded; charset=utf-8";
        return this;
    }

    /**
     * 整体超时。
     *
     * <p>**不接受 null 或非正数** —— 那等于「无限」,而无限超时
     * 是这个类存在的理由之一。
     */
    public PendingHttpRequest timeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("""
                    HTTP 超时必须是正数。
                      想要「不限时」的话:没有这个选项。
                      一个挂住的外部调用会占着请求线程或 worker 的并发额度不放,
                      而对方挂起不响应是一种常见的故障模式。
                      需要很长就给一个很长的值(比如 ofMinutes(5))。""");
        }
        this.timeout = value;
        return this;
    }

    public PendingHttpRequest connectTimeout(Duration value) {
        this.connectTimeout = value;
        return this;
    }

    /**
     * 重试。
     *
     * @param times 总尝试次数(含首次)。1 = 不重试
     * @param delay 重试之间等多久。对方给了 {@code Retry-After} 时**按它的**
     */
    public PendingHttpRequest retry(int times, Duration delay) {
        if (times < 1) {
            throw new IllegalArgumentException(
                    "retry 的次数至少是 1(1 = 不重试),得到 " + times);
        }
        this.retries = times;
        this.retryDelay = delay == null ? Duration.ZERO : delay;
        return this;
    }

    /** 失败时抛,而不是返回一个失败的响应 */
    public PendingHttpRequest orThrow() {
        this.throwOnError = true;
        return this;
    }

    // ================= 发送 =================

    public HttpResponse get(String url) {
        return send("GET", url, null);
    }

    /** 带查询参数的 GET */
    public HttpResponse get(String url, Map<String, ?> query) {
        return send("GET", appendQuery(url, query), null);
    }

    public HttpResponse post(String url, Object body) {
        return send("POST", url, encode(body));
    }

    public HttpResponse put(String url, Object body) {
        return send("PUT", url, encode(body));
    }

    public HttpResponse patch(String url, Object body) {
        return send("PATCH", url, encode(body));
    }

    public HttpResponse delete(String url) {
        return send("DELETE", url, null);
    }

    public HttpResponse head(String url) {
        return send("HEAD", url, null);
    }

    /**
     * 编码请求体。
     *
     * <p>{@code String} 原样发;其它序列化成 JSON(或表单,
     * 看 {@link #asForm()})。
     *
     * <p>原样发 String 是必要的:有些 API 要一段特定格式的
     * 纯文本或 XML,而把它再 JSON 编码一遍会变成一个带引号的字符串。
     */
    private String encode(Object body) {
        if (body == null) return null;
        if (body instanceof String text) return text;

        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            if (!(body instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("""
                        asForm() 的请求体必须是一个 Map,得到 %s。
                          表单编码是 key=value&key=value,而一个任意对象
                          没有明确的表单表示。"""
                        .formatted(body.getClass().getSimpleName()));
            }
            return formEncode(map);
        }

        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new HttpResponse.HttpRequestException("""
                    请求体无法序列化成 JSON:%s
                      常见原因:放了一个实体对象或一个 Lambda。"""
                    .formatted(e.getMessage()), e);
        }
    }

    private static String formEncode(Map<?, ?> values) {
        var out = new StringBuilder();
        values.forEach((key, value) -> {
            if (!out.isEmpty()) out.append('&');
            out.append(urlEncode(String.valueOf(key)))
               .append('=')
               .append(urlEncode(value == null ? "" : String.valueOf(value)));
        });
        return out.toString();
    }

    private static String appendQuery(String url, Map<String, ?> query) {
        if (query == null || query.isEmpty()) return url;

        var out = new StringBuilder(url);
        out.append(url.contains("?") ? '&' : '?');
        out.append(formEncode(query));
        return out.toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 发出去,按需重试。
     *
     * <p>重试的判定在 {@link #worthRetrying} 里 —— 它是这个方法里
     * 唯一需要想清楚的部分。
     */
    private HttpResponse send(String method, String url, String body) {
        var requestHeaders = new LinkedHashMap<>(headers);
        if (body != null && !requestHeaders.containsKey("Content-Type")) {
            requestHeaders.put("Content-Type", contentType);
        }

        var spec = new HttpRequestSpec(method, url, requestHeaders, body, timeout);

        HttpResponse response = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            response = transport.send(spec, connectTimeout);

            if (!worthRetrying(response) || attempt == retries) break;

            sleepBeforeRetry(response, attempt);
        }

        return throwOnError ? response.throwOnError() : response;
    }

    /**
     * 这个响应值得重试吗。
     *
     * <ul>
     *   <li>连接失败 → 是(可能是瞬时的网络问题);</li>
     *   <li>5xx → 是(对方的内部错误常常是瞬时的);</li>
     *   <li>429 → 是(限流,等一下再来);</li>
     *   <li>**其它 4xx → 不是**。</li>
     * </ul>
     *
     * <p>最后一条是关键:一个 404 / 401 / 422 重试一百次还是同样的结果,
     * 而那一百次里每一次都在给对方加压 —— 并且在一个有速率限制的
     * API 上会把配额烧掉。
     */
    private static boolean worthRetrying(HttpResponse response) {
        if (response.connectionFailed()) return true;
        if (response.status() == 429) return true;
        return response.serverError();
    }

    /**
     * 重试前等多久。
     *
     * <p>**对方给了 {@code Retry-After} 就按它的** ——
     * 用自己的退避时间会在对方明确要求等 60 秒时只等 200 毫秒,
     * 于是被继续限流甚至封禁。
     *
     * <p>没给的话指数退避:{@code delay * 2^(attempt-1)}。
     * 固定间隔在对方过载时会变成一轮同步的重试风暴,
     * 而那正是对方最需要喘息的时候(与队列的 backoff 同一个理由)。
     */
    private void sleepBeforeRetry(HttpResponse response, int attempt) {
        Duration wait = response.retryAfter()
                .orElseGet(() -> retryDelay.multipliedBy(1L << (attempt - 1)));

        // 上限 60 秒:一个恶意或写错的 Retry-After(比如 86400)
        // 会让这个线程挂一整天
        if (wait.compareTo(Duration.ofSeconds(60)) > 0) {
            wait = Duration.ofSeconds(60);
        }

        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
