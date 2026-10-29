package io.jaravel.core.crypt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对称加密。会话 cookie、{@code @Cast.Use(Encrypted.class)} 的列、签名 URL 都用它。
 *
 * <h2>AES-256-GCM,不是 AES-CBC + HMAC</h2>
 * <p>GCM 自带认证(AEAD),一次搞定机密性与完整性。CBC 需要额外拼一个 HMAC,
 * 而「先验证还是先解密」搞错就是 padding oracle —— 这类错误在实现层极易犯,
 * 而且写出来的代码看不出问题。少一个自由度就少一类漏洞。
 *
 * <h2>密文格式</h2>
 * <pre>
 * base64( 版本(1B) | IV(12B) | 密文+认证标签 )
 * </pre>
 * <p>版本号留着给将来换算法用:没有它,换算法就意味着所有已加密数据
 * 同时失效(会话全部掉线、加密列全部读不出)。
 *
 * <h2>这个类不做什么</h2>
 * <p>**不做密码哈希。** 密码必须用单向、慢速的 KDF,而这里是可逆的快加密 ——
 * 用它存密码是严重错误。密码请用 {@code io.jaravel.core.crypt.Hasher}。
 */
public final class Encrypter {

    private static final byte VERSION_AES_GCM = 1;

    private static final int KEY_BYTES = 32;      // AES-256
    private static final int IV_BYTES = 12;       // GCM 推荐 96 位
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public Encrypter(byte[] rawKey) {
        if (rawKey == null || rawKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("""
                    加密密钥必须是 32 字节(AES-256),当前是 %d 字节。
                      生成一个:Encrypter.generateKey()
                      然后放进 .env 的 APP_KEY=base64:...
                      密钥长度不对时宁可启动失败,也不要悄悄截断或补零 ——
                      那会让密钥的有效强度远低于看起来的样子。"""
                    .formatted(rawKey == null ? 0 : rawKey.length));
        }
        this.key = new SecretKeySpec(rawKey, "AES");
    }

    /** 从 {@code base64:...} 或裸 base64 构造 */
    public static Encrypter fromBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("""
                    APP_KEY 没有设置,无法加密会话与敏感列。
                      生成一个:
                        java -e 'System.out.println(io.jaravel.core.crypt.Encrypter.generateKey())'
                      或用 jaravel key:generate(v0.5)
                      然后写进 .env:APP_KEY=base64:xxxxx""");
        }
        String body = encoded.startsWith("base64:") ? encoded.substring(7) : encoded;
        try {
            return new Encrypter(Base64.getDecoder().decode(body));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "APP_KEY 不是合法的 base64:" + e.getMessage()
                  + "\n  正确形式:APP_KEY=base64:<44 个 base64 字符>", e);
        }
    }

    /** 生成一个新密钥,形如 {@code base64:...} */
    public static String generateKey() {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return "base64:" + Base64.getEncoder().encodeToString(bytes);
    }

    // ================= 加解密 =================

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        return encryptBytes(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public String encryptBytes(byte[] plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext);

            byte[] out = new byte[1 + IV_BYTES + sealed.length];
            out[0] = VERSION_AES_GCM;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(sealed, 0, out, 1 + IV_BYTES, sealed.length);

            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密失败:" + e.getMessage(), e);
        }
    }

    public String decrypt(String ciphertext) {
        byte[] plain = decryptBytes(ciphertext);
        return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * 解密。
     *
     * @throws DecryptionFailedException 密文被篡改、密钥不对、或格式不认识
     */
    public byte[] decryptBytes(String ciphertext) {
        if (ciphertext == null) return null;

        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            // 兼容标准 base64(历史数据或别处加密的)
            try {
                raw = Base64.getDecoder().decode(ciphertext);
            } catch (IllegalArgumentException e2) {
                throw new DecryptionFailedException("密文不是合法的 base64", e2);
            }
        }

        if (raw.length < 1 + IV_BYTES + 1) {
            throw new DecryptionFailedException("密文长度不足,至少需要 " + (IV_BYTES + 2) + " 字节");
        }
        if (raw[0] != VERSION_AES_GCM) {
            throw new DecryptionFailedException(
                    "不认识的密文版本 " + raw[0] + "(本版本只支持 " + VERSION_AES_GCM + ")"
                  + "\n  可能是用更新版本的 Jaravel 加密的数据,降级运行时读不回来。");
        }

        byte[] iv = Arrays.copyOfRange(raw, 1, 1 + IV_BYTES);
        byte[] sealed = Arrays.copyOfRange(raw, 1 + IV_BYTES, raw.length);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(sealed);
        } catch (javax.crypto.AEADBadTagException e) {
            // 认证失败。**不要透露是密钥不对还是数据被改** ——
            // 区分这两者会给攻击者一个预言机
            throw new DecryptionFailedException(
                    "密文的完整性校验失败。原因可能是:数据被篡改、"
                  + "APP_KEY 变了、或密文来自别的应用。", e);
        } catch (GeneralSecurityException e) {
            throw new DecryptionFailedException("解密失败:" + e.getMessage(), e);
        }
    }

    /**
     * 尝试解密,失败返回空。
     *
     * <p>会话 cookie 用它:换了 APP_KEY 之后老 cookie 解不开是**正常**的,
     * 应该当成「没有会话」重新开始,而不是让每个老用户看到 500。
     */
    public java.util.Optional<String> tryDecrypt(String ciphertext) {
        try {
            return java.util.Optional.ofNullable(decrypt(ciphertext));
        } catch (DecryptionFailedException e) {
            return java.util.Optional.empty();
        }
    }

    public static class DecryptionFailedException extends RuntimeException {
        public DecryptionFailedException(String message) { super(message); }
        public DecryptionFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
