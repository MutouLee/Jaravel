package io.jaravel.auth.guard;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * JWT 的签发与校验。只支持 HS256 与 RS256。
 *
 * <h2>为什么自己实现而不引 JWT 库</h2>
 * <p>JWT 的签名与校验在 JDK 里是现成的({@code Mac} / {@code Signature}),
 * 这里需要的代码不到两百行。而 JWT 库的历史充满 CVE —— 几乎全部来自
 * **解析与算法协商的宽容性**,而不是密码学原语。自己实现时把宽容性收紧
 * 比依赖一个「默认宽容、要靠配置收紧」的库更可控。
 *
 * <p>收紧的具体做法见下面三条防护。它们都是真实 CVE 的直接对应。
 *
 * <h2>三条必须防住的攻击</h2>
 * <ol>
 *   <li><b>alg=none</b> —— 攻击者把头部的 alg 改成 {@code none} 并删掉签名。
 *       宽容的库会「按 alg 说的不验签」。这里:**算法由验证方指定**,
 *       token 里的 alg 只用于校验是否一致,不用于选择验证方式。</li>
 *   <li><b>算法混淆(RS256 → HS256)</b> —— 攻击者把 RS256 token 改成 HS256,
 *       并用**公钥**(公开的)当 HMAC 密钥签名。宽容的库会用公钥去验 HMAC
 *       并通过。这里:同上,算法不由 token 决定。</li>
 *   <li><b>签名比较不定时安全</b> —— 逐字节爆破签名。这里用
 *       {@link MessageDigest#isEqual}。</li>
 * </ol>
 */
public final class Jwt {

    public enum Algorithm { HS256, RS256 }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final Algorithm algorithm;
    private final byte[] secret;            // HS256
    private final PrivateKey privateKey;    // RS256 签发
    private final PublicKey publicKey;      // RS256 校验
    private final Duration leeway;

    private Jwt(Algorithm algorithm, byte[] secret, PrivateKey privateKey,
                PublicKey publicKey, Duration leeway) {
        this.algorithm = algorithm;
        this.secret = secret;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.leeway = leeway == null ? Duration.ofSeconds(30) : leeway;
    }

    /**
     * HS256(对称)。
     *
     * @param secret 至少 32 字节 —— HMAC-SHA256 的密钥短于输出长度时
     *               会降低有效强度,而 JWT 的密钥常常被写成一个短口令
     */
    public static Jwt hs256(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("""
                    HS256 的密钥至少 32 字节,当前 %d 字节。
                      短密钥是 JWT 最常见的实际漏洞:一个 8 字符的口令
                      可以被离线爆破,而爆出来就等于能签发任意身份的 token。
                      生成一个:Encrypter.generateKey()(它产生 32 字节)。"""
                    .formatted(secret == null ? 0 : secret.length));
        }
        return new Jwt(Algorithm.HS256, secret.clone(), null, null, null);
    }

    /** RS256(非对称):私钥签发、公钥校验。微服务间传递身份时用这个。 */
    public static Jwt rs256(PrivateKey privateKey, PublicKey publicKey) {
        if (privateKey == null && publicKey == null) {
            throw new IllegalArgumentException(
                    "RS256 至少需要一个密钥:只签发给私钥,只校验给公钥。");
        }
        return new Jwt(Algorithm.RS256, null, privateKey, publicKey, null);
    }

    /** 容忍的时钟偏差。默认 30 秒 —— 分布式环境里时钟不会完全同步。 */
    public Jwt withLeeway(Duration leeway) {
        return new Jwt(algorithm, secret, privateKey, publicKey, leeway);
    }

    public Algorithm algorithm() {
        return algorithm;
    }

    // ================= 签发 =================

    public String issue(Map<String, Object> claims, Duration ttl) {
        var payload = new LinkedHashMap<String, Object>(claims);
        Instant now = Instant.now();

        payload.putIfAbsent("iat", now.getEpochSecond());
        if (ttl != null) {
            payload.put("exp", now.plus(ttl).getEpochSecond());
        }
        // jti 让 token 可被单独吊销(配合一个黑名单缓存)
        payload.putIfAbsent("jti", java.util.UUID.randomUUID().toString());

        String header = B64.encodeToString(
                ("{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\"}")
                        .getBytes(StandardCharsets.UTF_8));
        String body = B64.encodeToString(Json.write(payload).getBytes(StandardCharsets.UTF_8));

        String signingInput = header + "." + body;
        return signingInput + "." + B64.encodeToString(sign(signingInput));
    }

    // ================= 校验 =================

    /**
     * 校验并返回 claims。
     *
     * @throws InvalidTokenException 签名不对、过期、结构损坏、算法不符
     */
    public Map<String, Object> verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("token 为空");
        }

        // limit = -1 保留尾部空段:alg=none 攻击的典型形态是
        // "header.payload."(签名为空),默认的 split 会把它算成 2 段,
        // 于是撞在下面的段数检查上 —— 防护仍然有效,但报错变成
        // 「实际 2 段」,完全不说明是算法伪造。保留空段才能让
        // alg 一致性检查跑到并给出解释。
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException(
                    "JWT 必须是三段(header.payload.signature),实际 " + parts.length + " 段");
        }

        Map<String, Object> header = decodeJson(parts[0], "header");

        // ★ 关键防护:算法由**我们**决定,token 里的 alg 只用于一致性检查。
        // 反过来(按 token 的 alg 选验证方式)就是 alg=none 与
        // RS256→HS256 混淆这两个经典漏洞的成因。
        Object declaredAlg = header.get("alg");
        if (!algorithm.name().equals(declaredAlg)) {
            throw new InvalidTokenException("""
                    token 声明的算法是 "%s",而本端配置为 %s,拒绝校验。
                      **不会**按 token 说的算法去验 —— 那正是两个经典漏洞的成因:
                        · alg=none          攻击者删掉签名,宽容的实现会放过;
                        · RS256 → HS256     攻击者用公开的公钥当 HMAC 密钥重签。
                      两者都靠「算法由 token 决定」才能成立。"""
                    .formatted(declaredAlg, algorithm));
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] presented;
        try {
            presented = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("签名不是合法的 base64url");
        }

        if (presented.length == 0) {
            throw new InvalidTokenException(
                    "签名为空 —— 这是 alg=none 攻击的形态,而本端要求 " + algorithm);
        }
        if (!verifySignature(signingInput, presented)) {
            throw new InvalidTokenException("签名校验失败 —— token 被篡改或密钥不匹配");
        }

        Map<String, Object> claims = decodeJson(parts[1], "payload");
        validateTime(claims);
        return claims;
    }

    private void validateTime(Map<String, Object> claims) {
        long now = Instant.now().getEpochSecond();
        long slack = leeway.getSeconds();

        Object exp = claims.get("exp");
        if (exp instanceof Number n && now > n.longValue() + slack) {
            throw new InvalidTokenException(
                    "token 已过期(exp=" + n.longValue() + ",现在=" + now + ")");
        }

        Object nbf = claims.get("nbf");
        if (nbf instanceof Number n && now + slack < n.longValue()) {
            throw new InvalidTokenException("token 还未生效(nbf=" + n.longValue() + ")");
        }
    }

    /** 校验并要求某些 claim 必须存在 */
    public Map<String, Object> verify(String token, Set<String> requiredClaims) {
        var claims = verify(token);
        for (String required : requiredClaims) {
            if (!claims.containsKey(required)) {
                throw new InvalidTokenException("token 缺少必需的 claim \"" + required + "\"");
            }
        }
        return claims;
    }

    // ================= 签名原语 =================

    private byte[] sign(String signingInput) {
        try {
            if (algorithm == Algorithm.HS256) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            }
            if (privateKey == null) {
                throw new IllegalStateException(
                        "本端只配置了公钥,只能校验不能签发 JWT。");
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT 签名失败:" + e.getMessage(), e);
        }
    }

    private boolean verifySignature(String signingInput, byte[] presented) {
        try {
            if (algorithm == Algorithm.HS256) {
                byte[] expected = sign(signingInput);
                // 定时安全比较 —— 逐字节早退会泄露「前缀对了多少」
                return MessageDigest.isEqual(expected, presented);
            }
            if (publicKey == null) {
                throw new IllegalStateException(
                        "本端只配置了私钥,只能签发不能校验 JWT。");
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(presented);
        } catch (GeneralSecurityException e) {
            // 签名格式损坏也走到这里。当作校验失败而不是抛框架异常
            return false;
        }
    }

    private static Map<String, Object> decodeJson(String segment, String what) {
        try {
            String json = new String(B64D.decode(segment), StandardCharsets.UTF_8);
            return Json.read(json);
        } catch (InvalidTokenException e) {
            throw e;
        } catch (RuntimeException e) {
            // base64 解码失败(IllegalArgumentException)与 JSON 解析失败都走这里
            throw new InvalidTokenException("JWT 的 " + what + " 段解析失败:" + e.getMessage());
        }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    /** JWT 用的最小 JSON 处理 —— 复用 Jackson,但隔在一个地方 */
    private static final class Json {
        private static final com.fasterxml.jackson.databind.ObjectMapper M = create();

        static String write(Map<String, Object> value) {
            try {
                return M.writeValueAsString(value);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(
                        "JWT claims 无法序列化:" + e.getOriginalMessage()
                      + "\n  claims 只能放基本类型、字符串、列表与 Map。", e);
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> read(String json) {
            try {
                return M.readValue(json, Map.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new InvalidTokenException("不是合法的 JSON:" + e.getOriginalMessage());
            }
        }

        private static com.fasterxml.jackson.databind.ObjectMapper create() {
            var m = new com.fasterxml.jackson.databind.ObjectMapper();
            m.configure(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false);
            return m;
        }
    }
}
