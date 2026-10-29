package io.jaravel.testing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.jaravel.auth.Auth;
import io.jaravel.auth.Authenticatable;
import io.jaravel.auth.guard.SessionGuard;
import io.jaravel.session.SessionConfig;
import io.jaravel.session.SessionData;
import io.jaravel.session.SessionStore;
import io.jaravel.spring.JaravelHandlerAdapter;
import io.jaravel.spring.JaravelHandlerMapping;

/**
 * 功能测试基类(docs/14 §1)。
 *
 * <pre>{@code
 * class PostControllerTest extends FeatureTest {
 *
 *     @Test void lists_published_posts() {
 *         get("/posts").assertOk().assertSee("第一篇");
 *     }
 *
 *     @Test void creates_a_post() {
 *         actingAs(user)
 *             .post("/posts", Map.of("title", "新文章"))
 *             .assertRedirect("/posts/1")
 *             .assertSessionHas("status");
 *     }
 * }
 * }</pre>
 *
 * <h2>走的是真实管道,只是不经过 Servlet 容器</h2>
 * <p>请求经过**路由匹配 → 中间件链 → 参数绑定 → 控制器 → 异常处理 →
 * 响应写出**的完整路径,与生产一致。做法是用 Spring 的
 * {@code MockHttpServletRequest} 调真正的 {@code JaravelHandlerAdapter}。
 *
 * <p>这一点是本模块存在的主要理由。v0.3 有**六个**缺陷属于
 * 「单元测试全绿、真实运行下完全不工作」:flash 从未落进会话、
 * remember cookie 从未下发、异常路径上会话不写回、容器不构造中间件、
 * Bearer 令牌从未交给 TokenGuard……它们都有覆盖对应类的单元测试,
 * 缺的是**类之间的接线**。只有走完整管道的测试才能发现那一层。
 *
 * <h2>默认每个测试自动回滚</h2>
 * <p>见 {@link RollbackExtension}。测试里不需要写任何清理代码 ——
 * 需要清理的测试写起来麻烦,写起来麻烦的测试就不会被写。
 *
 * <h2>默认带上 CSRF 令牌</h2>
 * <p>POST 到 web 路由会经过 {@code VerifyCsrfToken}。这里**不绕过它**,
 * 而是预先在会话里种一个令牌并随请求带上 —— 于是 CSRF 防护在测试里
 * 也是真的在跑。想测 CSRF 本身用 {@link #withoutCsrfToken()}。
 *
 * <p>Laravel 的做法是在测试环境里直接跳过 CSRF 中间件,代价是
 * 「表单忘了加 @csrf」这类问题测不出来。
 *
 * <h2>测试类放在哪个包都可以</h2>
 * <p>靠 {@link JaravelTestApplication} —— 见那个类的注释。
 * 需要用自己的入口类时在测试类上写
 * {@code @SpringBootTest(classes = MyApp.class)} 覆盖。
 */
@SpringBootTest(
        classes = JaravelTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.junit.jupiter.api.extension.ExtendWith({SpringExtension.class, RollbackExtension.class})
public abstract class FeatureTest {

    /*
     * 请求与断言方法都是 public,不是 protected。
     *
     * 原因是 @Nested 内部类:它是测试类的内部类,但**不是 FeatureTest 的
     * 子类**,所以 Java 的 protected 规则不允许它访问继承来的 protected
     * 成员。而 @Nested 分组是组织测试最常用的方式 ——
     * 用 protected 会让「按场景分组」直接编译不过。
     *
     * 这一条是写第一个使用者(examples/auth)时才发现的:
     * 单看 FeatureTest 自己,protected 显得更「封装」。
     */

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Autowired
    protected JaravelHandlerMapping handlerMapping;

    @Autowired
    protected JaravelHandlerAdapter handlerAdapter;

    @Autowired(required = false)
    protected SessionStore sessionStore;

    @Autowired(required = false)
    protected SessionConfig sessionConfig;

    @Autowired(required = false)
    protected io.jaravel.database.Connection connection;

    // ---------- 下一个请求的状态 ----------

    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final Map<String, Object> sessionSeed = new LinkedHashMap<>();
    private final List<Class<?>> skippedMiddleware = new ArrayList<>();
    private boolean expectsJson;
    private boolean withCsrf = true;
    private String referer;

    /**
     * 每个测试前重置。
     *
     * <p>必须有:这些字段是实例状态,而 JUnit 为每个测试方法新建实例 ——
     * 但 Spring 的 {@code @Autowired} 字段注入会复用同一个
     * TestContext,所以显式清一遍比依赖 JUnit 的实例语义更稳。
     */
    @BeforeEach
    void resetRequestState() {
        headers.clear();
        cookies.clear();
        sessionSeed.clear();
        skippedMiddleware.clear();
        expectsJson = false;
        withCsrf = true;
        referer = null;
    }

    // ================= 请求构造 =================

    public FeatureTest withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public FeatureTest withHeaders(Map<String, String> values) {
        headers.putAll(values);
        return this;
    }

    /** {@code Authorization: Bearer <token>} —— Token / JWT guard 用 */
    public FeatureTest withToken(String token) {
        return withHeader("Authorization", "Bearer " + token);
    }

    public FeatureTest withBasicAuth(String user, String password) {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return withHeader("Authorization", "Basic " + encoded);
    }

    public FeatureTest withCookie(String name, String value) {
        cookies.put(name, value);
        return this;
    }

    /** 预先往会话里种数据。会话 cookie 自动带上。 */
    public FeatureTest withSession(Map<String, Object> values) {
        sessionSeed.putAll(values);
        return this;
    }

    /**
     * 以某个用户的身份发请求(会话型 guard)。
     *
     * <p>做法是**往会话存储里写真正的登录态**,而不是直接给 guard 塞用户 ——
     * 后者会绕过 StartSession 与 SessionGuard 的实际读取路径,
     * 于是「会话写回坏了」这类缺陷在测试里看不见。而那恰好是 v0.3
     * 踩过的坑之一。
     */
    public FeatureTest actingAs(Object user) {
        return actingAs(user, Auth.manager().defaultGuardName());
    }

    public FeatureTest actingAs(Object user, String guardName) {
        var guard = Auth.guard(guardName);
        if (!(guard instanceof SessionGuard session)) {
            throw new IllegalArgumentException("""
                    guard "%s" 不是会话型的,actingAs 对它没有意义。
                      无状态 guard(token / jwt)请用 withToken(...) ——
                      那才是它真正的凭据来源。"""
                    .formatted(guardName));
        }

        Authenticatable principal = user instanceof Authenticatable ready
                ? ready
                : guard.provider().wrap(user);

        sessionSeed.put(session.sessionKey(), principal.getAuthIdentifier());
        return this;
    }

    public FeatureTest actingAsGuest() {
        Auth.manager().registeredGuards().forEach(name -> {
            var guard = Auth.guard(name);
            if (guard instanceof SessionGuard session) {
                sessionSeed.remove(session.sessionKey());
            }
        });
        return this;
    }

    /** 设置 Referer,用于断言「回跳到来源页」 */
    public FeatureTest from(String previousUrl) {
        this.referer = previousUrl;
        return withHeader("Referer", previousUrl);
    }

    /**
     * 不带 CSRF 令牌 —— 用来测 CSRF 防护本身。
     *
     * <p>默认是**带**的(见类注释):绕过 CSRF 会让「表单忘了加 @csrf」
     * 这类问题测不出来。
     */
    public FeatureTest withoutCsrfToken() {
        this.withCsrf = false;
        return this;
    }

    /**
     * 跳过某些中间件。
     *
     * <p>走的是框架自己的按请求排除机制
     * ({@code JaravelHandlerAdapter.EXCLUDED_MIDDLEWARE_ATTRIBUTE}),
     * 不是测试侧的旁路 —— 后者会让「排除机制本身有 bug」测不出来。
     *
     * <p>**不能**通过改 {@code Route.withoutMiddleware} 实现:
     * 中间件链是按路由缓存的,改了在路由被命中过之后就不生效,
     * 症状是「单独跑绿、整体跑红」。
     */
    public FeatureTest withoutMiddleware(Class<?>... types) {
        skippedMiddleware.addAll(List.of(types));
        return this;
    }

    // ================= 发起请求 =================

    public TestResponse get(String uri)      { return call("GET", uri, null); }
    public TestResponse delete(String uri)   { return call("DELETE", uri, null); }
    public TestResponse head(String uri)     { return call("HEAD", uri, null); }

    public TestResponse post(String uri, Map<String, ?> form) {
        return call("POST", uri, form);
    }

    public TestResponse post(String uri) {
        return call("POST", uri, Map.of());
    }

    public TestResponse put(String uri, Map<String, ?> form) {
        return call("PUT", uri, form);
    }

    public TestResponse patch(String uri, Map<String, ?> form) {
        return call("PATCH", uri, form);
    }

    // ---------- JSON 版本 ----------

    public TestResponse getJson(String uri) {
        return asJson().call("GET", uri, null);
    }

    public TestResponse postJson(String uri, Object body) {
        return asJson().callJson("POST", uri, body);
    }

    public TestResponse putJson(String uri, Object body) {
        return asJson().callJson("PUT", uri, body);
    }

    public TestResponse patchJson(String uri, Object body) {
        return asJson().callJson("PATCH", uri, body);
    }

    public TestResponse deleteJson(String uri) {
        return asJson().call("DELETE", uri, null);
    }

    /**
     * 声明这是一个 JSON 客户端。
     *
     * <p>这个开关决定的是**框架返回什么**,不只是测试怎么读:
     * 未认证时浏览器拿 302 到登录页、JSON 客户端拿 401;
     * 邮箱未验证时分别是 302 与 409;校验失败分别是 302 回跳与 422。
     * 两套契约都该被测到。
     */
    public FeatureTest asJson() {
        expectsJson = true;
        return withHeader("Accept", "application/json");
    }

    // ================= 核心 =================

    public TestResponse call(String method, String uri, Map<String, ?> form) {
        var request = baseRequest(method, uri);
        if (form != null) {
            request.setContentType("application/x-www-form-urlencoded");
            form.forEach((k, v) -> request.addParameter(k, String.valueOf(v)));
        }
        return execute(request);
    }

    public TestResponse callJson(String method, String uri, Object body) {
        var request = baseRequest(method, uri);
        request.setContentType("application/json");
        try {
            request.setContent(JSON.writeValueAsBytes(body == null ? Map.of() : body));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法把请求体序列化成 JSON:" + e.getMessage(), e);
        }
        return execute(request);
    }

    private MockHttpServletRequest baseRequest(String method, String uri) {
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);

        var request = new MockHttpServletRequest(method, path);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        if (query >= 0) request.setQueryString(uri.substring(query + 1));

        if (!expectsJson) {
            // 不含 "/json" —— Request.wantsJson() 按这个判断,
            // 于是默认拿到的是「浏览器」那套契约
            request.addHeader("Accept", "text/html");
        }
        headers.forEach(request::addHeader);

        seedSession(request);

        if (!cookies.isEmpty()) {
            request.setCookies(cookies.entrySet().stream()
                    .map(e -> new Cookie(e.getKey(), e.getValue()))
                    .toArray(Cookie[]::new));
        }
        return request;
    }

    /**
     * 把 {@link #sessionSeed} 写进会话存储,并带上对应的 cookie。
     *
     * <p>**用真正的存储**,所以 StartSession 会按正常路径把它读出来。
     * 直接往 ExecutionScope 里塞 SessionData 会绕过 load/persist,
     * 那正是最容易出问题的两段代码。
     */
    private void seedSession(MockHttpServletRequest request) {
        boolean needsSession = !sessionSeed.isEmpty() || withCsrf;
        if (!needsSession || sessionStore == null || sessionConfig == null) return;

        var session = new SessionData(SessionData.generateId());
        session.replaceAll(new LinkedHashMap<>(sessionSeed));

        // token() 会在缺失时生成一个并写进会话 —— 与真实请求里
        // 模板读 @csrf 时发生的事完全一样
        String token = session.token();

        sessionStore.write(session.id(), session.raw(), sessionConfig.lifetime());
        cookies.put(sessionConfig.cookieName(), session.id());

        if (withCsrf) request.addHeader("X-CSRF-TOKEN", token);
    }

    private TestResponse execute(MockHttpServletRequest request) {
        var response = new MockHttpServletResponse();

        var chain = handlerMapping.getHandler(request);
        if (chain == null) {
            throw new AssertionError("""
                    没有路由匹配 %s %s。
                      已注册的路由:
                    %s"""
                    .formatted(request.getMethod(), request.getRequestURI(),
                               routeList()));
        }

        var handler = (JaravelHandlerMapping.Handler) chain.getHandler();

        if (!skippedMiddleware.isEmpty()) {
            // 请求属性 —— 随请求消失,不会泄漏给后面的测试
            request.setAttribute(JaravelHandlerAdapter.EXCLUDED_MIDDLEWARE_ATTRIBUTE,
                    skippedMiddleware.stream().map(Class::getSimpleName)
                            .collect(java.util.stream.Collectors.toSet()));
        }

        try {
            handlerAdapter.handle(request, response, handler);
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }

        // 最终的 Jaravel Response —— 适配器挂在请求属性上。
        // 视图名与视图数据在序列化成字节之后就没了,必须从这里取
        var captured = (io.jaravel.http.Response) request.getAttribute(
                JaravelHandlerAdapter.RESPONSE_ATTRIBUTE);

        String content;
        try {
            content = response.getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return new TestResponse(response, content, readBackSession(response), captured);
    }

    /**
     * 请求结束后的会话 —— 从**存储里重新读**。
     *
     * <p>不复用种进去的那个对象:要断言的恰好是「框架把会话写回去了吗」。
     * 读内存里那一份会让「异常路径上不写回」这类缺陷完全隐形,
     * 而那是 v0.3 的真实缺陷之一。
     */
    private SessionData readBackSession(MockHttpServletResponse response) {
        if (sessionStore == null || sessionConfig == null) return null;

        String id = sessionIdFrom(response);
        if (id == null) return null;

        return sessionStore.read(id).map(data -> {
            var session = new SessionData(id);
            session.replaceAll(data);
            return session;
        }).orElse(null);
    }

    /**
     * 会话 id:优先响应新下发的 cookie。
     *
     * <p>登录与登出都会**轮换会话 id**(防会话固定)。用请求里那个旧 id
     * 去读会读到一个空会话,于是 assertSessionHas 全部失败 ——
     * 而原因与被测代码无关。
     */
    private String sessionIdFrom(MockHttpServletResponse response) {
        String name = sessionConfig.cookieName();
        for (String header : response.getHeaders("Set-Cookie")) {
            if (!header.startsWith(name + "=")) continue;
            String pair = header.split(";", 2)[0];
            String value = pair.substring(pair.indexOf('=') + 1);
            if (!value.isEmpty()) return value;
        }
        return cookies.get(name);
    }

    private String routeList() {
        return handlerMapping.routes().all().stream()
                .map(r -> "      " + r.method() + " " + r.uri())
                .sorted()
                .reduce((a, b) -> a + "\n" + b)
                .orElse("      (没有任何路由)");
    }

    // ================= 数据库断言 =================

    public void assertDatabaseHas(String table, Map<String, ?> attributes) {
        DatabaseAssertions.has(requireConnection(), table, attributes);
    }

    public void assertDatabaseMissing(String table, Map<String, ?> attributes) {
        DatabaseAssertions.missing(requireConnection(), table, attributes);
    }

    public void assertDatabaseCount(String table, long expected) {
        DatabaseAssertions.count(requireConnection(), table, expected);
    }

    public void assertDatabaseEmpty(String table) {
        DatabaseAssertions.count(requireConnection(), table, 0);
    }

    public void assertSoftDeleted(io.jaravel.eloquent.Model<?> model) {
        DatabaseAssertions.softDeleted(requireConnection(), model, true);
    }

    public void assertNotSoftDeleted(io.jaravel.eloquent.Model<?> model) {
        DatabaseAssertions.softDeleted(requireConnection(), model, false);
    }

    public void assertModelExists(io.jaravel.eloquent.Model<?> model) {
        DatabaseAssertions.modelExists(requireConnection(), model, true);
    }

    public void assertModelMissing(io.jaravel.eloquent.Model<?> model) {
        DatabaseAssertions.modelExists(requireConnection(), model, false);
    }

    /**
     * 断言这段代码只发了 N 条 SQL。
     *
     * <p>这是对 Laravel 的增强:**把 N+1 变成会让 CI 失败的回归测试**,
     * 而不是靠人工 review 或上线后的数据库告警。
     */
    public void assertQueryCount(int expected, Runnable body) {
        Queries.assertCount(requireConnection(), expected, body);
    }

    protected <T> T countingQueries(java.util.function.Supplier<T> body,
                                    java.util.function.IntConsumer report) {
        return Queries.counting(requireConnection(), body, report);
    }

    private io.jaravel.database.Connection requireConnection() {
        if (connection == null) {
            throw new IllegalStateException("""
                    没有数据库连接,无法做数据库断言。
                      检查 spring.datasource.url 是否配置
                      (测试里常用 jdbc:sqlite:file:test?mode=memory&cache=shared ——
                       cache=shared 是必需的,否则连接池里每条连接各看到一个空库)。""");
        }
        return connection;
    }

    // ================= 认证断言 =================

    public void assertAuthenticated() {
        if (!Auth.check()) {
            throw new AssertionError("期望已登录,实际是游客");
        }
    }

    public void assertGuest() {
        if (Auth.check()) {
            throw new AssertionError("期望是游客,实际已登录为 " + Auth.id().orElse(null));
        }
    }
}
