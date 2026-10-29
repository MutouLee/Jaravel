package io.jaravel.spring.security;

import org.springframework.security.crypto.password.PasswordEncoder;

import io.jaravel.auth.hash.HashManager;

/**
 * 让 Spring Security 用 Jaravel 的哈希配置。
 *
 * <p>配合 {@link JaravelUserDetailsService} 使用 —— 见那边的类注释里
 * 「为什么必须一起用」。
 *
 * <h2>两边的接口刚好对得上</h2>
 * <table>
 *   <caption>映射关系</caption>
 *   <tr><th>Spring</th><th>Jaravel</th></tr>
 *   <tr><td>{@code encode}</td><td>{@code Hash.make}</td></tr>
 *   <tr><td>{@code matches}</td><td>{@code Hash.check}</td></tr>
 *   <tr><td>{@code upgradeEncoding}</td><td>{@code Hash.needsRehash}</td></tr>
 * </table>
 *
 * <p>第三行是容易漏的那个:实现了它,Spring 的
 * {@code DaoAuthenticationProvider} 会在登录成功时自动重算哈希,
 * 于是「调高了 iterations / 从 bcrypt 迁到 argon2」能随用户登录渐进生效。
 * 不实现就等于永不升级 —— 和 Jaravel 侧
 * {@code UserProvider.rehashPasswordIfRequired} 的理由完全一样。
 */
public class JaravelPasswordEncoder implements PasswordEncoder {

    private final HashManager hashes;

    public JaravelPasswordEncoder(HashManager hashes) {
        this.hashes = hashes;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return hashes.make(rawPassword == null ? null : rawPassword.toString());
    }

    /**
     * 校验。
     *
     * <p>{@code encodedPassword} 为 null 时返回 false 而不是抛异常 ——
     * SSO 用户的 {@code password} 列是空的({@link JaravelUserDetails#getPassword()}
     * 会返回 null),走到这里是正常流程而非错误。
     */
    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) return false;
        return hashes.check(rawPassword.toString(), encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (encodedPassword == null) return false;
        return hashes.needsRehash(encodedPassword);
    }
}
