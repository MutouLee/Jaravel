package io.jaravel.auth;

import java.util.Map;
import java.util.Optional;

/**
 * 认证守卫:回答「这个请求是谁」。
 *
 * <p>所有认证方式(会话、Bearer Token、JWT、Basic)都实现它,
 * 因此业务代码里的 {@code Auth.check()} 与具体方式无关 —— 换认证方式
 * 只改配置。这是 docs/08 的设计目标之二。
 */
public interface Guard {

    /** guard 的名字,用于报错与诊断 */
    String name();

    Optional<Authenticatable> user();

    default Optional<Object> id() {
        return user().map(Authenticatable::getAuthIdentifier);
    }

    default boolean check() {
        return user().isPresent();
    }

    default boolean guest() {
        return !check();
    }

    /**
     * 只验证凭据,**不登录**。
     *
     * <p>用途:改密码前确认当前密码、敏感操作二次确认。
     * 与 {@code attempt} 分开是因为后者会写会话 —— 在「确认身份」的场景里
     * 那是个副作用。
     */
    boolean validate(Map<String, Object> credentials);

    /** 直接设定当前用户,不走凭据校验。供测试与 Token 守卫内部使用。 */
    void setUser(Authenticatable user);

    UserProvider provider();
}
