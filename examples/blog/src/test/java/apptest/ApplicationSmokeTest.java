package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jaravel.testing.FeatureTest;

/**
 * 这个示例**能不能起来**。
 *
 * <h2>★ 为什么补这条测试</h2>
 * <p>因为它没有任何测试的那段时间里,它坏了,而且没人知道。
 *
 * <p>具体是这样:auth 模块(v0.3)把 {@code app.key} 做成了
 * **启动即校验**的饿加载 bean —— 那个设计是对的(缺密钥的问题应当在启动时
 * 暴露,而不是等到第一个加密 cookie 出现)。但这个示例写在 v0.2,
 * 配置里没有那一行,于是从那以后它**起不到端口**,报错是「app.key 未设置」。
 *
 * <p>auth 与 queue 两个示例没出这个问题 —— 它们各自带着真实的验收测试。
 * 区别只在这里。
 *
 * <p>所以这条测试要钉住的不是某个功能,而是**最低限度的事实**:
 * 真的 Spring 上下文能装配起来,真的路由能回一个响应。
 * 这两件事一错,这个示例对读者就是误导。
 */
class ApplicationSmokeTest extends FeatureTest {

    @Test
    @DisplayName("★ 容器装配得起来,首页回 200 —— 一个起不来的示例是误导")
    void theApplicationBoots() {
        var response = get("/").assertOk();

        assertThat(response.content())
                .as("首页是文章列表(jblade 渲染出来的 HTML)")
                .isNotBlank();
    }

    @Test
    @DisplayName("JSON 接口也通 —— 它与页面走的是不同的响应路径")
    void theApiResponds() {
        get("/api/posts").assertOk();
    }
}
