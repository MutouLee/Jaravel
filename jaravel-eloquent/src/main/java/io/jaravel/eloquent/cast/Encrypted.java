package io.jaravel.eloquent.cast;

import io.jaravel.core.crypt.Encrypter;
import io.jaravel.eloquent.meta.Cast;

/**
 * 列级加密:写库前加密,读出后解密。
 *
 * <pre>{@code
 * @Cast.Use(Encrypted.class)
 * public String idCardNumber;
 * }</pre>
 *
 * <p>用 AES-256-GCM(见 {@link Encrypter})。GCM 自带认证,所以
 * **被篡改的密文会解密失败而不是产出垃圾明文** —— 对于身份证号、
 * 银行卡号这类字段,「读出来是乱码」远好过「读出来是另一个合法号码」。
 *
 * <h2>代价,用之前要知道</h2>
 * <ul>
 *   <li><b>不能 WHERE、不能索引</b>。每次加密的 IV 都不同,所以同一个明文
 *       每次产出不同密文 —— 这正是语义安全的要求,但也意味着
 *       {@code where(idCardNumber, x)} 永远查不到。需要按值查询时,
 *       另存一列 HMAC 做确定性索引。</li>
 *   <li><b>换 APP_KEY = 数据读不出来</b>。轮换密钥需要先全表解密再重新加密,
 *       没有捷径。</li>
 *   <li>密文比明文长(IV 12 字节 + 认证标签 16 字节 + base64 膨胀 1/3),
 *       列宽要留足。</li>
 * </ul>
 */
public class Encrypted implements Cast<String, String> {

    /**
     * 加密器。由 starter 在启动时从 {@code APP_KEY} 注入。
     *
     * <p>静态而非实例字段:转换器由 APT 生成的元模型 {@code new} 出来,
     * 没有注入点。而密钥是全应用唯一的,静态持有与它的生命周期一致。
     */
    private static volatile Encrypter encrypter;

    public static void useEncrypter(Encrypter e) {
        encrypter = e;
    }

    static Encrypter encrypter() {
        Encrypter e = encrypter;
        if (e == null) {
            throw new IllegalStateException("""
                    @Cast.Use(Encrypted.class) 需要加密密钥,但还没有设置。
                      Spring 环境下由 starter 从 APP_KEY 自动注入;没配 APP_KEY 时:
                        1. 生成:Encrypter.generateKey()
                        2. 写进 .env:APP_KEY=base64:xxxxx
                      脱离框架使用时手动调用 Encrypted.useEncrypter(...)。

                      注意:这里刻意报错而不是以明文落库 ——
                      后者是会上安全公告的那种 bug。""");
        }
        return e;
    }

    @Override
    public String fromDatabase(String raw) {
        if (raw == null) return null;
        // 解不开不抛异常,返回 null:换过密钥、或迁移来的历史明文数据,
        // 都不该让整张列表页 500。真正需要「必须能解开」的场景应该显式校验。
        return encrypter().tryDecrypt(raw).orElse(null);
    }

    @Override
    public String toDatabase(String value) {
        if (value == null) return null;
        return encrypter().encrypt(value);
    }
}
