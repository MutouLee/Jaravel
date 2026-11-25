package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import io.jaravel.auth.twofactor.Totp;
import io.jaravel.support.Clock;

/**
 * 二步验证的端到端验收(docs/08 §6.4)。
 *
 * <h2>★ 为什么是真 HTTP 而不是 FeatureTest</h2>
 * <p>这条流程的全部要点是「跨请求的状态」:密码对了之后**没有登录**,
 * 只有会话里一个待验证标记;下一个请求带着同一个 cookie 过来才能完成。
 *
 * <p>而 {@code FeatureTest} 每个请求**新建一个会话**(见 {@code seedSession})
 * —— 在那里「登录后再访问 /dashboard」永远是未登录,于是
 * 「密码对了但没登录」这条断言是**空的**:它在实现写错时也会通过。
 * 第一版就是那么写的,这段注释是改过来之后留下的。
 *
 * <p>所以这里自己带一个 cookie 罐,照 {@code AuthFlowTest} 的做法。
 *
 * <h2>整条链都走 HTTP</h2>
 * <p>连密钥与恢复码都是从页面上读的(设置页本来就要显示它们:
 * 密钥给扫不了码的人手输,恢复码只在那一次出现)。
 * 这样测的就是用户真正走的那条路,而不是一个绕过了视图层的内部流程。
 */
@SpringBootTest(
        classes = app.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=" + TwoFactorFlowTest.DB_URL,
                "app.env=local",
                "server.error.whitelabel.enabled=false"
        })
class TwoFactorFlowTest {

    /** 独立的内存库;cache=shared 的理由见 AuthFlowTest */
    static final String DB_URL = "jdbc:sqlite:file:twofactor?mode=memory&cache=shared";

    private static final String PASSWORD = "correct-horse";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // ================= 一个记 cookie 的客户端 =================

    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String csrf;

    private static final Pattern TOKEN =
            Pattern.compile("name=\"_token\"\\s+value=\"([^\"]+)\"");
    private static final Pattern SECRET =
            Pattern.compile("<code id=\"secret\">([^<]+)</code>");
    private static final Pattern CODES =
            Pattern.compile("<li>([A-Z0-9]{5}-[A-Z0-9]{5})</li>");

    private ResponseEntity<String> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    private ResponseEntity<String> post(String path, Map<String, String> form) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        if (csrf != null) body.add("_token", csrf);
        return exchange(HttpMethod.POST, path, body);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body) {
        var headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML));
        if (body != null) headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (!cookies.isEmpty()) {
            var jar = new ArrayList<String>();
            cookies.forEach((k, v) -> jar.add(k + "=" + v));
            headers.add(HttpHeaders.COOKIE, String.join("; ", jar));
        }
        if (csrf != null) headers.add("X-CSRF-TOKEN", csrf);

        var response = rest.exchange("http://localhost:" + port + path, method,
                new HttpEntity<>(body, headers), String.class);

        absorbCookies(response.getHeaders());
        absorbCsrf(response.getBody());
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
            if (value.isEmpty() || raw.contains("Max-Age=0")) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    /**
     * 从页面里取 CSRF 令牌。
     *
     * <p>登录/登出会**轮换**令牌,不跟着更新的话下一个 POST 得到 419 ——
     * 而那看起来像「CSRF 防护坏了」。真实浏览器通过表单隐藏域拿到它,
     * 这里也从 {@code @csrf} 渲染出来的那个 input 里读 ——
     * 顺带把「@csrf 真的渲染了」这件事也测了。
     */
    private void absorbCsrf(String html) {
        if (html == null) return;
        var matcher = TOKEN.matcher(html);
        if (matcher.find()) csrf = matcher.group(1);

        // JSON 页面(首页 / dashboard)里的那个
        var json = Pattern.compile("\"csrf_token\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        if (json.find()) csrf = json.group(1);
    }

    private String registerAndEnableTwoFactor(String tag) {
        get("/");                                   // 建会话 + 拿令牌

        assertThat(post("/register", Map.of(
                "name", "测试用户",
                "email", tag + "@example.com",
                "password", PASSWORD,
                "password_confirmation", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);
        get("/dashboard");                          // 刷新令牌

        String setupPage = get("/settings/two-factor").getBody();
        assertThat(setupPage).as("设置页应当有二维码").contains("<svg");

        var secret = SECRET.matcher(setupPage);
        assertThat(secret.find()).as("设置页要显示手输密钥").isTrue();
        pageCodes = recoveryCodesIn(setupPage);

        // 确认一次,才算真的开启
        assertThat(post("/settings/two-factor",
                Map.of("code", Totp.code(secret.group(1), Clock.now()))).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);

        return secret.group(1);
    }

    private List<String> pageCodes = List.of();

    private List<String> recoveryCodesIn(String html) {
        var out = new ArrayList<String>();
        var matcher = CODES.matcher(html);
        while (matcher.find()) out.add(matcher.group(1));
        return List.copyOf(out);
    }

    /**
     * 登出。
     *
     * <p>先 GET 一个页面刷新 CSRF 令牌 —— 登录会**轮换**令牌,而 302 响应
     * 没有正文,拿不到新的那个。不刷新的话这个 POST 得到 419,
     * 于是「登出」静默失败、后面的登录撞上 guest 中间件被重定向到首页 ——
     * 那正是第一版的失败形态,而它看起来像「登录跳错了页」。
     *
     * <p>所以这里**断言登出真的发生了**:静默失败的助手会让后面每条断言
     * 都在测一个错的前提。
     */
    private void logout() {
        get("/dashboard");
        var response = post("/logout");

        assertThat(response.getHeaders().getLocation())
                .as("登出应当 302 回首页;419 说明 CSRF 令牌没刷新")
                .hasToString("/");
        get("/");
    }

    private ResponseEntity<String> post(String path) {
        return post(path, Map.of());
    }

    // ================= 验收 =================

    @Test
    @DisplayName("★ 密码对了但二步验证没过 —— 此时**没有登录**")
    void passwordAloneDoesNotLogIn() {
        registerAndEnableTwoFactor("2fa-pending");
        logout();

        var login = post("/login", Map.of("email", "2fa-pending@example.com",
                "password", PASSWORD));

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(login.getHeaders().getLocation()).hasToString("/two-factor");

        // 这一条是整个设计的关键。如果是 200,说明「先登录再补验证码」——
        // 那么任何一个忘了挂中间件的路由都是绕过口,而忘了挂一定会发生
        assertThat(get("/dashboard").getStatusCode())
                .isEqualTo(HttpStatus.FOUND);
    }

    @Test
    @DisplayName("★ 验证码对了才登录;错了停在挑战页")
    void challengeGatesTheLogin() {
        String secret = registerAndEnableTwoFactor("2fa-code");
        logout();

        post("/login", Map.of("email", "2fa-code@example.com", "password", PASSWORD));
        assertThat(get("/two-factor").getBody()).contains("再验一步");

        var wrong = post("/two-factor", Map.of("code", "000000"));
        assertThat(wrong.getHeaders().getLocation()).hasToString("/two-factor");
        assertThat(get("/dashboard").getStatusCode())
                .as("错码不该放行")
                .isEqualTo(HttpStatus.FOUND);

        get("/two-factor");                          // 重新拿令牌
        var right = post("/two-factor", Map.of("code", Totp.code(secret, Clock.now())));
        assertThat(right.getHeaders().getLocation()).hasToString("/dashboard");

        assertThat(get("/dashboard").getStatusCode())
                .as("这时才真的登录了")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("★ 恢复码能登录,而且只能用一次")
    void recoveryCodeWorksOnce() {
        registerAndEnableTwoFactor("2fa-recovery");
        assertThat(pageCodes).as("设置页应当列出十个恢复码").hasSize(10);
        String code = pageCodes.get(0);
        logout();

        post("/login", Map.of("email", "2fa-recovery@example.com", "password", PASSWORD));
        get("/two-factor");
        var recovered = post("/two-factor", Map.of("code", code));
        assertThat(recovered.getHeaders().getLocation())
                .as("恢复码登录的响应:%s / %s", recovered.getStatusCode(), recovered.getBody())
                .hasToString("/dashboard");
        assertThat(get("/dashboard").getStatusCode()).isEqualTo(HttpStatus.OK);

        // 同一个码再用一次
        logout();
        post("/login", Map.of("email", "2fa-recovery@example.com", "password", PASSWORD));
        get("/two-factor");
        var reuse = post("/two-factor", Map.of("code", code));

        assertThat(reuse.getHeaders().getLocation())
                .as("恢复码是一次性的 —— 用过的码必须已经从库里删掉")
                .hasToString("/two-factor");
        assertThat(get("/dashboard").getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }

    @Test
    @DisplayName("挑战页不是公开入口:没有待验证挑战时回登录页")
    void challengePageNeedsAPendingChallenge() {
        get("/");

        var response = get("/two-factor");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("/login");
    }

    @Test
    @DisplayName("没开二步验证的账号照常一步登录 —— 不该因为这个功能多一步")
    void usersWithoutTwoFactorAreUnaffected() {
        get("/");
        post("/register", Map.of(
                "name", "测试用户",
                "email", "no-2fa@example.com",
                "password", PASSWORD,
                "password_confirmation", PASSWORD));
        logout();

        var login = post("/login", Map.of("email", "no-2fa@example.com",
                "password", PASSWORD));

        assertThat(login.getHeaders().getLocation())
                .as("登录响应:%s / %s", login.getStatusCode(), login.getBody())
                .hasToString("/dashboard");
        assertThat(get("/dashboard").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
