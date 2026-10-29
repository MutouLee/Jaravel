package io.jaravel.auth;

import java.util.Optional;

/**
 * 可被认证的主体。
 *
 * <h2>为什么不让用户的模型直接实现它</h2>
 * <p>docs/08 §3.1 原本设想 {@code class User extends Model<User> implements Authenticatable},
 * 用户要写四个 getter(或靠 APT 生成)。实际落地时换了做法:
 * {@link io.jaravel.auth.provider.EloquentUserProvider} 从 {@code @AuthModel}
 * 读列名,再把模型**包装**成 Authenticatable。
 *
 * <p>理由:
 * <ul>
 *   <li>用户的模型保持干净 —— 一个注解 vs 四个样板方法;</li>
 *   <li>不需要为此再写一轮 APT;</li>
 *   <li>Authenticatable 变成框架内部的协议,它要加方法时不会推着
 *       所有用户模型一起改。</li>
 * </ul>
 *
 * <p>代价:{@code Auth.user()} 返回的是包装体,拿模型要 {@code Auth.user(User.class)}。
 * 这个取舍写在 {@link Auth} 的注释里。
 */
public interface Authenticatable {

    /** 主键值 */
    Object getAuthIdentifier();

    /** 主键的列名,默认 {@code id} */
    default String getAuthIdentifierName() {
        return "id";
    }

    /** 已哈希的密码。没有密码的主体(纯 Token 认证)返回空。 */
    Optional<String> getAuthPassword();

    default String getAuthPasswordName() {
        return "password";
    }

    /** remember me 令牌 */
    default Optional<String> getRememberToken() {
        return Optional.empty();
    }

    default void setRememberToken(String token) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " 不支持 remember me —— "
              + "它没有可写的 remember_token 列。"
              + "\n  要支持的话:给表加 remember_token varchar(100) null,"
              + "并在 @AuthModel 里声明 rememberToken = \"remember_token\"。");
    }

    default String getRememberTokenName() {
        return "remember_token";
    }

    /** 拿回被包装的原始对象(通常是 Eloquent 模型) */
    default Object unwrap() {
        return this;
    }

    /**
     * 把主体当作某个具体类型取出。
     *
     * <p>{@code Auth.user(User.class)} 走这条路径。类型不符时返回空而不是
     * 抛异常 —— 多 guard 应用里「这个请求的用户是 Admin 不是 User」是正常情况。
     */
    default <T> Optional<T> as(Class<T> type) {
        Object raw = unwrap();
        return type.isInstance(raw) ? Optional.of(type.cast(raw)) : Optional.empty();
    }
}
