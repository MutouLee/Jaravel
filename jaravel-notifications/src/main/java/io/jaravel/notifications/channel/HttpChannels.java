package io.jaravel.notifications.channel;

import java.time.Duration;
import java.util.Map;

import io.jaravel.http.client.Http;
import io.jaravel.http.client.HttpResponse;
import io.jaravel.notifications.HasSlack;
import io.jaravel.notifications.HasWebhook;
import io.jaravel.notifications.Notifiable;
import io.jaravel.notifications.Notification;
import io.jaravel.notifications.NotificationChannel;
import io.jaravel.notifications.SlackMessage;
import io.jaravel.notifications.WebhookMessage;

/**
 * slack 与 webhook 渠道 —— 都是「POST 一个 JSON」(docs/11 §2.5)。
 *
 * <p>两个渠道放一个文件里:它们只有 payload 的形状不同,
 * 而 HTTP 的那部分完全一样。
 *
 * <h2>★ 走 {@link Http} 门面,不再自己手写 HttpClient</h2>
 * <p>这个类的第一版自己 new 了一个 {@code HttpClient},自己设超时、
 * 自己判状态码、自己截断响应体 —— 而调度的心跳 ping 也做了同样的一遍。
 *
 * <p>那两处在没有一个共同门面之前注定会分叉:超时值不同、
 * 「什么算失败」的判定不同、错误消息的详细程度不同 ——
 * 而其中最不详细的那一处就是出问题时最难查的。
 *
 * <p>换成门面之后顺带得到两样东西:
 * <ul>
 *   <li><b>重试</b> —— 429 与 5xx 自动重试,且按对方的
 *       {@code Retry-After} 等。Slack 的限流很常见,而第一版
 *       完全没有重试;</li>
 *   <li><b>{@code Http.fake()} 可测</b> —— 这两个渠道原先只能靠
 *       起一个真 HTTP 服务器来测,所以实际上**没有测**。</li>
 * </ul>
 */
public final class HttpChannels {

    private HttpChannels() {
    }

    /**
     * 发出去并检查结果。
     *
     * <p>重试 3 次:slack 与大多数 webhook 接收方的失败多是瞬时的
     * (限流、连接重置)。**4xx 不重试** —— 一个
     * {@code channel_not_found} 重试一百次还是同样的结果。
     * 那条判定在 {@code PendingHttpRequest.worthRetrying} 里,
     * 这里不必重复。
     */
    private static void deliver(String url, String method, Map<String, Object> body,
                                Map<String, String> headers, String channelName) {
        if (url == null || url.isBlank()) {
            // 正常情况下走不到这 —— manager 已经按 requiresRoute 跳过了。
            // 但这个渠道可能被别人直接调
            throw new DeliveryException(
                    "%s 渠道没有目标 URL".formatted(channelName), null);
        }

        var request = Http.withHeaders(headers)
                .timeout(Duration.ofSeconds(10))
                .retry(3, Duration.ofMillis(300));

        HttpResponse response = "PUT".equalsIgnoreCase(method)
                ? request.put(url, body)
                : request.post(url, body);

        if (response.connectionFailed()) {
            throw new DeliveryException("""
                    %s 渠道请求失败:%s
                      常见原因:URL 写错、出口网络被封、对方限流。"""
                    .formatted(channelName,
                               response.failure() == null
                                       ? "连接失败"
                                       : response.failure().getMessage()),
                    response.failure());
        }

        if (response.failed()) {
            throw new DeliveryException("""
                    %s 渠道返回了 %d。
                      响应体:%s
                      (响应体通常就是原因 —— Slack 会返回 invalid_payload /
                       channel_not_found 这类说明,而只看状态码毫无帮助。)"""
                    .formatted(channelName, response.status(),
                               response.body().isEmpty() ? "(空)" : response.body()),
                    null);
        }
    }

    /** slack 渠道 */
    public static final class Slack implements NotificationChannel {

        @Override
        public String name() {
            return "slack";
        }

        @Override
        public void send(Notifiable notifiable, Notification notification) {
            SlackMessage message = require(notifiable, notification,
                    HasSlack.class).toSlack(notifiable);

            deliver(notifiable.routeNotificationFor("slack"), "POST",
                    message.payload(), Map.of(), "slack");
        }
    }

    /** webhook 渠道 */
    public static final class Webhook implements NotificationChannel {

        @Override
        public String name() {
            return "webhook";
        }

        @Override
        public void send(Notifiable notifiable, Notification notification) {
            WebhookMessage message = require(notifiable, notification,
                    HasWebhook.class).toWebhook(notifiable);

            deliver(notifiable.routeNotificationFor("webhook"), message.method(),
                    message.body(), message.headers(), "webhook");
        }
    }

    /** 投递失败 */
    public static class DeliveryException extends RuntimeException {
        public DeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
