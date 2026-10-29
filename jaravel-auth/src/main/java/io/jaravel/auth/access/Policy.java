package io.jaravel.auth.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个类声明为某个模型的授权策略。
 *
 * <pre>{@code
 * @Component
 * @Policy(Note.class)
 * public class NotePolicy {
 *     public boolean view(User user, Note note)   { return owns(user, note); }
 *     public boolean update(User user, Note note) { return owns(user, note); }
 *     public boolean create(User user)            { return true; }   // 不带模型
 * }
 * }</pre>
 *
 * <p>之后 {@code Gate.allows("update", note)} 与路由上的
 * {@code middleware("can:update,note")} 自动走到这个类。
 *
 * <h2>为什么用注解而不是在某处写一张映射表</h2>
 * <p>Laravel 要在 {@code AuthServiceProvider} 里维护
 * {@code $policies = [Note::class => NotePolicy::class]}。那张表的问题是
 * **新增 Policy 时会忘**,而忘了的症状是「授权规则不生效」—— 默认放行或
 * 默认拒绝都很难联想到「忘了注册」。
 *
 * <p>注解把声明放在它描述的那个类上,不可能写了 Policy 却忘了登记。
 *
 * <h2>方法签名不在编译期校验</h2>
 * <p>{@code update(User, Note)} 的形状由 {@link GateRegistry} 在**注册时**
 * 检查(启动期),而不是编译期 —— 因为 Gate 的调用点是字符串 ability,
 * 编译器无从知道哪些方法名会被用到。
 *
 * <p>启动期校验已经足够:它在第一个请求之前失败,且错误信息能列出
 * 这个 Policy 实际有哪些方法。真正的编译期校验要等 Gate 的调用点
 * 也变成类型安全的形式(列入 v0.5 的 APT 工作)。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Policy {

    /** 这个策略管哪个模型 */
    Class<?> value();
}
