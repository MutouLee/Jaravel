package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import app.models.Note;
import app.models.User;
import io.jaravel.auth.hash.Hash;
import io.jaravel.core.Application;
import io.jaravel.testing.FeatureTest;

/**
 * {@code @auth} / {@code @guest} / {@code @can} / {@code @env} 的端到端验收
 * (docs/04 §1.8)。
 *
 * <h2>★ 为什么必须在这个示例里测</h2>
 * <p>转写器那边的单元测试({@code JbladeTest.ConditionalDirectives})装的是
 * 一个假数据源 —— 它证明不了 starter 把指令接到了**真的** Auth 与 Gate 上。
 * 而这里有真的 Policy({@code NotePolicy}:只能改自己的笔记、新建要求邮箱
 * 已验证),于是模板里的 {@code @can('update', note)} 走的是与
 * {@code middleware("can:update,note")} 和 {@code Gate.authorize(...)}
 * 完全相同的那条路径。
 *
 * <p>页面是 {@code GET /notes/page},它刻意列出**所有人**的笔记 ——
 * 看点就在于「编辑」只出现在自己的那几条上。
 */
class ConditionalDirectivesTest extends FeatureTest {

    private User user(String email, boolean verified) {
        User u = new User();
        u.name = "测试用户";
        u.email = email;
        u.password = Hash.make("correct-horse");
        if (verified) u.emailVerifiedAt = java.time.Instant.now();
        u.save();
        return u;
    }

    private Note note(User owner, String title) {
        Note note = new Note();
        note.userId = owner.id;
        note.title = title;
        note.body = "正文";
        note.save();
        return note;
    }

    @Test
    @DisplayName("★ 游客看到 @guest 那一支,且一个编辑链接都没有")
    void guestSeesGuestBranch() {
        User someone = user("owner@example.com", true);
        note(someone, "别人的笔记");

        String page = get("/notes/page").assertOk().content();

        assertThat(page).contains("游客").doesNotContain("已登录");
        assertThat(page)
                .as("@can 在没有登录用户时必须拒绝 —— 默认值只能是拒绝")
                .doesNotContain("class=\"edit\"")
                .doesNotContain("可以新建笔记");
        assertThat(page).contains("别人的笔记");
    }

    @Test
    @DisplayName("★ @can('update', note) 按对象判:只有自己的笔记有编辑链接")
    void canIsEvaluatedPerObject() {
        User me = user("me@example.com", true);
        User other = user("other@example.com", true);
        Note mine = note(me, "我的笔记");
        note(other, "别人的笔记");

        String page = actingAs(me).get("/notes/page").assertOk().content();

        assertThat(page).contains("已登录").doesNotContain("游客");
        assertThat(page)
                .as("自己的那条有编辑链接")
                .contains("href=\"/notes/" + mine.id + "/edit\"");
        assertThat(page)
                .as("只能有一个编辑链接 —— 两条笔记里只有一条是我的")
                .containsOnlyOnce("class=\"edit\"");
        assertThat(page)
                .as("别人的那条连 @elsecan('view') 也不显示:NotePolicy 的 view "
                  + "同样要求 owns。两支都不出现正是「按对象判」的证据 —— "
                  + "只看能力名的实现会让两条笔记长得一样")
                .doesNotContain("class=\"readonly\"");
        assertThat(page)
                .as("别人的那条有「不能删」—— @cannot 的那一支")
                .contains("class=\"locked\"");
    }

    @Test
    @DisplayName("★ @can('create', Note.class):邮箱未验证的人看不到新建")
    void createAbilityFollowsThePolicy() {
        User verified = user("v@example.com", true);
        User pending = user("p@example.com", false);

        assertThat(actingAs(verified).get("/notes/page").assertOk().content())
                .contains("可以新建笔记")
                .doesNotContain("先验证邮箱");

        assertThat(actingAs(pending).get("/notes/page").assertOk().content())
                .as("NotePolicy.create 要求 hasVerifiedEmail —— 模板与接口同一条规则")
                .contains("先验证邮箱")
                .doesNotContain("可以新建笔记");
    }

    @Test
    @DisplayName("@env / @production 跟着 Application.environment()")
    void envDirectivesFollowTheEnvironment() {
        String before = Application.environment();
        try {
            Application.setEnvironment("local");
            assertThat(get("/notes/page").assertOk().content())
                    .contains("id=\"env-hint\"")
                    .doesNotContain("id=\"prod-hint\"");

            Application.setEnvironment("production");
            assertThat(get("/notes/page").assertOk().content())
                    .contains("id=\"prod-hint\"")
                    .doesNotContain("id=\"env-hint\"");
        } finally {
            Application.setEnvironment(before);
        }
    }

    @Test
    @DisplayName("登录用户的登出表单里有 @csrf 输出的令牌")
    void authBranchCarriesTheCsrfField() {
        User me = user("me@example.com", true);

        assertThat(actingAs(me).get("/notes/page").assertOk().content())
                .contains("name=\"_token\"");
    }

    @Test
    @DisplayName("★ @lang / @choice 走真的消息目录(resources/lang/)")
    void langAndChoiceReadTheCatalog() {
        User me = user("me@example.com", true);

        assertThat(get("/notes/page").assertOk().content())
                .as("一条笔记都没有时取复数的第一段")
                .contains("我的笔记")
                .contains("还没有笔记");

        note(me, "第一条");
        assertThat(get("/notes/page").assertOk().content())
                .as("一条 → 第二段")
                .contains("一条笔记");

        note(me, "第二条");
        assertThat(get("/notes/page").assertOk().content())
                .as("两条 → 第三段,:count 被替换")
                .contains("2 条笔记");
    }

    @Test
    @DisplayName("★ 切 locale 之后同一个页面换语言")
    void switchingLocaleSwitchesTheMessages() {
        User me = user("me@example.com", true);
        note(me, "第一条");

        io.jaravel.core.lang.Lang.withLocale("en", () ->
                assertThat(get("/notes/page").assertOk().content())
                        .as("en 目录里有同样的键 —— 页面与模板都不用改")
                        .contains("My notes")
                        .contains("One note")
                        .doesNotContain("我的笔记"));

        assertThat(io.jaravel.core.lang.Lang.locale())
                .as("withLocale 结束后还原")
                .isEqualTo("zh_CN");
    }
}
