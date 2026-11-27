# 14 · 测试

Laravel 的测试体验是其被低估的优势:一行 `$this->get('/users')->assertStatus(200)` 就是一个完整的 HTTP 功能测试。Java 侧的等价物(`MockMvc` / `TestRestTemplate` / `@SpringBootTest`)样板多且概念重。本模块的目标是把测试的书写成本降到 Laravel 水平。

---

## 实现状态(v0.3 之后提前交付)

本模块原计划在 v0.5。**提前到 v0.4 之前**,理由是 v0.3 有 6 个缺陷属于
「单元测试全绿、真实运行下完全不工作」—— 它们都有覆盖对应类的单元测试,
缺的是类之间的接线,而只有走完整管道的测试能发现那一层。
队列(v0.4)会让这个失效模式更严重:任务跑在请求作用域之外、
有自己的序列化与重试,而且**失败是静默的**(进 failed_jobs 表,没人看着浏览器)。

| 能力 | 状态 |
|---|---|
| `FeatureTest` + 全部请求构造 API | ✅ |
| `TestResponse` 的状态/内容/Cookie/JSON/视图/会话断言 | ✅(25 条自测) |
| 数据库断言 + 每测试自动回滚 | ✅ |
| `assertQueryCount`(N+1 回归测试) | ✅ |
| 认证断言 `assertAuthenticated` / `assertGuest` | ✅ |
| `BrowserTest` + Playwright 真实浏览器测试 | ✅ v0.8(默认关闭,见 §6.1) |
| Testcontainers 自动注入(`@UseDatabase`) | ✅ v0.8(默认关闭,见 §2.2) |
| 并行测试(`jaravel test --parallel` + 每进程测试库) | ✅ v0.8(见 §6.3) |
| 模型工厂(§3) | ✅ `@UseFactory` → 类型化 setter + 关联构建器 + `Post.factory()`;`make:factory` 生成骨架(`datafaker` 不做) |
| Fake(§4) | ✅ Mail / Event / Storage / Notifications / Broadcast / Http / Queue / Cache + `Clock` 时间控制 + `UploadedFile.fake()`;`Event.fakeExcept` 不提供(用 `Event.fake(types)`) |
| 隔离策略注解 `@DatabaseTransactions` / `@RefreshDatabase` / `@DatabaseMigrations` | ✅(`@WithoutDatabase` 不做,理由见 §2.1) |
| `AppTest`(容器 + 数据库,无 HTTP,§5) | ✅(与 `FeatureTest` 共用同一个上下文,见 §5) |

### 写出来之后发现的三件事

都是只有**真的用它写测试**才会暴露的:

1. **API 必须是 `public` 而不是 `protected`。** `@Nested` 内部类不是
   `FeatureTest` 的子类,Java 的 protected 规则不允许它访问继承来的
   protected 成员 —— 而 `@Nested` 分组是组织测试最常用的方式,
   用 protected 会让「按场景分组」直接编译不过。
2. **测试类不能依赖「向上搜 `@SpringBootConfiguration`」。**
   Jaravel 的约定把应用放 `app` 包,测试自然写在 `apptest` 之类的兄弟包里,
   而 Spring 只向上搜。为此让用户调整包结构是错的 ——
   改成由 `JaravelTestApplication` 引导,测试类放哪个包都行。
3. **`withoutMiddleware` 不能改 `Route`。** 中间件链是**按路由缓存**的
   且永不失效,改了在路由被命中过之后就不生效 ——
   症状是「单独跑绿、整体跑红」。改走按请求的排除属性。

### 一个对照数字

`examples/auth` 里两套测试覆盖同样的 31 个场景:

| | 有效行数 | 说明 |
|---|---|---|
| `AuthFlowTest` | 617 | 手写真 HTTP 客户端(140 行的 Browser 类、cookie 罐、CSRF 刷新、重定向控制) |
| `AuthFeatureTest` | 352 | 用 `FeatureTest` |

两套都保留:`AuthFlowTest` 额外覆盖真实 Servlet 容器那一层
(cookie 属性的实际序列化、容器的字符编码),而那一层也确实出过问题。

---

### v0.5:示例进了构建,因为它们正在静默腐坏

`examples/{todo,blog,auth,queue}` 此前**不在 reactor 里**。后果是实测的:

- `examples/blog` 与 `examples/todo` 按提交的样子**起不来** ——
  auth 模块(v0.3)把 `app.key` 做成启动即校验的饿加载 bean,
  而这两个示例写在那之前、配置里没有那一行。
  没有任何一条测试会发现,因为**没有任何东西跑它们**。
- 而 `examples/auth` 与 `examples/queue` 各自带着真实的验收测试
  (91 条 + 一批)—— 同样不被跑。也就是说这里本来就躺着一批
  已经写好的测试在白白腐坏。

区别只在一件事上:**有没有测试**。坏掉的那两个一条都没有。

所以:四个示例都成了 reactor 的模块,并给 blog / todo 各补了一条
最低限度的烟雾测试(真容器装配 + 真路由回 200)。那条测试的牙验过 ——
把 `app.key` 那行拿掉它就红。

**为什么必须进 reactor,而不是写个脚本跑它们**:reactor 里解析的是
**当前这棵树**的 artifact;脚本跑的是本地仓库里上一次 `install` 的那份。
后者会让烟雾测试在框架已经坏掉时照样通过 —— 那比没有测试更糟。

补充一条(同一轮的后续):`examples/queue` 那三条自定义 artisan 命令
也补了验收测试(13 条)。它们除了会被照抄,还**是 parity 切片 05 的
度量对象** —— 坏了而没人知道的话,门禁会拿一份不能跑的代码给出一个
好看的比值,那比没有门禁更糟。

代价:全量 `mvn -T1C test` 现在约 70 秒,四个示例各要起一次真的
Spring 上下文。

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
@DatabaseMigrations         // 每个测试前 migrate,后 reset
```

事务回滚是默认策略——**测试不需要写任何清理代码**。

三个注解标在类上或方法上都行,**方法压过类**(于是「整类 fresh,只有这个
不用」不必另开一个类);`@Nested` 的外层类与自己写的测试基类上标了也算。
同一层标了两个会在测试开始前报错,而不是让其中一个静默胜出。

**★ 两个迁移型策略刻意不在事务里。** DDL 在一部分数据库里不参与事务
(MySQL 会隐式提交),而迁移测试要断言的恰恰是「表真的建出来了」——
在一个永不提交的事务里断言这件事,断言的是幻觉。于是数据是真写进去的,
清理靠下一个测试前那次 fresh(`@RefreshDatabase`)或这个测试后那次 reset
(`@DatabaseMigrations`)。

> **`@WithoutDatabase` 不做。** 它要表达的是「纯单元测试」,而那在 Jaravel
> 里已经有更直接的写法:**不继承任何基类**(§5 的第一层,~1ms,连 Spring
> 上下文都不启动)。做成注解反而更差 —— 框架的 `Connection` bean 在没有
> `DataSource` 时是启动即失败的(那个设计是对的:缺配置要在启动时暴露),
> 于是「有上下文但没有库」需要每个子系统都补一条无库降级路径,换来的
> 只是把一个本来更快的写法包装成注解。登记在 `KNOWN-ISSUES.md`。

### 2.2 测试数据库:Testcontainers 自动注入 ✅ v0.8

```java
@UseDatabase(UseDatabase.Kind.MYSQL)
class OrderRepositoryTest extends FeatureTest {
    @Test void reserves_stock() { … }
}
```

一个注解做四件事:

1. 启动(或复用)本 JVM 内同 `(kind, image)` 的容器;
2. 注入 `spring.datasource.url / username / password / driver-class-name`;
3. 把 `database.default` 改成对应方言 —— **只换 URL 不换 Grammar**
   会得到「MySQL 上跑 SQLite 语法」;
4. 打开 `jaravel.migrate.on-boot`,让空库在上下文启动时迁移。

支持 MySQL / PostgreSQL / MariaDB。默认镜像 `mysql:8.0` / `postgres:16-alpine` /
`mariadb:11.4`,可用 `@UseDatabase(value = ..., image = "mysql:8.4")` 覆盖。

**依赖**:Testcontainers 对 `jaravel-testing` 是 optional,要用它的应用在自己的
`test` 作用域声明容器模块和 JDBC 驱动:

```xml
<dependency>
  <groupId>org.testcontainers</groupId><artifactId>mysql</artifactId>
  <version>1.21.4</version><scope>test</scope>
</dependency>
<dependency>
  <groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>test</scope>
</dependency>
```

PostgreSQL 换成 `org.testcontainers:postgresql` + `org.postgresql:postgresql`;
MariaDB 换成 `org.testcontainers:mariadb` + `org.mariadb.jdbc:mariadb-java-client`。

**默认关闭**,和浏览器测试同一个理由:默认套件不该拉镜像。

```bash
mvn test -Djaravel.testcontainers=true          # 或 JARAVEL_TESTCONTAINERS=true
```

显式开启但 Docker 不可用时**失败**,而不是静默退回 SQLite/内存 —— 那会让
方言差异重新漏过去。需要在没有 Docker 的机器上跳过,用
`@EnabledIf("dockerAvailable")` 并调用 `TestcontainersSupport.dockerAvailable()`。

**版本**:本项目钉 Testcontainers **1.21.4**。Spring Boot 3.5.16 的 BOM 已管理
1.21.4;显式钉住是为了不随 BOM 回退(1.20.x 连不上 Docker Engine 29)。
`TestcontainersSupport` 仍会在第一次连 Docker 前把 `api.version` 设成 `1.44` ——
不依赖具体补丁版本的默认行为。更老的 Docker 可用
`-Dapi.version=...` / `-Djaravel.testcontainers.api-version=...` 覆盖。

**与并行测试**:每个 fork 是独立 JVM,各自起自己的容器;`--processes=4` 就是
最多 4 个容器,成本要算。测试显式指定 `spring.datasource.url` 时,自动注入会让位
(那种测试自己管理隔离)。

**什么时候用 SQLite**:纯单元测试与不依赖方言的功能测试仍然用内存 SQLite ——
它快,而且每个 JVM 天然隔离。Testcontainers 用在方言敏感的地方(迁移、复杂
查询、JSON/时间类型、索引与锁)。

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
@Model
@UseFactory(PostFactory.class)        // ← 这一行决定 APT 生成什么
public class Post extends PostBase { ... }
```

```java
public class PostFactory extends PostFactoryBase {

    @Override
    protected void definition(Faker f, Post post) {
        post.title = f.sentence();
        post.body  = f.paragraph();
        post.published = true;
    }

    // 具名状态 —— 工厂类存在的理由就是这几个方法
    public PostFactory draft()   { return state(p -> p.published = false); }
    public PostFactory popular() { return state(p -> p.views = 10_000); }

    /** 每条都要做的创建后动作 */
    @Override
    protected void afterCreating(Post post) { ... }
}
```

**用户只写这两样:默认值与具名状态。** 其余的都是样板,所以都由
`@UseFactory` 生成进 `PostFactoryBase`:

```java
// 产出
Post p = Post.factory().createOne();
Coll<Post> many = Post.factory().count(10).create();
Post unsaved = Post.factory().makeOne();                    // 不写库

// 按列的类型化 setter(生成的)
Post p2 = Post.factory().title("指定标题").views(777).createOne();

// 具名状态(自己写的)接在生成的链式方法后面
Post p3 = Post.factory().draft().count(3).create().get(0);

// 序列、种子
Coll<Post> seq = Post.factory().count(4)
        .sequence(p -> p.published = false, p -> p.published = true)
        .create();
Coll<Post> reproducible = Post.factory().count(3).seed(42).create();

// 关联(生成的)
Post p4 = Post.factory().forAuthor(author).createOne();          // 用现成的父对象
Post p5 = Post.factory().forAuthor(Author.factory()).createOne(); // 现建一个
Author a = Author.factory().hasPosts(3).createOne();              // 子对象
Author b = Author.factory().hasPosts(3, f -> f.draft()).createOne();

// 中间表(@BelongsToMany)
Post p6 = Post.factory().hasTags(2).createOne();                  // 现造两个标签挂上
Post p7 = Post.factory().hasTags(2, f -> f.featured()).createOne();
Post p8 = Post.factory().hasTags(1, f -> {},
        Map.of("primary", true)).createOne();                     // 中间表上的额外字段
Post p9 = Post.factory().withTags(tagA, tagB).createOne();        // 挂**已有**的
Post p10 = Post.factory().withTags(tags.toList()).createOne();
```

骨架由 `jaravel make:factory PostFactory --model=Post` 生成(docs/13 §2.2)。

### 3.1 为什么是「类型化 lambda」而不是 Laravel 的 Map

Laravel 的 `definition()` 返回 `['title' => ..., 'status' => ...]`。
照搬到 Java 会得到 `Map<String, Object>` —— 列名写错是运行时的
「找不到列 titel」,类型写错是运行时的 ClassCastException。
docs/14 §10 为此登记过三个版本的开放项(「`definition()` 缺少类型安全」)。

这里关掉它:**默认值是一个写字段的 lambda,setter 是按列生成的方法**。
列名写错、类型写错都在编译期暴露,IDE 也能补全。代价是与 Laravel 的
代码形状不同 —— 但 `Post.factory().draft().count(3).create()` 这一行
(真正会被反复写的那一行)两边一模一样。

### 3.2 几个刻意的边界

| 行为 | 选择 | 理由 |
|---|---|---|
| `@UseFactory` 标在**模型**上,不是工厂上 | 模型侧 | 标在工厂上更像 Laravel,但生成 `Post.factory()` 需要在处理模型那一轮就知道工厂类名 —— 只改模型时工厂不在那一轮里,`factory()` 会在一次增量编译后**静默消失** |
| 工厂必须与模型在**同一次编译**里 | 报错 | 写在 `src/test/java` 的工厂,main 里的 `Post.factory()` 编译不过。处理器直接报错并说清;顺带:seeder 也是工厂的使用者,它本来就属于 main |
| 链式方法返回具体工厂类型 | 生成时写死 + 一次向下转型 | 替代方案是 `F extends ModelFactory<M, F>` 自引用泛型,它的全部作用只是让链式方法返回正确类型,代价是用户的类声明里一串泛型噪音 |
| 名字与机制方法冲突的列(`count` / `state` / `seed` …) | 不生成 setter,但报 warning | 生成了会变成重载歧义:`.count(3)` 既可能是「造 3 条」也可能是「把 count 列设成 3」,javac 按参数类型挑一个 —— 最糟的一种静默行为 |
| `forAuthor(Author.factory())` 只建**一个**父对象 | count 条共用 | 「同一个作者的三篇」是这句话想说的事;要「各自一个父对象」在 `state` 里自己建 |
| `hasPosts(n)` 只对 `create()` 有效 | 是 | `make()` 不入库,父对象没有主键,子对象的外键无处可填 |
| 中间表关联(`@BelongsToMany`) | `hasTags(n[, tweak][, pivot])` / `withTags(…)` | 它没有「模型上的外键」,挂接走关联访问器(`owner.tags().attach(ids, pivot)`)。因此它多一个参数(中间表上的额外字段),也因此**需要关联访问器存在** —— 关掉 `generateHelpers` 的模型跳过这个构建器,而不是生成一段引用 `owner.tags()` 的死代码 |
| through / 多态关联 | 不生成 | `@HasManyThrough` 是**三方**关系,一个 `hasXxx(int)` 的签名表达不了「中间那层从哪来」;多态的目标类型由一列决定,造数据时得先说清造哪一种。两者的写法留给 `afterCreating` |
| UNIQUE 列的默认值 | 用户负责 | `Faker` 的序号是**每个实例一份**(固定种子要可复现,就不能有跨实例共享状态),于是两次 `Post.factory()` 的 slug 都从 `slug-1` 开始。`examples/blog` 的 `PostFactory` 里有这件事的写法:加一个进程级计数器 |

### 3.3 不需要工厂类的那条路还在

```java
Factory.of(Post_.META, (f, p) -> {
    p.title = f.sentence();
}).count(20).seed(42).create();
```

`net.dobeen.jaravel.migration.Factory` 不需要 APT、不需要工厂类,适合 seeder 里
就地造几条。换来类型化 setter 与关联构建器的是「每个模型一个工厂类」那条路
—— 它们只能生成,手写就是样板。两者共用 `net.dobeen.jaravel.support.Faker`,
所以定义里的写法完全一样。

### 3.4 Faker

内置最小实现:`word` / `words` / `sentence` / `paragraph` / `unique` /
`email` / `slug` / `url` / `ipv4` / `uuid` / `number` / `longNumber` /
`decimal` / `bool` / `chance` / `pick` / `sequence` /
`pastInstant` / `futureInstant` / `pastDate`。

> ⚠ **没有 `net.datafaker:datafaker`,也没有 `locale`。** 原文设想的
> `faker.book().title()` / `faker.lorem()` / `locale: zh_CN` 都不存在 ——
> 10MB 的 locale 数据与一串传递依赖,换来的是造数据时更像真话的字符串,
> 而工厂真正需要的只是「不重复的字符串」和「范围内的数字」。
> 要更真实的数据:`definition` 是个 lambda,里面换成自己的 Faker 即可。
> 登记在 KNOWN-ISSUES.md。

---

## 4. 假实现(Fake)

```java
@Test void checkout_dispatches_jobs_and_sends_mail() {
    var queue = Queue.fake();
    var mail  = Mail.fake();
    var event = Event.fake();
    var disk  = Storage.fake("s3");
    var notif = Notifications.fake();
    var cache = Cache.fake();
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

    cache.assertForgotten("orders:" + order.getId());
}
```

> ⚠ **这段里有两处还不是当前状态。** 已实现的是
> `Mail.fake()` / `Event.fake()` / `Notifications.fake()` /
> `Broadcast.fake()` / `Http.fake()`,它们的断言是**静态方法**
> (`Mail.assertSent(...)`、`Event.assertDispatched(...)`、
> `Notifications.assertSentTo(...)`),不返回示例里的记录器;
> `Storage.fake(...)` 返回 `FakeDisk`,`Queue.fake()` 返回
> `QueueFake` —— 示例里 `disk` 与 `queue` 的用法都成立。
> `Cache.fake()` 返回 `CacheFake`(记录命中/未命中/写入/删除);
> `Clock` 见 §4.2;`UploadedFile.fake()` 见 §4.4。
> 门面名是 `Notifications`(`Notification` 是通知的基类)。
> 事件的部分假实现是 `Event.fake(OrderPaid.class)`(只假装指定类型,
> 其余照常分发),没有 `fakeExcept`。
>
> **`Queue.fake()` 的三条边界**(都写在 `QueueFake` 的类注释里):
> 不拦 `dispatchSync`(它的语义是「现在、在我的调用栈上执行」,拦下等于
> 让被测代码什么都没做而测试绿了);不发 `JobQueued` 事件;不抢
> `UniqueJob` 的锁,所以记录里可能出现真实运行时会被去重的重复任务。
>
> **`Cache.fake()` 的一条边界**:它换掉的是 `CacheManager` 的默认 store,
> 所以走 `Cache` 门面的代码会被接住,而**构造注入了 `Repository` bean**
> 的代码持有旧实例、不受影响。这不完全是缺点 —— 框架自己的会话存储、
> 限流器、队列唯一性锁都是注入式的,一个连它们一起换掉的 fake 会让
> 「测一下缓存」顺带清掉当前测试的登录态。

### 4.1 部分假实现

```java
Queue.fake(List.of(SendInvoice.class));   // 只假装这一个,其余真的入队
Queue.fake(SendInvoice.class);            // 变参写法
Event.fake(OrderPaid.class);              // 只假装这一个,其余照常分发
```

> 队列的部分 fake 对**链**按队首判定:一条链被拆成「前半假装、后半真跑」
> 没有可用的语义(它的执行顺序本就靠前一个成功才有下一个)。

### 4.2 时间控制

```java
Clock.freeze();                            // 停在此刻
Clock.freeze(Instant.parse("2026-01-01T00:00:00Z"));
Clock.travel(Duration.ofDays(7));          // 快进七天
Clock.travelTo(instant);
Clock.withFrozen(instant, () -> { ... });  // 只在这一段里冻结
Clock.reset();                             // FeatureTest 每个测试前自动调
```

门面是 `net.dobeen.jaravel.support.Clock`(在 support 模块 —— 每个模块都要用它,
它必须在最底层)。`FeatureTest` / `BrowserTest` 在每个测试前 `reset()`:
漏出去的冻结时间会让后面的测试以「令牌莫名过期/莫名不过期」失败,
而且只在特定执行顺序下出现。

这是它解决的问题:一条「密码重置链接 60 分钟后失效」的规则,
若时间只能靠 `Instant.now()` 读,测它只有两条路 —— **睡一小时**,
或者把 `expire` 配成 1 毫秒再 `sleep(2)`(那测的是「1 毫秒会不会过期」,
不是那条规则)。于是这类逻辑在多数项目里**没有测试**,而它们恰好
最容易写错。现在是:

```java
net.dobeen.jaravel.support.Clock.travel(Duration.ofMinutes(61));
post("/reset-password", ...).assertSessionHasErrors("token");
```

(`examples/auth` 的 `AuthFeatureTest.tokenExpiresAfterConfiguredWindow`,
以及它的反面 `tokenStillValidWithinWindow` —— 后者把「总是失效」排除掉。)

#### ★ 领域时间走 Clock,存活性计时不走

所有**领域**时间读取一律走 `Clock`:模型时间戳、令牌与签名过期、
缓存 TTL、任务的可见时刻与延迟、调度器的下次执行时间。

**存活性计时**显式用 `Clock.realNow()`:worker 的 `maxTime` 与关停宽限、
WebSocket 心跳。它们量的是「真的过了多久」—— 走可冻结的时钟会让一个
本该 5 秒后退出的循环**永远跑下去**,而挂死比断言失败难查得多。

`System.nanoTime()` 不受这条约束:它是单调时钟,量「这段代码跑了多久」
本来就该用它。

这条边界由 ArchUnit **阻断式**强制(`ArchitectureBoundaryTest`
的 `noDirectClockReads`),白名单逐条写明理由:

```java
ArchRule rule = noClasses()
        .that().resideInAPackage("net.dobeen.jaravel..")
        .and().doNotHaveFullyQualifiedName("net.dobeen.jaravel.support.Clock")
        .and().doNotHaveFullyQualifiedName("net.dobeen.jaravel.spring.broadcast.WsConnection")
        .should().callMethod(Instant.class, "now")
        .orShould().callMethod(LocalDateTime.class, "now")
        .orShould().callMethod(ZonedDateTime.class, "now")
        .orShould().callMethod(LocalDate.class, "now")
        .orShould().callMethod(System.class, "currentTimeMillis");
```

> 这条规则要能**真的**拦住东西才有意义:把 `Clock` 从白名单里拿掉跑一次,
> 它报出 Clock 自己的 4 处 `Instant.now()` —— 验证过了,再放回去。
> 为此 `jaravel-archunit-rules` 的 classpath 也补齐了 scheduler / mail /
> notifications / storage / dashboard / http-client / permissions / events:
> 不在 classpath 上的模块,ArchUnit 根本没导入它们的字节码,
> 规则对它们是**静默通过**的 —— 那比没有规则更糟。

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

> **v0.4 已实现**(`jaravel-http-client`,52 个测试)。
>
> ⚠ **docs 自己是矛盾的**:这一节说「应在 v0.4 提供」,而 docs/17 的
> 路线图把 `Http` 客户端门面列在 0.5。按这一节做了,因为有一个具体的
> 当下需求:通知的 slack / webhook 渠道与调度的心跳 ping 已经各自
> 手写了一遍 `HttpClient`(含各自的超时与错误处理)——
> 那两处在没有共同门面之前注定会分叉:超时值不同、「什么算失败」的
> 判定不同、错误消息的详细程度不同,而其中最不详细的那一处
> 就是出问题时最难查的。
>
> 收拢之后那两处顺带得到了两样东西:**重试**(429 与 5xx 自动重试,
> 且按对方的 `Retry-After` 等 —— slack 的限流很常见,而原先完全没有
> 重试)、以及**可测**(slack / webhook 渠道原先只能靠起一个真 HTTP
> 服务器来测,所以实际上**没有测**;现在有 6 条断言)。
>
> **三处与 Laravel 不同的决定:**
>
> **① fake 下未匹配的请求会报错。** Laravel 对没匹配上的 URL 返回一个
> 默认的 200 空响应,而那让 **URL 写错时测试仍然通过**:被测代码调了
> `/itmes`、fake 里注册的是 `/items`,于是它拿到 200 空响应、
> 解析出空列表、然后「同步了 0 个商品」—— 而那正是这个测试要防的 bug。
> Laravel 后来也加了 `preventStrayRequests()`;这里只是把它变成默认。
> 需要宽松行为:`Http.allowStray()`。
>
> **② 超时有默认值,而且不接受 0 / null。** JDK 的 `HttpClient` 默认
> 超时是**无限**,而这个默认值在这个项目里已经咬过两次(Jakarta Mail、
> 通知渠道)。默认连接 10 秒、整体 30 秒。想要「不限时」没有这个选项 ——
> 一个挂住的外部调用会占着请求线程或 worker 的并发额度不放。
>
> **③ 在失败的响应上调 `json()` 会报错。** 与 Laravel 一致,默认
> **不**在 4xx/5xx 抛(检查状态码是调用方的事)。但一个 500 响应的
> body 通常是一段 HTML 错误页,返回空数据会让调用方带着空结果继续走,
> 而真正的原因就丢了。所以 `json()` 这一步变响,并在消息里问
> 「是不是想调 `throwOnError()`」。
>
> **重试只重试值得重试的**:连接失败、5xx、429 才重试;
> **其它 4xx 不重试** —— 一个 404 重试一百次还是 404,而那一百次里
> 每一次都在给对方加压,并且在有速率限制的 API 上会把配额烧掉。
> 429/503 带 `Retry-After` 时**按对方说的等**(两种格式都认:
> 秒数与 HTTP 日期),但上限 60 秒 —— 一个写错的 `Retry-After: 86400`
> 会让线程挂一整天。
>
> **不自动跟随重定向。** 跟随的后果:一个 POST 被重定向后 JDK 会把它
> 变成 GET(RFC 允许),于是请求体丢了而调用方看到一个 200 ——
> 那是一个非常难查的「数据没保存」。
>
> **非法 URL 立刻抛,不包成一次失败的调用** —— 包成 `status=0` 会让它
> 被重试三次然后当成「对方挂了」,而它是调用方的 bug。

---

### 4.4 假的上传文件

```java
var avatar = UploadedFile.fake().image("avatar.png");            // 10×10
var big    = UploadedFile.fake().image("avatar.jpg", 1200, 800);
var pdf    = UploadedFile.fake().create("report.pdf", 512);      // 512 KB
var broken = UploadedFile.fake().withContent("avatar.png", new byte[]{(byte) 0x89, 'P'});
```

#### ★ 内容是真的,否则这个 fake 在骗人

本框架的扩展名与 MIME 一律**按内容嗅探**(docs/11 §3.3:客户端给的
文件名完全不可信)。所以一个「名字叫 avatar.png、内容是随机字节」的
假文件会让 `image` 与 `mimes:png` 这两条规则**在测试里与生产里行为
不同** —— 那是一个假实现最坏的形态。

因此:

- `image(...)` 用 `ImageIO` 编一张**真图片**(真宽高、真魔数),
  嗅探、`image` 规则、以及应用自己的缩略图代码都走真实路径;
- `create(name, kb)` 按扩展名写上**对应格式的魔数**再填充到指定大小,
  单位是 **KB** —— 与校验规则一致(`max:2048` 是 2 MB);
- 没有魔数的类型(txt / csv / json)**如实**嗅探不出来。
  伪造一个魔数会让 `mimes:txt` 在测试里通过而在生产里不通过;
- `withContent(...)` 用来构造坏输入(截断的 PNG、空文件、
  伪装成图片的脚本)。

`FakeUploadTest` 里有一条测试核对**魔数表与 `ContentSniffer` 一致**:
两边分叉时假文件就嗅探不出来,而那正是这个类要避免的事。

> `examples/auth` 的 `UploadFlowTest`(真 multipart + 真磁盘)已经改用
> 它生成素材 —— 那里原本有一份手写的魔数桩。顺带也就把这个 fake
> 放进了一条端到端的验收里。

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
class InventoryServiceTest extends AppTest {     // 容器 + 数据库,没有 HTTP 的 API
    @Autowired InventoryService service;

    @Test void reserves_stock() {
        service.reserve(sku, 3);
        assertDatabaseHas("reservations", Map.of("sku", sku, "quantity", 3));
    }
}
```

三层测试基类:

| 基类 | 能做什么 | 用途 |
|---|---|---|
| (无) | 什么都不启动,~1ms | 纯逻辑单元测试 |
| `AppTest` | 容器 + 数据库 + 执行作用域,**没有发请求的方法** | 服务层、仓储、作业、Policy |
| `FeatureTest` | 以上 + 请求构造与 HTTP 断言 | 端到端功能 |

**★ `AppTest` 的分层在 API 面上,不在启动成本上。** 它与 `FeatureTest` 的
`@SpringBootTest` 配置完全一致,于是 Spring 的上下文缓存命中**同一个
上下文**,整个测试 JVM 只启动一次。刻意没用 `webEnvironment = NONE` 去省
那个 mock servlet 环境:那会得到第二个缓存键,于是一个同时有服务层测试
与功能测试的项目要启动两个上下文 —— 省下的是毫秒,花掉的是秒。

那么它为什么还值得存在:**让测试说清自己测什么**。服务层逻辑用
`FeatureTest` 写,会得到一个带着 `get("/…")` 与会话断言的 API 面,于是
一次失败有四种可能的原因(路由、中间件、认证、被测逻辑),而且很容易
顺手加一个 HTTP 断言把它变成端到端测试。`examples/auth` 的
`NoteAuthorizationAppTest` 与 `AuthFeatureTest` 是这组对照的实例:
同一条授权规则,前者只有一种失败原因。

它有执行作用域(`ScopeExtension`),所以 `actingAs(user)` → `Auth.user()` /
`Gate` 的行为与请求里一致。两点边界:

- **没有会话。** 会话型 guard 读不到登录态 —— `actingAs` 走的是
  `guard.setUser(...)`(等价于 `Auth.once(...)` 成功后的状态)。
  这与队列任务里的情况一致,是刻意的:服务层代码用 `Session` 本身就是
  缺陷,让它在测试里能跑的代价是上线后在 worker 里炸;
- **作用域只覆盖测试方法本身。** JUnit 没有跨生命周期回调的绑定点,
  所以在 `@BeforeEach` 里 `actingAs` 会直接报错(而不是静默无效);
  造数据放 `@BeforeEach` 不受影响 —— 那只需要连接。

---

## 6. 其他

### 6.1 浏览器测试(Playwright)✅ v0.8

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

`BrowserTest` 自带 `@SpringBootTest(classes = JaravelTestApplication.class,
webEnvironment = RANDOM_PORT)`,测试类放哪个包都可以。需要用自己的入口类或额外配置时,
在子类上写自己的 `@SpringBootTest(...)` 覆盖。

基于 Playwright for Java(对应 Laravel Dusk),底层不是 Selenium:

- **自动等待**:`press` / `assertSee` 自己等元素出现,测试里不写 sleep;
- **真实浏览器**:真端口、真 HTTP、真页面 JS —— 与 `FeatureTest` 的 MockServlet 互补;
- **浏览器实例复用**:一个测试 JVM 共用一个浏览器(默认 Chromium),退出时由 shutdown hook 关闭;
- **逃逸口**:`browser().page()` 直接拿 Playwright 的 `Page`,高级交互不用等封装。

两条硬约束:

1. 必须在真实端口上跑。基类默认 `RANDOM_PORT`;若子类覆盖了
   `@SpringBootTest` 的环境,必须用 `RANDOM_PORT` 或 `DEFINED_PORT` ——
   浏览器不会走 MockServlet,`@LocalServerPort` 必须真的有值;
2. `BrowserTest` 刻意不继承 `FeatureTest`:后者默认每测试回滚事务、在进程内直接调
   handler;混在一起会得到「以为测了浏览器,实际走的还是 MockServlet」。

**默认关闭**,因为它要浏览器二进制、每个用例秒级,而默认套件必须保持可跑。
用系统属性打开:

```bash
# 首次:Playwright 自动下载浏览器(Chromium/Firefox/WebKit,合计数百 MB,一次性)
mvn -pl examples/todo -am test -Djaravel.browser.enabled=true

# 已预装 Chromium,且不想让 Playwright 再装另外两个浏览器
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
  mvn -pl examples/todo -am test -Djaravel.browser.enabled=true

# 看真实窗口
mvn -pl examples/todo -am test -Djaravel.browser.enabled=true \
  -Djaravel.browser.headed=true

# 换引擎(chromium 默认 / firefox / webkit)
mvn -pl examples/todo -am test -Djaravel.browser.enabled=true \
  -Djaravel.browser.name=firefox
```

Playwright 对 `jaravel-testing` 是 **optional** 依赖 —— 绝大多数测试不需要真实
浏览器,不该让所有应用都拖入 driver 与浏览器二进制。要写浏览器测试的应用在自己的
`test` 作用域加一行(版本由 `jaravel-bom` 管理):

```xml
<dependency>
  <groupId>com.microsoft.playwright</groupId>
  <artifactId>playwright</artifactId>
  <scope>test</scope>
</dependency>
```

验收样例:`examples/todo` 的 `TodoBrowserTest` —— 添加 → 完成 → 删除走完整流程,
外加一条「页面脚本真的执行了」的断言(输入框拿到焦点)。后者正是 MockServlet 看不到的
那一层。另一个 `TodoHomeBrowserTest` 专门盯「同一 JVM 里多个浏览器测试类共用
一个浏览器实例」这条不变量。

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

### 6.3 并行测试 ✅ v0.8

```bash
jaravel test                                  # = mvn test
jaravel test --tests 'Todo*'                  # 只跑匹配的测试
jaravel test --parallel --processes=4         # 4 个 Surefire fork
jaravel test --parallel --offline             # 依赖已缓存的 CI
```

这是**进程级**并行,不是 JUnit 的线程级并行:`--parallel` 映射到 Surefire
`forkCount`,N 个独立 JVM,各自一份 Spring 上下文缓存。理由见
[ADR-017](18-risks-and-decisions.md):`Facade.SWAP`、`Event.fake`、静态
`Eloquent` 连接、`BrowserTest` 的静态浏览器都是 JVM 全局的,线程级并行会互相踩。

每进程一个独立测试库:

| 数据源 | 处理 |
|---|---|
| SQLite 内存(`:memory:` / `mode=memory`) | 不动 —— 每个 JVM 天然隔离 |
| SQLite 相对文件路径 | 派生到 `target/jaravel-test-db/<pid>/<原相对路径>`;源文件存在则拷贝,保留本地迁移/seed |
| SQLite 绝对路径 | 不动 —— 通常是测试自己 `@TempDir` 管理的,跨 fork 已唯一 |
| PostgreSQL / MySQL / MariaDB | 派生 `<db>_jaravel_test_<pid>`;启动时 DROP + CREATE,退出时尽力 DROP |
| 其它(含 `jdbc:tc:`) | 明确报错,不静默共享 |

要点:

- **自动迁移**:派生库是空的;并行模式会把 `jaravel.migrate.on-boot` 置为 true
  (除非你显式设置它,或传 `-Djaravel.test.migrate=false`)。不强制 seed。
- **权限**:PG/MySQL 需要 `CREATE` / `DROP` / `CONNECT`(PG 还要能连 `postgres`
  或 `template1`)。失败会给出具体授权语句,不会退化成共享库。
- **默认进程数**:`--parallel` 不给 `--processes` 时取 CPU 核数,封顶 8;
  `--processes` 取 1..64;`--processes=1` 不启用库隔离。
- **测试自己指定数据源时优先**:`@SpringBootTest(properties = "spring.datasource.url=...")`
  或 `@TestPropertySource` 会覆盖派生;那种测试自己负责隔离。
- **清理**:SQLite 派生文件在退出时尽力删除;Surefire 在 Windows 上可能直接杀掉
  fork,所以下一次启动会扫掉「进程已不在」的旧目录。`mvn clean` 也会清掉
  `target/`。被强杀时服务端派生库可能残留,需要手动清理(名字带
  `_jaravel_test_<pid>`)。
- **限制**:JUnit 线程级并行在同一个 fork 内仍共享一个库;一个测试类不能拆分到
  多个进程;`forkCount` 是上限,实际 fork 数 ≤ 测试类数。

> `-DforkCount=N` 也可以直接传给 Maven;`jaravel test` 只是把
> `--parallel/--processes` 翻译过去。`-T1C` 是**模块级**并行,与 `forkCount`
> 相乘;`jaravel test` 不会自动加 `-T`。

### 6.4 覆盖率

⏳ 还没实现:`jaravel test` 目前是 Maven 的薄代理,JaCoCo 仍由 Maven 生命周期
提供。用 `mvn test jacoco:report` 查看覆盖率;`--coverage --min=70` 是后续项。

---

## 7. 内部架构

> ⚠ **这是目标结构,不是当前实现。** 当前 `jaravel-testing` 的类是平铺的
> (`FeatureTest`、`AppTest`、`BrowserTest`、`RollbackExtension`、
> `DatabaseStrategyExtension`、`ScopeExtension`、`TestcontainersSupport`、
> `testcontainers/` 等),没有 `bases/` / `fakes/` / `time/` 这些包;
> `TestClock`、`JaravelExtension` 不存在 —— 时间控制是 `support` 的
> `Clock`,扩展是三个各管一件事的小扩展 + Spring 的 `SpringExtension`。
> 各个 fake 住在自己的模块里(`QueueFake` 在 jaravel-queue 等),
> 而不是集中在 testing 里 —— 那样 testing 会反向依赖全部模块。

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
- [x] Testcontainers 自动注入:**v0.8 已实现** —— `@UseDatabase` + ContextCustomizer,
  支持 MySQL / PostgreSQL / MariaDB,默认关闭、Docker 不可用则失败(见 §2.2、ADR-018)。
  `jaravel new` 是否默认开启仍开放:它会增加首次拉镜像的时间与 CI 的 Docker 依赖,
  倾向在脚手架里询问。
- [x] 并行测试的数据库隔离:**v0.8 已实现** —— Surefire `forkCount` +
  `jaravel-testing` 的 `ParallelTestDatabase`(SQLite / PostgreSQL / MySQL,
  见 §6.3、[ADR-017](18-risks-and-decisions.md))。模板库
  `CREATE DATABASE ... TEMPLATE` / dump-restore 的性能优化仍可做,但不阻塞交付。
- [x] 测试中的作用域绑定:**已实现** —— `ScopeExtension` 把测试方法包进一个
  `ExecutionScope`,于是 `AppTest` 里 `actingAs(user)` → `Auth.user()` / `Gate`
  与请求里行为一致(§5)。两点边界也定下来了:作用域只覆盖测试方法本身
  (在 `@BeforeEach` 里 `actingAs` 直接报错,而不是静默无效),
  且**不造合成会话** —— 会话型 guard 在 `AppTest` 里读不到登录态,
  与队列任务一致。
- [x] 工厂的类型安全:**已实现** —— 不走 `Map<String,Object>`,默认值是写字段的
  lambda,setter 按列生成(`.title("X").views(777)`),列名与类型都在编译期
  检查(§3.1)。这条开放项在 v0.6 / v0.7 两次都记成「等反馈再定」,
  这次做掉的理由是:工厂的 APT 既然要做,Map 那一版就没有存在的必要了。
- [x] 测试隔离注解与 `AppTest`:**已实现** —— `@DatabaseTransactions`(默认,
  显式声明)/ `@RefreshDatabase` / `@DatabaseMigrations` + `AppTest`
  (§2.1、§5)。`@WithoutDatabase` **不做**:它要表达的「纯单元测试」已经有
  更快更直接的写法(不继承基类),理由见 §2.1 与 `KNOWN-ISSUES.md`。
- [ ] `Event.fakeExcept` 不提供,部分假实现用 `Event.fake(types)`(§4)。
  `Queue.fake()` / `Cache.fake()` / `Clock` / `UploadedFile.fake()`
  都已落地(边界见 `QueueFake` / `CacheFake` / `Clock` / `FakeUpload`
  的类注释)。
- [x] 工厂:**已实现** —— `@UseFactory(PostFactory.class)` 生成
  `PostFactoryBase`(类型化 setter、关联构建器 `forAuthor` / `hasPosts`、
  count / state / sequence / seed / make / create)与模型上的
  `Post.factory()`;`jaravel make:factory` 生成骨架(§3、docs/13 §2.2)。
  边界与理由见 §3.2。
  **`datafaker` 不做**:10MB locale 数据与一串传递依赖,换来的只是更像
  真话的字符串;`Faker` 留在 `net.dobeen.jaravel.support`,要更真实的数据在
  `definition` 里换自己的(§3.4,登记在 KNOWN-ISSUES.md)。
- [x] 中间表关联的工厂构建器:**已实现** —— `hasTags(n[, tweak][, pivot])`
  与 `withTags(…)`(§3、§3.2)。名字跟着关联走(与 `hasPosts` / `forAuthor`
  一致),而不是 Laravel 的 `hasAttached(Tag.factory(), pivot)` ——
  那个写法里「挂哪个关联」要靠目标类型去猜,而一个模型可以对同一个目标有
  两个中间表关联。`examples/blog` 的 `BlogSeeder` 里那段手写的
  `attach` 循环已经换成 `withTags(tags.toList())`。
