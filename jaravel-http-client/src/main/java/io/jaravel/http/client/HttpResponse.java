package io.jaravel.http.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 一次 HTTP 调用的响应(docs/14 §4.3)。
 *
 * <pre>{@code
 * var response = Http.get("https://api.example.com/items");
 *
 * if (response.successful()) {
 *     List<Item> items = response.json("items", new TypeReference<>() {});
 * }
 * }</pre>
 *
 * <h2>★ 默认**不**在 4xx/5xx 时抛异常 —— 以及为此补的一道闸</h2>
 * <p>与 Laravel 一致:失败的响应仍然是一个正常返回的 {@code Response},
 * 要抛就显式调 {@link #throwOnError()}。
 *
 * <p>这个默认值有一个真实的陷阱:一个 500 响应的 body 通常是
 * 一段 HTML 错误页,而 {@code json()} 在上面解析失败 ——
 * 如果那时返回一个空 map,调用方会带着空数据继续走下去,
 * 而真正的原因(对方挂了)已经丢了。
 *
 * <p>所以 {@link #json()} 在**响应失败时**抛,并在消息里问
 * 「是不是想调 throwOnError()」。这保留了 Laravel 的默认行为
 * (检查状态码是调用方的事),同时让「忘了检查」在真正会出问题的
 * 那一步变响。
 *
 * @param status  HTTP 状态码。0 表示连接失败(见 {@link #connectionFailed()})
 * @param body    响应体
 * @param headers 响应头。键是**小写**的 —— HTTP 头大小写不敏感,
 *                而不同服务器返回的大小写不同
 */
public record HttpResponse(int status, String body, Map<String, List<String>> headers,
                           String url, Throwable failure) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public HttpResponse {
        body = body == null ? "" : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /**
     * 构造一个响应。
     *
     * <p>**public** 因为自定义的 {@link HttpTransport} 需要它 ——
     * 而写一个自定义 transport(对接 OkHttp、或者一个带埋点的包装)
     * 是这个模块预期的扩展方式。
     */
    public static HttpResponse of(int status, String body,
                           Map<String, List<String>> headers, String url) {
        return new HttpResponse(status, body, lowercaseKeys(headers), url, null);
    }

    /** 连接层面的失败 —— 连不上、超时、DNS 解析不了。public 的理由同上 */
    public static HttpResponse connectionFailure(String url, Throwable cause) {
        return new HttpResponse(0, "", Map.of(), url, cause);
    }

    private static Map<String, List<String>> lowercaseKeys(Map<String, List<String>> in) {
        if (in == null) return Map.of();

        var out = new java.util.LinkedHashMap<String, List<String>>();
        in.forEach((key, values) -> out.put(key.toLowerCase(), List.copyOf(values)));
        return Map.copyOf(out);
    }

    // ================= 状态 =================

    public boolean successful() {
        return status >= 200 && status < 300;
    }

    public boolean redirect() {
        return status >= 300 && status < 400;
    }

    public boolean clientError() {
        return status >= 400 && status < 500;
    }

    public boolean serverError() {
        return status >= 500;
    }

    public boolean failed() {
        return !successful();
    }

    /**
     * 连接根本没建立 / 超时。
     *
     * <p>与「服务器返回了错误」是两件事:前者该重试,
     * 后者看状态码 —— 一个 404 重试一百次还是 404。
     */
    public boolean connectionFailed() {
        return status == 0;
    }

    /** 失败时抛。链式用:{@code Http.get(url).throwOnError().json()} */
    public HttpResponse throwOnError() {
        if (successful()) return this;

        throw new HttpRequestException("""
                %s 返回了 %s。
                  响应体:%s"""
                .formatted(url, describeStatus(), preview()), failure);
    }

    /** 只在 5xx 时抛 —— 4xx 常常是业务上合法的(404 = 不存在) */
    public HttpResponse throwOnServerError() {
        if (!serverError() && !connectionFailed()) return this;
        return throwOnError();
    }

    private String describeStatus() {
        return connectionFailed()
                ? "连接失败(" + (failure == null ? "未知原因" : failure.getMessage()) + ")"
                : String.valueOf(status);
    }

    // ================= 正文 =================

    /**
     * 解析成 JSON。
     *
     * <p>**响应失败时抛** —— 见类注释。
     */
    public JsonNode json() {
        requireSuccessForJson();

        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new HttpRequestException("""
                    %s 的响应不是合法的 JSON:%s
                      响应体:%s
                      Content-Type:%s"""
                    .formatted(url, e.getMessage(), preview(),
                               header("content-type").orElse("(没有)")), e);
        }
    }

    /** 取 JSON 里的一个字段 */
    public Optional<JsonNode> json(String field) {
        JsonNode node = json().get(field);
        return Optional.ofNullable(node);
    }

    /** 解析成一个类型 */
    public <T> T json(Class<T> type) {
        requireSuccessForJson();

        try {
            return MAPPER.readValue(body, type);
        } catch (Exception e) {
            throw new HttpRequestException("""
                    %s 的响应无法还原成 %s:%s
                      响应体:%s"""
                    .formatted(url, type.getSimpleName(), e.getMessage(), preview()), e);
        }
    }

    /**
     * 这道闸是对「默认不抛」的补偿 —— 见类注释。
     */
    private void requireSuccessForJson() {
        if (successful()) return;

        throw new HttpRequestException("""
                在一个失败的响应上调用了 json():%s 返回了 %s。
                  响应体:%s

                  这里报错而不是返回空数据 —— 一个 500 响应的 body 通常是
                  一段 HTML 错误页,在它上面解析 JSON 会失败,
                  而返回空数据会让调用方带着空结果继续走下去,
                  真正的原因(对方挂了)就丢了。

                  两条出路:
                    · 先判 response.successful();
                    · 或者在请求上链一个 .throwOnError(),让失败在更早的地方抛。
                  (与 Laravel 一致,默认**不**在 4xx/5xx 抛 ——
                   检查状态码是调用方的事。)"""
                .formatted(url, describeStatus(), preview()), failure);
    }

    /** 响应体的前 500 字符 —— 错误消息里不该放一整个 HTML 错误页 */
    private String preview() {
        if (body.isEmpty()) return "(空)";
        return body.length() <= 500 ? body : body.substring(0, 500) + "…(已截断)";
    }

    // ================= 头部 =================

    /** 取一个头部。**键不区分大小写** */
    public Optional<String> header(String name) {
        var values = headers.get(name.toLowerCase());
        return values == null || values.isEmpty()
                ? Optional.empty()
                : Optional.of(values.get(0));
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name.toLowerCase());
    }

    /**
     * {@code Retry-After} 的秒数。
     *
     * <p>429 与 503 常带这个头。它有两种格式:秒数,或一个 HTTP 日期。
     * 两种都处理 —— 只认秒数的话遇到日期格式会退回默认退避,
     * 而那通常比对方要求的短。
     */
    public Optional<java.time.Duration> retryAfter() {
        var value = header("retry-after");
        if (value.isEmpty()) return Optional.empty();

        String raw = value.get().trim();
        try {
            return Optional.of(java.time.Duration.ofSeconds(Long.parseLong(raw)));
        } catch (NumberFormatException notSeconds) {
            // HTTP 日期格式
        }
        try {
            var when = java.time.ZonedDateTime.parse(raw,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            var delay = java.time.Duration.between(java.time.ZonedDateTime.now(), when);
            return delay.isNegative() ? Optional.empty() : Optional.of(delay);
        } catch (RuntimeException unparseable) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "%s %s → %s".formatted(status == 0 ? "ERR" : status, url, preview());
    }

    /** 请求失败 */
    public static class HttpRequestException extends RuntimeException {
        public HttpRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
