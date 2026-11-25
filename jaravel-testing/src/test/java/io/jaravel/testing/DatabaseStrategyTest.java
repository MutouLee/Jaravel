package io.jaravel.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 隔离策略注解的优先级(docs/14 §2.1)。
 *
 * <p>这里测的是**解析规则**,而不是迁移真的跑了 —— 后者需要一个真库,
 * 放在 examples/todo 的验收测试里。但规则本身是纯函数,而它出错的后果
 * (「标了注解却没生效」)在真库测试里只会表现为一个莫名其妙的失败。
 */
class DatabaseStrategyTest {

    // ---------- 被解析的样本类 ----------

    static class Plain {
        void plainMethod() { }

        @RefreshDatabase
        void refreshOnMethod() { }

        @DatabaseTransactions
        void transactionsOnMethod() { }

        @RefreshDatabase
        @DatabaseMigrations
        void twoOnMethod() { }
    }

    @RefreshDatabase
    static class RefreshOnClass {
        void inherited() { }

        @DatabaseTransactions
        void overridden() { }
    }

    static class SubclassOfRefresh extends RefreshOnClass {
    }

    @DatabaseMigrations
    static class Outer {
        static class Inner {
            void inInner() { }
        }
    }

    @RefreshDatabase
    @DatabaseTransactions
    static class TwoOnClass {
    }

    // ---------- 测试 ----------

    @Test
    @DisplayName("什么都不标就是事务回滚 —— 默认策略")
    void defaultsToTransactions() {
        assertThat(resolve(Plain.class, "plainMethod")).isEqualTo(DatabaseStrategy.TRANSACTIONS);
        assertThat(DatabaseStrategy.of(null, Plain.class)).isEqualTo(DatabaseStrategy.TRANSACTIONS);
    }

    @Test
    @DisplayName("方法上的注解生效")
    void readsMethodAnnotation() {
        assertThat(resolve(Plain.class, "refreshOnMethod")).isEqualTo(DatabaseStrategy.REFRESH);
    }

    @Test
    @DisplayName("类上的注解对类里所有方法生效")
    void readsClassAnnotation() {
        assertThat(resolve(RefreshOnClass.class, "inherited")).isEqualTo(DatabaseStrategy.REFRESH);
    }

    @Test
    @DisplayName("★ 方法压过类 —— 否则「整类 fresh,只有这个不用」只能另开一个类")
    void methodBeatsClass() {
        assertThat(resolve(RefreshOnClass.class, "overridden"))
                .isEqualTo(DatabaseStrategy.TRANSACTIONS);
    }

    @Test
    @DisplayName("超类上的注解算 —— 项目里常有一个自己的测试基类")
    void readsSuperclassAnnotation() {
        assertThat(DatabaseStrategy.of(null, SubclassOfRefresh.class))
                .isEqualTo(DatabaseStrategy.REFRESH);
    }

    @Test
    @DisplayName("★ @Nested 里也算外层类的注解 —— @Inherited 管不了内部类")
    void readsEnclosingClassAnnotation() {
        assertThat(resolve(Outer.Inner.class, "inInner"))
                .isEqualTo(DatabaseStrategy.MIGRATIONS);
    }

    @Nested
    @DisplayName("同一层标两个策略")
    class Conflicts {

        @Test
        @DisplayName("方法上两个 → 报错,并说清是哪两个")
        void twoOnMethod() {
            assertThatThrownBy(() -> resolve(Plain.class, "twoOnMethod"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@RefreshDatabase")
                    .hasMessageContaining("@DatabaseMigrations")
                    .hasMessageContaining("twoOnMethod");
        }

        @Test
        @DisplayName("类上两个 → 报错(不让其中一个静默胜出)")
        void twoOnClass() {
            assertThatThrownBy(() -> DatabaseStrategy.of(null, TwoOnClass.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TwoOnClass");
        }
    }

    @Test
    @DisplayName("只有事务策略需要回滚包装")
    void onlyTransactionsRollsBack() {
        assertThat(DatabaseStrategy.TRANSACTIONS.rollsBack()).isTrue();
        assertThat(DatabaseStrategy.REFRESH.rollsBack()).isFalse();
        assertThat(DatabaseStrategy.MIGRATIONS.rollsBack()).isFalse();
    }

    private DatabaseStrategy resolve(Class<?> type, String methodName) {
        return DatabaseStrategy.of(methodOf(type, methodName), type);
    }

    private Method methodOf(Class<?> type, String name) {
        try {
            return type.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("样本类里没有这个方法:" + name, e);
        }
    }
}
