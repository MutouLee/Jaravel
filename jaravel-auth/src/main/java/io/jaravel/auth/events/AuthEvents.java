package io.jaravel.auth.events;

import java.util.Map;
import java.util.Set;

import io.jaravel.auth.Authenticatable;

/**
 * 认证事件(docs/10 §2.5)。
 *
 * <pre>{@code
 * @Listener
 * public class AuditLogins {
 *     public void handle(AuthEvents.Login e)  { audit("login", e.user().getAuthIdentifier()); }
 *     public void handle(AuthEvents.Failed e) { audit("login-failed", e.identifier()); }
 * }
 * }</pre>
 *
 * <h2>Failed 事件里**没有密码**</h2>
 * <p>这是整组事件里最要紧的一条设计约束。
 *
 * <p>Laravel 的 {@code Failed} 事件带着完整的 credentials 数组,
 * 于是任何一个把事件写进日志的监听器都会**把明文密码写进日志文件**。
 * 那是一个真实且常见的事故 —— 日志通常比数据库更容易被读到
 * (集中式日志、第三方 APM、客服工单附件)。
 *
 * <p>所以 Jaravel 的 {@code Failed} 只带:
 * <ul>
 *   <li>{@code identifier} —— 用来登录的那个标识(邮箱/用户名);</li>
 *   <li>{@code attemptedKeys} —— 提交了哪些字段(**只有键名**),
 *       用来排查「前端少传了一个字段」。</li>
 * </ul>
 *
 * <p>一个「记录可疑登录」的监听器不需要密码;真的需要密码的场景
 * 不存在。把它排除在类型之外比写一条「请不要记录它」的注释可靠得多。
 */
public final class AuthEvents {

    private AuthEvents() {
    }

    /**
     * 登录成功。
     *
     * @param guard    哪个 guard
     * @param user     登录的用户
     * @param remember 是否签发了「记住我」
     */
    public record Login(String guard, Authenticatable user, boolean remember) {
    }

    /**
     * 登出。
     *
     * @param user 登出的用户。**可能为 null** —— 一个未登录的会话
     *             调 logout() 是合法的(前端的「退出」按钮在会话
     *             已过期时仍然可点),那时没有用户可报
     */
    public record Logout(String guard, Authenticatable user) {
    }

    /**
     * 登录失败。
     *
     * <p>**刻意不含密码** —— 见类注释。
     *
     * @param identifier    用来登录的标识(邮箱/用户名)。查不到字段时为 null
     * @param attemptedKeys 提交了哪些凭据字段,**只有键名没有值**
     * @param userExists    那个标识对应的用户存在吗。
     *                      用来区分「密码错」与「账号不存在」——
     *                      这个区别**不该返回给客户端**(会被用来枚举账号),
     *                      但对内部审计有用
     */
    public record Failed(String guard, String identifier,
                         Set<String> attemptedKeys, boolean userExists) {

        /** 从一组凭据里安全地提取事件数据 —— 值一律不带 */
        public static Failed from(String guard, Map<String, Object> credentials,
                                  boolean userExists) {
            String identifier = null;
            for (String candidate : new String[] {"email", "username", "name", "phone"}) {
                Object value = credentials.get(candidate);
                if (value != null) {
                    identifier = String.valueOf(value);
                    break;
                }
            }
            return new Failed(guard, identifier,
                    Set.copyOf(credentials.keySet()), userExists);
        }
    }

    /**
     * 节流锁定 —— 同一来源失败次数过多。
     *
     * @param identifier 被锁的标识
     * @param key        节流器用的 key(通常是 标识 + IP)
     * @param seconds    还要等多少秒
     */
    public record Lockout(String identifier, String key, long seconds) {
    }

    /**
     * 注册完成。
     *
     * <p>⚠ **这个事件由应用自己发**,框架不发它 —— 因为 Jaravel 没有
     * 内置的注册控制器。注册是业务:要收哪些字段、要不要邀请码、
     * 要不要创建一个默认工作区,这些框架无从决定。
     *
     * <pre>{@code
     * public Response register(RegisterRequest request) {
     *     var user = User.create(request.validated());
     *     Auth.login(user);
     *     Event.dispatch(new AuthEvents.Registered(Auth.wrap(user)));
     *     return Redirect.to("/dashboard");
     * }
     * }</pre>
     *
     * <p>它仍然定义在框架里,理由是「发送欢迎邮件」「发邮箱验证链接」
     * 这类监听器是跨项目通用的 —— 让每个项目各自定义一个
     * {@code UserRegistered} 会让那些监听器没法复用。
     *
     * <p>Laravel 的这个事件也是由它的 {@code RegisterController} 发的,
     * 而那个控制器属于 {@code laravel/ui} 脚手架而不是框架核心 ——
     * 本质上是同一个分工,只是 Jaravel 没有那层脚手架
     * ({@code install:auth} 在 v0.5)。
     */
    public record Registered(Authenticatable user) {
    }

    /** 邮箱验证完成 */
    public record Verified(Authenticatable user) {
    }

    /** 密码通过重置链接改掉了 */
    public record PasswordReset(Authenticatable user) {
    }
}
