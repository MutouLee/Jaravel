package io.jaravel.auth.twofactor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户侧的二步验证(docs/08 §6.4)。
 *
 * <p>应用的用户模型实现这几个访问器,就得到 docs 里的那套
 * {@code enableTwoFactor()} / {@code confirmTwoFactor(code)} ——
 * 存到哪几列由应用决定(与 {@code Authenticatable} 同一个取舍:
 * 框架不规定存储形状)。
 *
 * <pre>{@code
 * public class User implements TwoFactorAuthenticatable {
 *     public String twoFactorSecret;
 *     public boolean twoFactorEnabled;
 *     public List<String> twoFactorRecoveryCodes = List.of();
 *
 *     @Override public String twoFactorSecret() { return twoFactorSecret; }
 *     @Override public void twoFactorSecret(String s) { twoFactorSecret = s; }
 *     ...
 * }
 *
 * TwoFactorSetup setup = user.enableTwoFactor();   // 显示一次,含恢复码
 * boolean ok = user.confirmTwoFactor(code);        // 确认一次后 enabled=true
 * }</pre>
 *
 * <h2>★ 恢复码只存哈希,而且用掉就删</h2>
 * <p>{@link #twoFactorRecoveryCodes()} 存的是
 * {@link RecoveryCodes#hash(String)} 的结果;{@link #useRecoveryCode(String)}
 * 在匹配时把那个哈希从列表里移除 —— 同一个码不能用两次。
 *
 * <h2>时间可注入</h2>
 * <p>{@code confirm} / {@code verify} 都有收 {@link Instant} 的重载:
 * 测试用固定时间点,窗口边界才测得到。
 */
public interface TwoFactorAuthenticatable {

    /** Base32 密钥;没有启用时为 null */
    String twoFactorSecret();

    void twoFactorSecret(String secret);

    /** 是否**已确认**启用(刚生成密钥时还是 false) */
    boolean twoFactorEnabled();

    void twoFactorEnabled(boolean enabled);

    /** 恢复码的 **SHA-256 哈希**列表(不是明文) */
    List<String> twoFactorRecoveryCodes();

    void twoFactorRecoveryCodes(List<String> hashedCodes);

    /** 二维码/otpauth URI 里的账号标签,通常是邮箱或用户名 */
    String twoFactorAccount();

    // ================= 默认流程 =================

    default TwoFactorSetup enableTwoFactor() {
        return enableTwoFactor("Jaravel", twoFactorAccount());
    }

    /**
     * 生成密钥与恢复码。**恢复码明文只在这里返回一次**;
     * 存储里只有哈希。此时 {@link #twoFactorEnabled()} 仍是 false,
     * 要等用户输入一次验证码确认。
     */
    default TwoFactorSetup enableTwoFactor(String issuer, String account) {
        String secret = Totp.generateSecret();
        List<String> codes = RecoveryCodes.generate();

        twoFactorSecret(secret);
        twoFactorRecoveryCodes(codes.stream().map(RecoveryCodes::hash).toList());
        twoFactorEnabled(false);

        return new TwoFactorSetup(secret, Totp.otpauthUri(issuer, account, secret), codes);
    }

    default boolean confirmTwoFactor(String code) {
        return confirmTwoFactor(code, Instant.now());
    }

    /** 确认一次,过后才算启用 */
    default boolean confirmTwoFactor(String code, Instant time) {
        if (twoFactorSecret() == null) return false;
        if (!Totp.verify(twoFactorSecret(), code, time)) return false;

        twoFactorEnabled(true);
        return true;
    }

    default boolean verifyTwoFactor(String code) {
        return verifyTwoFactor(code, Instant.now());
    }

    default boolean verifyTwoFactor(String code, Instant time) {
        return twoFactorEnabled()
            && twoFactorSecret() != null
            && Totp.verify(twoFactorSecret(), code, time);
    }

    /** 用掉一个恢复码;成功时这个哈希从列表里删除,不能重复使用 */
    default boolean useRecoveryCode(String code) {
        String normalized = RecoveryCodes.normalize(code);
        if (normalized.isEmpty()) return false;

        var remaining = new ArrayList<>(twoFactorRecoveryCodes());
        boolean used = remaining.removeIf(hash -> RecoveryCodes.verify(normalized, hash));

        if (used) twoFactorRecoveryCodes(List.copyOf(remaining));
        return used;
    }

    /** 关掉并清空 —— 调用方应当先验证密码或当前验证码 */
    default void disableTwoFactor() {
        twoFactorSecret(null);
        twoFactorEnabled(false);
        twoFactorRecoveryCodes(List.of());
    }

    /** {@link #enableTwoFactor()} 的返回值:明文密钥、otpauth URI、明文恢复码 */
    record TwoFactorSetup(String secret, String otpauthUri, List<String> recoveryCodes) {
    }
}
