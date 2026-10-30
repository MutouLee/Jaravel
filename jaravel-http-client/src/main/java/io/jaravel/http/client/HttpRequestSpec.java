package io.jaravel.http.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 一次请求的**不变快照** —— 真正发出去的东西。
 *
 * <p>它存在是为了 {@code Http.fake()} 的断言:
 *
 * <pre>{@code
 * Http.assertSent(r -> r.url().contains("/items") && r.hasHeader("X-Key"));
 * }</pre>
 *
 * <p>所以它要**可读且不可变** —— 一个断言不该能改掉被断言的对象。
 *
 * @param headers 请求头。键是**原样**的,不像响应那样小写化 ——
 *                断言 {@code hasHeader("X-Key")} 时用户写的是他自己
 *                设的那个大小写,而 {@link #hasHeader} 做不区分大小写的比较
 * @param body    请求体。GET 这类没有体的为 null
 */
public record HttpRequestSpec(
        String method,
        String url,
        Map<String, String> headers,
        String body,
        Duration timeout
) {

    public HttpRequestSpec {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** 不区分大小写 —— HTTP 头大小写不敏感 */
    public boolean hasHeader(String name) {
        return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
    }

    public java.util.Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** 请求头里有这个值吗 —— 断言「带了正确的 token」时用 */
    public boolean hasHeader(String name, String value) {
        return header(name).map(value::equals).orElse(false);
    }

    public boolean hasBody() {
        return body != null && !body.isEmpty();
    }

    /** 请求体里有这个子串吗 —— 断言「发了正确的字段」时用 */
    public boolean bodyContains(String fragment) {
        return body != null && body.contains(fragment);
    }

    /**
     * URL 匹配一个带 {@code *} 的模式吗。
     *
     * <p>与 {@code Http.fake(...)} 用的是**同一个**匹配实现 ——
     * 分成两套的话「fake 的 key 匹配到了但 assertSent 的模式没匹配到」
     * 会变成一个非常困惑的问题。
     */
    public boolean urlMatches(String pattern) {
        return UrlPattern.matches(pattern, url);
    }

    @Override
    public String toString() {
        return "%s %s%s".formatted(method, url,
                hasBody() ? " body=" + preview() : "");
    }

    private String preview() {
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }

    /**
     * 带 {@code *} 的 URL 模式匹配。
     *
     * <p>{@code *} 匹配任意字符(**含 {@code /}**)。
     *
     * <p>这一点与 glob 不同,是刻意的:
     * {@code "https://api.example.com/*"} 的自然意图是
     * 「这个域名下的所有路径」,而 glob 语义下它匹配不到
     * {@code /v1/items}(因为那里有一个 {@code /})。
     *
     * <p>要「只匹配一段」就自己写得更具体
     * ({@code "https://api.example.com/*​/items"})。
     */
    public static final class UrlPattern {

        private UrlPattern() {
        }

        public static boolean matches(String pattern, String url) {
            if (pattern == null || url == null) return false;
            if (pattern.equals("*")) return true;

            var regex = new StringBuilder();
            for (char c : pattern.toCharArray()) {
                if (c == '*') {
                    regex.append(".*");
                } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                    regex.append('\\').append(c);
                } else {
                    regex.append(c);
                }
            }
            return url.matches(regex.toString());
        }
    }
}
