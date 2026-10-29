package io.jaravel.auth;

import java.util.function.Consumer;

/**
 * 邮箱验证的静态门面。见 {@link io.jaravel.auth.passwords.Password}
 * 的类注释里「为什么补这个门面」—— 同一个理由。
 *
 * <pre>{@code
 * String url = Verification.url("https://app.com", user);
 * var result = Verification.verify(request.fullUrl(), user, hash, u -> {
 *     ((User) u.unwrap()).emailVerifiedAt = Instant.now();
 * });
 * }</pre>
 *
 * <p>{@link EmailVerification#isVerified} 本来就是静态的(它只读注解),
 * 所以这里不重复转发它 —— 两个入口做同一件事只会让人犹豫该用哪个。
 */
public final class Verification {

    private static volatile EmailVerification verification;

    private Verification() {
    }

    public static void use(EmailVerification configured) {
        verification = configured;
    }

    public static EmailVerification instance() {
        EmailVerification current = verification;
        if (current == null) {
            throw new IllegalStateException("""
                    邮箱验证尚未装配。
                      正常情况下 jaravel-spring-boot-starter 会在启动时完成;
                      单元测试里调 Verification.use(new EmailVerification(signer, key))。
                      它需要 app.key —— 验证链接的 HMAC 密钥由它派生。""");
        }
        return current;
    }

    // ================= 转发 =================

    /** 生成验证链接 */
    public static String url(String baseUrl, Authenticatable user) {
        return instance().verificationUrl(baseUrl, user);
    }

    public static EmailVerification.Result verify(String fullUrl, Authenticatable user,
                                                  String emailHash,
                                                  Consumer<Authenticatable> marker) {
        return instance().verify(fullUrl, user, emailHash, marker);
    }

    public static String emailHash(String email) {
        return instance().emailHash(email);
    }

    /** 测试结束后清掉 */
    public static void reset() {
        verification = null;
    }
}
