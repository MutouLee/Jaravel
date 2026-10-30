package io.jaravel.notifications.channel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.notifications.Notifiable;
import io.jaravel.notifications.Notification;
import io.jaravel.notifications.HasSlack;
import io.jaravel.notifications.HasWebhook;
import io.jaravel.notifications.NotificationChannel;
import io.jaravel.notifications.SlackMessage;
import io.jaravel.notifications.WebhookMessage;

/**
 * slack 与 webhook 渠道 —— 都是「POST 一个 JSON」(docs/11 §2.5)。
 *
 * <p>两个渠道放一个文件里:它们只有 payload 的形状不同,
 * 而 HTTP 的那部分(超时、状态码判定、错误消息)完全一样 ——
 * 分成两个文件意味着那部分要么重复、要么抽一个共同父类,
 * 而两者都比放在一起更绕。
 *
 * <h2>用 JDK 的 HttpClient,不引库</h2>
 * <p>「POST 一个 JSON」不需要 OkHttp 或 Apache HttpClient。
 * JDK 11+ 自带的够用,而少一个依赖意味着少一次版本冲突。
 *
 * <h2>超时必须配</h2>
 * <p>{@code HttpClient} 的默认超时是**无限**(与 Jakarta Mail 一样)。
 * 一个挂住的 Slack webhook 会让这个任务永久占着一个 worker 的
 * 并发额度 —— 而 Slack 偶尔会挂。
 *
 * <p>所以连接与请求各 10 秒。
 */
public final class HttpChannels {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpChannels() {
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * POST 一个 JSON 并检查状态码。
     *
     * <p>2xx 以外都算失败。**把响应体放进错误消息** ——
     * Slack 与大多数 webhook 接收方会在响应体里说明原因
     * ({@code invalid_payload}、{@code channel_not_found}),
     * 而只报一个状态码的错误消息毫无帮助。
     */
    private static void post(String url, String method, Map<String, Object> body,
                             Map<String, String> headers, String channelName) {
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new DeliveryException(
                    "%s 渠道的 payload 无法序列化成 JSON:%s"
                            .formatted(channelName, e.getMessage()), e);
        }

        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(json));

        headers.forEach(builder::header);

        HttpResponse<String> response;
        try {
            response = client().send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryException(
                    "%s 渠道的请求被中断".formatted(channelName), e);
        } catch (Exception e) {
            throw new DeliveryException("""
                    %s 渠道请求失败:%s
                      常见原因:URL 写错、出口网络被封、对方限流。"""
                    .formatted(channelName, e.getMessage()), e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new DeliveryException("""
                    %s 渠道返回了 %d。
                      响应体:%s
                      (响应体通常就是原因 —— Slack 会返回 invalid_payload /
                       channel_not_found 这类说明,而只看状态码毫无帮助。)"""
                    .formatted(channelName, response.statusCode(),
                               truncate(response.body())), null);
        }
    }

    /** 响应体可能很长(一个 HTML 错误页),截断它 */
    private static String truncate(String body) {
        if (body == null) return "(空)";
        return body.length() <= 500 ? body : body.substring(0, 500) + "…(已截断)";
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

            String webhook = notifiable.routeNotificationFor("slack");
            post(webhook, "POST", message.payload(), Map.of(), "slack");
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

            String url = notifiable.routeNotificationFor("webhook");
            post(url, message.method(), message.body(), message.headers(), "webhook");
        }
    }

    /** 投递失败 */
    public static class DeliveryException extends RuntimeException {
        public DeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
