package io.jaravel.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;

import io.jaravel.core.crypt.UrlSigner;

/**
 * 邮箱验证。
 *
 * <pre>{@code
 * // 1. 生成链接发给用户
 * String url = verification.verificationUrl("https://app.com", user);
 *
 * // 2. 控制器里校验并标记
 * verification.verify(fullRequestUrl, user, id, hash);
 * }</pre>
 *
 * <h2>链接里为什么要带一个 hash</h2>
 * <p>链接形如 {@code /verify/42/<hash>?expires=…&signature=…}。
 * 签名已经防住了篡改,hash 看起来是多余的 —— 但它解决另一个问题:
 *
 * <p>hash 是**邮箱地址的 HMAC**。用户申请验证后改了邮箱,旧链接里的 hash
 * 就与当前邮箱不符,于是链接失效。没有它的话:用户用 a@x.com 申请验证,
 * 改成 b@y.com,再点旧链接 —— 系统会把 b@y.com 标记为已验证,
 * 而那个地址从未收到过任何验证邮件。**这是一个真实的账号接管路径**
 * (配合「验证邮箱后可改密码」之类的流程)。
 */
public class EmailVerification {

    private final UrlSigner signer;
    private final byte[] hashKey;
    private final Duration linkTtl;

    public EmailVerification(UrlSigner signer, byte[] hashKey) {
        this(signer, hashKey, Duration.ofHours(24));
    }

    public EmailVerification(UrlSigner signer, byte[] hashKey, Duration linkTtl) {
        this.signer = signer;
        this.hashKey = hashKey.clone();
        this.linkTtl = linkTtl;
    }

    // ================= 生成 =================

    /**
     * 生成验证链接。
     *
     * @param baseUrl 应用的基地址,如 {@code https://app.example.com}
     */
    public String verificationUrl(String baseUrl, Authenticatable user) {
        String email = emailOf(user);
        String path = baseUrl.replaceAll("/+$", "")
                + "/email/verify/" + user.getAuthIdentifier() + "/" + emailHash(email);
        return signer.sign(path, linkTtl);
    }

    // ================= 校验 =================

    public enum Result {
        /** 刚刚验证成功 */
        VERIFIED,
        /** 之前已经验证过了 —— 不是错误 */
        ALREADY_VERIFIED,
        /** 签名无效或已过期 */
        INVALID_LINK,
        /**
         * 链接里的 hash 与当前邮箱不符。
         *
         * <p>几乎总是「申请验证后改了邮箱」。见类注释里为什么这必须拦住。
         */
        EMAIL_CHANGED
    }

    /**
     * 校验链接并标记为已验证。
     *
     * @param fullUrl   收到的完整 URL(含查询串)
     * @param user      按链接里的 id 查出的用户
     * @param emailHash 链接路径里的 hash 段
     * @param marker    标记已验证的动作 —— 由调用方写回模型
     */
    public Result verify(String fullUrl, Authenticatable user, String emailHash,
                         java.util.function.Consumer<Authenticatable> marker) {
        if (!signer.isValid(fullUrl)) {
            return Result.INVALID_LINK;
        }

        // 定时安全比较 —— hash 可被逐字节爆破
        String expected = emailHash(emailOf(user));
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                emailHash == null ? new byte[0] : emailHash.getBytes(StandardCharsets.UTF_8))) {
            return Result.EMAIL_CHANGED;
        }

        if (isVerified(user)) {
            // 已验证过不算错误:用户可能点了两次,或邮件被转发
            return Result.ALREADY_VERIFIED;
        }

        marker.accept(user);
        return Result.VERIFIED;
    }

    // ================= 状态 =================

    /**
     * 是否已验证。
     *
     * <p>**没有 {@code @MustVerifyEmail} 的模型一律视为已验证** ——
     * 否则给一个不需要邮箱验证的模型挂上 {@code verified} 中间件会
     * 永久 302,而那看起来像死循环。
     */
    public static boolean isVerified(Authenticatable user) {
        Object model = user.unwrap();
        var annotation = model.getClass().getAnnotation(MustVerifyEmail.class);
        if (annotation == null) return true;

        Object value = readField(model, annotation.verifiedAt());
        return value != null;
    }

    public static Instant verifiedAt(Authenticatable user) {
        Object model = user.unwrap();
        var annotation = model.getClass().getAnnotation(MustVerifyEmail.class);
        if (annotation == null) return null;

        Object value = readField(model, annotation.verifiedAt());
        return value == null ? null : io.jaravel.support.Cast.to(value, Instant.class);
    }

    private static String emailOf(Authenticatable user) {
        Object model = user.unwrap();
        var annotation = model.getClass().getAnnotation(MustVerifyEmail.class);
        String column = annotation == null ? "email" : annotation.email();

        Object value = readField(model, column);
        if (value == null) {
            throw new IllegalStateException("""
                    模型 %s 的邮箱字段 "%s" 为空,无法生成验证链接。
                      检查 @MustVerifyEmail(email = "...") 指的列名是否正确。"""
                    .formatted(model.getClass().getSimpleName(), column));
        }
        return String.valueOf(value);
    }

    /**
     * 按列名读字段。
     *
     * <p>同时试 snake_case 的列名与 camelCase 的字段名 —— 注解里写的是
     * 列名({@code email_verified_at}),而 Java 字段是 {@code emailVerifiedAt}。
     */
    private static Object readField(Object model, String columnName) {
        String camel = io.jaravel.support.Str.camel(columnName);

        for (String candidate : new String[] {camel, columnName}) {
            try {
                var field = model.getClass().getField(candidate);
                return field.get(model);
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // 试下一个写法
            }
        }

        throw new IllegalStateException("""
                模型 %s 上找不到字段 "%s"(也试过 "%s")。
                  @MustVerifyEmail 的列名要能对应到一个 public 字段。
                  该模型的 public 字段:%s"""
                .formatted(model.getClass().getSimpleName(), columnName, camel,
                           java.util.Arrays.stream(model.getClass().getFields())
                                   .map(java.lang.reflect.Field::getName).toList()));
    }

    /**
     * 邮箱的 HMAC-SHA256 —— 见类注释里「为什么链接要带 hash」。
     *
     * <p>public 是因为调用方需要它来构造链接里的那一段,
     * 以及在收到请求时自己比对(有些应用不走本类的 verify)。
     */
    public String emailHash(String email) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(hashKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    email.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("计算邮箱 hash 失败:" + e.getMessage(), e);
        }
    }

    public Duration linkTtl() {
        return linkTtl;
    }
}
