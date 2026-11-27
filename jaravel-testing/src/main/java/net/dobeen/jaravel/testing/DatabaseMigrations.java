package net.dobeen.jaravel.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 每个测试前 {@code migrate},测试后 {@code migrate:reset}(docs/14 §2.1)。
 *
 * <pre>{@code
 * @DatabaseMigrations
 * class RollbackIsReversibleTest extends AppTest { ... }
 * }</pre>
 *
 * <h2>与 {@link RefreshDatabase} 的区别</h2>
 * <table border="1">
 *   <caption>两个迁移型策略</caption>
 *   <tr><th></th><th>测试前</th><th>测试后</th></tr>
 *   <tr><td>{@code @RefreshDatabase}</td><td>reset + migrate</td><td>(什么都不做)</td></tr>
 *   <tr><td>{@code @DatabaseMigrations}</td><td>migrate</td><td>reset</td></tr>
 * </table>
 *
 * <p>两者都保证测试开始时结构是新的,区别在**谁付清理的代价**。
 * 这个注解在测试后 reset,于是它验证的是「down 真的能把结构拆干净」——
 * 一个只写了 up、down 写错的迁移会在这里暴露,而 {@code @RefreshDatabase}
 * 下一次 reset 失败时你已经在另一个测试里了。
 *
 * <p>和 {@link RefreshDatabase} 一样,标了它的测试**不在事务里**
 * (理由见那个注解)。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface DatabaseMigrations {
}
