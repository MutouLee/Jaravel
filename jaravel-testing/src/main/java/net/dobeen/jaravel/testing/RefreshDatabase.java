package net.dobeen.jaravel.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 每个测试前 {@code migrate:fresh} —— 全部回滚再全部重建(docs/14 §2.1)。
 *
 * <pre>{@code
 * @RefreshDatabase
 * class CreateTodosTableTest extends AppTest {
 *     @Test void the_migration_creates_the_table() { ... }
 * }
 * }</pre>
 *
 * <h2>慢,所以用途很窄</h2>
 * <p>它把全部迁移 down 再 up 一遍,成本随迁移数量线性增长。值得付这个
 * 代价的只有一类测试:**被测对象就是迁移本身**(建表、改列、加索引、
 * 数据回填)。其余情况用默认的事务回滚。
 *
 * <h2>★ 它不在事务里</h2>
 * <p>标了这个注解的测试**不会**被包进回滚事务。两个理由:
 * <ul>
 *   <li>DDL 在一部分数据库里不参与事务(MySQL 会隐式提交),
 *       「回滚」对建表语句本来就是假的;</li>
 *   <li>迁移测试要断言的恰恰是「表真的建出来了」—— 在一个
 *       永不提交的事务里断言这件事,断言的是幻觉。</li>
 * </ul>
 *
 * <p>于是数据是**真写进去**的,清理靠下一个测试前的那次 fresh。
 * 代价是最后一个测试留下的数据会留在库里 —— 测试库本来就是一次性的。
 *
 * <p>与 {@link DatabaseTransactions} / {@link DatabaseMigrations} 同时出现
 * 会在测试开始前报错,而不是让一个策略静默胜出。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RefreshDatabase {
}
