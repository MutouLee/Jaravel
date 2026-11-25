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

/**
 * {@code AppTest} 的验收:容器 + 数据库,没有 HTTP(docs/14 §5)。
 *
 * <h2>★ 为什么这条测试必须在示例里,而不是在 jaravel-testing 里</h2>
 * <p>{@code AppTest} 要验证的事情是「真的能启动一个应用上下文,真的
 * 连得上库,事务回滚真的生效」。这三件事在 jaravel-testing 自己的
 * 测试里做不到:那个模块没有应用、没有迁移、没有数据源 —— 只能测到
 * 注解解析那一层(见 {@code DatabaseStrategyTest})。
 *
 * <p>整个 v0.3 的六个「单元测试全绿、真实运行完全不工作」缺陷都是这个
 * 形状,所以基类的接线必须在一个真示例里被跑过。
 *
 * <h2>方法顺序是这条测试的一部分</h2>
 * <p>「回滚生效」没法在一个测试方法里断言 —— 要断言的恰好是
 * **测试结束之后**的状态。所以用 {@code @Order} 固定两个方法的顺序:
 * 前一个写数据,后一个断言它不见了。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppTestIsolationTest extends AppTest {

    private static final String MARKER = "AppTest 的回滚样本";

    @Test
    @Order(1)
    @DisplayName("容器起得来,Eloquent 写得进去 —— 不经过任何 HTTP")
    void writesThroughEloquent() {
        new Todo(MARKER).save();

        assertDatabaseHas("todos", Map.of("title", MARKER));
        assertThat(Todo.query().whereTitle(MARKER).count())
                .as("模型层读回来的也是同一条")
                .isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("★ 上一个测试写的数据被回滚了 —— 默认策略是事务")
    void previousTestWasRolledBack() {
        assertDatabaseMissing("todos", Map.of("title", MARKER));
    }

    @Test
    @DisplayName("N+1 断言在 AppTest 里也有 —— 它与 HTTP 无关")
    void countsQueries() {
        new Todo("数 SQL").save();

        assertQueryCount(1, () -> Todo.query().get());
    }

    @Test
    @DisplayName("★ 断言用的连接与模型是同一条 —— 否则断言看的是另一个库")
    void sharesTheApplicationConnection() {
        new Todo("同一个库").save();

        var rows = testConnection().select("select title from todos where title = ?", "同一个库");

        assertThat(rows).as("裸 SQL 看得到模型刚写的那条").hasSize(1);
        assertDatabaseHas("todos", Map.of("title", "同一个库"));
    }
}
