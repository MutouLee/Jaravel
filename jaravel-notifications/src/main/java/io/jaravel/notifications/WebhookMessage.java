package io.jaravel.notifications;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个通用 HTTP 回调(docs/11 §2.5 的 webhook 渠道)。
 *
 * <pre>{@code
 * WebhookMessage.post(Map.of("event", "invoice.paid", "id", invoice.id()))
 *               .header("X-Signature", sign(body));
 * }</pre>
 *
 * <h2>签名是**用户的事**,框架不做</h2>
 * <p>一个负责的 webhook 要带签名,好让接收方验证来源。
 * 但签名算法与密钥管理是双方约定的(HMAC-SHA256、header 名、
 * 时间戳是否参与、密钥从哪来)—— 框架替用户选一套的话,
 * 接收方那边未必能对上。
 *
 * <p>所以这里只提供 {@link #header} 让用户自己加,并在这里说清:
 * **一个不带签名的 webhook 等于任何人都能伪造你的事件。**
 *
 * @param method  HTTP 方法。默认 POST
 * @param body    请求体,会被序列化成 JSON
 * @param headers 自定义头部
 */
public record WebhookMessage(String method, Map<String, Object> body,
                             Map<String, String> headers) {

    public WebhookMessage {
        body = body == null ? Map.of() : Map.copyOf(body);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static WebhookMessage post(Map<String, Object> body) {
        return new WebhookMessage("POST", body, null);
    }

    public static WebhookMessage put(Map<String, Object> body) {
        return new WebhookMessage("PUT", body, null);
    }

    /** 加一个头部 —— 签名通常走这里 */
    public WebhookMessage header(String name, String value) {
        var merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return new WebhookMessage(method, body, merged);
    }
}
