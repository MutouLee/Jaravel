package io.jaravel.testing;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;

import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 一个测试用哪种数据库隔离策略(docs/14 §2.1)。
 *
 * <p>三个注解({@link DatabaseTransactions} / {@link RefreshDatabase} /
 * {@link DatabaseMigrations})在运行时都归到这里,由
 * {@link DatabaseStrategyExtension} 执行、由 {@link RollbackExtension} 查询。
 *
 * <h2>解析顺序:方法 → 本类 → 外层类 / 超类</h2>
 * <p>**最近的声明胜出**,而不是「任何一个出现就算」。于是一个
 * {@code @RefreshDatabase} 的类里可以有一个方法标回
 * {@code @DatabaseTransactions} —— 否则「整类 fresh,只有这一个
 * 不需要」就只能把方法搬到另一个类。
 *
 * <p>同一层出现两个策略注解时**报错**。让某一个静默胜出的代价是:
 * 两年后有人删掉其中一个,测试的隔离行为悄悄变了,而 diff 看起来
 * 只是「删了一个没用的注解」。
 */
enum DatabaseStrategy {

    /** 默认:测试体包在永不提交的事务里(见 {@link RollbackExtension}) */
    TRANSACTIONS,

    /** 测试前 reset + migrate,测试后什么都不做 */
    REFRESH,

    /** 测试前 migrate,测试后 reset */
    MIGRATIONS;

    /** 这个策略需要测试体跑在回滚事务里吗 */
    boolean rollsBack() {
        return this == TRANSACTIONS;
    }

    static DatabaseStrategy of(ExtensionContext context) {
        return of(context.getTestMethod().orElse(null), context.getTestClass().orElse(null));
    }

    /**
     * 解析的本体 —— 只看方法与类,不看 JUnit 的上下文。
     *
     * <p>拆出来是为了能直接用「方法 + 类」测它:
     * {@code ExtensionContext} 有二十来个方法,为测三个注解的优先级
     * 去造一个假实现,假实现本身就会成为需要维护的东西。
     */
    static DatabaseStrategy of(java.lang.reflect.Method method, Class<?> testClass) {
        if (method != null) {
            DatabaseStrategy declared = declaredOn(method, describe(method));
            if (declared != null) return declared;
        }

        // 从当前类往外层/往上一路找 —— @Nested 的外层类与抽象基类上标了也算。
        // (@Inherited 管得了超类,管不了外部类:内部类不是外部类的子类)
        for (Class<?> type = testClass; type != null; type = type.getEnclosingClass()) {
            for (Class<?> level = type; level != null; level = level.getSuperclass()) {
                DatabaseStrategy declared = declaredOn(level, level.getName());
                if (declared != null) return declared;
            }
        }
        return TRANSACTIONS;
    }

    /**
     * 这个元素上直接标了哪个策略注解。
     *
     * <p>用 {@code getDeclaredAnnotation} 而不是 {@code getAnnotation}:
     * 这个方法自己负责逐层向上走,交给 {@code @Inherited} 会让
     * 「超类标了、子类标了另一个」看起来是同一层冲突。
     */
    private static DatabaseStrategy declaredOn(AnnotatedElement element, String where) {
        var found = new ArrayList<String>();
        DatabaseStrategy strategy = null;

        if (present(element, DatabaseTransactions.class)) {
            found.add("@DatabaseTransactions");
            strategy = TRANSACTIONS;
        }
        if (present(element, RefreshDatabase.class)) {
            found.add("@RefreshDatabase");
            strategy = REFRESH;
        }
        if (present(element, DatabaseMigrations.class)) {
            found.add("@DatabaseMigrations");
            strategy = MIGRATIONS;
        }

        if (found.size() > 1) {
            throw new IllegalStateException("""
                    %s 上同时标了 %s —— 这三个注解是互斥的隔离策略,
                      只能选一个(不标就是 @DatabaseTransactions)。
                      它们的区别见 docs/14 §2.1。"""
                    .formatted(where, String.join(" 与 ", found)));
        }
        return strategy;
    }

    private static boolean present(AnnotatedElement element, Class<? extends Annotation> type) {
        return element.getDeclaredAnnotation(type) != null;
    }

    private static String describe(java.lang.reflect.Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName() + "()";
    }
}
