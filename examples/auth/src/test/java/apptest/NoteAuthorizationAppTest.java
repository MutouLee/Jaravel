package apptest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import app.models.Note;
import app.models.User;
import net.dobeen.jaravel.auth.Auth;
import net.dobeen.jaravel.auth.access.Gate;
import net.dobeen.jaravel.auth.hash.Hash;
import net.dobeen.jaravel.testing.AppTest;

/**
 * 授权规则的测试,**不经过 HTTP**(docs/14 §5)。
 *
 * <h2>★ 这个文件是 AppTest 存在理由的证据</h2>
 * <p>同样的规则在 {@code AuthFeatureTest} 里是这样测的:登录 → 请求
 * {@code /notes/{id}/edit} → 断言 403。那条测试是对的,它覆盖的是
 * 「路由上的 {@code can:} 中间件接对了吗」。
 *
 * <p>但 Policy 本身是一段**不需要请求**的逻辑。用请求去测它,一次失败
 * 有四种可能的原因(路由、中间件、认证、Policy),而这里只有一种。
 *
 * <h2>它依赖执行作用域</h2>
 * <p>{@code Gate} 从当前 guard 取用户,而 guard 活在
 * {@code ExecutionScope} 里。没有作用域时每次 {@code Auth.guard()}
 * 都是一个新实例,{@code actingAs} 设的用户下一行就读不到了 ——
 * 这正是 {@code ScopeExtension} 要解决的事,所以这里顺带把它钉住。
 */
class NoteAuthorizationAppTest extends AppTest {

    @Test
    @DisplayName("actingAs 之后 Auth.user() 读得到 —— 作用域里的 guard 是同一个")
    void actingAsEstablishesTheCurrentUser() {
        User user = createUser("owner@example.com");

        actingAs(user);

        assertAuthenticated();
        assertThat(Auth.id()).contains(user.id);
        assertThat(Auth.user(User.class).map(u -> u.email)).contains("owner@example.com");
    }

    @Test
    @DisplayName("没调 actingAs 就是游客 —— 默认状态不需要「撤销登录」")
    void defaultsToGuest() {
        assertGuest();
    }

    @Test
    @DisplayName("Policy:自己的笔记能改")
    void ownerMayUpdateOwnNote() {
        User owner = createUser("owner2@example.com");
        Note note = createNote(owner, "我的笔记");

        actingAs(owner);

        assertThat(Gate.allows("update", note)).isTrue();
    }

    @Test
    @DisplayName("★ Policy:别人的笔记不能改 —— 一次失败只有一种原因")
    void strangerMayNotUpdateSomeoneElsesNote() {
        User owner = createUser("owner3@example.com");
        User stranger = createUser("stranger@example.com");
        Note note = createNote(owner, "别人的笔记");

        actingAs(stranger);

        assertThat(Gate.denies("update", note)).isTrue();
    }

    @Test
    @DisplayName("Policy 的不带模型分支:未验证邮箱不能新建")
    void unverifiedUserMayNotCreate() {
        User unverified = createUser("unverified@example.com");
        unverified.emailVerifiedAt = null;
        unverified.save();

        actingAs(unverified);

        assertThat(Gate.allows("create", Note.class)).isFalse();
    }

    @Test
    @DisplayName("数据库断言也在 —— AppTest 与 FeatureTest 用的是同一份")
    void databaseAssertionsAreAvailable() {
        User owner = createUser("db@example.com");
        createNote(owner, "落库了吗");

        assertDatabaseHas("notes", java.util.Map.of("title", "落库了吗"));
        assertDatabaseCount("notes", 1);
    }

    /*
     * 「在 @BeforeEach 里 actingAs 要报错」那条不在这里:测试方法本身就在
     * 作用域里,从里面构造不出「没有作用域」的状态。它在 jaravel-testing 的
     * AppTestScopeTest 里 —— 那个 JVM 没有任何作用域。
     */

    private User createUser(String email) {
        User user = new User();
        user.name = "测试用户";
        user.email = email;
        user.password = Hash.make("secret123");
        user.emailVerifiedAt = java.time.Instant.now();
        user.save();
        return user;
    }

    private Note createNote(User owner, String title) {
        Note note = new Note();
        note.userId = owner.id;
        note.title = title;
        note.body = "正文";
        note.save();
        return note;
    }
}
