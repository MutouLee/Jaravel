package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.jaravel.database.Connection;
import io.jaravel.eloquent.Eloquent;
import io.jaravel.testing.BrowserTest;

/**
 * Todo 示例的真实浏览器测试(docs/14 §6.1)。
 *
 * <p>与 {@code FeatureTest} 的区别:这里真的开了 Chromium、点了按钮、
 * 跟着重定向走、执行了页面 JS。它覆盖的正是 MockServlet 看不到的那一层。
 *
 * <p>默认关闭(需要浏览器二进制);用
 * {@code -Djaravel.browser.enabled=true} 打开。
 *
 * <p>没有写 {@code @SpringBootTest} —— 基类默认就用
 * {@code JaravelTestApplication} 起一个 RANDOM_PORT 的真实服务。
 *
 * <p>用示例自己的文件库(它已经有表),测试前后清空 todos —— 不改变
 * 示例的配置,也不依赖测试里再跑一遍迁移。
 */
class TodoBrowserTest extends BrowserTest {

    @Autowired Connection db;

    @BeforeEach
    @AfterEach
    void clearTodos() {
        Eloquent.useConnection(db);
        db.table("todos").allowUnconditional().delete();
    }

    @Test
    @DisplayName("★ 浏览器里完成:添加 → 完成 → 删除")
    void addCompleteDelete() {
        browse(b -> b.visit("/")
                .assertSee("Todo")
                .assertSee("还没有任何待办")
                .type("title", "买牛奶")
                .press("添加")
                .assertPathIs("/")
                .assertSee("买牛奶")
                .assertSee("1 项待办")
                .press("完成")
                .assertSee("已完成")
                .press("删除")
                .assertDontSee("买牛奶")
                .assertSee("还没有任何待办"));
    }

    @Test
    @DisplayName("★ 页面脚本真的执行了 —— 输入框拿到焦点")
    void clientScriptRuns() {
        browse(b -> b.visit("/"));

        Object focused = browser().page().evaluate("() => document.activeElement?.name");
        assertThat(focused)
                .as("index 模板 @push 的脚本把焦点还给了输入框")
                .isEqualTo("title");
    }
}
