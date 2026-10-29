package io.jaravel.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * remember me 令牌的生成、哈希与比对。
 *
 * <p>集中在一个类里,因为写入端({@link UserProvider#updateRememberToken})
 * 与读取端({@code retrieveByToken})必须用同一个哈希 —— 两边各写一遍的话,
 * 任何一边改了参数就变成「所有人的长期登录静默失效」。
 *
 * <h2>60 字节</h2>
 * <p>docs/08 §7.2 要求 60 字节。这个长度的意义:令牌是一个
 * **可直接用于登录的凭据**,且攻击者可以离线枚举(cookie 格式是
 * {@code <id>|<token>},id 是公开的小整数)。128 位已足够,
 * 取 60 字节是向 Laravel 的 60 字符对齐并留出余量。
 *
 * <h2>为什么要哈希存库</h2>
 * <p>明文存库时,任何**只读**的数据库泄露(SQL 注入、备份文件、
 * 误入日志的查询结果、离职员工的只读账号)都直接产出一批可用的登录凭据 ——
 * 攻击者不需要知道密码,拿 {@code id|token} 拼个 cookie 就是那个用户。
 *
 * <p>哈希之后,同样的泄露只给出一堆无法反推的摘要。这与「API token 存
 * SHA-256」是同一个理由,而 remember token 的危害更大:它的有效期是
 * 以月计的。
 *
 * <p><b>升级提示</b>:从明文存储升上来时,库里已有的 remember_token
 * 全部失效 —— 受影响的用户下次访问需要重新登录一次。这是刻意的:
 * 「同时接受明文与哈希」会让明文令牌继续可用,等于没修。
 *
 * <h2>为什么哈希用 SHA-256 而不是 PBKDF2</h2>
 * <p>令牌是 60 字节的**高熵随机值**,不是人选的密码。慢哈希的作用是
 * 抵抗字典攻击,而这里没有字典可查 —— 换成 PBKDF2 只会让每个带
 * remember cookie 的请求多花两百毫秒。API token 的处理方式相同。
 */
public final class RememberToken {

    /** docs/08 §7.2 指定的长度 */
    public static final int TOKEN_BYTES = 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private RememberToken() {
    }

    /** 生成一个新的明文令牌 —— 只会出现在 cookie 里,不进数据库 */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 存库用的摘要 */
    public static String hash(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用:" + e.getMessage(), e);
        }
    }

    /**
     * 比对 cookie 里的明文与库里的摘要。
     *
     * <p>定时安全:令牌可被逐字节爆破,逐字符早退的比较会把
     * 「前几个字节对不对」通过耗时泄露出去。
     */
    public static boolean matches(String storedHash, String presentedPlaintext) {
        if (storedHash == null || presentedPlaintext == null) return false;
        if (storedHash.isBlank() || presentedPlaintext.isBlank()) return false;

        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8),
                hash(presentedPlaintext).getBytes(StandardCharsets.UTF_8));
    }
}
