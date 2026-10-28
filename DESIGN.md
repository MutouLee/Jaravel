# Jaravel 设计总纲

> Laravel 级开发体验,构建在 Spring Boot 之上的 Java 应用框架。

| 项 | 值 |
|---|---|
| 项目代号 | Jaravel (`Java` + `Laravel`) |
| groupId | `io.jaravel` |
| 语言基线 | Java 21 (LTS),可选启用 Java 25 特性 |
| 运行时底座 | Spring Boot 3.x(可替换的 adapter,非硬绑定) |
| 构建工具 | Maven(Gradle 支持列入 v1.1) |
| 许可 | Apache-2.0 |
| 文档索引 | [docs/README.md](docs/README.md) |
| v0.0 技术验证 | **已完成**(2026-10-28)· [verification/README.md](verification/README.md) |

---

## 1. 为什么做这个

### 1.1 问题陈述

PHP 生态里 Laravel 几乎是默认选择,原因不是它功能多,而是它**把一个 Web 应用需要的 80% 决策都替开发者做完了**,并且做得一致:路由怎么写、模型怎么查、模板怎么渲染、队列怎么投递、权限怎么判断,都有唯一且优雅的答案。

Java 生态的现状不同。Spring Boot 是事实标准,但它的定位是「企业级的、可配置到极致的、面向架构师的」框架:

- 一个简单的 CRUD 需要 Entity / Repository / Service / DTO / Mapper / Controller 六层;
- Spring Security 的配置复杂度与「给接口加个登录校验」这个需求严重不匹配;
- JPA/Hibernate 的 Session 与脏检查模型,让「查一条数据改个字段存回去」这件事产生了超出必要的认知负担;
- 没有一等公民的模板引擎方案、没有内置的队列/调度/邮件/通知抽象、没有 `artisan` 这样的脚手架 CLI;
- 改一行代码要重启。

> 需要澄清的前提:Java 侧并非只有 Spring Boot,还有 Micronaut、Quarkus、Helidon、Javalin、Jooby、Vert.x 等。这不改变「Spring Boot 是事实标准」的判断,但其中 **Micronaut / Quarkus 的编译期 DI + 注解处理器路线**,是 Jaravel 最重要的技术参照——本方案的核心实现手段正是编译期代码生成。

### 1.2 Jaravel 的定位

**不是** 另一个 Web 框架,**不是** Spring 的替代品。

Jaravel 是**骑在 Spring Boot 之上的一层 DX(开发体验)框架**:

- 向下:复用 Spring 的 IoC、AOP、事务管理、嵌入式容器、Actuator、整个三方生态;
- 向上:提供 Laravel 风格的、有唯一正确答案的、简洁且类型安全的 API;
- 对外:用户只需引入一个 `jaravel-spring-boot-starter`,照常能用任何 Spring 库。

一句话:**让 Java 开发者能像写 Laravel 一样写 Spring Boot 应用,同时拿到编译期类型安全。**

---

## 2. 可行性结论

**可行,技术上没有不可逾越的障碍。**

> **2026-10-28 更新**:v0.0 技术验证(四个会推翻架构的技术点)**全部通过,无一需要启用退路**。
> 其中两项的结论改变了设计:模型访问器的字节码织入方案被**证伪**(改为 public 字段 + 快照脏检查),
> 模板引擎确定**基于 jte** 构建(工作量 14 周 → 7.5 周)。详见 [verification/README.md](verification/README.md)。

但难点不在路由、模板、RestFul 这些功能本身——那些都是确定性的工程量。真正的难点是:**Laravel 的优雅有相当一部分来自 PHP 的运行时模型**,这部分必须换一套 Java 的实现思路才能等价复现。

| PHP 的能力 | Laravel 如何依赖它 | Jaravel 的对策 |
|---|---|---|
| 每请求进程隔离 | 全局门面 `Auth::user()` 天然线程安全 | 虚拟线程-per-request + `ScopedValue` 绑定请求上下文 |
| `__get` / `__set` 魔术方法 | `$user->any_column` 动态属性 | APT 注解处理器编译期生成 getter/setter + 元模型 |
| `__callStatic` | `User::where(...)` 静态转实例 | 生成类型化查询器 `User.query()` |
| 字符串类名反射 | `app('cache')`、`Route::get('C@m')` | 方法引用 + 泛型契约,编译期校验 |
| 无编译期,改完即生效 | 热重载是免费的 | 三层热重载方案(见 §6),目标 < 2s |
| 数组即配置 | `config/app.php` 返回数组 | YAML + 类型化 `@ConfigProps` record + `Config` 门面 |

### 2.1 逐项可行性评估

| Laravel 能力 | Jaravel 实现路径 | 难度 | 细化文档 |
|---|---|---|---|
| 路由 DSL | 方法引用 + Fluent API,注册进自定义 `HandlerMapping` | 低 | [03](docs/03-routing-http.md) |
| 中间件管道 | 自研 `Pipeline`,**不用** Servlet Filter | 低 | [03](docs/03-routing-http.md) |
| 门面 Facade | 静态门面 + `ScopedValue` 请求上下文 | 中 | [02](docs/02-core-container.md) |
| 模板引擎 | jblade:Blade 前端 + **jte 后端** + JbContext 编排 | 中 | [04](docs/04-view-jblade.md) · [V3](verification/v3-jblade-jte/RESULT.md) |
| 查询构造器 / 迁移 | 自研 QueryBuilder + 按方言分离 Grammar | 中 | [05](docs/05-database.md) |
| Eloquent ORM | **public 字段 + 快照脏检查 + APT 元模型**(项目胜负点) | 高 | [06](docs/06-eloquent.md) · [V1](verification/v1-model-accessor/RESULT.md) |
| 验证器 | 字符串规则 + 类型化 Builder + FormRequest | 低 | [07](docs/07-validation.md) |
| 认证 / 授权 | **自研轻量 Guard**,不依赖 Spring Security | 中 | [08](docs/08-auth.md) |
| 缓存 / 会话 | 门面包 Caffeine / Redis / DB / File | 低 | [09](docs/09-cache-session.md) |
| 队列 / 事件 / 调度 | Laravel 风格 API + 虚拟线程 Worker | 中 | [10](docs/10-queue-events-scheduler.md) |
| 邮件 / 通知 / 存储 | Mailable + jblade 模板 + 多 Channel | 低 | [11](docs/11-mail-notifications-storage.md) |
| RestFul / API 资源 | `JsonResource` + 分页 + 异常转 JSON | 低 | [12](docs/12-api-resources.md) |
| Artisan CLI / Tinker | picocli + GraalVM native;Tinker 用 JShell | 中 | [13](docs/13-console-artisan.md) |
| 测试工具 | HTTP 测试 DSL + 数据库断言 + 工厂 | 低 | [14](docs/14-testing.md) |
| 热重载 | 四层机制;增强 HotSwap 实测覆盖 **87%** | 中 | [15](docs/15-devx-hot-reload.md) · [V4](verification/v4-hotreload-coverage/RESULT.md) |

**唯一无法完全等价的两点**:

1. **热重载**——JVM 的硬约束,做不到 PHP 的零重启,目标压到 1~2 秒;
2. **运行时任意动态性**——比如 Laravel 模型可以持有任意未声明的 `$attributes`。用代码生成换来的是编译期安全,这是**升级而非降级**。

---

## 3. 三条顶层架构决策

这三条是整个项目的技术主心骨,必须在第一行代码之前定下来。详细论证见 [docs/01-architecture.md](docs/01-architecture.md) 与 [docs/18-risks-and-decisions.md](docs/18-risks-and-decisions.md)。

### 决策一:骑在 Spring Boot 上,但不嫁给它

`jaravel-core` 及所有功能模块**零 Spring 依赖**,只依赖自己定义的契约接口;Spring 仅作为可替换的 runtime adapter,存在于 `jaravel-spring-boot-starter` 一个模块里。

```
┌─────────────────────────────────────────┐
│          用户应用代码                     │
├─────────────────────────────────────────┤
│  Jaravel API 层(门面 / DSL / 契约)      │  ← 用户只面对这一层
├─────────────────────────────────────────┤
│  Jaravel 功能模块(零 Spring 依赖)       │
│  routing / view / eloquent / auth / ... │
├─────────────────────────────────────────┤
│  Runtime Adapter(薄,可替换)            │  ← spring-boot-starter
├─────────────────────────────────────────┤
│  Spring Boot 3.x / Servlet / DataSource │
└─────────────────────────────────────────┘
```

**收益**:
- 用户照常能用整个 Spring 生态(Actuator、Micrometer、Spring Cloud、任意三方 starter);
- Spring Boot 大版本升级的冲击被收窄到一个模块,核心不受伤;
- 将来可出 `jaravel-quarkus-starter`,或直接跑在 Netty 上;
- API 设计不被 Spring 的概念污染——这是「优雅」的前提。

**约束**:适配层必须薄且只许单向依赖(adapter → core,绝不反向)。CI 中用 ArchUnit 强制校验 `jaravel-core` 的字节码里不出现任何 `org.springframework.*` 引用。

### 决策二:用编译期代码生成替代 PHP 魔术方法

凡 Laravel 用 `__get` / `__call` / 字符串反射实现的地方,Jaravel 一律用 APT(Annotation Processing Tool)在编译期生成等价代码:

- 模型元模型(`User_.NAME`)+ 类型化查询器
- 路由表注册代码
- jblade 模板编译成的 Java 类
- 配置绑定类
- API Resource 的 JSON 序列化器

**收益**:零运行时反射 → 启动快、吞吐高、GraalVM native-image 原生友好、IDE 可跳转可重构、错误在编译期而非线上暴露。

**代价**:APT 的错误信息必须做得极其友好(生成代码保留 source position 映射),否则会直接毁掉 DX。这是 [docs/06-eloquent.md](docs/06-eloquent.md) 的头号质量要求。

### 决策三:强制虚拟线程-per-request 作为并发模型

Laravel 的全局门面之所以不出问题,是因为 PHP 每请求一个独立进程。Java 要还原这个心智模型,必须:

```properties
spring.threads.virtual.enabled=true   # Jaravel starter 默认开启
```

配合 `ScopedValue<RequestScope>` 绑定当前请求的 `Request` / `Auth` / 事务上下文 / 本地化语言 / 请求级缓存。于是 `Auth.user()` 这种写法**既优雅又线程安全**。

这个决策必须在第一天定下,否则门面层会从根上烂掉(`ThreadLocal` 在池化线程 + 异步场景下的泄漏与串号问题无法事后修补)。

---

## 4. 核心 API 预览

完整 API 设计见各模块文档,这里给出「一个博客应用」的全貌,用于直观对比 Laravel。

### 4.1 路由

```java
public class WebRoutes implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/", HomeController::index).name("home");
        r.resource("/posts", PostController.class).except("destroy");

        r.group(g -> g.prefix("/admin").middleware("auth", "can:admin"), g -> {
            g.get("/dashboard", DashboardController::index);
        });

        r.prefix("/api/v1").middleware("auth:api").group(api -> {
            api.apiResource("/orders", OrderController.class);
        });
    }
}
```

`PostController::index` 是**方法引用**,编译期就能发现路由指向了不存在的方法——这是 Laravel 的字符串路由做不到的。

### 4.2 控制器

```java
public class PostController {
    private final PostService posts;
    public PostController(PostService posts) { this.posts = posts; }   // 构造注入

    public View index() {
        return View.of("posts.index")
                   .with("posts", Post.query().with(Post_.author).latest().paginate(15));
    }

    public Response store(StorePostRequest req) {       // FormRequest 自动校验
        Post post = posts.create(Auth.user(), req);
        return Redirect.route("posts.show", post.getId()).with("status", "created");
    }

    public JsonResource show(@Path Long id) {
        return new PostResource(Post.findOrFail(id));
    }
}
```

### 4.3 模型

```java
@Model(table = "posts")
@SoftDeletes
@BelongsTo(name = "author", target = User.class)
@HasMany(name = "comments", target = Comment.class)
@BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag")
public class Post extends PostBase {

    @Id public Long id;
    public String title;
    public String body;
    @Cast(Json.class) public Map<String, Object> meta;
    public Instant publishedAt;

    @Scope public static Query<Post> published(Query<Post> q) {
        return q.whereNotNull(Post_.publishedAt);
    }
}
```

> 标量列是 **public 字段**(`post.title = "X"`,与 `$post->title = 'X'` 等长),
> 关联用**类级注解**声明、由 APT 生成 `post.author()` / `post.comments()` 访问器。
> 两项都是 v0.0 验证的结论(ADR-010):字节码织入方案已证伪,关联作为 public 字段会踩中
> 「父类构造器初始化子类字段被静默覆盖」的陷阱。详见 [V1 验证报告](verification/v1-model-accessor/RESULT.md)。

```java
var list = Post.query()
        .published()                            // 生成的作用域方法
        .where(Post_.views, Op.GT, 100)
        .with(Post_.author, Post_.tags)          // 预加载,从根上解决 N+1
        .orderByDesc(Post_.createdAt)
        .paginate(15);

Post p = Post.findOrFail(1L);
p.title = "New title";                           // 直接赋值
p.save();                                        // 与快照比对,只 UPDATE 变化的列
p.comments().get();                              // 关联走生成的方法

DB.transaction(() -> {
    order.save();
    Inventory.query().where(Inventory_.sku, sku).decrement("qty", 1);
});
```

### 4.4 模板(jblade)

```html
@params(Paginator<Post> posts)
@extends('layouts.app')

@section('content')
  @forelse (posts as post)
    <x-post-card :post="post" :highlighted="post.isFeatured()" />
  @empty
    <p>@lang('posts.empty')</p>
  @endforelse

  {{ posts.links() }}
@endsection
```

顶部 `@params` 声明类型 → 模板编译成**强类型 Java 类**,`{{ post.title }}` 直接编译为 `__esc(post.getTitle())`,无反射、IDE 可跳转。

### 4.5 迁移

```java
// database/migrations/2026_10_28_143000_create_posts_table.java
public class CreatePostsTable extends Migration {
    public void up() {
        Schema.create("posts", t -> {
            t.id();
            t.foreignId("user_id").constrained("users").cascadeOnDelete();
            t.string("title", 200);
            t.text("body");
            t.json("meta").nullable();
            t.timestamp("published_at").nullable();
            t.softDeletes();
            t.timestamps();
            t.index("published_at", "created_at");
        });
    }
    public void down() { Schema.dropIfExists("posts"); }
}
```

### 4.6 认证与授权

```java
if (Auth.attempt(Map.of("email", email, "password", pwd), remember)) { /* ... */ }
User me = Auth.user();

Gate.define("update-post", (User u, Post p) -> u.getId().equals(p.getUserId()));
Gate.policy(Post.class, PostPolicy.class);
if (Gate.allows("update-post", post)) { /* ... */ }
```

### 4.7 队列 / 事件 / 调度

```java
Queue.dispatch(new SendInvoice(orderId)).onQueue("mail").delay(Duration.ofMinutes(5));
Event.dispatch(new OrderPaid(order));
schedule.job(PruneLogs.class).dailyAt("03:00").withoutOverlapping();
```

### 4.8 CLI

```bash
jaravel new blog                   # 脚手架新项目
jaravel serve                      # 带热重载的开发服务器
jaravel make:model Post -mcrf      # 模型 + 迁移 + 控制器 + 请求 + 工厂
jaravel migrate --seed
jaravel route:list
jaravel tinker                     # JShell + 预加载应用容器与全部门面
jaravel queue:work --queue=mail,default
```

---

## 5. 模块与 Maven 结构

完整说明见 [docs/16-module-layout.md](docs/16-module-layout.md)。

```
jaravel/                               (parent pom,Java 21 baseline)
├── jaravel-bom                        统一版本管理
├── jaravel-support                    Coll / Str / Arr / DateX(Carbon 等价)
├── jaravel-core                       容器 / 门面 / 契约 / Pipeline / Config / .env / ServiceProvider
├── jaravel-routing
├── jaravel-http                       Request / Response / Session / Cookie / CSRF / Upload
├── jaravel-view                       jblade 编译器 + 运行时
├── jaravel-database                   QueryBuilder / Grammar / Schema / Migration / Seeder
├── jaravel-eloquent                   Model / 关联 / 作用域 / 软删除 / 模型事件
├── jaravel-eloquent-processor         APT 注解处理器(独立 artifact,provided scope)
├── jaravel-validation
├── jaravel-auth                       Guard / Provider / Gate / Policy / Hash
├── jaravel-cache  ·  jaravel-session
├── jaravel-queue  ·  jaravel-events  ·  jaravel-scheduler
├── jaravel-mail  ·  jaravel-notifications  ·  jaravel-storage
├── jaravel-api                        JsonResource / Pagination / 异常转 JSON
├── jaravel-console                    Artisan 内核 + 全部命令 + stub
├── jaravel-testing
├── jaravel-spring-boot-starter        自动装配 + Spring 适配
├── jaravel-maven-plugin               模板预编译 / 迁移校验 / native 打包
└── jaravel-cli                        `jaravel` 命令(picocli,GraalVM native)
```

用户侧只需要一个依赖:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.jaravel</groupId><artifactId>jaravel-bom</artifactId>
      <version>${jaravel.version}</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.jaravel</groupId><artifactId>jaravel-spring-boot-starter</artifactId>
  </dependency>
</dependencies>
```

> **命名提醒**:Java 生态已存在一个名为 **Blade** 的 Web 框架(lets-blade)。Jaravel 的模板引擎命名为 **jblade**,文件后缀 `.jblade.html`,以避免搜索与品牌冲突。

---

## 6. 开发体验:热重载(DX 的生死线)

PHP 最大的体验优势是「改完刷新就生效」。Jaravel 用三层组合逼近,详见 [docs/15-devx-hot-reload.md](docs/15-devx-hot-reload.md):

| 变更类型 | 机制 | 效果 |
|---|---|---|
| 模板 / 配置 / 语言包 | 文件监听 + 内存重编译 + ClassLoader 替换 | **不重启**,毫秒级 |
| 方法体修改 | HotswapAgent + DCEVM(或 JRebel) | **不重启** |
| 新增类 / 改签名 | devtools 重启类加载器(三方依赖留在 base loader) | 目标 **< 2s** |

**优先级等同于 Eloquent,不得延后到后期**。用户留存与否在此一举。

---

## 7. 里程碑路线图

完整版见 [docs/17-roadmap.md](docs/17-roadmap.md)。

| 版本 | 内容 | 验收标准 |
|---|---|---|
| **v0.1 垂直切片** | core + routing + http + jblade(运行时编译)+ QueryBuilder | 跑通一个 CRUD 页面,代码量 ≤ 等价 Laravel 的 **1.2 倍** |
| v0.2 | Eloquent + APT + Migration/Seeder + Validation | 博客应用全部用 Eloquent 写完,无裸 SQL |
| v0.3 | Auth/Gate/Policy + Session + Cache + CSRF + 热重载 | 登录注册权限全链路;改代码 2s 内生效 |
| v0.4 | Queue + Events + Scheduler + Mail + Notifications | 异步下单通知流程打通 |
| v0.5 | Artisan 全量 + Tinker + jaravel-testing + API Resources | 测试覆盖率 > 70%,CLI 体验对齐 artisan |
| v0.9 | Maven 插件预编译 + GraalVM native + 性能基准 | native 启动 < 100ms,吞吐 ≥ 裸 Spring MVC 的 90% |
| **v1.0** | 文档站 + 中英双语 + starter kit + 示例项目 | 外部开发者照文档 30 分钟跑通 Todo 应用 |

### 工作量实话

Laravel 的 illuminate 全家桶约 **50 万行**代码。一个可信的 1.0 子集约 **6~10 万行**。

- 单人全职 → v0.5:约 **12~18 个月**
- 3~4 人小团队 → v1.0:约 **9~12 个月**

---

## 8. 风险

详见 [docs/18-risks-and-decisions.md](docs/18-risks-and-decisions.md)。

1. **生态引力**:Java 开发者的默认选择是 Spring,新框架采纳曲线极陡。「骑在 Spring Boot 上」这个决策大幅降低迁移成本,是方案里最关键的一点,必须坚持。
2. **维护负担**:ORM + 模板引擎 + 迁移工具,任何一个单拎出来都是一个完整开源项目的体量。
3. **Spring 版本漂移**:适配层必须收窄到 starter 一个模块,并用 ArchUnit 在 CI 中强制。
4. **APT 调试体验**:注解处理器报错不友好会直接毁掉 DX,需在生成代码中保留源码位置映射。
5. **抽象泄漏**:一旦用户为了实现某个需求必须「绕过 Jaravel 直接写 Spring」,优雅性就破产了。每个模块都必须设计明确的**逃生口**(escape hatch),而不是让用户自己去撬。

---

## 9. 第一步该做什么

**不要先搭 20 个 Maven 模块。**

花 2~3 周做 **v0.1 垂直切片**——只做「路由 + 一个 jblade 页面 + 一个 Eloquent 模型的 CRUD」,跑在 Spring Boot 上。然后拿这段代码和同功能的 Laravel 代码做**逐行对比**:

- 若 Java 版行数与噪音在 **1.2 倍以内**、读起来一样顺 → 项目值得全力投入;
- 若到了 **2 倍** → 回头重新设计 API,而不是继续往上堆模块。

这条验收标准应当贯穿每个版本,写进 CI(维护一个 `benchmark/laravel-parity/` 目录,存放等价功能的 Laravel 与 Jaravel 实现,自动统计行数比)。

> **口径必须明确为「按完整功能切片统计」**(控制器 + 模型 + 路由 + 视图 + 测试),**不能按单一维度统计**。
> V1 验证实测:Laravel 的模型文件是 **O(1)**(完全不声明字段),任何 Java 版本都是 **O(n)** —— 30 字段的模型
> Laravel 8 行、Jaravel 33 行(最优方案)。这是静态类型语言不可消除的固有成本,与设计选择无关。
> 若按模型声明维度统计,1.3 的门禁从第一天起就不可能通过,会变成一个所有人都忽略的假指标。

---

## 附:文档地图

| 文档 | 内容 |
|---|---|
| [docs/01-architecture.md](docs/01-architecture.md) | 分层架构、与 Spring 的边界、请求生命周期、并发模型 |
| [docs/02-core-container.md](docs/02-core-container.md) | 容器、服务提供者、门面、Pipeline、配置与 .env |
| [docs/03-routing-http.md](docs/03-routing-http.md) | 路由 DSL、Request/Response、中间件、CSRF |
| [docs/04-view-jblade.md](docs/04-view-jblade.md) | jblade 语法、编译器架构、组件、热重载 |
| [docs/05-database.md](docs/05-database.md) | 查询构造器、方言 Grammar、Schema DSL、迁移、Seeder |
| [docs/06-eloquent.md](docs/06-eloquent.md) | 模型、APT 代码生成、关联、作用域、模型事件 |
| [docs/07-validation.md](docs/07-validation.md) | 规则引擎、FormRequest、自定义规则、错误消息 |
| [docs/08-auth.md](docs/08-auth.md) | Guard / Provider / Gate / Policy / Hash / Token |
| [docs/09-cache-session.md](docs/09-cache-session.md) | 缓存驱动、标签、锁、会话、限流 |
| [docs/10-queue-events-scheduler.md](docs/10-queue-events-scheduler.md) | 队列驱动、Worker、事件、监听器、任务调度 |
| [docs/11-mail-notifications-storage.md](docs/11-mail-notifications-storage.md) | Mailable、通知多通道、文件存储抽象 |
| [docs/12-api-resources.md](docs/12-api-resources.md) | JsonResource、分页、异常转 JSON、OpenAPI |
| [docs/13-console-artisan.md](docs/13-console-artisan.md) | Artisan 内核、命令清单、stub、Tinker |
| [docs/14-testing.md](docs/14-testing.md) | HTTP 测试 DSL、数据库断言、工厂、Faker、Mock |
| [docs/15-devx-hot-reload.md](docs/15-devx-hot-reload.md) | 三层热重载、`jaravel serve`、错误页 |
| [docs/16-module-layout.md](docs/16-module-layout.md) | Maven 多模块、BOM、starter、插件、项目骨架 |
| [docs/17-roadmap.md](docs/17-roadmap.md) | 里程碑拆解、验收标准、工作量估算 |
| [docs/18-risks-and-decisions.md](docs/18-risks-and-decisions.md) | ADR 架构决策记录、风险登记册 |
