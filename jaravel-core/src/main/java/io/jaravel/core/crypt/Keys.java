package io.jaravel.core.crypt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 从 {@code app.key} 派生用途专属的子密钥。
 *
 * <pre>{@code
 * byte[] emailHashKey = Keys.derive(appKey, "jaravel:email-verification");
 * }</pre>
 *
 * <h2>为什么不直接复用 app.key</h2>
 * <p>「一个密钥只做一件事」是密码学工程的基本惯例。同一把 key 既用来
 * AES 加密会话、又用来算邮箱 HMAC、又用来签 URL 时,任何一处的实现缺陷
 * 都会把另外两处一起带下水 —— 而这三处的威胁模型完全不同(签名 URL 的
 * HMAC 输出是**公开的**,会话密钥则绝不能泄露任何比特)。
 *
 * <p>派生的代价是零:用户仍然只配一个 {@code APP_KEY}。
 *
 * <h2>为什么 salt 是固定标签而不是随机值</h2>
 * <p>这里要的是**确定性**:同一个 app.key 每次派生出同一把子密钥。
 * 随机 salt 会让每次重启都换 key,于是已发出的验证链接、已写出的加密
 * cookie 在部署后全部失效 —— 症状是「上线后用户全被登出,邮件里的链接全坏」。
 *
 * <p>标签在这里的作用是**域分离**:换个标签就是互不相干的另一把 key。
 * 这是 HKDF 里 {@code info} 参数的同一个思路,只是这里用单轮
 * HMAC-SHA256 就够 —— 输入({@code app.key})本身已经是高熵的随机密钥,
 * 不需要 HKDF 的 extract 阶段去「浓缩熵」。
 */
public final class Keys {

    private Keys() {
    }

    /** 派生一把 32 字节的子密钥 */
    public static byte[] derive(String appKey, String label) {
        return derive(appKey, label, 32);
    }

    /**
     * @param appKey {@code base64:} 前缀可有可无
     * @param label  用途标签,建议写成 {@code jaravel:<用途>}
     * @param length 1..32 字节(单轮 HMAC-SHA256 的输出上限)
     */
    public static byte[] derive(String appKey, String label, int length) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("app.key 为空,无法派生子密钥");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException(
                    "label 不能为空 —— 它是区分用途的唯一依据,空标签等于没做域分离");
        }
        if (length < 1 || length > 32) {
            throw new IllegalArgumentException(
                    "length 必须在 1..32 之间(当前 " + length + ")。"
                  + "\n  需要更长的密钥材料请用 HKDF 的多轮扩展,单轮 HMAC-SHA256 最多 32 字节。");
        }

        byte[] master = decode(appKey);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            byte[] full = mac.doFinal(label.getBytes(StandardCharsets.UTF_8));

            if (length == full.length) return full;
            byte[] truncated = new byte[length];
            System.arraycopy(full, 0, truncated, 0, length);
            return truncated;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("派生子密钥失败:" + e.getMessage(), e);
        }
    }

    /**
     * 解出 app.key 的原始字节。
     *
     * <p>不是 base64 时按 UTF-8 原样使用 —— 用户可能直接写了一串字符。
     * 这不理想(熵低),但静默失败更糟,而启动期的强度校验由
     * {@link Encrypter} 与 {@link UrlSigner} 的构造器负责。
     */
    private static byte[] decode(String appKey) {
        String body = appKey.startsWith("base64:") ? appKey.substring(7) : appKey;
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException notBase64) {
            return appKey.getBytes(StandardCharsets.UTF_8);
        }
    }
}
