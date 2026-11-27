package net.dobeen.jaravel.testing;

import java.util.Map;

import net.dobeen.jaravel.database.Connection;
import net.dobeen.jaravel.eloquent.Model;

/**
 * 数据库断言(docs/14 §2.3)—— {@link FeatureTest} 与 {@link AppTest} 共用。
 *
 * <h2>为什么是接口而不是公共父类</h2>
 * <p>两个基类的父类位置已经被占了:它们各自带着不同的
 * {@code @SpringBootTest} 与不同的扩展集合,而那是类级注解,
 * 不能由一个共同父类提供(提供了就无法分别覆盖)。
 *
 * <p>接口的默认方法正好:断言的实现只依赖一个
 * {@link #testConnection()},而那是实现方能给的唯一东西。
 *
 * <h2>方法都是 public</h2>
 * <p>接口成员只能是 public,而这恰好与 {@code FeatureTest} 自己的理由一致:
 * {@code @Nested} 内部类不是外层类的子类,protected 的继承成员在里面
 * 访问不到(那条坑写在 {@code FeatureTest} 的注释里)。
 */
public interface DatabaseTestSupport {

    /**
     * 被测应用的数据库连接。
     *
     * <p>没有连接时**抛异常**而不是返回 null:断言方法拿到 null 只会
     * 变成一个 NPE,而 NPE 不会告诉你「去配 spring.datasource.url」。
     */
    Connection testConnection();

    default void assertDatabaseHas(String table, Map<String, ?> attributes) {
        DatabaseAssertions.has(testConnection(), table, attributes);
    }

    default void assertDatabaseMissing(String table, Map<String, ?> attributes) {
        DatabaseAssertions.missing(testConnection(), table, attributes);
    }

    default void assertDatabaseCount(String table, long expected) {
        DatabaseAssertions.count(testConnection(), table, expected);
    }

    default void assertDatabaseEmpty(String table) {
        DatabaseAssertions.count(testConnection(), table, 0);
    }

    default void assertSoftDeleted(Model<?> model) {
        DatabaseAssertions.softDeleted(testConnection(), model, true);
    }

    default void assertNotSoftDeleted(Model<?> model) {
        DatabaseAssertions.softDeleted(testConnection(), model, false);
    }

    default void assertModelExists(Model<?> model) {
        DatabaseAssertions.modelExists(testConnection(), model, true);
    }

    default void assertModelMissing(Model<?> model) {
        DatabaseAssertions.modelExists(testConnection(), model, false);
    }

    /**
     * 断言这段代码只发了 N 条 SQL。
     *
     * <p>这是对 Laravel 的增强:**把 N+1 变成会让 CI 失败的回归测试**,
     * 而不是靠人工 review 或上线后的数据库告警。
     */
    default void assertQueryCount(int expected, Runnable body) {
        Queries.assertCount(testConnection(), expected, body);
    }

    /** 数一下发了多少条 SQL,自己决定怎么断言 */
    default <T> T countingQueries(java.util.function.Supplier<T> body,
                                  java.util.function.IntConsumer report) {
        return Queries.counting(testConnection(), body, report);
    }
}
