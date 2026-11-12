package io.jaravel.auth.twofactor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP(RFC 6238)的实现(docs/08 §6.4)。
 *
 * <pre>{@code
 * String secret = Totp.generateSecret();          // Base32,160 位
 * String uri = Totp.otpauthUri("MyApp", "tom@example.com", secret);
 * boolean ok = Totp.verify(secret, userInput);    // 默认 ±1 个 30 秒窗口
 * }</pre>
 *
 * <h2>默认参数与主流客户端一致</h2>
 * <p>SHA-1 / 6 位 / 30 秒 —— Google Authenticator、1Password、Authy 的默认。
 * RFC 6238 允许别的组合,但这里不做配置界面:一个「和手机对不上」的默认值
 * 是二步验证最常见、也最难自己排查的故障。
 *
 * <h2>★ 时间要能注入</h2>
 * <p>所有方法都收 {@link Instant}。生产传 {@code Instant.now()},测试传
 * RFC 的固定时间点 —— 否则二步验证的测试只能在「正确的当下」写,
 * 而那种测试既不稳定也测不到边界(窗口两侧、跨步长)。
 *
 * <h2>验证用常数时间比较</h2>
 * <p>六位码只有一百万种可能,但比较仍用 {@link MessageDigest#isEqual}:
 * 一个按字节短路返回的比较在理论上可被计时攻击逐位试出来。
 * 代价是一次几十字节的比较,没有理由省。
 */
public final class Totp {

    public static final int DEFAULT_DIGITS = 6;
    public static final int DEFAULT_PERIOD_SECONDS = 30;
    /** 允许前后各一个窗口 —— 客户端时钟漂移的常规容忍度 */
    public static final int DEFAULT_WINDOW = 1;

    private static final int SECRET_BYTES = 20;   // 160 位,与 Authenticator 一致

    private Totp() {
    }

    // ================= 密钥 =================

    /** 20 字节 SecureRandom → 不带填充的 Base32 */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base32.encode(bytes);
    }

    /**
     * {@code otpauth://totp/...} —— 二维码的内容。
     *
     * <p>SVG/PNG 的渲染不在这里:那需要一整个二维码库,而
     * {@code otpauth} URI 是任何二维码库的输入。应用拿到 URI 之后
     * 用前端(或任意 QR 库)画图即可。
     */
    public static String otpauthUri(String issuer, String account, String secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
             + "?secret=" + urlEncode(secret)
             + "&issuer=" + urlEncode(issuer)
             + "&algorithm=SHA1"
             + "&digits=" + DEFAULT_DIGITS
             + "&period=" + DEFAULT_PERIOD_SECONDS;
    }

    // ================= 生成与验证 =================

    public static String code(String secret, Instant time) {
        return code(secret, time, DEFAULT_DIGITS, DEFAULT_PERIOD_SECONDS);
    }

    public static String code(String secret, Instant time, int digits, int periodSeconds) {
        validate(digits, periodSeconds);
        long counter = Math.floorDiv(time.getEpochSecond(), periodSeconds);
        return codeForCounter(Base32.decode(secret), counter, digits);
    }

    public static boolean verify(String secret, String code, Instant time) {
        return verify(secret, code, time, DEFAULT_WINDOW,
                DEFAULT_DIGITS, DEFAULT_PERIOD_SECONDS);
    }

    /**
     * @param window 前后各容忍多少个步长;0 表示只接受当前窗口
     */
    public static boolean verify(String secret, String code, Instant time,
                                 int window, int digits, int periodSeconds) {
        if (secret == null || secret.isBlank() || code == null) return false;
        validate(digits, periodSeconds);
        if (window < 0) throw new IllegalArgumentException("window 不能为负");

        String expected = code.replace(" ", "");
        if (!expected.matches("\\d{" + digits + "}")) return false;

        byte[] key;
        try {
            key = Base32.decode(secret);
        } catch (IllegalArgumentException e) {
            return false;
        }

        long counter = Math.floorDiv(time.getEpochSecond(), periodSeconds);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);

        for (int offset = -window; offset <= window; offset++) {
            byte[] actual = codeForCounter(key, counter + offset, digits)
                    .getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(actual, expectedBytes)) return true;
        }
        return false;
    }

    // ================= 内部 =================

    private static String codeForCounter(byte[] key, long counter, int digits) {
        byte[] message = new byte[8];
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (counter & 0xFF);
            counter >>>= 8;
        }

        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("这个 JVM 没有 HmacSHA1", e);
        }

        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                   | ((hash[offset + 1] & 0xFF) << 16)
                   | ((hash[offset + 2] & 0xFF) << 8)
                   |  (hash[offset + 3] & 0xFF);

        int modulo = (int) Math.pow(10, digits);
        return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulo);
    }

    private static void validate(int digits, int periodSeconds) {
        if (digits < 6 || digits > 8) {
            throw new IllegalArgumentException("TOTP 位数只能是 6~8,收到:" + digits);
        }
        if (periodSeconds <= 0) {
            throw new IllegalArgumentException("TOTP 步长必须大于 0,收到:" + periodSeconds);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
