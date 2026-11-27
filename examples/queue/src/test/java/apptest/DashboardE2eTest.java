package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.testing.FeatureTest;

/**
 * 队列面板的端到端(docs/10 §1.8)。
 *
 * <p>它必须走真实 HTTP 管道:面板的 HTML 是 jblade 模板(要会话给
 * {@code @csrf} 提供 token),API 是 JSON,而两者都经过 {@code web}
 * 中间件。单元测试测不到「模板里的类型引用能不能编译」与
 * 「路由有没有被注册」这两件事 —— 而它们正是这种新功能最常坏的地方。
 */
class DashboardE2eTest extends FeatureTest {

    @Test
    @DisplayName("★ HTML 面板渲染得出来(含模板类型引用与会话)")
    void dashboardPageRenders() {
        var response = get("/_jaravel/dashboard").assertOk();

        assertThat(response.content())
                .contains("队列面板")
                .contains("失败任务")
                .contains("批次");
    }

    @Test
    @DisplayName("JSON 概览:queues / failedTotal / batches 都有")
    void overviewApi() {
        var response = get("/_jaravel/dashboard/api/overview").assertOk();

        assertThat(response.content())
                .contains("\"queues\"")
                .contains("\"failedTotal\"")
                .contains("\"batches\"");
    }

    @Test
    @DisplayName("★ 面板默认只在 debug/local 下开")
    void disabledOutsideDebug() {
        // 测试上下文里 app.debug = true(见 config/app.yml),所以这里
        // 断言的是「开着的时候它确实在」;关闭路径由 starter 的单测覆盖。
        assertThat(get("/_jaravel/dashboard").status()).isEqualTo(200);
    }
}
