package io.jaravel.http.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * 真正发请求的那一层。
 *
 * <p>抽成接口是为了 {@code Http.fake()} —— 假实现换掉的就是它。
 *
 * <p>这个边界刻意很窄(一个方法):假实现要替换的东西越少,
 * 「fake 下的行为与真实行为不一致」的可能就越小。
 */
public interface HttpTransport {

    /**
     * 发出去。
     *
     * <p>**不抛连接异常** —— 把它包成一个 {@code status=0} 的响应。
     *
     * <p>理由:重试逻辑要能统一处理「连不上」与「500」,
     * 而一个抛异常、一个返回值的话那段代码会分叉成两条路径。
     * 调用方想要异常就链 {@code orThrow()}。
     */
    HttpResponse send(HttpRequestSpec request, Duration connectTimeout);

    /** 基于 JDK {@code HttpClient} 的实现 */
    class Jdk implements HttpTransport {

        /**
         * 客户端是**共享的**。
         *
         * <p>每次请求新建一个 {@code HttpClient} 的后果:连接池无法复用,
         * 于是每次调用都要重新建 TCP 连接 + TLS 握手 ——
         * 对一个每秒调几十次的外部 API 那是几百毫秒的净开销。
         *
         * <p>连接超时是客户端级的(JDK 的 API 如此),所以按超时值
         * 缓存若干个客户端,而不是为每个请求建一个。
         */
        private static final java.util.Map<Duration, HttpClient> CLIENTS =
                new java.util.concurrent.ConcurrentHashMap<>();

        private static HttpClient clientFor(Duration connectTimeout) {
            return CLIENTS.computeIfAbsent(
                    connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout,
                    timeout -> HttpClient.newBuilder()
                            .connectTimeout(timeout)
                            // NEVER:**不自动跟随重定向**。
                            //
                            // 跟随的后果:一个 POST 被重定向后 JDK 会把它
                            // 变成 GET(RFC 允许),于是请求体丢了而调用方
                            // 看到一个 200 —— 那是一个非常难查的「数据没保存」。
                            //
                            // 需要跟随的话调用方自己看 Location 头再发一次。
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build());
        }

        @Override
        public HttpResponse send(HttpRequestSpec request, Duration connectTimeout) {
            HttpRequest.BodyPublisher publisher = request.hasBody()
                    ? HttpRequest.BodyPublishers.ofString(request.body())
                    : HttpRequest.BodyPublishers.noBody();

            HttpRequest.Builder builder;
            try {
                builder = HttpRequest.newBuilder(URI.create(request.url()))
                        .timeout(request.timeout())
                        .method(request.method(), publisher);
            } catch (IllegalArgumentException badUrl) {
                // URL 写错是**调用方的 bug**,不是一次失败的调用 ——
                // 包成 status=0 会让它被重试三次然后当成「对方挂了」
                throw new HttpResponse.HttpRequestException("""
                        URL 不合法:%s
                          %s"""
                        .formatted(request.url(), badUrl.getMessage()), badUrl);
            }

            request.headers().forEach((name, value) -> {
                // JDK 禁止设一些「受限」的头部(Host、Connection、
                // Content-Length 等),设了会抛。
                // 跳过而不是抛:它们由 JDK 自己管,而用户设它们
                // 几乎总是从别处抄配置带过来的
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException restricted) {
                    System.err.printf(
                            "[jaravel] HTTP 头部 %s 被 JDK 限制,已跳过(它由运行时管理)%n",
                            name);
                }
            });

            try {
                var response = clientFor(connectTimeout).send(builder.build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());

                return HttpResponse.of(response.statusCode(), response.body(),
                        response.headers().map(), request.url());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return HttpResponse.connectionFailure(request.url(), e);

            } catch (Exception e) {
                // 连接失败、超时、DNS —— 全部包成 status=0。
                // 见接口的 javadoc:让重试逻辑只有一条路径
                return HttpResponse.connectionFailure(request.url(), e);
            }
        }
    }
}
