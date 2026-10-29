package io.jaravel.auth;

import java.util.Map;
import java.util.Optional;

/**
 * 有状态守卫:登录状态跨请求保持(会话型)。
 *
 * <p>Token / JWT 守卫**不**实现它 —— 它们没有「登录」这个动作,
 * 凭据每次请求自带。把 {@code login} 放进 {@link Guard} 会让
 * {@code Auth.guard("api").login(user)} 变成一个编译通过但运行时无意义的调用。
 */
public interface StatefulGuard extends Guard {

    /**
     * 用凭据登录。
     *
     * @param remember 是否写 remember cookie(长期登录)
     * @return 是否成功
     */
    boolean attempt(Map<String, Object> credentials, boolean remember);

    default boolean attempt(Map<String, Object> credentials) {
        return attempt(credentials, false);
    }

    /** 只在本次请求内生效,不写会话 */
    boolean once(Map<String, Object> credentials);

    void login(Authenticatable user, boolean remember);

    default void login(Authenticatable user) {
        login(user, false);
    }

    Optional<Authenticatable> loginUsingId(Object id, boolean remember);

    default Optional<Authenticatable> loginUsingId(Object id) {
        return loginUsingId(id, false);
    }

    void logout();

    /**
     * 让其它设备上的会话失效。
     *
     * <p>需要当前密码:这个操作的后果不可逆(别的设备会被踢下线),
     * 而触发它的往往是「我怀疑账号被盗」—— 此时必须确认操作者是本人。
     */
    void logoutOtherDevices(String currentPassword);

    /** 本次认证是否来自 remember cookie 而非主动登录 */
    boolean viaRemember();
}
