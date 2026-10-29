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

    /**
     * 写入 remember 令牌。
     *
     * <p><b>收到的是明文,实现必须用 {@link RememberToken#hash} 哈希后再存。</b>
     * 明文只存在于 cookie 里 —— 见 {@link RememberToken} 的类注释里
     * 「为什么要哈希存库」。
     *
     * <p>这个契约放在注释里而不是由框架代劳,是因为写入动作本身在实现里
     * (不同存储的写法差别很大)。{@code AbstractUserProvider} 的子类
     * 可以直接调 {@code RememberToken.hash(token)}。
     */
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
     * 把一个已有的领域对象包装成 {@link Authenticatable}。
     *
     * <p>用途是**注册后立刻登录**这个场景:
     *
     * <pre>{@code
     * user.save();
     * Auth.login(user);      // 不需要再把它从库里查一遍
     * }</pre>
     *
     * <p>没有这个方法时调用方只能写
     * {@code Auth.login(provider.retrieveById(user.id).orElseThrow())} ——
     * 多两行代码,以及**多一次查库**去取一条刚刚才写进去的记录。
     *
     * <p>默认实现拒绝。不支持包装的提供器(LDAP、远程 API)确实做不到这件事:
     * 它们的主体不是本地对象。静默返回一个半空的包装体会更糟。
     */
    default Authenticatable wrap(Object model) {
        if (model instanceof Authenticatable ready) return ready;

        throw new UnsupportedOperationException("""
                provider "%s" 不支持把 %s 包装成 Authenticatable。
                  这个提供器的用户主体不是本地对象(LDAP / 远程 API 等)。
                  改用 Auth.loginUsingId(id)。"""
                .formatted(name(), model == null ? "null" : model.getClass().getName()));
    }

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
