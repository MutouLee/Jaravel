package io.jaravel.core.crypt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 签名 URL:把一个链接做成「不可篡改 + 可过期」的。
 *
 * <pre>{@code
 * String url = signer.sign("https://app.com/verify/42/abc", Duration.ofHours(24));
 * // → https://app.com/verify/42/abc?expires=1760000000&signature=xxxxx
 *
 * signer.verify(requestUrl);   // 篡改或过期则抛异常
 * }</pre>
 *
 * <h2>用途</h2>
 * <p>邮箱验证链接、一次性下载链接、无需登录的退订链接。共同特点是
 * **链接本身就是凭据** —— 所以它必须防篡改(否则改个 id 就能验证别人的邮箱)
 * 且必须能过期(否则泄露一次就永久有效)。
 *
 * <h2>三个必须做对的细节</h2>
 * <ol>
 *   <li><b>查询参数按名字排序后才参与签名。</b> 否则
 *       {@code ?a=1&b=2} 与 {@code ?b=2&a=1} 是同一个链接却算出不同签名,
 *       而浏览器、邮件客户端、CDN 都可能重排参数 —— 症状是「链接有时有效
 *       有时无效」,极难复现。</li>
 *   <li><b>签名比较定时安全。</b> 逐字符早退会让签名可被爆破。</li>
 *   <li><b>expires 也必须在签名覆盖范围内。</b> 不然攻击者改一下
 *       expires 就能无限续期 —— 这是签名 URL 最容易漏掉的一点。</li>
 * </ol>
 */
public final class UrlSigner {

    private static final String SIGNATURE_PARAM = "signature";
    private static final String EXPIRES_PARAM = "expires";

    private final byte[] key;

    public UrlSigner(byte[] key) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException(
                    "签名密钥至少 32 字节,当前 " + (key == null ? 0 : key.length)
                  + "。签名 URL 的安全性完全取决于密钥强度 —— "
                  + "短密钥可被离线爆破,爆出来就能伪造任意验证链接。");
        }
        this.key = key.clone();
    }

    public static UrlSigner fromBase64(String appKey) {
        String body = appKey.startsWith("base64:") ? appKey.substring(7) : appKey;
        return new UrlSigner(Base64.getDecoder().decode(body));
    }

    // ================= 签名 =================

    /** 永久有效的签名 URL */
    public String sign(String url) {
        return sign(url, null);
    }

    /**
     * 带过期时间的签名 URL。
     *
     * @param ttl null 表示永不过期 —— **慎用**,泄露一次就永久有效。
     *            粒度是**秒**(expires 是 epoch 秒,与 Laravel 一致),
     *            所以小于一秒的 TTL 会被取整到当前秒,实际上等于「本秒内有效」
     */
    public String sign(String url, Duration ttl) {
        var parsed = Parsed.of(url);
        var params = new LinkedHashMap<>(parsed.params());

        params.remove(SIGNATURE_PARAM);     // 重复签名时先去掉旧签名
        if (ttl != null) {
            params.put(EXPIRES_PARAM,
                       String.valueOf(Instant.now().plus(ttl).getEpochSecond()));
        }

        String signature = compute(parsed.base(), params);
        params.put(SIGNATURE_PARAM, signature);

        return parsed.base() + "?" + encode(params);
    }

    // ================= 校验 =================

    /**
     * 校验签名与过期。
     *
     * @throws InvalidSignatureException 签名不符、缺失、或已过期
     */
    public void verify(String url) {
        var parsed = Parsed.of(url);
        var params = new LinkedHashMap<>(parsed.params());

        String presented = params.remove(SIGNATURE_PARAM);
        if (presented == null || presented.isBlank()) {
            throw new InvalidSignatureException(
                    "链接缺少 signature 参数 —— 它不是一个签名 URL。"
                  + "\n  若是从邮件里复制的,检查是否被换行截断了。");
        }

        String expected = compute(parsed.base(), params);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidSignatureException("""
                    链接的签名不匹配。可能的原因:
                      · 链接被修改过(哪怕只改一个字符);
                      · APP_KEY 变了 —— 已发出的链接全部失效;
                      · 邮件客户端在 URL 上加了跟踪参数。
                    注意 expires 也在签名覆盖范围内,所以改它无法续期。""");
        }

        String expires = params.get(EXPIRES_PARAM);
        if (expires != null) {
            long deadline;
            try {
                deadline = Long.parseLong(expires);
            } catch (NumberFormatException e) {
                throw new InvalidSignatureException("expires 不是合法的时间戳:" + expires);
            }
            if (Instant.now().getEpochSecond() > deadline) {
                throw new InvalidSignatureException(
                        "链接已过期(" + Instant.ofEpochSecond(deadline) + ")。"
                      + "\n  请重新获取一个新链接。");
            }
        }
    }

    public boolean isValid(String url) {
        try {
            verify(url);
            return true;
        } catch (InvalidSignatureException e) {
            return false;
        }
    }

    /** 只检查签名,不检查过期 —— 用于「链接过期了,但我们想告诉用户是哪一个」 */
    public boolean hasValidSignature(String url) {
        try {
            var parsed = Parsed.of(url);
            var params = new LinkedHashMap<>(parsed.params());
            String presented = params.remove(SIGNATURE_PARAM);
            if (presented == null) return false;

            return MessageDigest.isEqual(
                    compute(parsed.base(), params).getBytes(StandardCharsets.UTF_8),
                    presented.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ================= 内部 =================

    /**
     * 计算签名。
     *
     * <p>**参数按名字排序**后拼接 —— 见类注释的第一条。
     */
    private String compute(String base, Map<String, String> params) {
        // TreeMap 保证顺序与参数在 URL 里的出现顺序无关
        var sorted = new TreeMap<>(params);
        String payload = base + "?" + encode(sorted);

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("计算 URL 签名失败:" + e.getMessage(), e);
        }
    }

    private static String encode(Map<String, String> params) {
        var sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(urlEncode(k)).append('=').append(urlEncode(v));
        });
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Parsed(String base, Map<String, String> params) {

        static Parsed of(String url) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("URL 不能为空");
            }
            int question = url.indexOf('?');
            if (question < 0) return new Parsed(url, Map.of());

            var params = new LinkedHashMap<String, String>();
            for (String pair : url.substring(question + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                params.put(urlDecode(name), urlDecode(value));
            }
            return new Parsed(url.substring(0, question), params);
        }

        private static String urlDecode(String value) {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }
}
