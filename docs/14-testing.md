# 14 · 测试

Laravel 的测试体验是其被低估的优势:一行 `$this->get('/users')->assertStatus(200)` 就是一个完整的 HTTP 功能测试。Java 侧的等价物(`MockMvc` / `TestRestTemplate` / `@SpringBootTest`)样板多且概念重。本模块的目标是把测试的书写成本降到 Laravel 水平。

---

## 设计目标

1. 一个功能测试 ≤ 5 行;
2. 数据库测试默认隔离(每个测试自动回滚),不需要用户管理清理;
3. 所有有副作用的门面都有假实现与断言 API;
4. 模型工厂让「准备测试数据」这件事成本接近零;
5. 底层仍是 JUnit 5 —— 不发明测试框架,只提供基类与断言。

---

## 1. 功能测试(HTTP)

```java
class PostControllerTest extends FeatureTest {

    @Test void guests_cannot_see_drafts() {
        get("/posts/" + draft.getId())
            .assertRedirect("/login");
    }

    @Test void lists_published_posts() {
        Post.factory().count(3).published().create();
        Post.factory().draft().create();

        get("/posts")
            .assertOk()
            .assertViewIs("posts.index")
            .assertViewHas("posts", p -> p.size() == 3)
            .assertSee("第一篇")
            .assertDontSee("草稿");
    }

    @Test void creates_a_post() {
        actingAs(user)
            .post("/posts", Map.of("title", "新文章", "body", "内容"))
            .assertRedirect("/posts/1")
            .assertSessionHas("status", "created");

        assertDatabaseHas("posts", Map.of("title", "新文章", "user_id", user.getId()));
        assertDatabaseCount("posts", 1);
    }

    @Test void validates_input() {
        actingAs(user)
            .post("/posts", Map.of("title", ""))
            .assertStatus(422)
            .assertJsonValidationErrors("title", "body");

        assertDatabaseEmpty("posts");
    }

    @Test void api_returns_resource_shape() {
        actingAs(user, "api")
            .getJson("/api/v1/posts/" + post.getId())
            .assertOk()
            .assertJsonPath("data.id", post.getId())
            .assertJsonPath("data.author.name", user.getName())
            .assertJsonStructure("data", List.of("id", "title", "body", "author"))
            .assertJsonMissing("data.internal_note")
            .assertJsonCount(3, "data.tags");
    }

    @Test void authorization_is_enforced() {
        actingAs(otherUser)
            .put("/posts/" + post.getId(), Map.of("title", "改"))
            .assertForbidden();
    }
}
```

### 1.1 请求 API

```java
get(uri) · getJson(uri) · post(uri, data) · postJson(uri, data)
put · putJson · patch · patchJson · delete · deleteJson · head · options
call(method, uri, data)

withHeaders(Map) · withHeader(k, v) · withToken(token) · withBasicAuth(u, p)
withSession(Map) · withCookie(k, v) · withoutMiddleware() · withoutMiddleware(X.class)
actingAs(user) · actingAs(user, "api") · actingAsGuest()
followingRedirects() · withoutExceptionHandling()
from(previousUrl)                      // 设置 referer,用于 assertRedirect(back)
withFile("avatar", UploadedFile.fake().image("a.png", 100, 100))
```

### 1.2 断言 API

```java
// 状态
assertOk() · assertCreated() · assertNoContent() · assertNotFound()
assertForbidden() · assertUnauthorized() · assertStatus(code)
assertRedirect(uri) · assertRedirectToRoute("posts.show", id) · assertSuccessful()

// 内容
assertSee(text) · assertSeeInOrder(List) · assertDontSee(text)
assertSeeText(text)                    // 剥离 HTML 标签后比对
assertHeader(k, v) · assertCookie(k, v) · assertCookieExpired(k)

// JSON
assertJson(Map) · assertExactJson(Map) · assertJsonPath(path, value)
assertJsonStructure(Map) · assertJsonCount(n, path)
assertJsonFragment(Map) · assertJsonMissing(path)
assertJsonValidationErrors(fields...) · assertJsonMissingValidationErrors()

// 视图
assertViewIs(name) · assertViewHas(key) · assertViewHas(key, predicate)
assertViewMissing(key)

// Session
assertSessionHas(k) · assertSessionHas(k, v) · assertSessionHasErrors(fields)
assertSessionHasNoErrors() · assertSessionMissing(k)

// 认证
assertAuthenticated() · assertAuthenticatedAs(user) · assertGuest()
```

### 1.3 实现

`FeatureTest` 不走真实网络,直接调用 `JaravelHandlerAdapter`:

```java
public abstract class FeatureTest {
    protected TestResponse get(String uri) {
        return call("GET", uri, null);
    }
    protected TestResponse call(String method, String uri, Object body) {
        var req = new TestRequest(method, uri, body, headers, session, actingUser);
        var resp = app.make(HttpKernel.class).handle(req);     // 完整走中间件管道
        return new TestResponse(resp);
    }
}
```

**完整经过中间件管道、路由匹配、参数绑定、异常处理** —— 与真实请求路径一致,只是不经过 Servlet 容器。这让测试既快(无网络、无 Tomcat)又真实。

需要真实 HTTP 的场景(测试 Servlet Filter、CORS 预检)用 `@HttpServerTest` 启动真实端口。

---

## 2. 数据库测试

### 2.1 隔离策略

```java
class PostTest extends FeatureTest {
    // 默认:每个测试方法在事务中执行,结束回滚
}

@RefreshDatabase            // 每个测试前 migrate:fresh(慢,用于迁移本身的测试)
@DatabaseTransactions       // 默认,显式声明
@DatabaseMigrations         // 每个测试前 migrate,后 rollback
@WithoutDatabase            // 不连数据库(纯单元测试)
```

事务回滚是默认策略——**测试不需要写任何清理代码**。

### 2.2 测试数据库

```yaml
# config/database.yml(testing profile)
database:
  default: testing
  connections:
    testing:
      driver: sqlite
      database: ":memory:"
```

或用 Testcontainers(更真实):

```java
@Testcontainers
class PostTest extends FeatureTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    // starter 自动把容器的连接信息注入配置
}
```

默认推荐 **Testcontainers + 真实数据库**(SQLite 与 MySQL 的方言差异会让测试通过但生产失败),同时提供 SQLite 内存模式用于需要极致速度的场景。`jaravel new` 生成的项目默认用 Testcontainers。

### 2.3 断言

```java
assertDatabaseHas("posts", Map.of("title", "X"));
assertDatabaseMissing("posts", Map.of("title", "X"));
assertDatabaseCount("posts", 3);
assertDatabaseEmpty("posts");
assertSoftDeleted(post);
assertNotSoftDeleted(post);
assertModelExists(post);
assertModelMissing(post);

// 查询数量断言(N+1 防御的测试化)
assertQueryCount(2, () -> controller.index());
assertNoLazyLoading(() -> controller.index());
```

`assertQueryCount` 是对 Laravel 的增强:**把 N+1 变成会让 CI 失败的回归测试**,而不是靠人工 review。

---

## 3. 模型工厂

```java
public class PostFactory extends Factory<Post> {

    @Override public Map<String, Object> definition() {
        return Map.of(
            "title",   faker.book().title(),
            "body",    faker.lorem().paragraphs(3),
            "user_id", User.factory(),                 // 嵌套工厂,自动创建关联
            "status",  "published"
        );
    }

    public PostFactory draft()     { return state(Map.of("status", "draft", "published_at", null)); }
    public PostFactory published() { return state(Map.of("status", "published", "published_at", Instant.now())); }
    public PostFactory byAuthor(User u) { return state(Map.of("user_id", u.getId())); }

    /** 创建后的钩子 */
    @Override public void afterCreating(Post post) {
        post.tags().attach(Tag.factory().create().getId());
    }
}
```

```java
// 使用
Post p = Post.factory().create();
Post p2 = Post.factory().draft().create();
Post p3 = Post.factory().create(Map.of("title", "指定标题"));
Coll<Post> many = Post.factory().count(10).create();
Post unsaved = Post.factory().make();                       // 不写库
Coll<Post> seq = Post.factory().count(3)
    .sequence(Map.of("status", "draft"), Map.of("status", "published")).create();

// 关联
User u = User.factory().hasPosts(3).create();
User u2 = User.factory().has(Post.factory().count(3).draft()).create();
Post p4 = Post.factory().for_(User.factory().admin()).create();
Post p5 = Post.factory().hasAttached(Tag.factory().count(2), Map.of("primary", true)).create();
```

工厂由 `jaravel make:factory` 生成骨架,`@Model` 的 APT 生成 `Post.factory()` 静态方法与类型安全的 `hasXxx()` 关联方法。

Faker 用 `net.datafaker:datafaker`,支持 `locale: zh_CN`。

---

## 4. 假实现(Fake)

```java
@Test void checkout_dispatches_jobs_and_sends_mail() {
    var queue = Queue.fake();
    var mail  = Mail.fake();
    var event = Event.fake();
    var disk  = Storage.fake("s3");
    var notif = Notification.fake();
    Clock.freeze(Instant.parse("2026-10-28T10:00:00Z"));

    service.checkout(order);

    queue.assertPushed(SendInvoice.class, j -> j.orderId().equals(order.getId()));
    queue.assertPushedOnQueue("mail", SendInvoice.class);
    queue.assertNotPushed(RefundJob.class);
    queue.assertPushedCount(1);
    queue.assertChained(List.of(ProcessPayment.class, SendInvoice.class));
    queue.assertBatched(b -> b.jobs().size() == 3);

    mail.assertSent(OrderConfirmation.class, m -> m.hasTo(order.email()));
    mail.assertQueued(OrderConfirmation.class);

    event.assertDispatched(OrderPaid.class);
    event.assertNotDispatched(OrderFailed.class);
    event.assertListening(OrderPaid.class, NotifyWarehouse.class);

    disk.assertExists("invoices/" + order.getId() + ".pdf");
    notif.assertSentTo(order.customer(), InvoicePaid.class);
}
```

### 4.1 部分假实现

```java
Queue.fake(List.of(SendInvoice.class));   // 只假装这一个,其他真实执行
Event.fakeExcept(List.of(OrderPaid.class));
```

### 4.2 时间控制

```java
Clock.freeze();
Clock.freeze(Instant.parse("2026-01-01T00:00:00Z"));
Clock.travel(Duration.ofDays(7));
Clock.travelTo(instant);
Clock.withFrozen(instant, () -> { ... });
Clock.reset();                            // 由 JUnit 扩展自动调用
```

所有框架内部的时间读取一律走 `Clock` 门面(而非 `Instant.now()`),否则时间旅行无效。**这是一条架构约束**,需要 ArchUnit 规则强制:

```java
@ArchTest static final ArchRule no_direct_now =
    noClasses().that().resideInAPackage("io.jaravel..")
        .should().callMethod(Instant.class, "now")
        .orShould().callMethod(LocalDateTime.class, "now");
```

### 4.3 HTTP 客户端假实现

```java
Http.fake(Map.of(
    "https://api.supplier.com/*", Http.response(Map.of("items", List.of()), 200),
    "https://api.slow.com/*",     Http.failedConnection()
));

service.sync();

Http.assertSent(r -> r.url().contains("/items") && r.hasHeader("X-Key"));
Http.assertSentCount(1);
Http.assertNothingSent();
```

`Http` 是 Jaravel 提供的 HTTP 客户端门面(包装 `java.net.http.HttpClient`),对应 Laravel 的 `Http` facade。应在 v0.4 提供——外部 API 调用是现代应用的常见需求,且 `RestTemplate`/`WebClient` 的样板较重。

---

## 5. 单元测试

```java
class PriceCalculatorTest {        // 不继承 FeatureTest,无容器,极快
    @Test void applies_discount() {
        var calc = new PriceCalculator(new FlatDiscount(10));
        assertEquals(new BigDecimal("90.00"), calc.total(new BigDecimal("100.00")));
    }
}
```

需要容器但不需要 HTTP:

```java
class InventoryServiceTest extends AppTest {     // 启动容器,不启动 HTTP
    @Inject InventoryService service;
    @Test void reserves_stock() { ... }
}
```

三层测试基类:

| 基类 | 启动内容 | 速度 | 用途 |
|---|---|---|---|
| (无) | 什么都不启动 | ~1ms | 纯逻辑单元测试 |
| `AppTest` | 容器 + 数据库 | ~50ms/测试类 | 服务层、仓储、作业 |
| `FeatureTest` | 容器 + 数据库 + HTTP 内核 | ~60ms/测试类 | 端到端功能 |

---

## 6. 其他

### 6.1 浏览器测试(v0.8)

```java
class LoginTest extends BrowserTest {
    @Test void user_can_login() {
        browse(b -> b.visit("/login")
                     .type("email", "a@b.com")
                     .type("password", "secret")
                     .press("登录")
                     .assertPathIs("/dashboard")
                     .assertSee("欢迎"));
    }
}
```

基于 Playwright for Java(对应 Laravel Dusk)。列入 v0.8。

### 6.2 架构测试

```java
class ArchitectureTest extends ArchTest {
    @Test void controllers_dont_access_db_directly() {
        expect(classes().in("..Http.Controllers.."))
            .not().toUse(DB.class);
    }
    @Test void models_are_in_models_package() {
        expect(classes().annotatedWith(Model.class)).toResideIn("..models..");
    }
}
```

薄封装 ArchUnit,提供 Jaravel 项目的常用规则预设(对应 Pest 的 arch 测试)。

### 6.3 并行测试

```bash
jaravel test --parallel --processes=4
```

每个进程用独立的测试数据库(`testing_1`…`testing_4`),自动创建与迁移。JUnit 5 的并行执行 + 数据库隔离。

### 6.4 覆盖率

```bash
jaravel test --coverage --min=70         # JaCoCo,低于阈值则失败
```

---

## 7. 内部架构

```
jaravel-testing
├── bases/      FeatureTest · AppTest · BrowserTest · ArchTest
├── http/       TestRequest · TestResponse(全部断言)· TestKernel
├── database/   TransactionalExtension · RefreshDatabaseExtension
│                · DatabaseAssertions · TestcontainersSupport
├── factories/  Factory<M> · Sequence · RelationshipBuilder · FakerProvider
├── fakes/      QueueFake · MailFake · EventFake · NotificationFake
│                · StorageFake · CacheFake · HttpFake · BusFake
├── time/       TestClock
├── junit/      JaravelExtension(容器生命周期、fake 清理、clock 重置)
└── assertions/ 自定义 AssertJ 断言(assertThat(user).hasRole("admin"))
```

### 7.1 JUnit 扩展的职责

```java
public class JaravelExtension implements BeforeAllCallback, BeforeEachCallback,
                                         AfterEachCallback, ParameterResolver {
    // beforeAll:  启动/复用应用容器(整个测试 JVM 共享一个,除非 @RefreshDatabase)
    // beforeEach: 开启事务、重置 RequestScope、安装配置覆盖
    // afterEach:  回滚事务、清理所有 Facade.swap、Clock.reset()、清空 fake 记录
}
```

容器在整个测试 JVM 中**只启动一次**(类比 Spring 的 context caching),这是测试速度的关键。配置覆盖(`@WithConfig("cache.default=array")`)不触发容器重建,而是在请求作用域内覆盖。

---

## 8. 逃生口

| 需求 | 途径 |
|---|---|
| 需要 `@SpringBootTest` | 照常使用,与 `FeatureTest` 并存 |
| 需要 `MockMvc` | `FeatureTest` 中注入即可 |
| 需要 Mockito | 照常使用;`Container.instance(X.class, mock)` 注入 mock |
| 需要真实 HTTP 端口 | `@HttpServerTest` |
| 需要自定义断言 | `TestResponse#raw()` 取原始响应 |

---

## 9. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| 测试框架 | PHPUnit / Pest | JUnit 5 | 生态标准 |
| 数据库 | SQLite 内存常见 | **推荐 Testcontainers** | 避免方言差异导致的假通过 |
| `assertQueryCount` | 无 | 有 | N+1 的回归防护 |
| `assertNoLazyLoading` | 无 | 有 | 同上 |
| 容器启动 | 每测试重建(PHP 无状态) | 整个 JVM 复用一次 | 速度 |
| 架构测试 | Pest 插件 | 内置 ArchUnit 封装 | Java 生态更需要边界约束 |
| 浏览器测试 | Dusk(Selenium) | Playwright | 更现代、更快 |

---

## 10. 待决问题

- [ ] `FeatureTest` 不经过 Servlet 容器,会漏掉哪些 bug?(multipart 解析差异、字符编码、Filter 行为)。需要一组「双跑」测试:同一断言分别在 `FeatureTest` 和 `@HttpServerTest` 下执行,验证两条路径等价。若差异大,可能需要默认走真实容器。
- [ ] Testcontainers 作为默认会增加首次测试的耗时(拉镜像)与 CI 对 Docker 的依赖。是否默认 SQLite、文档强烈推荐 Testcontainers?倾向:`jaravel new` 询问,默认 Testcontainers,`--preset=minimal` 用 SQLite。
- [ ] 并行测试的数据库隔离:4 个进程 × `migrate:fresh` 很慢。方案:一次建好模板库,各进程 `create database X template Y`(PG 支持)或 dump/restore(MySQL)。需要实现。
- [ ] 测试中的 `ScopedValue` 绑定:`AppTest` 没有 HTTP 请求,但 `Auth.user()` 需要作用域。需要 `actingAs` 在非 HTTP 测试中也能建立一个合成作用域。
- [ ] 工厂的 `definition()` 返回 `Map<String,Object>` 缺少类型安全。考虑生成 `PostFactory` 的类型化 setter(`.title("X").status(PUBLISHED)`)。倾向 v0.6 补充。
