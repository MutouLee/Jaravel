package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.dobeen.jaravel.testing.FeatureTest;

/**
 * {@code @csrf} / {@code @error} / {@code @old} 的端到端验收(docs/04 §1.8)。
 *
 * <h2>★ 为什么必须在一个真应用里测</h2>
 * <p>这三个指令的数据不来自模板参数,而来自**会话**:令牌、以及上一次
 * 请求校验失败时闪存进去的 errors 与 old。中间串着四层:
 * 校验失败 → 异常处理器闪存 → StartSession 写回 → 下一次请求读出来 →
 * starter 的 {@code SessionViewRequestState} 喂给指令。
 *
 * <p>转写器那边的单元测试({@code JbladeTest.FrameworkDirectives})装的是
 * 一个假数据源 —— 它证明不了上面任何一层接对了。而这一类
 * 「每个零件都对、接起来不工作」的缺陷正是这个项目最常见的一种。
 */
class FormDirectivesTest extends FeatureTest {

    @Test
    @DisplayName("★ @csrf 渲染出的令牌能让表单真的提交过去")
    void csrfFieldIsAcceptedByTheCsrfMiddleware() {
        String page = get("/posts/create").assertOk().content();

        assertThat(page)
                .as("指令输出的是整个 input,name 必须是框架校验的 _token")
                .contains("name=\"_token\"");

        String token = page.replaceAll("(?s).*name=\"_token\" value=\"([^\"]+)\".*", "$1");
        assertThat(token).isNotBlank().doesNotContain("<input");
    }

    @Test
    @DisplayName("★ 校验失败回跳后:@error 显示消息,@old 回填刚填的值")
    void errorsAndOldInputSurviveTheRedirect() {
        // 正文太短(min:10)+ 没有作者 —— 两个字段都挂
        var failed = from("/posts/create").post("/posts", Map.of(
                "title", "我的标题",
                "body", "太短"));

        failed.assertRedirect("/posts/create").assertSessionHasErrors("body");

        // 把回跳带着的会话原样带进下一个请求 —— 浏览器就是这么做的
        var session = failed.session().orElseThrow();
        String page = withSession(Map.of(
                        "errors", session.get("errors", Object.class).orElseThrow(),
                        // 旧输入的键是 _old_input(SessionData.flashInput 写的那个)
                        "_old_input", session.get("_old_input", Object.class).orElseThrow()))
                .get("/posts/create")
                .assertOk()
                .content();

        assertThat(page)
                .as("@error('body') 要显示那条消息,字段名在模板里只写了一次")
                .contains("class=\"error\"")
                .contains("正文");
        assertThat(page)
                .as("@old('title') 回填用户刚填的标题 —— 否则他要重填整张表单")
                .contains("value=\"我的标题\"");
    }

    @Test
    @DisplayName("没有错误时 @error 不输出,@old 为空")
    void cleanFormHasNoErrorMarkup() {
        String page = get("/posts/create").assertOk().content();

        assertThat(page).doesNotContain("class=\"error\"");
        assertThat(page)
                .as("第一次进入表单时 @old 是空串,不是 null 字样")
                .contains("value=\"\"")
                .doesNotContain("value=\"null\"");
    }
}
