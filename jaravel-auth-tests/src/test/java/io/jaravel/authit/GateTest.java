package io.jaravel.authit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.jaravel.auth.Auth;
import io.jaravel.auth.access.AuthorizationException;
import io.jaravel.auth.access.Decision;
import io.jaravel.auth.access.Gate;
import io.jaravel.auth.access.GateRegistry;
import io.jaravel.authit.models.Article;
import io.jaravel.authit.models.User;

/** Gate 与 Policy */
class GateTest extends AuthTestBase {

    @BeforeEach
    void freshGate() {
        Gate.use(new GateRegistry());
    }

    // ================= Gate =================

    @Nested
    @DisplayName("Gate 规则")
    class GateRules {

        @Test
        @DisplayName("无参规则")
        void simpleAbility() {
            Gate.<User>define("view-admin", u -> "admin@example.com".equals(u.email));

            var admin = createUser("admin@example.com", "x");
            var normal = createUser("user@example.com", "x");

            assertThat(Gate.forUser(admin).allows("view-admin")).isTrue();
            assertThat(Gate.forUser(normal).allows("view-admin")).isFalse();
            assertThat(Gate.forUser(normal).denies("view-admin")).isTrue();
        }

        @Test
        @DisplayName("带模型参数的规则")
        void abilityWithModel() {
            Gate.<User, Article>define("update-article", (u, a) -> u.id.equals(a.authorId));

            var owner = createUser("owner@example.com", "x");
            var other = createUser("other@example.com", "x");
            var article = createArticle(owner, "我的文章");

            assertThat(Gate.forUser(owner).allows("update-article", article)).isTrue();
            assertThat(Gate.forUser(other).allows("update-article", article)).isFalse();
        }

        @Test
        @DisplayName("★ 未定义的权限拒绝,并说清是「没定义」而不是「规则不通过」")
        void undefinedAbilityIsDeniedAndExplained() {
            Gate.<User>define("view-admin", u -> true);
            var user = createUser("a@example.com", "x");

            var decision = Gate.forUser(user).inspect("view-admln");

            assertThat(decision.denied()).isTrue();
            assertThat(decision.reason()).get().asString()
                    .contains("没有定义")
                    .contains("是否想写 \"view-admin\"")
                    .contains("最小权限原则");
        }

        @Test
        @DisplayName("Decision 可带原因与状态码")
        void decisionWithReasonAndStatus() {
            Gate.<User, Article>define("publish", (u, a) -> a.body != null && !a.body.isBlank()
                    ? Decision.allow()
                    : Decision.deny("文章正文为空,无法发布", 422));

            var user = createUser("a@example.com", "x");
            var empty = createArticle(user, "标题");
            empty.body = "";
            empty.save();

            var decision = Gate.forUser(user).inspect("publish", empty);
            assertThat(decision.denied()).isTrue();
            assertThat(decision.status()).isEqualTo(422);
            assertThat(decision.reason()).contains("文章正文为空,无法发布");
        }

        @Test
        @DisplayName("authorize 失败时抛的异常带上原因与状态码")
        void authorizeThrowsWithDetails() {
            Gate.<User, Article>define("publish", (u, a) -> Decision.deny("未完成", 422));

            var user = createUser("a@example.com", "x");
            var article = createArticle(user, "标题");

            assertThatThrownBy(() -> Gate.forUser(user).authorize("publish", article))
                    .isInstanceOf(AuthorizationException.class)
                    .hasMessageContaining("未完成")
                    .satisfies(e -> assertThat(
                            ((AuthorizationException) e).status()).isEqualTo(422));
        }

        @Test
        @DisplayName("没给原因时,报错至少说出是哪个权限")
        void authorizeWithoutReasonNamesTheAbility() {
            Gate.<User>define("manage-billing", u -> false);
            var user = createUser("a@example.com", "x");

            assertThatThrownBy(() -> Gate.forUser(user).authorize("manage-billing"))
                    .hasMessageContaining("manage-billing");
        }

        @Test
        @DisplayName("any / none")
        void anyAndNone() {
            Gate.<User>define("a", u -> true);
            Gate.<User>define("b", u -> false);
            var user = createUser("a@example.com", "x");

            inRequest(newSession(), () -> {
                Auth.login(auth.provider("users").retrieveById(user.id).orElseThrow());
                assertThat(Gate.any(List.of("a", "b"))).isTrue();
                assertThat(Gate.none(List.of("b"))).isTrue();
                assertThat(Gate.none(List.of("a", "b"))).isFalse();
            });
        }

        @Test
        @DisplayName("before 钩子短路 —— 超级管理员一刀切")
        void beforeHookShortCircuits() {
            Gate.<User, Article>define("update-article", (u, a) -> u.id.equals(a.authorId));
            Gate.before((user, ability) -> user.as(User.class)
                    .filter(u -> "root@example.com".equals(u.email))
                    .map(u -> true));

            var root = createUser("root@example.com", "x");
            var owner = createUser("owner@example.com", "x");
            var article = createArticle(owner, "别人的文章");

            assertThat(Gate.forUser(root).allows("update-article", article))
                    .as("root 应当通过所有权限").isTrue();
        }

        @Test
        @DisplayName("before 返回空时不干预,规则照常生效")
        void beforeHookCanAbstain() {
            Gate.<User>define("x", u -> false);
            Gate.before((user, ability) -> Optional.empty());

            var user = createUser("a@example.com", "x");
            assertThat(Gate.forUser(user).allows("x")).isFalse();
        }

        @Test
        @DisplayName("after 钩子能观察到每次判定 —— 审计日志用")
        void afterHookObserves() {
            Gate.<User>define("x", u -> true);
            var observed = new java.util.ArrayList<String>();
            Gate.after((user, ability, decision, args) ->
                    observed.add(ability + "=" + decision.allowed()));

            var user = createUser("a@example.com", "x");
            Gate.forUser(user).allows("x");

            assertThat(observed).containsExactly("x=true");
        }

        @Test
        @DisplayName("规则返回非 boolean/Decision 时报错并给出正确写法")
        void badRuleReturnType() {
            Gate.registry().define("weird", (user, args) -> Gate.toDecisionForTest("字符串"));

            var user = createUser("a@example.com", "x");
            assertThatThrownBy(() -> Gate.forUser(user).allows("weird"))
                    .hasMessageContaining("只接受 boolean 或 Decision");
        }

        @Test
        @DisplayName("★ 规则里抛异常时,报错指出是规则本身的问题")
        void ruleThrowingIsDiagnosable() {
            // 典型写法错误:没判空就访问关联
            Gate.<User, Article>define("broken", (u, a) -> u.email.length() > 0
                    && a.nonExistent().isEmpty());

            var user = createUser("a@example.com", "x");
            var article = createArticle(user, "标题");

            assertThatThrownBy(() -> Gate.forUser(user).allows("broken", article))
                    .hasMessageContaining("规则本身抛出了")
                    .hasMessageContaining("不该抛异常")
                    .hasMessageContaining("Decision.deny");
        }

        @Test
        @DisplayName("带参数的规则在没给参数时报错并给出调用写法")
        void missingArgumentIsActionable() {
            Gate.<User, Article>define("update-article", (u, a) -> true);
            var user = createUser("a@example.com", "x");

            assertThatThrownBy(() -> Gate.forUser(user).allows("update-article"))
                    .hasMessageContaining("需要一个参数")
                    .hasMessageContaining("Gate.allows(\"update-article\", post)");
        }
    }

    // ================= Policy =================

    @Nested
    @DisplayName("Policy")
    class Policies {

        @Test
        @DisplayName("按模型类型找到 policy 并调用同名方法")
        void dispatchesToPolicy() {
            Gate.policy(Article.class, new ArticlePolicy());

            var owner = createUser("owner@example.com", "x");
            var other = createUser("other@example.com", "x");
            var article = createArticle(owner, "文章");

            assertThat(Gate.forUser(owner).allows("update", article)).isTrue();
            assertThat(Gate.forUser(other).allows("update", article)).isFalse();
            assertThat(Gate.forUser(other).allows("view", article)).isTrue();
        }

        @Test
        @DisplayName("不带模型的操作:传 Class")
        void classLevelAbility() {
            Gate.policy(Article.class, new ArticlePolicy());
            var user = createUser("a@example.com", "x");

            assertThat(Gate.forUser(user).allows("viewAny", Article.class)).isTrue();
            assertThat(Gate.forUser(user).allows("create", Article.class)).isTrue();
        }

        @Test
        @DisplayName("ability 名字支持 Gate 风格(update-article → update)")
        void gateStyleAbilityNames() {
            Gate.policy(Article.class, new ArticlePolicy());
            var owner = createUser("owner@example.com", "x");
            var article = createArticle(owner, "文章");

            assertThat(Gate.forUser(owner).allows("update-article", article)).isTrue();
        }

        @Test
        @DisplayName("policy 的 before 钩子")
        void policyBefore() {
            Gate.policy(Article.class, new ArticlePolicy());

            var root = createUser("root@example.com", "x");
            var owner = createUser("owner@example.com", "x");
            var article = createArticle(owner, "别人的文章");

            assertThat(Gate.forUser(root).allows("update", article)).isTrue();
        }

        @Test
        @DisplayName("★ 未登录时需要用户的 policy 方法返回 401 而不是 NPE")
        void guestGetsUnauthorizedNotNpe() {
            Gate.policy(Article.class, new ArticlePolicy());
            var owner = createUser("owner@example.com", "x");
            var article = createArticle(owner, "文章");

            // 没有当前用户(命令行/队列上下文)
            var decision = Gate.inspect("update", article);

            assertThat(decision.denied()).isTrue();
            assertThat(decision.status()).isEqualTo(401);
            assertThat(decision.reason()).contains("需要登录");
        }

        @Test
        @DisplayName("★ policy 方法的第二个参数类型不对时,注册就失败")
        void policySignatureIsValidatedAtRegistration() {
            assertThatThrownBy(() -> Gate.policy(Article.class, new WrongTypePolicy()))
                    .hasMessageContaining("第二个参数应是 Article")
                    .hasMessageContaining("argument type mismatch");
        }

        @Test
        @DisplayName("policy 方法参数超过两个时,注册就失败")
        void tooManyParametersRejected() {
            assertThatThrownBy(() -> Gate.policy(Article.class, new TooManyParamsPolicy()))
                    .hasMessageContaining("最多两个");
        }

        @Test
        @DisplayName("policy 里没有对应方法时,报错列出它有哪些方法")
        void missingPolicyMethodIsExplained() {
            Gate.policy(Article.class, new ArticlePolicy());
            var user = createUser("a@example.com", "x");
            var article = createArticle(user, "文章");

            var decision = Gate.forUser(user).inspect("archive", article);

            assertThat(decision.denied()).isTrue();
            assertThat(decision.reason()).get().asString()
                    .contains("没有名为 \"archive\" 的方法")
                    .contains("update");
        }

        @Test
        @DisplayName("没注册 policy 的模型,报错提示怎么注册")
        void unregisteredPolicyIsExplained() {
            var user = createUser("a@example.com", "x");
            var article = createArticle(user, "文章");

            var decision = Gate.forUser(user).inspect("update", article);

            assertThat(decision.reason()).get().asString()
                    .contains("没有为 Article 注册 Policy")
                    .contains("Gate.policy(Article.class");
        }

        @Test
        @DisplayName("Gate 规则优先于 Policy —— 同名时 Gate 赢")
        void gateWinsOverPolicy() {
            Gate.policy(Article.class, new ArticlePolicy());
            Gate.<User, Article>define("update", (u, a) -> false);

            var owner = createUser("owner@example.com", "x");
            var article = createArticle(owner, "文章");

            // policy 会允许(是作者),但 Gate 规则拒绝
            assertThat(Gate.forUser(owner).allows("update", article)).isFalse();
        }
    }

    // ================= 与 Auth 联动 =================

    @Nested
    @DisplayName("与当前用户联动")
    class WithCurrentUser {

        @Test
        @DisplayName("Gate 自动用当前登录用户")
        void usesCurrentUser() {
            Gate.policy(Article.class, new ArticlePolicy());
            var owner = createUser("owner@example.com", "secret");
            var article = createArticle(owner, "文章");

            inRequest(newSession(), () -> {
                Auth.attempt(credentials("owner@example.com", "secret"));
                assertThat(Gate.allows("update", article)).isTrue();
                assertThat(Gate.denies("update", article)).isFalse();
            });
        }

        @Test
        @DisplayName("未登录时当作访客,而不是报错")
        void guestIsNotAnError() {
            Gate.<User>define("public-thing", u -> u != null);

            inRequest(newSession(), () ->
                    assertThat(Gate.allows("public-thing")).isFalse());
        }
    }

    // ================= 夹具 =================

    protected Article createArticle(User author, String title) {
        Article article = new Article();
        article.title = title;
        article.body = "正文";
        article.authorId = author.id;
        article.save();
        return article;
    }

    /** 正常的 policy */
    static class ArticlePolicy {
        public boolean viewAny(User user)               { return true; }
        public boolean view(User user, Article a)       { return true; }
        public boolean create(User user)               { return true; }
        public boolean update(User user, Article a)    { return user.id.equals(a.authorId); }
        public boolean delete(User user, Article a)    { return user.id.equals(a.authorId); }

        public Optional<Boolean> before(User user, String ability) {
            return "root@example.com".equals(user.email) ? Optional.of(true) : Optional.empty();
        }
    }

    /** 第二个参数类型错了 —— 注册时就该被拦住 */
    static class WrongTypePolicy {
        public boolean update(User user, User wrong) { return false; }
    }

    /** 参数太多 */
    static class TooManyParamsPolicy {
        public boolean update(User user, Article a, String extra) { return false; }
    }
}
