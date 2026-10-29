package io.jaravel.core.crypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码哈希。
 *
 * <pre>{@code
 * String hash = Hash.make("secret");
 * boolean ok  = Hash.check("secret", hash);
 * boolean old = Hash.needsRehash(hash);    // 参数已升级过,该重新哈希
 * }</pre>
 *
 * <h2>与 Laravel 的差异:默认算法是 PBKDF2-HMAC-SHA512,不是 bcrypt</h2>
 * <p>原因只有一个:**JDK 内置 PBKDF2,不内置 bcrypt**。要用 bcrypt 就得引入
 * 一个第三方库,而 ADR-001 要求核心模块零外部依赖(Spring 都不许)。
 * 为了一个哈希算法破例,会让「核心模块可以脱离任何生态单独使用」这个承诺失效。
 *
 * <p>安全上这个选择是站得住的:OWASP 把 PBKDF2 列为可接受的密码哈希算法
 * (且是 FIPS-140 场景下的唯一选项),只要迭代次数足够。这里用
 * {@value #DEFAULT_ITERATIONS} 次 HMAC-SHA512,对应 OWASP 2023 的建议值。
 *
 * <p>代价要说清楚:PBKDF2 **不是内存困难的**,对 GPU/ASIC 的抵抗力弱于
 * Argon2id 与 scrypt。如果你的威胁模型包含「数据库泄露后被专用硬件离线爆破」,
 * 应该换 Argon2id —— 实现 {@link Algorithm} 接口注册进来即可,
 * 已有的哈希不受影响(格式带算法标识,见下)。
 *
 * <h2>哈希格式</h2>
 * <pre>
 * $pbkdf2-sha512$i=210000$&lt;base64 盐&gt;$&lt;base64 派生密钥&gt;
 * </pre>
 * <p>把算法与参数写进哈希本身,这样:升级迭代次数不会让旧密码失效
 * ({@link #needsRehash} 能识别出来并在下次登录时静默升级),
 * 换算法也只是多认一个前缀。
 */
public final class Hasher {

    /** OWASP 2023 对 PBKDF2-HMAC-SHA512 的建议值 */
    public static final int DEFAULT_ITERATIONS = 210_000;

    private static final String PREFIX = "$pbkdf2-sha512$";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 512;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public Hasher() {
        this(DEFAULT_ITERATIONS);
    }

    public Hasher(int iterations) {
        if (iterations < 10_000) {
            throw new IllegalArgumentException("""
                    密码哈希的迭代次数 %d 太低(下限 10000)。
                      迭代次数就是攻击者爆破一个密码的成本。调低它唯一的理由是
                      「测试跑太慢」—— 那应该在测试里显式用低参数的 Hasher,
                      而不是把全局默认改低。"""
                    .formatted(iterations));
        }
        this.iterations = iterations;
    }

    /** 测试专用:迭代次数低到可以跑几百次。**绝不要用在生产。** */
    public static Hasher forTests() {
        return new Hasher(10_000);
    }

    // ================= 哈希与校验 =================

    public String make(String plaintext) {
        requirePassword(plaintext);

        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(plaintext, salt, iterations);

        return PREFIX + "i=" + iterations + "$"
             + Base64.getEncoder().encodeToString(salt) + "$"
             + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * 校验。
     *
     * <p>用 {@link MessageDigest#isEqual} 做**定时安全**比较。普通的
     * {@code Arrays.equals} 会在第一个不同的字节上返回,于是响应时间泄露
     * 「前缀匹配了多少」—— 这足以把爆破从指数级降到线性级。
     */
    public boolean check(String plaintext, String hash) {
        if (plaintext == null || hash == null) return false;

        Parsed parsed;
        try {
            parsed = parse(hash);
        } catch (IllegalArgumentException e) {
            // 哈希格式不认识 → 校验失败,而不是抛异常。
            // 数据库里可能存着别的系统迁移过来的哈希,
            // 让登录接口 500 比让它返回「密码错」糟糕得多。
            return false;
        }

        byte[] candidate = derive(plaintext, parsed.salt(), parsed.iterations());
        return MessageDigest.isEqual(candidate, parsed.derived());
    }

    /**
     * 这个哈希是否该重算。
     *
     * <p>用途:提高了迭代次数之后,老用户的哈希仍然是旧参数。在登录成功的
     * 那一刻(此时明文密码在手)静默重算,就能无感升级 ——
     * 否则只能等用户自己改密码,实际上等于永不升级。
     */
    public boolean needsRehash(String hash) {
        try {
            return parse(hash).iterations() < iterations;
        } catch (IllegalArgumentException e) {
            return true;      // 格式不认识的,升级成当前格式
        }
    }

    public String algorithm() {
        return "pbkdf2-sha512";
    }

    public int iterations() {
        return iterations;
    }

    // ================= 内部 =================

    private record Parsed(int iterations, byte[] salt, byte[] derived) {}

    private static Parsed parse(String hash) {
        if (!hash.startsWith(PREFIX)) {
            throw new IllegalArgumentException("不是本实现产生的哈希:" + abbreviate(hash));
        }
        String[] parts = hash.substring(PREFIX.length()).split("\\$");
        if (parts.length != 3) {
            throw new IllegalArgumentException("哈希结构不完整:" + abbreviate(hash));
        }
        if (!parts[0].startsWith("i=")) {
            throw new IllegalArgumentException("哈希里缺少迭代次数:" + abbreviate(hash));
        }
        return new Parsed(
                Integer.parseInt(parts[0].substring(2)),
                Base64.getDecoder().decode(parts[1]),
                Base64.getDecoder().decode(parts[2]));
    }

    private static byte[] derive(String plaintext, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(plaintext.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                    .generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "JVM 不支持 PBKDF2WithHmacSHA512,无法哈希密码:" + e.getMessage(), e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 密码长度上限。
     *
     * <p>**必须有上限**:PBKDF2 的耗时随输入长度增长,接受 10MB 的「密码」
     * 等于给了一个 CPU 耗尽的攻击入口(每个登录请求烧掉一核几秒)。
     * 72 是 bcrypt 的历史上限,这里取一个宽松得多但仍有界的值。
     */
    private static final int MAX_PASSWORD_BYTES = 4096;

    private static void requirePassword(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException(
                    "不能对空密码做哈希。空密码应该在校验阶段被 required 规则挡住。");
        }
        int bytes = plaintext.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("""
                    密码长度 %d 字节超过上限 %d。
                      上限不是刁难用户 —— PBKDF2 的耗时随输入增长,
                      不设限等于开放一个 CPU 耗尽的攻击面。
                      请在校验规则里加 max:%d。"""
                    .formatted(bytes, MAX_PASSWORD_BYTES, MAX_PASSWORD_BYTES));
        }
    }

    private static String abbreviate(String hash) {
        return hash.length() <= 24 ? hash : hash.substring(0, 24) + "…";
    }

    /** 可替换的算法接口 —— 想接 Argon2id / bcrypt 时实现它 */
    public interface Algorithm {
        String name();
        String make(String plaintext);
        boolean check(String plaintext, String hash);
        boolean needsRehash(String hash);
    }
}
