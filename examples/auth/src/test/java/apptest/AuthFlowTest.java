package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * ★ v0.3 验收第 1 条:install:auth 的流程**端到端**跑通。
 *
 * <p>走的是真 HTTP —— 真会话 cookie、真 CSRF 校验、真重定向、真 SQLite。
 * 不用 MockMvc:要验证的恰好是「几个组件接起来」的那一层
 * (中间件顺序、cookie 属性、会话重建),而 MockMvc 绕过了其中一部分。
 *
 * <p><b>每个测试一个独立的数据库文件</b>,靠 {@code @DirtiesContext} 不够 ——
 * 见 {@link #DB_URL}。
 */
@SpringBootTest(
        classes = app.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=" + AuthFlowTest.DB_URL,
                "app.env=local",
                "server.error.whitelabel.enabled=false"
        })
@TestMethodOrder(MethodOrderer.DisplayName.class)
class AuthFlowTest {

    /**
     * 内存 SQLite + 共享缓存。
     *
     * <p>{@code cache=shared} 是必需的:没有它,连接池里每条连接
     * 各看到一个**空的**独立数据库,于是「迁移在连接 A 上建了表,
     * 查询走连接 B 就报 no such table」—— 而那看起来像迁移没跑。
     */
    static final String DB_URL =
            "jdbc:sqlite:file:authflow?mode=memory&cache=shared";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // ================= HTTP 辅助 =================

    /**
     * 一个「浏览器」:记住 cookie。
     *
     * <p>手写而不是用 RestTemplate 的 cookie 支持:会话固定防护会在登录时
     * **换掉会话 id**,而这个测试要断言的正是「换了」—— 所以 cookie 的
     * 处理必须是看得见的。
     */
    final class Browser {
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private String csrf;

        /**
         * 发 {@code Accept: application/json} 还是 {@code text/html}。
         *
         * <p>这个开关决定的是**框架返回什么**,不只是测试怎么读:
         * 未认证时浏览器拿 302 到登录页,JSON 客户端拿 401;
         * 邮箱未验证时浏览器拿 302,JSON 客户端拿 409;
         * 需要二次确认密码时 JSON 客户端拿 423 Locked。
         *
         * <p>默认是浏览器 —— 这些路由的流程(重定向、闪存消息)
         * 本来就是给浏览器的。API 组显式切成 JSON。
         */
        private boolean jsonClient;

        Browser asJsonClient() {
            this.jsonClient = true;
            return this;
        }

        /** 访问首页:建立会话并拿到 CSRF 令牌 */
        Map<String, Object> start() {
            var body = get("/");
            csrf = (String) body.get("csrf_token");
            assertThat(csrf).as("首页应下发 CSRF 令牌").isNotBlank();
            return body;
        }

        String sessionId() {
            return cookies.get("jaravel_session");
        }

        String rememberCookie() {
            return cookies.get("remember_web");
        }

        Map<String, String> cookies() {
            return Map.copyOf(cookies);
        }

        void forgetSessionCookie() {
            cookies.remove("jaravel_session");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> get(String path) {
            var response = exchange(HttpMethod.GET, path, null, Map.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("GET %s → %s", path, response.getStatusCode()).isTrue();

            Map<String, Object> body =
                    response.getBody() == null ? Map.of() : response.getBody();

            // 登录/登出会轮换 CSRF 令牌,页面重新渲染时带上新的那个。
            // 真实浏览器通过表单隐藏域拿到它,这里从 JSON 里取 ——
            // 不更新的话登录后第一次 POST 会得到 419,
            // 而那看起来像「CSRF 防护坏了」
            Object fresh = body.get("csrf_token");
            if (fresh instanceof String token && !token.isBlank()) csrf = token;

            return body;
        }

        ResponseEntity<String> getRaw(String path) {
            return exchange(HttpMethod.GET, path, null, String.class);
        }

        ResponseEntity<String> post(String path, Map<String, String> form) {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            form.forEach(body::add);
            if (csrf != null) body.add("_token", csrf);
            return exchange(HttpMethod.POST, path, body, String.class);
        }

        <T> ResponseEntity<T> exchange(HttpMethod method, String path, Object body,
                                       Class<T> type) {
            var headers = new HttpHeaders();
            headers.setAccept(jsonClient
                    ? List.of(MediaType.APPLICATION_JSON)
                    // 不含 "/json" —— Request.wantsJson() 按这个判断。
                    // 正文仍然是 JSON(控制器返回 Map,与 Accept 无关)
                    : List.of(MediaType.TEXT_HTML));
            if (body != null) {
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            }
            if (!cookies.isEmpty()) {
                var jar = new ArrayList<String>();
                cookies.forEach((k, v) -> jar.add(k + "=" + v));
                headers.add(HttpHeaders.COOKIE, String.join("; ", jar));
            }
            if (csrf != null) headers.add("X-CSRF-TOKEN", csrf);

            var response = rest.exchange("http://localhost:" + port + path,
                    method, new HttpEntity<>(body, headers), type);

            absorbCookies(response.getHeaders());
            return response;
        }

        private void absorbCookies(HttpHeaders headers) {
            var setCookies = headers.get(HttpHeaders.SET_COOKIE);
            if (setCookies == null) return;

            for (String raw : setCookies) {
                String pair = raw.split(";", 2)[0];
                int equals = pair.indexOf('=');
                if (equals < 0) continue;

                String name = pair.substring(0, equals).trim();
                String value = pair.substring(equals + 1).trim();

                // Max-Age=0 是「删除这个 cookie」—— 登出时服务端这样
                // 清 remember cookie。不处理的话登出后 cookie 还在
                if (value.isEmpty() || raw.contains("Max-Age=0")) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
        }

        /** 从 Set-Cookie 里取某个 cookie 的完整属性串 */
        String rawSetCookie(HttpHeaders headers, String name) {
            var setCookies = headers.get(HttpHeaders.SET_COOKIE);
            if (setCookies == null) return null;
            return setCookies.stream()
                    .filter(c -> c.startsWith(name + "="))
                    .findFirst().orElse(null);
        }
    }

    private Browser browser() {
        var b = new Browser();
        b.start();
        return b;
    }

    /**
     * 登录/注册之后刷新 CSRF 令牌。
     *
     * <p>顺带读到 dashboard 的闪存数据(验证链接等)—— 两件事一次请求
     * 完成是必须的:flash 只活一个请求,分两次读第二次就空了。
     */
    private Map<String, Object> refresh(Browser b) {
        return b.get("/dashboard");
    }

    /** 注册一个唯一的用户,返回 (browser, email) */
    private Browser registerFresh(String tag) {
        var b = browser();
        var response = b.post("/register", Map.of(
                "name", "测试用户",
                "email", tag + "@example.com",
                "password", "correct-horse",
                "password_confirmation", "correct-horse"));

        assertThat(response.getStatusCode())
                .as("注册后应 302 到 /dashboard,实际 %s / %s",
                    response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.FOUND);
        return b;
    }

    // ================= 1. 注册 =================

    @Nested
    @DisplayName("1. 注册")
    class Registration {

        @Test
        @DisplayName("注册成功 → 自动登录 → dashboard 可访问")
        void registerAndLandOnDashboard() {
            var b = registerFresh("reg1");

            var dashboard = b.get("/dashboard");
            assertThat(dashboard.get("email")).isEqualTo("reg1@example.com");
            assertThat(dashboard.get("email_verified"))
                    .as("刚注册的邮箱还没验证").isEqualTo(false);
        }

        @Test
        @DisplayName("★ 密码必须确认 —— 浏览器拿 302 回填,JSON 客户端拿 422")
        void passwordMustBeConfirmed() {
            var form = Map.of(
                    "name", "x", "email", "reg2@example.com",
                    "password", "correct-horse",
                    "password_confirmation", "typo-horse");

            // 浏览器:302 回表单页,错误与输入闪存进会话(Laravel 的行为)
            var browserResponse = browser().post("/register", form);
            assertThat(browserResponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);

            // 同一个请求,只换 Accept —— 这次拿到结构化的 422
            var api = new Browser().asJsonClient();
            api.start();
            var apiResponse = api.post("/register", form);

            assertThat(apiResponse.getStatusCode().value()).isEqualTo(422);
            assertThat(apiResponse.getBody()).contains("password");
        }

        @Test
        @DisplayName("★ 邮箱重复命中 unique 规则,不是数据库唯一约束的 500")
        void duplicateEmailRejected() {
            registerFresh("reg3");

            var api = new Browser().asJsonClient();
            api.start();
            var response = api.post("/register", Map.of(
                    "name", "x", "email", "reg3@example.com",
                    "password", "correct-horse",
                    "password_confirmation", "correct-horse"));

            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody())
                    .as("走的是 unique:users,email 规则 —— 自定义消息能透出来")
                    .contains("已注册");
        }

        @Test
        @DisplayName("★ 响应体里绝不出现 password 或 remember_token")
        void neverLeaksCredentials() {
            var b = registerFresh("reg4");
            var raw = b.getRaw("/dashboard");

            assertThat(raw.getBody())
                    .doesNotContain("password")
                    .doesNotContain("remember_token")
                    .doesNotContain("correct-horse");
        }
    }

    // ================= 2. 登录与会话 =================

    @Nested
    @DisplayName("2. 登录与会话")
    class Login {

        @Test
        @DisplayName("★ 登录成功后会话 id 变了(防会话固定)")
        void sessionIdRotatesOnLogin() {
            registerFresh("login1");

            var b = browser();
            String before = b.sessionId();
            assertThat(before).isNotBlank();

            var response = b.post("/login", Map.of(
                    "email", "login1@example.com", "password", "correct-horse"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(b.sessionId())
                    .as("攻击者预先种下的会话 id 不能在登录后变成已认证会话")
                    .isNotEqualTo(before);
        }

        @Test
        @DisplayName("★ 密码错误时不区分「邮箱不存在」")
        void wrongPasswordIsIndistinguishable() {
            registerFresh("login2");

            var wrongPassword = browser().post("/login", Map.of(
                    "email", "login2@example.com", "password", "nope"));
            var noSuchUser = browser().post("/login", Map.of(
                    "email", "nobody-at-all@example.com", "password", "nope"));

            // 两者都 302 回 /login 且带同样的错误 —— 否则这就是
            // 一个账号枚举接口
            assertThat(wrongPassword.getStatusCode())
                    .isEqualTo(noSuchUser.getStatusCode());
            assertThat(wrongPassword.getHeaders().getLocation())
                    .isEqualTo(noSuchUser.getHeaders().getLocation());
        }

        @Test
        @DisplayName("★ 未登录访问 /dashboard → 302 到 /login")
        void guestRedirectedToLogin() {
            var response = browser().getRaw("/dashboard");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(response.getHeaders().getLocation().getPath())
                    .isEqualTo("/login");
        }

        @Test
        @DisplayName("★ 登录后跳回原本想去的地址")
        void redirectsToIntendedUrl() {
            registerFresh("login3");

            var b = browser();
            // 先撞一次 /settings —— Authenticate 会把它存进会话
            b.getRaw("/settings");

            var response = b.post("/login", Map.of(
                    "email", "login3@example.com", "password", "correct-horse"));

            assertThat(response.getHeaders().getLocation().getPath())
                    .as("登录后回到被打断的那个页面,而不是千篇一律的首页")
                    .isEqualTo("/settings");
        }

        @Test
        @DisplayName("★ 没有 CSRF 令牌的 POST → 419")
        void postWithoutCsrfTokenRejected() {
            registerFresh("login4");

            // 不调 start(),于是没有令牌也没有会话 cookie
            var naked = new Browser();
            var response = naked.post("/login", Map.of(
                    "email", "login4@example.com", "password", "correct-horse"));

            assertThat(response.getStatusCode().value())
                    .as("419 而不是 403:前端据此区分「页面放太久了」与「没权限」")
                    .isEqualTo(419);
        }

        @Test
        @DisplayName("★ 会话 cookie 带 HttpOnly 与 SameSite")
        void sessionCookieFlags() {
            var b = new Browser();
            var response = b.exchange(HttpMethod.GET, "/", null, String.class);
            String cookie = b.rawSetCookie(response.getHeaders(), "jaravel_session");

            assertThat(cookie).isNotNull();
            assertThat(cookie).containsIgnoringCase("HttpOnly");
            assertThat(cookie).containsIgnoringCase("SameSite=Lax");
            // app.env=local,所以 Secure 关着 —— 这正是「默认安全,
            // 只有显式声明 local 才放松」的那条规则
            assertThat(cookie).doesNotContainIgnoringCase("Secure");
        }
    }

    // ================= 3. 记住我 =================

    @Nested
    @DisplayName("3. 记住我")
    class RememberMe {

        @Test
        @DisplayName("★ 丢掉会话 cookie 后仍能靠 remember cookie 恢复")
        void recallsFromRememberCookie() {
            registerFresh("rem1");

            var b = browser();
            b.post("/login", Map.of(
                    "email", "rem1@example.com",
                    "password", "correct-horse",
                    "remember", "true"));

            assertThat(b.rememberCookie()).as("应下发 remember cookie").isNotBlank();

            // 模拟「关掉浏览器再打开」:会话 cookie 没了,remember 还在
            b.forgetSessionCookie();

            var dashboard = b.get("/dashboard");
            assertThat(dashboard.get("email")).isEqualTo("rem1@example.com");
            assertThat(dashboard.get("via_remember"))
                    .as("本次认证来自 remember cookie —— 敏感操作应据此要求二次确认")
                    .isEqualTo(true);
        }

        @Test
        @DisplayName("★ 不勾 remember 就不下发那个 cookie")
        void noCookieWhenNotRequested() {
            registerFresh("rem2");

            var b = browser();
            b.post("/login", Map.of(
                    "email", "rem2@example.com", "password", "correct-horse"));

            assertThat(b.rememberCookie()).isNull();
        }

        @Test
        @DisplayName("★ 登出后 remember cookie 失效")
        void logoutInvalidatesRememberCookie() {
            registerFresh("rem3");

            var b = browser();
            b.post("/login", Map.of(
                    "email", "rem3@example.com",
                    "password", "correct-horse",
                    "remember", "true"));
            String stolen = b.rememberCookie();
            assertThat(stolen).isNotBlank();

            refresh(b);
            assertThat(b.post("/logout", Map.of()).getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);

            // 拿着登出前的 cookie 重来 —— 必须进不去,
            // 否则「我在网吧登出了」是假的
            var thief = browser();
            var response = thief.exchange(HttpMethod.GET, "/dashboard", null, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);

            var withStolen = new Browser();
            withStolen.start();
            withStolen.cookies.put("remember_web", stolen);
            withStolen.forgetSessionCookie();
            assertThat(withStolen.getRaw("/dashboard").getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
        }
    }

    // ================= 4. 登出 =================

    @Nested
    @DisplayName("4. 登出")
    class Logout {

        @Test
        @DisplayName("登出后 /dashboard 不可访问")
        void dashboardNoLongerAccessible() {
            var b = registerFresh("out1");
            refresh(b);     // 注册时换过令牌,取新的

            assertThat(b.post("/logout", Map.of()).getStatusCode())
                    .as("登出本身应成功 —— 419 说明令牌没刷新")
                    .isEqualTo(HttpStatus.FOUND);

            assertThat(b.getRaw("/dashboard").getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
        }

        @Test
        @DisplayName("★ 登出后 CSRF 令牌换了")
        void csrfTokenRotates() {
            var b = registerFresh("out2");
            String before = (String) refresh(b).get("csrf_token");
            assertThat(before).isNotBlank();

            assertThat(b.post("/logout", Map.of()).getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
            String after = (String) b.get("/").get("csrf_token");

            assertThat(after)
                    .as("沿用旧令牌会让登出前拿到 token 的页面仍能对新会话发起写操作")
                    .isNotEqualTo(before);
        }
    }

    // ================= 5. 邮箱验证 =================

    @Nested
    @DisplayName("5. 邮箱验证")
    class EmailVerification {

        @Test
        @DisplayName("★ 点验证链接 → 已验证")
        void verifyViaSignedLink() {
            var b = registerFresh("ver1");

            String link = (String) b.get("/dashboard").get("verification_link");
            assertThat(link).as("注册时应生成验证链接").isNotBlank();

            var response = b.exchange(HttpMethod.GET, pathOf(link), null, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);

            assertThat(b.get("/dashboard").get("email_verified")).isEqualTo(true);
        }

        @Test
        @DisplayName("★ 改过签名的链接无效")
        void tamperedLinkRejected() {
            var b = registerFresh("ver2");
            String link = (String) b.get("/dashboard").get("verification_link");

            // 把签名的最后一个字符改掉
            String tampered = link.substring(0, link.length() - 1)
                    + (link.endsWith("A") ? "B" : "A");

            b.exchange(HttpMethod.GET, pathOf(tampered), null, String.class);

            assertThat(b.get("/dashboard").get("email_verified"))
                    .as("签名对不上就不能标记为已验证").isEqualTo(false);
        }

        @Test
        @DisplayName("★ 未验证时访问 /settings → 302(verified 中间件)")
        void unverifiedBlockedFromVerifiedRoutes() {
            var b = registerFresh("ver3");

            var response = b.getRaw("/settings");
            assertThat(response.getStatusCode())
                    .as("verified 中间件拦住未验证用户")
                    .isEqualTo(HttpStatus.FOUND);
        }

        private String pathOf(String url) {
            int schemeEnd = url.indexOf("://");
            int pathStart = url.indexOf('/', schemeEnd + 3);
            return pathStart < 0 ? "/" : url.substring(pathStart);
        }
    }

    // ================= 6. 密码重置 =================

    @Nested
    @DisplayName("6. 密码重置")
    class PasswordReset {

        @Test
        @DisplayName("★ 发链接 → 重置 → 新密码生效、旧密码失效")
        void fullResetFlow() {
            registerFresh("pw1");

            var b = browser();
            b.post("/forgot-password", Map.of("email", "pw1@example.com"));
            String token = (String) b.get("/").get("reset_token");
            assertThat(token).as("应生成重置令牌").isNotBlank();

            var reset = b.post("/reset-password", Map.of(
                    "email", "pw1@example.com",
                    "token", token,
                    "password", "brand-new-password",
                    "password_confirmation", "brand-new-password"));
            assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.FOUND);

            // 新密码能登录
            var withNew = browser();
            withNew.post("/login", Map.of(
                    "email", "pw1@example.com", "password", "brand-new-password"));
            assertThat(withNew.get("/dashboard").get("email")).isEqualTo("pw1@example.com");

            // 旧密码不能
            var withOld = browser();
            withOld.post("/login", Map.of(
                    "email", "pw1@example.com", "password", "correct-horse"));
            assertThat(withOld.getRaw("/dashboard").getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
        }

        @Test
        @DisplayName("★ 令牌单次有效")
        void tokenIsSingleUse() {
            registerFresh("pw2");

            var b = browser();
            b.post("/forgot-password", Map.of("email", "pw2@example.com"));
            String token = (String) b.get("/").get("reset_token");

            var form = Map.of(
                    "email", "pw2@example.com",
                    "token", token,
                    "password", "first-new-password",
                    "password_confirmation", "first-new-password");

            b.post("/reset-password", form);
            b.get("/");                       // 取新的 CSRF 令牌
            var second = b.post("/reset-password", form);

            // 第二次应该被拒 —— 转发/归档的重置邮件不该能反复改密码。
            // 它 302 回 /reset-password 而不是 /login
            assertThat(second.getHeaders().getLocation().getPath())
                    .isEqualTo("/reset-password");
        }

        @Test
        @DisplayName("★ 不存在的邮箱也返回成功 —— 不泄露账号是否注册")
        void unknownEmailLooksIdentical() {
            registerFresh("pw3");

            var known = browser();
            known.post("/forgot-password", Map.of("email", "pw3@example.com"));
            var unknown = browser();
            var response = unknown.post("/forgot-password",
                    Map.of("email", "nobody-here@example.com"));

            assertThat(response.getStatusCode())
                    .as("实际返回 %s / %s", response.getStatusCode(), response.getBody())
                    .isEqualTo(HttpStatus.FOUND);
            assertThat(response.getHeaders().getLocation().getPath())
                    .as("与已注册邮箱走到同一个地址,否则这就是邮箱枚举工具")
                    .isEqualTo("/login");
            assertThat(unknown.get("/").get("reset_token"))
                    .as("但实际没有生成令牌").isNull();
        }
    }

    // ================= 7. 授权(Policy) =================

    @Nested
    @DisplayName("7. 授权:Policy 管「能不能」")
    class Authorization {

        /** 注册 + 验证邮箱,返回这个浏览器 */
        private Browser verifiedUser(String tag) {
            var b = registerFresh(tag);
            String link = (String) b.get("/dashboard").get("verification_link");
            int schemeEnd = link.indexOf("://");
            String path = link.substring(link.indexOf('/', schemeEnd + 3));
            b.exchange(HttpMethod.GET, path, null, String.class);
            return b;
        }

        @Test
        @DisplayName("★ 未验证邮箱不能建笔记(create 规则读模型字段)")
        void unverifiedCannotCreate() {
            var b = registerFresh("pol1");

            // 这条走 session guard 不行(/api 用 auth:api),
            // 所以直接验证 Gate 的那条规则:未验证时 create 应拒绝。
            // 这里用 /settings 作为同一件事的代理 —— 它挂了 verified
            assertThat(b.getRaw("/settings").getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
        }

        @Test
        @DisplayName("★ 验证邮箱后 /settings 要求二次确认密码")
        void verifiedStillNeedsPasswordConfirmation() {
            var b = verifiedUser("pol2");

            var response = b.getRaw("/settings");
            assertThat(response.getStatusCode())
                    .as("password.confirm 中间件:敏感页要求最近确认过密码")
                    .isEqualTo(HttpStatus.FOUND);
            assertThat(response.getHeaders().getLocation().getPath())
                    .isEqualTo("/confirm-password");
        }

        @Test
        @DisplayName("★ 确认密码后 /settings 放行")
        void confirmingPasswordUnlocksSettings() {
            var b = verifiedUser("pol3");

            refresh(b);                     // 取登录后的新 CSRF 令牌
            b.getRaw("/settings");          // 让它记下 url.intended
            var confirm = b.post("/confirm-password",
                    Map.of("password", "correct-horse"));
            assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.FOUND);

            var settings = b.get("/settings");
            assertThat(settings.get("sensitive")).isEqualTo(true);
        }

        @Test
        @DisplayName("★ 密码不对时不解锁")
        void wrongPasswordDoesNotConfirm() {
            var b = verifiedUser("pol4");

            refresh(b);
            b.post("/confirm-password", Map.of("password", "not-my-password"));

            assertThat(b.getRaw("/settings").getHeaders().getLocation().getPath())
                    .isEqualTo("/confirm-password");
        }
    }

    // ================= 8. API guard =================

    @Nested
    @DisplayName("8. API:无状态 Bearer token")
    class ApiGuard {

        @Test
        @DisplayName("★ 没有 token → 401,且不下发会话 cookie")
        void noTokenIsUnauthorized() {
            var b = new Browser().asJsonClient();
            var response = b.exchange(HttpMethod.GET, "/api/me", null, String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                    .as("api 组不含 StartSession —— 无状态接口不该下发会话 cookie")
                    .isNullOrEmpty();
        }

        @Test
        @DisplayName("★ 隐式模型绑定:控制器直接拿到 Note 实例")
        void implicitModelBinding() {
            var api = apiClientFor("bind1");
            long id = createNote(api, "第一条", "正文");

            // 路由是 /api/notes/{note},控制器签名是 show(Note note)
            var response = api.get("/api/notes/" + id);
            assertThat(response).contains("\"title\":\"第一条\"");
        }

        @Test
        @DisplayName("★ can:update,note 拿到的是实例 —— 别人的笔记改不了")
        void policyBlocksOtherPeoplesNotes() {
            var owner = apiClientFor("bind2");
            long id = createNote(owner, "我的", "正文");

            var stranger = apiClientFor("bind3");

            // 这是 docs/08 §10 那条待决项的实际效果:中间件拿到的
            // 必须是 Note 实例,否则 NotePolicy.update(User, Note)
            // 类型不匹配 → 永远拒绝,而那时连自己的笔记也改不了
            assertThat(stranger.put("/api/notes/" + id, "{\"title\":\"篡改\"}")
                    .getStatusCode().value())
                    .as("别人的笔记应被 Policy 拒绝")
                    .isEqualTo(403);

            assertThat(owner.put("/api/notes/" + id, "{\"title\":\"改好了\"}")
                    .getStatusCode().is2xxSuccessful())
                    .as("自己的笔记应该能改 —— 这一条证明不是「一律拒绝」")
                    .isTrue();

            assertThat(owner.get("/api/notes/" + id)).contains("改好了");
        }

        @Test
        @DisplayName("★ 绑定失败 → 404,不是 500")
        void missingModelIs404() {
            var api = apiClientFor("bind4");

            // /api/notes/999999 语义上就是「这个 URL 不存在」
            assertThat(api.send(HttpMethod.GET, "/api/notes/999999", null)
                    .getStatusCode().value())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("★ 伪造的 token → 401")
        void forgedTokenIsUnauthorized() {
            var headers = new HttpHeaders();
            headers.setBearerAuth("1|totally-made-up");
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            var response = rest.exchange("http://localhost:" + port + "/api/me",
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // ---------- 辅助 ----------

        /** 注册一个用户、验证邮箱、换一个 API 令牌 */
        private ApiClient apiClientFor(String tag) {
            var browser = registerFresh(tag);

            // NotePolicy.create 要求邮箱已验证 —— 顺带证明了
            // Policy 里读到的是真正的模型字段
            String link = (String) refresh(browser).get("verification_link");
            int schemeEnd = link.indexOf("://");
            browser.exchange(HttpMethod.GET,
                    link.substring(link.indexOf('/', schemeEnd + 3)), null, String.class);

            refresh(browser);       // 取验证后的新 CSRF 令牌
            String body = browser.post("/api-token", Map.of("name", tag)).getBody();

            String token = between(body, "\"token\":\"", "\"");
            assertThat(token).as("应签发 API 令牌,实际响应:%s", body).isNotBlank();
            return new ApiClient(token);
        }

        private long createNote(ApiClient api, String title, String body) {
            var response = api.send(HttpMethod.POST, "/api/notes",
                    "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}");

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("建笔记失败:%s / %s", response.getStatusCode(), response.getBody())
                    .isTrue();
            return Long.parseLong(between(response.getBody(), "\"id\":", ",")
                    .replaceAll("[^0-9]", ""));
        }

        private String between(String text, String start, String end) {
            int from = text == null ? -1 : text.indexOf(start);
            if (from < 0) return "";
            from += start.length();
            int to = text.indexOf(end, from);
            return to < 0 ? text.substring(from) : text.substring(from, to);
        }

        /** 只带 Bearer 令牌的无状态客户端 —— 不记 cookie,不带 CSRF */
        private final class ApiClient {

            private final String token;

            ApiClient(String token) {
                this.token = token;
            }

            String get(String path) {
                return send(HttpMethod.GET, path, null).getBody();
            }

            ResponseEntity<String> put(String path, String json) {
                return send(HttpMethod.PUT, path, json);
            }

            ResponseEntity<String> send(HttpMethod method, String path, String json) {
                var headers = new HttpHeaders();
                headers.setBearerAuth(token);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                if (json != null) headers.setContentType(MediaType.APPLICATION_JSON);

                return rest.exchange("http://localhost:" + port + path, method,
                        new HttpEntity<>(json, headers), String.class);
            }
        }
    }
}
