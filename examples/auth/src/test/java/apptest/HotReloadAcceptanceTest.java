package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import io.jaravel.session.SessionStore;
import io.jaravel.session.store.FileSessionStore;

/**
 * ★ v0.3 验收第 3、4 条:热重启的耗时与**登录状态不丢失**。
 *
 * <p>做法是起**两个先后的应用上下文**,共用同一个会话目录与数据库文件,
 * 然后拿第一个上下文里登录拿到的 cookie 去访问第二个。
 *
 * <p>这是对 L3「丢弃 app 类加载器 → 重建容器」最接近的可测模型:
 * 容器被整个关掉重建,内存里的一切(guard 实例、缓存、会话 Map)全部丢失。
 * 唯一不同的是类加载器没换 —— 而那恰好不影响本测试要验证的东西
 * (会话是否活过重建)。
 *
 * <p>第 3 条的「< 2s」在这里测的是**容器重建**的耗时,它是 L3 的主要成本。
 * 真实的 devtools 重启还要加上增量编译的时间,那部分取决于改了多少文件,
 * 本测试无法覆盖 —— 这一点在断言的消息里写明了。
 */
class HotReloadAcceptanceTest {

    @TempDir
    Path workspace;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void closeContexts() {
        // 倒序关闭 —— 后起的先关
        for (int i = contexts.size() - 1; i >= 0; i--) {
            contexts.get(i).close();
        }
        contexts.clear();
    }

    /**
     * 起一个应用上下文。
     *
     * <p>**文件型 SQLite**:内存库在所有连接关闭时就消失了,
     * 而这里要测的恰好是「关掉再起来」。
     */
    private ConfigurableApplicationContext boot() {
        var context = new SpringApplicationBuilder(app.Application.class)
                .properties(Map.of(
                        "server.port", "0",
                        // **必须用占位符的来源名**(DB_URL),不是
                        // spring.datasource.url —— SpringApplicationBuilder
                        // .properties 加的是优先级**最低**的属性源,
                        // 而 application.yml 里写的是
                        // ${DB_URL:jdbc:sqlite:storage/auth.db}。
                        // 直接设 spring.datasource.url 会被 yml 盖掉,
                        // 于是测试悄悄用上了仓库里那个持久的 storage/auth.db,
                        // 症状是「第二次跑测试开始失败,因为用户已存在」
                        "DB_URL", "jdbc:sqlite:" + workspace.resolve("auth.db"),
                        // 开发模式:会话默认落盘
                        "APP_DEBUG", "true",
                        "APP_ENV", "local",
                        "SESSION_FILES", workspace.resolve("sessions").toString(),
                        // 不在测试里起文件监听 —— 它会盯着 TempDir 一直有动静
                        "jaravel.hot-reload.watch", "false"))
                .run();
        contexts.add(context);
        return context;
    }

    private int portOf(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment()
                .getProperty("local.server.port", "0"));
    }

    // ================= 验收 #4 =================

    @Test
    @DisplayName("★ 验收 #4:重启后登录状态不丢失")
    void loginSurvivesRestart() {
        var first = boot();
        var browser = new Browser(portOf(first));

        browser.start();
        browser.register("hot1@example.com");
        assertThat(browser.dashboard().get("email")).isEqualTo("hot1@example.com");

        String sessionCookie = browser.sessionCookie();
        assertThat(sessionCookie).isNotBlank();

        // 模拟 L3:整个容器关掉重建
        long start = System.nanoTime();
        first.close();
        var second = boot();
        long millis = (System.nanoTime() - start) / 1_000_000;

        // 同一个浏览器(同一个 cookie),换了端口
        browser.movePort(portOf(second));

        assertThat(browser.dashboard().get("email"))
                .as("""
                    每次改代码都要重新登录一次,这个摩擦足以让人放弃热重载。
                    会话活过重启靠的是开发模式默认用 file 驱动(docs/15 §3.5)。
                    容器重建耗时 %d ms。""".formatted(millis))
                .isEqualTo("hot1@example.com");
    }

    @Test
    @DisplayName("★ 开发模式下会话驱动默认是 file")
    void devModeUsesFileSessions() {
        var context = boot();
        var store = context.getBean(SessionStore.class);

        assertThat(store)
                .as("cache 驱动在进程内,重启就丢 —— 那就等于每改一行代码重新登录")
                .isInstanceOf(FileSessionStore.class);
        assertThat(store.name()).isEqualTo("file");
    }

    @Test
    @DisplayName("★ 生产模式下不用 file —— 它不跨机器共享")
    void productionDoesNotUseFileSessions() {
        var context = new SpringApplicationBuilder(app.Application.class)
                .properties(Map.of(
                        "server.port", "0",
                        "DB_URL", "jdbc:sqlite:" + workspace.resolve("prod.db"),
                        // config/app.yml 里写的是 ${APP_DEBUG:true},
                        // 所以要设占位符解析的来源而不是 app.debug 本身 ——
                        // 这也正是真实部署里的做法
                        "APP_DEBUG", "false",
                        "APP_ENV", "production",
                        "jaravel.hot-reload.watch", "false"))
                .run();
        contexts.add(context);

        // file 驱动多实例部署会出现「刷新页面随机掉登录」。
        // 名字带着底层 cache store:"cache(caffeine)"
        assertThat(context.getBean(SessionStore.class).name()).startsWith("cache");
        assertThat(context.getBean(SessionStore.class))
                .isNotInstanceOf(FileSessionStore.class);
    }

    @Test
    @DisplayName("会话文件真的落在配置的目录里")
    void sessionsAreOnDisk() throws Exception {
        var context = boot();
        var browser = new Browser(portOf(context));
        browser.start();
        browser.register("hot2@example.com");

        try (var files = Files.list(workspace.resolve("sessions"))) {
            assertThat(files.toList())
                    .as("会话应落在 session.files 指定的目录")
                    .isNotEmpty();
        }
    }

    // ================= 验收 #3 =================

    @Test
    @DisplayName("★ 验收 #3:容器重建耗时")
    void containerRebuildTime() {
        // 先跑一次把 JIT 与类加载的一次性成本吃掉 —— 真实的开发流里
        // 第一次启动已经发生过了,量第二次才有意义
        boot().close();

        long start = System.nanoTime();
        var context = boot();
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("%n[验收 #3] 容器重建耗时 %d ms%n", millis);

        assertThat(context.isActive()).isTrue();
        assertThat(millis)
                .as("""
                    L3 的目标是 < 2s(docs/15 §1)。这里量的是**容器重建**,
                    它是 L3 的主要成本;真实的 devtools 重启还要加上增量编译,
                    那部分取决于改了多少文件,本测试覆盖不到。
                    实测 %d ms。""".formatted(millis))
                .isLessThan(2000L);
    }

    @Test
    @DisplayName("重启后数据库里的数据还在 —— 它不随容器走")
    void databaseSurvivesRestart() {
        var first = boot();
        var browser = new Browser(portOf(first));
        browser.start();
        browser.register("hot3@example.com");
        first.close();

        var second = boot();
        var fresh = new Browser(portOf(second));
        fresh.start();

        // 用注册时的密码登录 —— 用户记录活过了重启
        var response = fresh.post("/login", Map.of(
                "email", "hot3@example.com", "password", "correct-horse"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(fresh.dashboard().get("email")).isEqualTo("hot3@example.com");
    }

    // ================= 最小 HTTP 客户端 =================

    /**
     * 记 cookie、能换端口的客户端。
     *
     * <p>换端口是这个测试的核心需求:同一个「浏览器」要先访问旧上下文、
     * 再访问新上下文,而两者端口不同(都是 0 → 随机)。
     * 真实场景里端口不变,这里的差别不影响会话是否保持。
     */
    private final class Browser {

        private final RestTemplate rest;
        private final Map<String, String> cookies = new java.util.LinkedHashMap<>();
        private int port;
        private String csrf;

        Browser(int port) {
            this.port = port;
            // 必须禁用重定向跟随,否则看到的是跳转目标的状态码
            var factory = new HttpComponentsClientHttpRequestFactory(
                    org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                            .disableRedirectHandling()
                            .build());
            this.rest = new RestTemplate(factory);
            this.rest.setErrorHandler(
                    new org.springframework.web.client.DefaultResponseErrorHandler() {
                        @Override
                        public boolean hasError(org.springframework.http.client.ClientHttpResponse r) {
                            return false;
                        }
                    });
        }

        void movePort(int newPort) {
            this.port = newPort;
        }

        String sessionCookie() {
            return cookies.get("jaravel_session");
        }

        void start() {
            csrf = (String) get("/").get("csrf_token");
            assertThat(csrf).isNotBlank();
        }

        void register(String email) {
            var response = post("/register", Map.of(
                    "name", "测试", "email", email,
                    "password", "correct-horse",
                    "password_confirmation", "correct-horse"));

            // 校验失败也是 302(回跳并闪存错误),所以只看状态码不够 ——
            // 必须看跳到哪里。这正是上一版测试漏掉的那个坑
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(response.getHeaders().getLocation().getPath())
                    .as("注册应跳到 /dashboard。跳回表单说明校验失败了,"
                      + "错误在闪存里(下一个请求可读)")
                    .isEqualTo("/dashboard");
        }

        Map<String, Object> dashboard() {
            return get("/dashboard");
        }

        /**
         * 按**字符串**读响应,自己取字段。
         *
         * <p>不用 {@code Map.class}:裸 RestTemplate 的 Jackson 转换器只认
         * {@code application/json},而错误页是 HTML —— 于是任何非预期响应
         * 都变成一个 UnknownContentType 异常,看不到真正的响应体。
         * 按字符串读之后,断言失败时能直接看到服务端说了什么。
         */
        Map<String, Object> get(String path) {
            var response = exchange(HttpMethod.GET, path, null, String.class);
            String body = response.getBody() == null ? "" : response.getBody();

            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("GET %s → %s%n%s", path, response.getStatusCode(), body)
                    .isTrue();

            // 登录会轮换 CSRF 令牌,页面重新渲染时取新的
            field(body, "csrf_token").ifPresent(token -> csrf = token);

            var result = new java.util.LinkedHashMap<String, Object>();
            for (String key : new String[] {"email", "name", "csrf_token", "status"}) {
                field(body, key).ifPresent(v -> result.put(key, v));
            }
            return result;
        }

        /** 从 JSON 文本里取一个字符串字段。够用就好。 */
        private java.util.Optional<String> field(String json, String key) {
            // 手工扫描而不是正则:这个模式里的引号与反斜杠要同时在
            // Java 字符串与正则两层转义,写对一次、改一次就错 ——
            // 而它只是个测试辅助,不值得这个风险
            String needle = '"' + key + "\":";
            int at = json.indexOf(needle);
            if (at < 0) return java.util.Optional.empty();

            int i = at + needle.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != '"') {
                return java.util.Optional.empty();      // 不是字符串值
            }

            i++;
            var sb = new StringBuilder();
            while (i < json.length() && json.charAt(i) != '"') {
                char c = json.charAt(i++);
                if (c == '\\' && i < json.length()) {
                    char escaped = json.charAt(i++);
                    sb.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> escaped;
                    });
                } else {
                    sb.append(c);
                }
            }
            return java.util.Optional.of(sb.toString());
        }

        ResponseEntity<String> post(String path, Map<String, String> form) {
            var body = new LinkedMultiValueMap<String, String>();
            form.forEach(body::add);
            if (csrf != null) body.add("_token", csrf);
            return exchange(HttpMethod.POST, path, body, String.class);
        }

        <T> ResponseEntity<T> exchange(HttpMethod method, String path, Object body,
                                       Class<T> type) {
            var headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.TEXT_HTML));
            if (body != null) headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (!cookies.isEmpty()) {
                var jar = new ArrayList<String>();
                cookies.forEach((k, v) -> jar.add(k + "=" + v));
                headers.add(HttpHeaders.COOKIE, String.join("; ", jar));
            }
            if (csrf != null) headers.add("X-CSRF-TOKEN", csrf);

            var response = rest.exchange("http://localhost:" + port + path,
                    method, new HttpEntity<>(body, headers), type);

            var setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (setCookies != null) {
                for (String raw : setCookies) {
                    String pair = raw.split(";", 2)[0];
                    int equals = pair.indexOf('=');
                    if (equals < 0) continue;
                    String name = pair.substring(0, equals).trim();
                    String value = pair.substring(equals + 1).trim();
                    if (value.isEmpty() || raw.contains("Max-Age=0")) cookies.remove(name);
                    else cookies.put(name, value);
                }
            }
            return response;
        }
    }
}
