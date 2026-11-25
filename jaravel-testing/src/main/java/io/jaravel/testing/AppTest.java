package io.jaravel.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.jaravel.auth.Auth;
import io.jaravel.auth.Authenticatable;
import io.jaravel.core.context.ExecutionScope;

/**
 * 容器 + 数据库,不启动 HTTP(docs/14 §5)。
 *
 * <pre>{@code
 * class InventoryServiceTest extends AppTest {
 *
 *     @Autowired InventoryService service;
 *
 *     @Test void reserves_stock() {
 *         service.reserve(sku, 3);
 *         assertDatabaseHas("reservations", Map.of("sku", sku, "quantity", 3));
 *     }
 * }
 * }</pre>
 *
 * <h2>三层测试基类中间的那一层</h2>
 * <table border="1">
 *   <caption>按「启动了什么」分层</caption>
 *   <tr><th>基类</th><th>启动内容</th><th>用途</th></tr>
 *   <tr><td>(无)</td><td>什么都不启动</td><td>纯逻辑单元测试</td></tr>
 *   <tr><td>{@code AppTest}</td><td>容器 + 数据库</td><td>服务层、仓储、作业</td></tr>
 *   <tr><td>{@link FeatureTest}</td><td>容器 + 数据库 + HTTP 内核</td><td>端到端功能</td></tr>
 * </table>
 *
 * <p>它存在的理由不是「更快」,而是**让测试说清自己测什么**。服务层的
 * 测试用 {@code FeatureTest} 写,会得到一个带着 {@code get("/...")} 与
 * 会话断言的 API 面 —— 于是「这个测试到底在验证哪一层」要靠读测试体猜,
 * 而且很容易顺手加一个 HTTP 断言,把一个服务层测试变成端到端测试。
 *
 * <h2>★ 它与 FeatureTest 共用同一个 Spring 上下文</h2>
 * <p>两个基类的 {@code @SpringBootTest} 配置完全一样(都是 MOCK 环境),
 * 于是 Spring 的上下文缓存**命中同一个上下文**,整个测试 JVM 只启动一次。
 *
 * <p>这里刻意**没有**用 {@code webEnvironment = NONE} 换「不建 mock servlet
 * 环境」那点开销:那会得到第二个缓存键,于是一个同时有服务层测试与功能
 * 测试的项目要启动两个上下文。少建一个 mock servlet 环境省下的是毫秒,
 * 多启动一个上下文花掉的是秒 —— 而且框架自己的 web bean(handler mapping、
 * filter 注册)并没有标 {@code @ConditionalOnWebApplication},在 NONE 下
 * 还得逐个处理。
 *
 * <p>所以本类的分层是**API 面**上的,不是启动成本上的:它不给你发请求的
 * 方法。docs/14 §5 的表格里那个「~50ms/测试类」说的是上下文复用之后的
 * 单类成本,不是说它比 {@code FeatureTest} 启动得快。
 *
 * <h2>默认每个测试自动回滚</h2>
 * <p>与 {@code FeatureTest} 一样,见 {@link RollbackExtension};
 * 要换策略用 {@link RefreshDatabase} / {@link DatabaseMigrations}。
 *
 * <h2>有执行作用域</h2>
 * <p>测试方法跑在一个 {@link ExecutionScope} 里(见 {@link ScopeExtension}),
 * 于是 {@code Auth.user()}、Eloquent 的身份映射、作用域内单例的行为与
 * 请求/任务里一致 —— 这是 {@link #actingAs} 能工作的前提。
 */
@SpringBootTest(
        classes = JaravelTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@org.junit.jupiter.api.extension.ExtendWith({
        SpringExtension.class, ScopeExtension.class,
        DatabaseStrategyExtension.class, RollbackExtension.class})
public abstract class AppTest implements DatabaseTestSupport {

    @Autowired(required = false)
    protected io.jaravel.database.Connection connection;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 把静态门面重绑到**这个测试的**上下文(见 {@link StaticBindings})。
     *
     * <p>理由与 {@code FeatureTest} 上同名方法完全一样:门面是 JVM 全局的
     * 静态字段,而一个测试 JVM 里缓存着多个上下文。漏了这一步的症状是
     * 「刚插入的数据查不到」,且只在某个执行顺序下出现。
     */
    @BeforeEach
    void rebindStaticFacades() {
        StaticBindings.bind(applicationContext);
    }

    /** 测试结束后把时间放回去(docs/14 §4.2) */
    @AfterEach
    void restoreClock() {
        io.jaravel.support.Clock.reset();
    }

    // ================= 认证 =================

    /**
     * 以某个用户的身份执行后续代码。
     *
     * <h2>与 FeatureTest 的 actingAs 不是一回事</h2>
     * <p>那个往**会话存储**里写登录态,因为它要让 StartSession 与
     * SessionGuard 按真实路径把用户读出来 —— 那条路径是它要测的东西之一。
     *
     * <p>这里没有请求,也没有会话:直接把用户设进当前作用域的 guard
     * (等价于 {@code Auth.once(...)} 成功之后的状态)。服务层代码读的是
     * {@code Auth.user()} / {@code Auth.id()} / {@code Gate} —— 这三者
     * 都从 guard 取,所以这一步足够。
     */
    public AppTest actingAs(Object user) {
        return actingAs(user, Auth.manager().defaultGuardName());
    }

    public AppTest actingAs(Object user, String guardName) {
        requireScope("actingAs");

        var guard = Auth.guard(guardName);
        Authenticatable principal = user instanceof Authenticatable ready
                ? ready
                : guard.provider().wrap(user);

        guard.setUser(principal);
        return this;
    }

    /*
     * 没有 actingAsGuest()。
     *
     * FeatureTest 需要它,因为会话里可能已经有登录态(它是跨请求存活的,
     * 而一个测试会发多个请求)。这里的 guard 活在**本次测试的作用域**里,
     * 没调过 actingAs 就已经是游客 —— 一个「把没发生过的事撤销掉」的
     * 方法只会让读者以为默认状态是已登录。
     *
     * 测试中途要登出就调 Auth.logout(),那是被测行为本身。
     */

    public void assertAuthenticated() {
        if (currentUser().isEmpty()) {
            throw new AssertionError("""
                    期望已登录,实际是游客。
                      AppTest 里没有会话,登录态只能来自 actingAs(user) ——
                      「先请求 /login」那种写法属于 FeatureTest。""");
        }
    }

    public void assertGuest() {
        currentUser().ifPresent(user -> {
            throw new AssertionError("期望是游客,实际已登录为 " + user.getAuthIdentifier());
        });
    }

    /**
     * 当前用户 —— **没有会话时算游客**,而不是抛异常。
     *
     * <h2>★ 为什么要吞这一个异常</h2>
     * <p>会话型 guard 解析用户的办法是「读会话里的那个 id」,而 AppTest
     * 没有请求也就没有会话,于是 {@code Auth.check()} 在一个本该是
     * 「游客」的测试里抛 {@code OutsideSessionException}。那个异常对
     * {@code assertGuest()} 来说不是错误 —— 它恰好就是「没有登录态」。
     *
     * <p>**只吞这一种**。认证里其他的失败(provider 查库出错、guard 配错)
     * 仍然原样抛出去 —— 把它们也当成「游客」会让一个配置错误表现为
     * 「用户莫名未登录」。
     *
     * <p>⚠ 这里吞的恰好是 {@code SessionGuard} **刻意抛出来**的那个异常
     * (它的注释写着「静默返回访客身份会让权限检查以访客的规则通过」)。
     * 那条理由针对的是**生产代码路径**:队列任务里误用 {@code Auth} 必须炸。
     * 而这是一个断言方法,它的全部作用就是回答「现在有没有登录态」——
     * 对它来说「没有会话」就是答案,不是故障。被测代码自己调
     * {@code Auth.user()} 时仍然会原样撞上那个异常。
     *
     * <p>这里不给 AppTest 造一个合成会话(那样 {@code Session.put} 在
     * 服务层测试里也能跑)。理由:服务层与队列任务里用 {@code Session}
     * 本身就是缺陷,而让它在测试里能跑的代价是上线后在 worker 里炸。
     * AppTest 的会话行为与队列任务一致,这是刻意的。
     */
    private java.util.Optional<Authenticatable> currentUser() {
        try {
            return Auth.user();
        } catch (io.jaravel.auth.guard.SessionGuard.NoSessionException noSession) {
            return java.util.Optional.empty();
        }
    }

    /**
     * 作用域只覆盖测试方法本身(见 {@link ScopeExtension})。
     *
     * <p>在 {@code @BeforeEach} 里调 {@code actingAs} 会设进一个随即丢弃的
     * guard 实例,测试体里 {@code Auth.user()} 读到空 —— 看起来像认证坏了。
     * 所以这里直接拦住,并说清该写在哪。
     */
    private void requireScope(String method) {
        if (!ExecutionScope.isActive()) {
            throw new IllegalStateException("""
                    %s() 只能在测试方法里调用,不能在 @BeforeEach / @BeforeAll 里。
                      执行作用域由 ScopeExtension 在**测试方法**上建立
                      (JUnit 没有跨生命周期回调的绑定点),
                      在它之外设的登录态不会带进测试体。
                      造数据可以放在 @BeforeEach —— 那只需要连接。"""
                    .formatted(method));
        }
    }

    // ================= 数据库断言 =================

    @Override
    public io.jaravel.database.Connection testConnection() {
        if (connection == null) {
            throw new IllegalStateException("""
                    没有数据库连接,无法做数据库断言。
                      检查 spring.datasource.url 是否配置
                      (测试里常用 jdbc:sqlite:file:test?mode=memory&cache=shared ——
                       cache=shared 是必需的,否则连接池里每条连接各看到一个空库)。""");
        }
        return connection;
    }
}
