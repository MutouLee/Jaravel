package io.jaravel.auth.twofactor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.jaravel.support.Clock;

/**
 * 用户侧的二步验证(docs/08 §6.4)。
 *
 * <p>存到哪几列由应用决定(与 {@code Authenticatable} 同一个取舍:
 * 框架不规定存储形状)—— 但**不需要**为此实现这个接口:
 * 列名写进 {@code @AuthModel},provider 包装模型时就把这七个访问器补齐了。
 *
 * <pre>{@code
 * @Model
 * @AuthModel(twoFactorSecret = "two_factor_secret",
 *            twoFactorEnabled = "two_factor_enabled",
 *            twoFactorRecoveryCodes = "two_factor_recovery_codes",
 *            account = "email")
 * public class User extends UserBase {
 *     public String twoFactorSecret;          // 只有字段
 *     public boolean twoFactorEnabled;
 *     @Cast.Use(AsList.class)
 *     public List<String> twoFactorRecoveryCodes = List.of();
 * }
 *
 * var setup = Auth.twoFactor().enableTwoFactor("记账本");   // 显示一次,含恢复码
 * Auth.userOrFail(User.class).save();
 *
 * boolean ok = Auth.twoFactor().confirmTwoFactor(code);     // 确认后 enabled=true
 * }</pre>
 *
 * <p>模型不是当前登录用户时用 {@link #of(Object)}。
 * 存储形状特殊(比如密钥在另一张表)的话,照样可以让模型自己实现本接口 ——
 * {@code of(...)} 优先认模型自己的实现。
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

    /**
     * 取一个模型(或主体)的二步验证入口。
     *
     * <p>{@code @AuthModel} 的模型**不实现**这个接口 —— 列名写在注解里,
     * provider 负责包装(与 {@code Authenticatable} 同一套机制)。
     * 所以拿到一个模型想用那套 {@code enableTwoFactor()} 时走这里,
     * 而不是把七个访问器抄到模型上:
     *
     * <pre>{@code
     * var setup = TwoFactorAuthenticatable.of(user).enableTwoFactor("记账本");
     * user.save();
     * }</pre>
     *
     * <p>当前用户用 {@code Auth.twoFactor()} 更短。
     *
     * <p>⚠ 这里**不看**当前激活的是哪个 guard:二步验证的列写在模型的
     * {@code @AuthModel} 上,与 guard 无关。走 {@code Auth.guard().provider()}
     * 的话,admin guard 激活时传一个 User 进来会报「这个 provider 管的不是它」,
     * 而那跟调用方想做的事毫无关系。
     *
     * @param user 模型、或已经包装好的 {@link io.jaravel.auth.Authenticatable}
     * @throws IllegalStateException 模型既没实现本接口,也没标 {@code @AuthModel}。
     *                               标了注解但漏了那几列时,报错发生在**访问那一列**时
     *                               (说清漏的是哪一个,见 {@code ModelUser})
     */
    static TwoFactorAuthenticatable of(Object user) {
        if (user instanceof TwoFactorAuthenticatable ready) return ready;

        Object raw = user instanceof io.jaravel.auth.Authenticatable wrapped
                ? wrapped.unwrap()
                : user;
        if (raw instanceof TwoFactorAuthenticatable model) return model;

        if (raw instanceof io.jaravel.eloquent.Model<?> model
                && io.jaravel.auth.provider.EloquentUserProvider
                        .wrapStandalone(model) instanceof TwoFactorAuthenticatable usable) {
            return usable;
        }
        throw new IllegalStateException("""
                %s 上没有二步验证。
                  给 @AuthModel 写出那几列(推荐,模型侧只留字段):
                    @AuthModel(twoFactorSecret = "two_factor_secret",
                               twoFactorEnabled = "two_factor_enabled",
                               twoFactorRecoveryCodes = "two_factor_recovery_codes",
                               account = "email")
                  或者让模型自己实现 TwoFactorAuthenticatable 的七个访问器
                  (docs/08 §6.4)。"""
                .formatted(raw == null ? "null" : raw.getClass().getName()));
    }

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

    /** 账号标签用 {@link #twoFactorAccount()} —— 验证器里显示的那个名字 */
    default TwoFactorSetup enableTwoFactor(String issuer) {
        return enableTwoFactor(issuer, twoFactorAccount());
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
        return confirmTwoFactor(code, Clock.now());
    }

    /** 确认一次,过后才算启用 */
    default boolean confirmTwoFactor(String code, Instant time) {
        if (twoFactorSecret() == null) return false;
        if (!Totp.verify(twoFactorSecret(), code, time)) return false;

        twoFactorEnabled(true);
        return true;
    }

    default boolean verifyTwoFactor(String code) {
        return verifyTwoFactor(code, Clock.now());
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

    /**
     * 当前密钥的二维码(内联 SVG,docs/08 §6.4)。
     *
     * <pre>{@code
     * // 控制器
     * var setup = user.enableTwoFactor("记账本", user.email);
     * return View.of("settings.two-factor")
     *         .with("qr", user.twoFactorQrCodeSvg("记账本"))
     *         .with("codes", setup.recoveryCodes());
     *
     * // 模板:SVG 直接内联,不需要图片路由
     * {{!! qr !!}}
     * }</pre>
     *
     * <p>★ **返回 SVG 而不是 PNG/data-URI**:SVG 内联进 HTML 就不需要一条
     * 返回图片的路由 —— 而那条路由必须做鉴权(二维码里是 TOTP 密钥,
     * 等同于密码),一条忘了鉴权的图片路由是一个密钥泄露接口。
     * 内联的东西不可能忘。
     *
     * <p>⚠ 它渲染的是**当前**密钥。密钥还没生成({@code enableTwoFactor}
     * 之前)时报错而不是返回一张空图 —— 一张扫不出东西的二维码会被当成
     * 「这个二维码不好扫」,而真正的原因是没有密钥。
     */
    default String twoFactorQrCodeSvg(String issuer) {
        String secret = twoFactorSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("""
                    还没有二步验证密钥,画不出二维码。
                      先调 enableTwoFactor()(它生成密钥与恢复码),
                      再把返回值里的 otpauthUri 或这个方法的 SVG 给用户扫。""");
        }
        return QrCode.svg(Totp.otpauthUri(issuer, twoFactorAccount(), secret));
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
