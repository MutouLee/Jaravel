package io.jaravel.auth.twofactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 二步验证的恢复码(docs/08 §6.4)。
 *
 * <p>用户丢了手机时用它登录。每个码只能用一次 —— 本类只提供生成、哈希与
 * 验证;{@link TwoFactorAuthenticatable#useRecoveryCode(String)} 会在匹配后
 * 把哈希从列表里删掉,应用负责把改过的列表存回去。
 *
 * <pre>{@code
 * List<String> codes = RecoveryCodes.generate();  // 显示给用户,只显示这一次
 * List<String> stored = codes.stream().map(RecoveryCodes::hash).toList();
 * // 登录时:
 * boolean ok = stored.removeIf(hash -> RecoveryCodes.verify(input, hash));
 * }</pre>
 *
 * <h2>★ 库里只存哈希</h2>
 * <p>恢复码等价于密码:数据库泄漏时明文恢复码就是一批可直接登录的凭据。
 * 用 SHA-256 而不是 bcrypt —— 恢复码本身是 50 位随机值,不需要慢哈希,
 * 而慢哈希会让「逐个比对十个恢复码」变慢(那正是登录路径)。
 */
public final class RecoveryCodes {

    public static final int DEFAULT_COUNT = 10;

    private static final int CODE_CHARS = 10;
    private static final int RANDOM_BYTES = 8;

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        return generate(DEFAULT_COUNT);
    }

    /** 生成 {@code count} 个形如 {@code ABCDE-FGHIJ} 的恢复码(去重) */
    public static List<String> generate(int count) {
        if (count <= 0) throw new IllegalArgumentException("恢复码数量必须大于 0");

        var random = new SecureRandom();
        var codes = new LinkedHashSet<String>(count);

        while (codes.size() < count) {
            byte[] bytes = new byte[RANDOM_BYTES];
            random.nextBytes(bytes);
            String raw = Base32.encode(bytes);
            String code = raw.substring(0, CODE_CHARS);
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
        }
        return List.copyOf(new ArrayList<>(codes));
    }

    /** 按用户可能输入的写法归一化:去空白/连字符,转大写 */
    public static String normalize(String code) {
        if (code == null) return "";
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** SHA-256 十六进制 */
    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalize(code).getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("这个 JVM 没有 SHA-256", e);
        }
    }

    /** 常数时间比较 —— 与 {@link Totp} 同一条理由 */
    public static boolean verify(String code, String storedHash) {
        if (code == null || storedHash == null) return false;
        return MessageDigest.isEqual(
                hash(code).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
