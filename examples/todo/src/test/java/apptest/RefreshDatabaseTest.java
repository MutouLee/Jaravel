package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import app.models.Todo;
import io.jaravel.testing.AppTest;
import io.jaravel.testing.RefreshDatabase;

/**
 * {@code @RefreshDatabase} 的验收:每个测试前把结构整个重建(docs/14 §2.1)。
 *
 * <h2>它要同时钉住两件事</h2>
 * <ol>
 *   <li><b>写入是真的</b> —— 这个策略刻意不在回滚事务里,
 *       因为迁移测试要断言「表真的建出来了」,而在一个永不提交的事务里
 *       断言这件事等于断言幻觉;</li>
 *   <li><b>上一个测试的数据不会漏过来</b> —— 清理靠的是下一个测试前
 *       那次 reset + migrate,而不是事务。</li>
 * </ol>
 *
 * <p>两件事互为对照:只测第一件,一个「什么都没迁移」的实现也会绿;
 * 只测第二件,一个「把测试包进事务」的实现也会绿。
 *
 * <p>⚠ 这个类会把本模块的测试库整个重建。它能安全地这么做,是因为
 * surefire 在一个模块里顺序跑测试类,而示例里真正并行的那些
 * ({@code ParallelDatabaseTest} / Testcontainers 两条)各自用的是
 * 另一个库。
 */
@RefreshDatabase
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RefreshDatabaseTest extends AppTest {

    private static final String MARKER = "fresh 之后应当消失";

    @Test
    @Order(1)
    @DisplayName("结构重建完就能用,而且写入是真提交的")
    void writesAreCommitted() {
        new Todo(MARKER).save();

        assertDatabaseHas("todos", Map.of("title", MARKER));
    }

    @Test
    @Order(2)
    @DisplayName("★ 上一个测试的数据没了 —— 这一次 fresh 把表重建过")
    void previousDataIsGone() {
        assertDatabaseMissing("todos", Map.of("title", MARKER));

        assertThat(Todo.query().count())
                .as("重建之后是一张空表(这个示例没有种子数据)")
                .isZero();
    }
}
