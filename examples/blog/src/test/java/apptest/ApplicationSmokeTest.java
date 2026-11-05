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

    @Test
    @DisplayName("★ 分页信封完整:prev/next 即便是 null 也要有那个键")
    void paginationEnvelopeIsComplete() throws Exception {
        var response = get("/api/posts/paged?page=1").assertOk();

        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.content());

        assertThat(json.has("data")).isTrue();
        assertThat(json.get("links").has("prev"))
                .as("""
                        第一页的 prev 是 null —— 而这个应用的 ObjectMapper 配了
                        「不输出 null 字段」(API 响应的常见设置)。
                        第一版的序列化器直接 writeObject(map),于是那个键**整个消失**。
                        前端分页组件靠 links.prev === null 判断能不能往前翻,
                        键不存在时拿到 undefined —— 在 JS 里也是假值,
                        所以多数组件还能用:这正是最糟的那种 bug,
                        它不报错,只在某些组件上表现为按钮状态不对。""")
                .isTrue();
        assertThat(json.get("links").get("prev").isNull()).isTrue();
        assertThat(json.get("links").get("next").asText()).contains("page=2");

        var meta = json.get("meta");
        assertThat(meta.get("current_page").asInt()).isEqualTo(1);
        assertThat(meta.get("per_page").asInt()).isEqualTo(5);
        assertThat(meta.get("path").asText())
                .as("path 要是真的请求 URL —— resolver 装上了才有")
                .contains("/api/posts/paged");
    }

    @Test
    @DisplayName("★ ?page=2 真的翻页 —— 页码由 starter 装的 resolver 读")
    void pageParameterIsHonoured() throws Exception {
        var first = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(get("/api/posts/paged?page=1").assertOk().content());
        var second = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(get("/api/posts/paged?page=2").assertOk().content());

        assertThat(second.get("meta").get("current_page").asInt()).isEqualTo(2);
        assertThat(second.get("links").get("next").isNull())
                .as("第二页是最后一页(共 7 条、每页 5 条)")
                .isTrue();
        assertThat(first.get("data").get(0).get("id").asInt())
                .as("两页的内容不该一样 —— 一样就说明 offset 没生效")
                .isNotEqualTo(second.get("data").get(0).get("id").asInt());
    }
}
