package io.jaravel.auth;

import java.util.Map;
import java.util.Optional;

/**
 * 用户来源:按 id / 凭据 / remember 令牌取用户。
 *
 * <p>与 {@link Guard} 分开的意义:认证方式(会话 / JWT)与用户存储
 * (Eloquent / 裸表 / LDAP)是两个独立的维度。合在一起的话,
 * 「JWT + LDAP」就需要第四个类,而拆开后是 2×2。
 */
public interface UserProvider {

    String name();

    Optional<Authenticatable> retrieveById(Object id);

    /**
     * 按 remember 令牌取用户。
     *
     * <p>**必须同时校验 id 与令牌**,且令牌比较要定时安全 ——
     * 只按 id 取然后信任 cookie 等于「谁都能伪造登录」。
     */
    Optional<Authenticatable> retrieveByToken(Object id, String token);

    void updateRememberToken(Authenticatable user, String token);

    /**
     * 按凭据查用户(不校验密码)。
     *
     * <p>{@code credentials} 里的 {@code password} 会被忽略 ——
     * 密码校验是 {@link #validateCredentials} 的事。分成两步是为了
     * 让「用户不存在」与「密码不对」走同样的耗时路径(见
     * {@code AbstractUserProvider} 的时序攻击防护)。
     */
    Optional<Authenticatable> retrieveByCredentials(Map<String, Object> credentials);

    boolean validateCredentials(Authenticatable user, Map<String, Object> credentials);

    /**
     * 哈希参数升级后,在登录成功时静默重算。
     *
     * <p>只有此刻手里有明文密码,不做就等于永不升级。
     */
    default void rehashPasswordIfRequired(Authenticatable user,
                                          Map<String, Object> credentials) {
        // 默认不做;能写回密码的提供器覆盖它
    }
}
