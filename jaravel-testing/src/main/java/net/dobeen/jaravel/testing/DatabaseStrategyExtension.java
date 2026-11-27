package net.dobeen.jaravel.testing;

import java.util.List;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.dobeen.jaravel.database.Connection;
import net.dobeen.jaravel.migration.Migration;
import net.dobeen.jaravel.migration.Migrator;
import net.dobeen.jaravel.spring.MigrationRunner;

/**
 * 执行 {@link RefreshDatabase} / {@link DatabaseMigrations} 两个迁移型策略
 * (docs/14 §2.1)。
 *
 * <h2>为什么是 BeforeEachCallback 而不是 InvocationInterceptor</h2>
 * <p>因为迁移必须发生在 {@link RollbackExtension} 的事务**之外**。JUnit 的
 * 顺序是「全部 beforeEach 回调 → 拦截器包住的测试方法」,于是回调里跑的
 * 迁移天然在事务外 —— 而拦截器之间的相对顺序要靠注册次序维持,
 * 那是一个「改了 @ExtendWith 的顺序就静默失效」的脆弱约定。
 *
 * <p>(实际上这两个策略下根本没有事务:{@code RollbackExtension} 会
 * 查 {@link DatabaseStrategy#rollsBack()} 后直接放行。两道保险都留着。)
 *
 * <h2>每个测试都迁移一次,很慢,这是明说的代价</h2>
 * <p>两个注解的 javadoc 都写了这一点。不做「每个类一次」的优化:
 * 那会让「上一个测试写进去的数据」漏给下一个测试,而这两个注解的
 * 全部意义就是「结构与数据都是新的」。要快就用默认的事务回滚。
 */
public class DatabaseStrategyExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        switch (DatabaseStrategy.of(context)) {
            case REFRESH -> {
                var fixture = fixtureOf(context, "@RefreshDatabase");
                fixture.reset();
                fixture.migrate();
            }
            case MIGRATIONS -> fixtureOf(context, "@DatabaseMigrations").migrate();
            case TRANSACTIONS -> { /* 默认策略由 RollbackExtension 负责 */ }
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (DatabaseStrategy.of(context) == DatabaseStrategy.MIGRATIONS) {
            fixtureOf(context, "@DatabaseMigrations").reset();
        }
    }

    /**
     * 迁移需要的两样东西:连接,以及 classpath 上的迁移类。
     *
     * <p>取不到就报错 —— 而且报的是「你标了哪个注解」。标了
     * {@code @RefreshDatabase} 却没有数据库,唯一合理的反应是立刻说清楚:
     * 静默退化成「什么都不迁移」会让迁移测试**全绿**,而它们什么都没验证。
     */
    private Fixture fixtureOf(ExtensionContext context, String annotation) {
        ApplicationContext spring;
        try {
            spring = SpringExtension.getApplicationContext(context);
        } catch (RuntimeException e) {
            throw new IllegalStateException("""
                    %s 需要一个 Spring 上下文,但这个测试没有。
                      继承 AppTest 或 FeatureTest,或自己加 @SpringBootTest。"""
                    .formatted(annotation), e);
        }

        if (spring.getBeanNamesForType(Connection.class).length == 0) {
            throw new IllegalStateException("""
                    %s 需要数据库连接,但上下文里没有。
                      配置 spring.datasource.url 后再用这个注解
                      (测试里常用 jdbc:sqlite:file:test?mode=memory&cache=shared)。"""
                    .formatted(annotation));
        }

        return new Fixture(
                spring.getBean(Connection.class),
                spring.getBean(MigrationRunner.class).discover(),
                annotation);
    }

    /** 一次迁移操作要用的全部状态 */
    private record Fixture(Connection connection, List<Migration> migrations, String annotation) {

        void migrate() {
            run(migrator -> migrator.migrate(migrations));
        }

        void reset() {
            run(migrator -> migrator.reset(migrations));
        }

        /**
         * 跑一个迁移动作,失败时把 migrator 的输出带上。
         *
         * <p>migrator 把「跑了哪些迁移」记在自己的 {@code output()} 里,
         * 异常消息里没有。不带上的话,一个在第 7 个迁移上失败的
         * {@code @RefreshDatabase} 看起来就是「建表失败」,
         * 而真正有用的信息是前 6 个过了。
         */
        void run(java.util.function.Consumer<Migrator> action) {
            if (migrations.isEmpty()) {
                throw new IllegalStateException("""
                        %s 在 classpath 上找不到任何迁移类。
                          迁移约定放在 database.migrations 包下
                          (见 docs/05 §2),没有迁移时这个注解没有意义。"""
                        .formatted(annotation));
            }

            var migrator = new Migrator(connection);
            try {
                action.accept(migrator);
            } catch (RuntimeException e) {
                throw new IllegalStateException("""
                        %s 执行迁移失败:%s
                          已经跑完的部分:
                        %s"""
                        .formatted(annotation, e.getMessage(),
                                migrator.output().isEmpty() ? "      (没有)"
                                        : "      " + String.join("\n      ", migrator.output())),
                        e);
            }
        }
    }
}
