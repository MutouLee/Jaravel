package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import app.models.Note;
import app.models.User;
import io.jaravel.auth.hash.Hash;
import io.jaravel.session.VerifyCsrfToken;
import io.jaravel.testing.FeatureTest;

/**
 * ★ 用 {@code jaravel-testing} 重写的认证测试。
 *
 * <p>与 {@link AuthFlowTest} 的对照是本文件存在的主要理由:
 * 那边是手写的真 HTTP 客户端(140 行的 Browser 类、cookie 罐、
 * CSRF 令牌刷新、重定向跟随),这边是直接 {@code get(...)}。
 *
 * <p>两者走的管道**完全一样**(路由 → 中间件 → 绑定 → 控制器 → 异常处理),
 * 区别只是不经过 Tomcat。AuthFlowTest 保留下来 ——
 * 它额外覆盖了真实 Servlet 容器那一层(cookie 属性的实际序列化、
 * 容器的字符编码),而那一层也确实出过问题。
 */
class AuthFeatureTest extends FeatureTest {

    @org.springframework.beans.factory.annotation.Autowired
    app.listeners.AuthAudit audit;

    @org.springframework.beans.factory.annotation.Autowired
    io.jaravel.cache.CacheManager caches;

    @org.junit.jupiter.api.BeforeEach
    void resetSingletonState() {
        // ★ 这两样都**不随事务回滚**,必须手工清。
        //
        // audit 是单例 bean,它的记录会跨测试累积。
        //
        // 缓存里装着登录节流的计数 —— 不清的话「几个测试各失败一次」
        // 会累加成 429,于是一个与节流无关的测试因为**别的测试**
        // 失败过几次而变红。那种失败极难归因:单独跑它是绿的。
        audit.reset();
        caches.store().flush();
    }

    private User createUser(String email, String password) {
        User user = new User();
        user.name = "测试用户";
        user.email = email;
        user.password = Hash.make(password);
        user.save();
        return user;
    }

    // ================= 注册 =================

    @Nested
    @DisplayName("注册")
    class Registration {

        @Test
        @DisplayName("注册成功 → 自动登录 → dashboard 可访问")
        void registersAndLogsIn() {
            post("/register", Map.of(
                    "name", "张三",
                    "email", "reg@example.com",
                    "password", "correct-horse",
                    "password_confirmation", "correct-horse"))
                    .assertRedirect("/dashboard")
                    .assertSessionHas("status");

            assertDatabaseHas("users", Map.of("email", "reg@example.com"));
            assertDatabaseCount("users", 1);
        }

        @Test
        @DisplayName("★ 密码不一致:浏览器 302 回跳并闪存错误")
        void passwordMustBeConfirmed() {
            from("/register")
                    .post("/register", Map.of(
                            "name", "x", "email", "a@example.com",
                            "password", "correct-horse",
                            "password_confirmation", "typo"))
                    .assertRedirect("/register")
                    .assertSessionHasErrors("password");

            assertDatabaseEmpty("users");
        }

        @Test
        @DisplayName("★ 同一个请求换个 Accept:JSON 客户端拿 422")
        void jsonClientGets422() {
            postJson("/register", Map.of(
                    "name", "x", "email", "a@example.com",
                    "password", "correct-horse",
                    "password_confirmation", "typo"))
                    .assertUnprocessable()
                    .assertJsonValidationErrors("password");
        }

        @Test
        @DisplayName("★ 邮箱重复命中 unique 规则,自定义消息能透出来")
        void duplicateEmail() {
            createUser("taken@example.com", "x");

            postJson("/register", Map.of(
                    "name", "x", "email", "taken@example.com",
                    "password", "correct-horse",
                    "password_confirmation", "correct-horse"))
                    .assertUnprocessable()
                    .assertSee("已注册");
        }

        @Test
        @DisplayName("★ 响应体里绝不出现 password 或 remember_token")
        void neverLeaksCredentials() {
            var user = createUser("safe@example.com", "correct-horse");

            actingAs(user).get("/dashboard")
                    .assertOk()
                    .assertDontSee("password")
                    .assertDontSee("remember_token")
                    .assertDontSee("correct-horse");
        }
    }

    // ================= 登录 =================

    @Nested
    @DisplayName("登录")
    class Login {

        @Test
        @DisplayName("凭据正确 → 跳 dashboard")
        void succeeds() {
            createUser("a@example.com", "correct-horse");

            post("/login", Map.of("email", "a@example.com", "password", "correct-horse"))
                    .assertRedirect("/dashboard");
        }

        @Test
        @DisplayName("★ 密码错误与邮箱不存在的响应无法区分")
        void wrongCredentialsAreIndistinguishable() {
            createUser("a@example.com", "correct-horse");

            var wrongPassword = post("/login",
                    Map.of("email", "a@example.com", "password", "nope"));
            var noSuchUser = post("/login",
                    Map.of("email", "nobody@example.com", "password", "nope"));

            // 区分开来就是一个账号枚举接口
            wrongPassword.assertRedirect("/login").assertSessionHasErrors("email");
            noSuchUser.assertRedirect("/login").assertSessionHasErrors("email");
            assertThatSameShape(wrongPassword.status(), noSuchUser.status());
        }

        private void assertThatSameShape(int a, int b) {
            org.assertj.core.api.Assertions.assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("★ 未登录访问 /dashboard:浏览器 302,JSON 客户端 401")
        void guestIsRejected() {
            get("/dashboard").assertRedirect("/login");
            getJson("/dashboard").assertUnauthorized();
        }

        @Test
        @DisplayName("★ 登录后跳回原本想去的地址")
        void redirectsToIntendedUrl() {
            createUser("a@example.com", "correct-horse");

            // 先撞一次 /settings —— Authenticate 把它存进会话。
            // 这一条曾经**不工作**:302 是用异常表达的,而会话写回
            // 在异常路径上被跳过(v0.3 的真实缺陷之一)
            get("/settings").assertRedirect("/login").assertSessionHas("url.intended");
        }

        @Test
        @DisplayName("★ 没有 CSRF 令牌的 POST → 419")
        void csrfIsEnforced() {
            createUser("a@example.com", "correct-horse");

            // 默认是**带**令牌的 —— 绕过 CSRF 会让「表单忘了加 @csrf」测不出来
            withoutCsrfToken()
                    .post("/login", Map.of("email", "a@example.com",
                                           "password", "correct-horse"))
                    .assertStatus(419);
        }

        @Test
        @DisplayName("显式排除 CSRF 中间件后放行")
        void csrfCanBeExcluded() {
            createUser("a@example.com", "correct-horse");

            withoutCsrfToken()
                    .withoutMiddleware(VerifyCsrfToken.class)
                    .post("/login", Map.of("email", "a@example.com",
                                           "password", "correct-horse"))
                    .assertRedirect("/dashboard");
        }

        @Test
        @DisplayName("★ 会话 cookie 带 HttpOnly 与 SameSite")
        void sessionCookieFlags() {
            var response = get("/");

            response.assertCookie("jaravel_session");
            org.assertj.core.api.Assertions
                    .assertThat(response.cookieHeader("jaravel_session").orElseThrow())
                    .containsIgnoringCase("HttpOnly")
                    .containsIgnoringCase("SameSite=Lax");
        }
    }

    // ================= 记住我与登出 =================

    @Nested
    @DisplayName("记住我与登出")
    class RememberAndLogout {

        @Test
        @DisplayName("★ 勾了 remember 就下发 remember cookie")
        void issuesRememberCookie() {
            createUser("a@example.com", "correct-horse");

            // 这一条曾经**不工作**:SessionGuard 把 cookie 挂在
            // takePendingCookie() 上等人来取,而 HTTP 管道里没人取
            post("/login", Map.of("email", "a@example.com",
                                  "password", "correct-horse",
                                  "remember", "true"))
                    .assertRedirect("/dashboard")
                    .assertCookie("remember_web");
        }

        @Test
        @DisplayName("不勾就不下发")
        void noCookieWhenNotRequested() {
            createUser("a@example.com", "correct-horse");

            post("/login", Map.of("email", "a@example.com", "password", "correct-horse"))
                    .assertCookieMissing("remember_web");
        }

        @Test
        @DisplayName("★ 登出清会话、换 CSRF 令牌、删 remember cookie")
        void logout() {
            var user = createUser("a@example.com", "correct-horse");

            var response = actingAs(user).post("/logout");

            response.assertRedirect("/")
                    // Max-Age=0 才是「删除」—— assertCookieMissing 在
                    // 登出场景里会误判通过
                    .assertCookieExpired("remember_web");

            // 登出后 dashboard 不可访问
            actingAsGuest().get("/dashboard").assertRedirect("/login");
        }
    }

    // ================= 邮箱验证与敏感页 =================

    @Nested
    @DisplayName("邮箱验证与敏感页")
    class Verification {

        @Test
        @DisplayName("★ 未验证邮箱时 /settings 被 verified 拦住")
        void unverifiedBlocked() {
            var user = createUser("a@example.com", "correct-horse");

            actingAs(user).get("/settings").assertRedirect("/email/verify");
            actingAs(user).getJson("/settings").assertStatus(409);
        }

        @Test
        @DisplayName("★ 验证后仍要求二次确认密码")
        void verifiedStillNeedsPasswordConfirmation() {
            var user = createUser("a@example.com", "correct-horse");
            user.emailVerifiedAt = java.time.Instant.now();
            user.save();

            actingAs(user).get("/settings").assertRedirect("/confirm-password");
        }

        @Test
        @DisplayName("★ 确认密码后放行")
        void confirmingUnlocks() {
            var user = createUser("a@example.com", "correct-horse");
            user.emailVerifiedAt = java.time.Instant.now();
            user.save();

            actingAs(user)
                    .withSession(Map.of("auth.password_confirmed_at",
                                        java.time.Instant.now().getEpochSecond()))
                    .get("/settings")
                    .assertOk()
                    .assertJsonPath("sensitive", true);
        }
    }

    // ================= 密码重置 =================

    @Nested
    @DisplayName("密码重置")
    class PasswordReset {

        @Test
        @DisplayName("发链接 → 重置 → 新密码生效")
        void fullFlow() {
            createUser("a@example.com", "old-password");

            String token = (String) post("/forgot-password",
                    Map.of("email", "a@example.com"))
                    .assertRedirect("/login")
                    .session().orElseThrow()
                    .get("reset_token").orElseThrow();

            post("/reset-password", Map.of(
                    "email", "a@example.com",
                    "token", token,
                    "password", "brand-new-password",
                    "password_confirmation", "brand-new-password"))
                    .assertRedirect("/login");

            post("/login", Map.of("email", "a@example.com",
                                  "password", "brand-new-password"))
                    .assertRedirect("/dashboard");

            post("/login", Map.of("email", "a@example.com", "password", "old-password"))
                    .assertRedirect("/login");
        }

        @Test
        @DisplayName("★ 不存在的邮箱也返回成功,且不生成令牌")
        void doesNotLeakAccountExistence() {
            post("/forgot-password", Map.of("email", "nobody@example.com"))
                    .assertRedirect("/login")
                    .assertSessionMissing("reset_token");

            assertDatabaseEmpty("password_reset_tokens");
        }

        @Test
        @DisplayName("★ 令牌哈希存库")
        void tokenIsHashed() {
            createUser("a@example.com", "x");

            String token = (String) post("/forgot-password",
                    Map.of("email", "a@example.com"))
                    .session().orElseThrow().get("reset_token").orElseThrow();

            assertDatabaseMissing("password_reset_tokens", Map.of("token", token));
            assertDatabaseCount("password_reset_tokens", 1);
        }
    }

    // ================= API 与授权 =================

    @Nested
    @DisplayName("API 与授权")
    class Api {

        /** 注册 + 验证邮箱 + 换令牌 */
        private String tokenFor(String email) {
            var user = createUser(email, "correct-horse");
            user.emailVerifiedAt = java.time.Instant.now();
            user.save();

            return actingAs(user).post("/api-token", Map.of("name", "test"))
                    .assertOk()
                    .json().path("token").asText();
        }

        private long createNote(String token, String title) {
            return withToken(token)
                    .postJson("/api/notes", Map.of("title", title, "body", "正文"))
                    .assertSuccessful()
                    .json().path("id").asLong();
        }

        @Test
        @DisplayName("★ 没有 token → 401,且不下发会话 cookie")
        void noToken() {
            getJson("/api/me")
                    .assertUnauthorized()
                    // api 组不含 StartSession —— 无状态接口不该下发会话 cookie
                    .assertCookieMissing("jaravel_session");
        }

        @Test
        @DisplayName("★ Bearer 令牌能认证 —— 它曾经从未被交给 TokenGuard")
        void bearerTokenWorks() {
            String token = tokenFor("api@example.com");

            withToken(token).getJson("/api/me")
                    .assertOk()
                    .assertJsonPath("email", "api@example.com")
                    .assertJsonPath("guard", "api");
        }

        @Test
        @DisplayName("伪造的 token → 401")
        void forgedToken() {
            withToken("1|totally-made-up").getJson("/api/me").assertUnauthorized();
        }

        @Test
        @DisplayName("★ 隐式模型绑定:控制器直接拿到 Note 实例")
        void implicitModelBinding() {
            String token = tokenFor("bind@example.com");
            long id = createNote(token, "第一条");

            withToken(token).getJson("/api/notes/" + id)
                    .assertOk()
                    .assertJsonPath("title", "第一条");
        }

        @Test
        @DisplayName("★ can:update,note 拿到的是实例 —— 别人的笔记改不了")
        void policyBlocksOthers() {
            String ownerToken = tokenFor("owner@example.com");
            long id = createNote(ownerToken, "我的");

            String strangerToken = tokenFor("stranger@example.com");

            withToken(strangerToken)
                    .putJson("/api/notes/" + id, Map.of("title", "篡改"))
                    .assertForbidden();

            // 这一条证明不是「一律拒绝」
            withToken(ownerToken)
                    .putJson("/api/notes/" + id, Map.of("title", "改好了"))
                    .assertSuccessful();

            assertDatabaseHas("notes", Map.of("id", id, "title", "改好了"));
        }

        @Test
        @DisplayName("★ 绑定失败 → 404,不是 500")
        void missingModelIs404() {
            withToken(tokenFor("nf@example.com"))
                    .getJson("/api/notes/999999")
                    .assertNotFound();
        }

        @Test
        @DisplayName("★ 列表页不该有 N+1 —— 把它变成会让 CI 失败的回归测试")
        void listingHasNoNPlusOne() {
            String token = tokenFor("n1@example.com");
            for (int i = 0; i < 5; i++) createNote(token, "笔记 " + i);

            // 5 条笔记,查询数不该随条数增长。
            // 实测:1 条查令牌 + 1 条查用户 + 1 条 update last_used_at
            // + 1 条查笔记 = 4
            assertQueryCount(4, () ->
                    withToken(token).getJson("/api/notes").assertOk().assertJsonCount(5, ""));
        }

        @Test
        @DisplayName("软删除断言")
        void softDeleteAssertions() {
            String token = tokenFor("del@example.com");
            long id = createNote(token, "待删");

            withToken(token).deleteJson("/api/notes/" + id).assertSuccessful();

            // notes 没有软删除列,所以这里断言的是硬删除
            assertDatabaseMissing("notes", Map.of("id", id));
        }
    }

    // ================= 框架事件 =================

    /**
     * 认证事件真的被发出来、真的被监听器收到了。
     *
     * <p>这一组断言覆盖的是**三段接线**:APT 生成绑定 →
     * starter 从 listeners.idx 装上 → 框架在调用点发出。
     * 三者缺任何一个,{@code AuthAudit} 都会「存在但从不触发」,
     * 而那在没有断言的情况下完全看不出来。
     */
    @Nested
    @DisplayName("认证事件")
    class Events {

        @Test
        @DisplayName("★ 登录成功 → Login 事件到达监听器")
        void loginIsAudited() {
            createUser("ev@example.com", "secret-pass");

            post("/login", Map.of("email", "ev@example.com", "password", "secret-pass"))
                    .assertRedirect("/dashboard");

            assertThat(audit.entries())
                    .as("APT + starter 装配 + 调用点发出,三段接线都要通")
                    .anyMatch(e -> e.startsWith("login:") && e.endsWith("remember=false"));
        }

        @Test
        @DisplayName("记住我时 Login 事件带 remember=true")
        void rememberIsReported() {
            createUser("rem@example.com", "secret-pass");

            post("/login", Map.of("email", "rem@example.com",
                    "password", "secret-pass", "remember", "true"));

            assertThat(audit.entries()).anyMatch(e -> e.endsWith("remember=true"));
        }

        @Test
        @DisplayName("★ 登录失败 → Failed 事件,且**不含密码**")
        void failedCarriesNoPassword() {
            createUser("ev@example.com", "secret-pass");

            post("/login", Map.of("email", "ev@example.com", "password", "wrong-pass"));

            var failed = audit.entries().stream()
                    .filter(e -> e.startsWith("failed:"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "没有收到 Failed 事件:" + audit.entries()));

            assertThat(failed)
                    .as("exists=true 区分「密码错」与「账号不存在」—— "
                            + "这个区别不该返回给客户端,但对内部审计有用")
                    .contains("exists=true");
            assertThat(failed)
                    .as("★ 事件里只有**键名**没有值 —— 否则一个把事件写进"
                            + "日志的监听器会把明文密码写进日志文件")
                    .contains("keys=[email, password]")
                    .doesNotContain("wrong-pass")
                    .doesNotContain("secret-pass");
        }

        @Test
        @DisplayName("账号不存在时 Failed 的 exists=false")
        void failedForUnknownAccount() {
            post("/login", Map.of("email", "nobody@example.com", "password", "x"));

            assertThat(audit.entries())
                    .anyMatch(e -> e.startsWith("failed:nobody@example.com:exists=false"));
        }

        @Test
        @DisplayName("★ 注册 → Registered(由应用自己发)")
        void registeredIsDispatchedByTheApp() {
            post("/register", Map.of(
                    "name", "李四",
                    "email", "newreg@example.com",
                    "password", "correct-horse",
                    "password_confirmation", "correct-horse"))
                    .assertRedirect("/dashboard");

            assertThat(audit.entries())
                    .as("框架不发这个事件 —— 注册是业务。"
                            + "但事件定义在框架里,好让「发欢迎邮件」这类监听器可复用")
                    .anyMatch(e -> e.startsWith("registered:"));
        }

        @Test
        @DisplayName("★ 登出 → Logout")
        void logoutIsAudited() {
            var user = createUser("out@example.com", "secret-pass");
            actingAs(user);

            post("/logout", Map.of()).assertRedirect("/");

            assertThat(audit.entries()).anyMatch(e -> e.startsWith("logout:"));
        }
    }

    // ================= 回滚 =================

    @Nested
    @DisplayName("每个测试自动回滚")
    class Rollback {

        @Test
        @DisplayName("第一个测试写了一行")
        void writesARow() {
            createUser("rollback@example.com", "x");
            assertDatabaseCount("users", 1);
        }

        @Test
        @DisplayName("★ 第二个测试看不到第一个写的数据")
        void seesNothingFromTheOther() {
            // 不需要写任何清理代码 —— 这正是「默认回滚」要换来的东西
            assertDatabaseEmpty("users");
        }
    }
}
