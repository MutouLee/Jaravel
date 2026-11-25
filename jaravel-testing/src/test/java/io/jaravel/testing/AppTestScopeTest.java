package io.jaravel.testing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code AppTest.actingAs} 在作用域之外的行为(docs/14 §5)。
 *
 * <p>这条测试只能写在这里:它需要一个**没有执行作用域**的 JVM。
 * 真正继承 {@code AppTest} 的测试方法永远跑在 {@code ScopeExtension}
 * 建立的作用域里,从里面构造不出「作用域不存在」。
 *
 * <p>钉住的是一条错误消息。值得为一条消息写测试,是因为它替代的
 * 默认行为是**静默无效**:在 {@code @BeforeEach} 里 actingAs 会设进一个
 * 随即丢弃的 guard,测试体里 {@code Auth.user()} 读到空,看起来像认证坏了。
 */
class AppTestScopeTest {

    /** 一个不经 JUnit 运行的 AppTest 实例 —— 只用来调那个方法 */
    private static final class Standalone extends AppTest {
    }

    @Test
    @DisplayName("★ 作用域之外调 actingAs 直接报错,并说清该写在哪")
    void actingAsOutsideAScopeFailsLoudly() {
        assertThatThrownBy(() -> new Standalone().actingAs(new Object(), "web"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@BeforeEach")
                .hasMessageContaining("测试方法")
                .hasMessageContaining("造数据");
    }

    @Test
    @DisplayName("没有连接时,数据库断言报的是「去配 datasource」而不是 NPE")
    void databaseAssertionsWithoutAConnectionExplainThemselves() {
        assertThatThrownBy(() -> new Standalone().assertDatabaseCount("todos", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url");
    }
}
