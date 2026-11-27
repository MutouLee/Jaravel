package net.dobeen.jaravel.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 每个测试在事务里跑,结束回滚(docs/14 §2.1)—— **这是默认策略**。
 *
 * <pre>{@code
 * @DatabaseTransactions          // 可以不写,写出来是为了「我知道我在用哪个策略」
 * class PostTest extends FeatureTest { }
 * }</pre>
 *
 * <h2>为什么给默认行为也留一个注解</h2>
 * <p>因为另外两个({@link RefreshDatabase} / {@link DatabaseMigrations})存在。
 * 一个测试类上什么都不写时,读者无法区分「作者选了事务回滚」与
 * 「作者没想过隔离这件事」。显式写出来让 code review 里的那句
 * 「这个类为什么不回滚?」有地方问。
 *
 * <p>运行时它**什么都不做** —— 默认就是它。真正关掉回滚的是
 * {@link WithoutRollback}。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface DatabaseTransactions {
}
