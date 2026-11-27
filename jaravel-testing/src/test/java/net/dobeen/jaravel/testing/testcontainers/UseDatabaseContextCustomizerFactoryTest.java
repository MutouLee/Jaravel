package net.dobeen.jaravel.testing.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

import net.dobeen.jaravel.testing.UseDatabase;

/** 注解发现与上下文缓存键(docs/14 §2.2);不启动 Docker */
class UseDatabaseContextCustomizerFactoryTest {

    private final UseDatabaseContextCustomizerFactory factory =
            new UseDatabaseContextCustomizerFactory();

    @UseDatabase(UseDatabase.Kind.MYSQL)
    static class Annotated {
    }

    static class NotAnnotated {
    }

    @UseDatabase(UseDatabase.Kind.POSTGRESQL)
    static class Base {
    }

    static class Child extends Base {
    }

    @UseDatabase(UseDatabase.Kind.MARIADB)
    static class Outer {
        class Inner {
        }
    }

    private ContextCustomizer customizerFor(Class<?> type) {
        return factory.createContextCustomizer(type, List.of());
    }

    @Test
    @DisplayName("标了注解的类得到 customizer,没标的为 null")
    void findsTheAnnotation() {
        assertThat(customizerFor(Annotated.class)).isNotNull();
        assertThat(customizerFor(NotAnnotated.class)).isNull();
    }

    @Test
    @DisplayName("超类上的注解也生效 —— 团队可以做一个共用基类")
    void inheritedFromSuperclass() {
        assertThat(customizerFor(Child.class)).isNotNull();
    }

    @Test
    @DisplayName("@Nested 的注解可以写在外层类上")
    void inheritedFromEnclosingClass() {
        assertThat(customizerFor(Outer.Inner.class)).isNotNull();
    }

    @Test
    @DisplayName("上下文缓存键按 (kind, image) 区分")
    void customizersAreCacheKeys() {
        var mysql = customizerFor(Annotated.class);
        var mysqlAgain = customizerFor(Annotated.class);
        var postgres = customizerFor(Child.class);

        assertThat(mysql)
                .isEqualTo(mysqlAgain)
                .hasSameHashCodeAs(mysqlAgain)
                .isNotEqualTo(postgres);
    }

    @Test
    @DisplayName("测试显式指定数据源时不启容器 —— 尊重测试作者的隔离")
    void explicitDatasourceWins() {
        var customizer = new TestDatabaseCustomizer(UseDatabase.Kind.MYSQL, "");
        try (var context = new GenericApplicationContext()) {
            var config = new MergedContextConfiguration(
                    Annotated.class, null, null, Set.of(), null, null,
                    new String[]{"spring.datasource.url=jdbc:sqlite::memory:"},
                    null, null, null);

            customizer.customizeContext(context, config);

            assertThat(context.getEnvironment().getPropertySources()
                    .get(TestDatabaseCustomizer.SOURCE_NAME)).isNull();
        }
    }
}
