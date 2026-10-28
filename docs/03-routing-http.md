# 03 · 路由与 HTTP

---

## 设计目标

1. 路由定义的简洁度对齐 Laravel,但**编译期校验控制器方法存在性**;
2. 中间件是路由的一等属性(可按路由/分组/全局配置,可排序,可排除);
3. 控制器方法签名自由:既能 `(Request)`,也能 `(@Path Long id, CreateUserRequest body)`;
4. 返回值归一化:`View` / `JsonResource` / `Response` / `Redirect` / 任意对象 / `void` 都合法;
5. Jaravel 路由与 Spring 原生 `@RequestMapping` **共存**,未命中自动回落。

---

## 1. 路由定义

### 1.1 基本形态

```java
public class WebRoutes implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/", HomeController::index).name("home");
        r.get("/users/{id}", UserController::show).name("users.show");
        r.post("/users", UserController::store);
        r.put("/users/{id}", UserController::update);
        r.patch("/users/{id}", UserController::update);
        r.delete("/users/{id}", UserController::destroy);
        r.any("/webhook", WebhookController::handle);
        r.match(List.of(GET, POST), "/search", SearchController::handle);

        r.view("/about", "pages.about");                       // 直出视图,无控制器
        r.redirect("/old", "/new", 301);
        r.fallback(ErrorController::notFound);                  // 404 兜底
    }
}
```

`RouteDefinition` 的实现类由 APT 生成索引(`META-INF/jaravel/routes.idx`),启动时按**文件名字典序**加载,保证路由注册顺序可重现。

### 1.2 方法引用的类型安全

`UserController::show` 是方法引用。要让它既类型安全又能拿到元数据(参数名、注解),用**函数式接口 + APT 辅助**:

```java
// Router 的重载签名
<C> Route get(String uri, Action0<C> action);                 // Response m()
<C> Route get(String uri, Action1<C, Request> action);        // Response m(Request)
<C> Route get(String uri, ActionN<C> action);                 // 任意签名,APT 解析
```

**核心难点**:Java 的方法引用在运行时拿不到参数名和注解。解决方案:

`jaravel-routing-processor`(APT)扫描所有 `RouteDefinition` 实现,解析 `r.get("/users/{id}", UserController::show)` 这个表达式,在编译期:

1. 校验 `UserController#show` 存在且可见;
2. 校验 URI 中的 `{id}` 与方法参数能对上(数量、类型可转换);
3. 生成 `UserController$$Invoker`:一个无反射的直接调用适配器
   ```java
   final class UserController$$Invoker implements HandlerInvoker {
       public Object invoke(Object ctrl, ArgSource args) {
           return ((UserController) ctrl).show(args.pathLong("id"));
       }
       public ParamSpec[] params() { return SPECS; }   // 参数名/类型/注解,编译期固化
   }
   ```
4. 生成路由元数据表,启动时直接读取,**零反射、零扫描**。

> 若 APT 不可用(例如动态注册路由),回落到反射 + `-parameters` 编译参数读取参数名。回落路径必须存在但不是主路径。

### 1.3 路由参数

```java
r.get("/users/{id}", UserController::show)
 .where("id", "\\d+");                      // 正则约束

r.get("/posts/{slug}", PostController::show)
 .whereSlug("slug");                        // 预置约束:whereNumber/whereAlpha/whereUuid/whereUlid/whereIn

r.get("/files/{path*}", FileController::show);   // 通配,path* 匹配多段

r.get("/users/{id?}", UserController::index);    // 可选参数
```

全局约束:

```java
// AppServiceProvider#boot
Route.pattern("id", "\\d+");
```

### 1.4 分组

```java
r.group(g -> g.prefix("/admin")
              .middleware("auth", "can:admin")
              .name("admin.")
              .namespace(AdminControllers.class)      // 可选,仅用于简化书写
              .domain("admin.{tenant}.example.com"),
   g -> {
       g.get("/dashboard", DashboardController::index).name("dashboard");  // → admin.dashboard
       g.resource("/users", AdminUserController.class);
   });
```

链式简写:

```java
r.prefix("/api/v1").middleware("auth:api").group(api -> {
    api.apiResource("/orders", OrderController.class);
});
```

### 1.5 资源路由

```java
r.resource("/posts", PostController.class);
```

展开为 7 条:

| 方法 | URI | 控制器方法 | 路由名 |
|---|---|---|---|
| GET | `/posts` | `index` | `posts.index` |
| GET | `/posts/create` | `create` | `posts.create` |
| POST | `/posts` | `store` | `posts.store` |
| GET | `/posts/{post}` | `show` | `posts.show` |
| GET | `/posts/{post}/edit` | `edit` | `posts.edit` |
| PUT/PATCH | `/posts/{post}` | `update` | `posts.update` |
| DELETE | `/posts/{post}` | `destroy` | `posts.destroy` |

```java
r.resource("/posts", PostController.class).only("index", "show");
r.resource("/posts", PostController.class).except("destroy");
r.apiResource("/posts", PostController.class);            // 去掉 create/edit
r.resource("/posts.comments", CommentController.class);   // 嵌套 → /posts/{post}/comments
r.resource("/posts", PostController.class).shallow();     // 浅嵌套
r.resource("/photo", PhotoController.class).singleton();  // 单例资源,无 index
```

APT 校验:资源路由展开出的每个方法**必须在控制器上存在**,缺失则编译报错并提示 `only()/except()`。这比 Laravel 的运行时 404 强得多。

### 1.6 注解式路由(可选风格)

```java
@Controller("/api/v1/orders")
@Middleware("auth:api")
public class OrderController {

    @Get public Paginated<OrderResource> index(@Query int page) { ... }
    @Get("/{id}") public OrderResource show(@Path Long id) { ... }
    @Post @Middleware("throttle:10,1") public OrderResource store(CreateOrder body) { ... }
    @Delete("/{id}") @Can("delete,order") public void destroy(@Path Long id) { ... }
}
```

两种风格共享同一张路由表与同一套中间件管道。`jaravel route:list` 统一输出,并标注来源(`routes/WebRoutes.java:12` 或 `@Get OrderController#show`)。

**提供两种风格是否违背「唯一正确答案」原则?** 这是有意的妥协:Java 团队对注解式路由有强路径依赖,强制 DSL 会直接挡住采纳。文档中明确推荐 DSL(因为集中可见、易于分组),注解式定位为迁移友好路径。

### 1.7 路由缓存与 URL 生成

```bash
jaravel route:cache     # 路由表序列化 + 编译为静态匹配树
jaravel route:list --method=GET --name=admin --except-vendor
```

URL 生成:

```java
Url.route("users.show", 42);                      // /users/42
Url.route("users.show", Map.of("id", 42, "tab", "posts"));  // /users/42?tab=posts
Url.signedRoute("unsubscribe", Duration.ofDays(7), Map.of("id", 42));
Url.action(UserController::show, 42);             // ★ 类型安全的 URL 生成
Url.asset("css/app.css");
Url.current();  Url.full();  Url.previous();
```

`Url.action(UserController::show, 42)` 是 Laravel 做不到的:**重命名控制器方法时 URL 生成跟着变,编译期发现断链**。

---

## 2. 中间件

### 2.1 定义

```java
public interface Middleware extends Pipe<Request, Response> {
    @Override Response handle(Request request, Function<Request, Response> next);
}
```

示例:

```java
public class EnsureEmailVerified implements Middleware {
    public Response handle(Request req, Function<Request, Response> next) {
        if (Auth.user().getEmailVerifiedAt() == null) {
            return req.expectsJson()
                ? Response.json(Map.of("message", "Email not verified")).status(409)
                : Redirect.route("verification.notice");
        }
        return next.apply(req);     // 前置逻辑在上,后置逻辑在下
    }
}
```

### 2.2 注册

```java
// app/Http/Kernel.java
public class HttpKernel extends Kernel {

    @Override protected List<Class<? extends Middleware>> global() {
        return List.of(
            TrustProxies.class,
            HandleCors.class,
            PreventRequestsDuringMaintenance.class,
            TrimStrings.class,
            ConvertEmptyStringsToNull.class
        );
    }

    @Override protected Map<String, List<Class<? extends Middleware>>> groups() {
        return Map.of(
            "web", List.of(
                EncryptCookies.class, AddQueuedCookiesToResponse.class,
                StartSession.class, ShareErrorsFromSession.class,
                VerifyCsrfToken.class, SubstituteBindings.class),
            "api", List.of(
                ThrottleRequests.class.named("api"), SubstituteBindings.class)
        );
    }

    /** 带参数的别名中间件 */
    @Override protected Map<String, Class<? extends Middleware>> aliases() {
        return Map.of(
            "auth",      Authenticate.class,
            "can",       Authorize.class,
            "guest",     RedirectIfAuthenticated.class,
            "throttle",  ThrottleRequests.class,
            "signed",    ValidateSignature.class,
            "verified",  EnsureEmailVerified.class
        );
    }

    /** 优先级:影响同一路由上多个中间件的实际执行顺序 */
    @Override protected List<Class<? extends Middleware>> priority() {
        return List.of(StartSession.class, Authenticate.class, Authorize.class);
    }
}
```

### 2.3 参数化中间件

Laravel 写 `'throttle:60,1'`。Jaravel 同样支持字符串形式(保持简洁),但同时提供类型安全形式:

```java
r.get("/api/data", DataController::index).middleware("throttle:60,1");
r.get("/api/data", DataController::index).middleware(Throttle.perMinute(60));   // 类型安全
```

参数化中间件实现:

```java
public class ThrottleRequests implements ParameterizedMiddleware {
    private int maxAttempts = 60;
    private Duration window = Duration.ofMinutes(1);

    @Override public void configure(String... params) {
        if (params.length > 0) maxAttempts = Integer.parseInt(params[0]);
        if (params.length > 1) window = Duration.ofMinutes(Long.parseLong(params[1]));
    }
    // ...
}
```

### 2.4 排除

```java
r.group(g -> g.middleware("web"), g -> {
    g.post("/webhook", WebhookController::handle)
     .withoutMiddleware(VerifyCsrfToken.class);
});
```

### 2.5 为什么不用 Servlet Filter

Filter 是**全局的、按 URL pattern 匹配的、顺序由注册决定的**。而 Laravel 式中间件需要:

- 按**路由**绑定(同一 URL 不同 HTTP 方法可有不同中间件);
- 分组继承 + 单路由排除;
- 显式优先级重排;
- 能访问已匹配的路由元数据(如 `can:update,post` 需要知道路由参数 `post`)。

这些 Filter 都做不到。因此中间件管道位于 `JaravelHandlerAdapter` 内部,路由匹配**之后**执行。

**代价**:中间件无法拦截「路由未匹配」的请求。因此真正需要全局拦截的横切关注点(CORS、代理头、维护模式)放在 `global()`,由 Jaravel 自己注册为一个 Servlet Filter 统一执行。这是刻意的分层。

---

## 3. Request

```java
public interface Request {
    // 基本信息
    String method();  String path();  String url();  String fullUrl();
    boolean isMethod(HttpMethod m);
    String ip();  List<String> ips();  String userAgent();

    // 输入(查询参数 + body 合并,与 Laravel 一致)
    Optional<String> input(String key);
    String input(String key, String def);
    <T> Optional<T> input(String key, Class<T> type);
    Map<String, Object> all();
    Map<String, Object> only(String... keys);
    Map<String, Object> except(String... keys);
    boolean has(String... keys);
    boolean filled(String key);
    boolean missing(String key);
    Optional<String> query(String key);
    <T> T body(Class<T> type);                  // 反序列化 JSON body

    // 路由
    Route route();
    Optional<String> routeParam(String name);
    <T> T routeParam(String name, Class<T> type);

    // 头与内容协商
    Optional<String> header(String name);
    boolean expectsJson();  boolean wantsJson();  boolean isAjax();
    boolean accepts(String... contentTypes);

    // 文件
    Optional<UploadedFile> file(String key);
    List<UploadedFile> files(String key);
    boolean hasFile(String key);

    // Cookie / Session / 用户
    Optional<String> cookie(String name);
    SessionStore session();
    Optional<User> user();
    <U> Optional<U> user(Class<U> type);

    // 验证(便捷入口)
    Validated validate(Map<String, String> rules);

    // 逃生口
    HttpServletRequest raw();      // 仅在 servlet 适配下可用
}
```

### 3.1 FormRequest

```java
public class StorePostRequest extends FormRequest {

    @Override public boolean authorize() {
        return Gate.allows("create", Post.class);
    }

    @Override public Map<String, String> rules() {
        return Map.of(
            "title", "required|string|max:200",
            "body",  "required|string",
            "tags",  "array|max:5",
            "tags.*", "exists:tags,id"
        );
    }

    @Override public Map<String, String> messages() {
        return Map.of("title.required", "标题不能为空");
    }

    @Override protected void prepareForValidation() {
        merge("slug", Str.slug(input("title", "")));
    }

    // 类型安全的取值器(APT 可从 rules 生成,v0.5)
    public String title() { return validated().string("title"); }
    public String body()  { return validated().string("body"); }
    public List<Long> tagIds() { return validated().longs("tags"); }
}
```

控制器方法参数一旦是 `FormRequest` 子类,参数绑定阶段自动:实例化 → `prepareForValidation()` → `authorize()`(失败 403)→ 校验(失败 422/回跳)→ 注入。

### 3.2 参数绑定规则

| 注解 / 类型 | 来源 |
|---|---|
| `@Path String id` | 路由参数 |
| `@Query int page` | 查询串 |
| `@Body CreateOrder dto` | 请求体 JSON |
| `@Header("X-Trace") String trace` | 请求头 |
| `@Cookie("locale") String locale` | Cookie |
| `Request req` | 请求对象本身 |
| `FormRequest` 子类 | 自动校验后注入 |
| `Model` 子类(如 `Post post`) | **隐式模型绑定** |
| 其他类型 | 从容器解析(服务注入) |
| 无注解的简单类型 | 按参数名匹配路由参数,再匹配查询串 |

### 3.3 隐式模型绑定

```java
r.get("/posts/{post}", PostController::show);

public View show(Post post) {        // 自动 Post.findOrFail(post 参数)
    return View.of("posts.show").with("post", post);
}
```

自定义键:

```java
r.get("/posts/{post:slug}", PostController::show);     // 按 slug 查找
```

模型侧自定义:

```java
@Model(table = "posts")
public class Post extends Model<Post> {
    @Override public String routeKeyName() { return "slug"; }
    @Override public Query<Post> resolveRouteBinding(Query<Post> q) {
        return q.published();        // 绑定时自动加作用域
    }
}
```

显式绑定:

```java
// RouteServiceProvider#boot
Route.bind("post", (value, route) -> Post.query().where(Post_.slug, value).firstOrFail());
```

---

## 4. Response

```java
// JSON
Response.json(data);
Response.json(data).status(201).header("X-Total", "100");
Response.noContent();

// 视图
View.of("posts.index").with("posts", posts);
View.of("posts.index").with(Map.of(...)).status(200);

// 重定向
Redirect.to("/dashboard");
Redirect.route("posts.show", post.getId());
Redirect.action(PostController::show, post.getId());    // 类型安全
Redirect.back().withInput().withErrors(errors);
Redirect.away("https://external.com");
Redirect.to("/dashboard").with("status", "Profile updated!");   // flash session

// 文件
Response.download(path, "report.pdf");
Response.file(path);
Response.stream(out -> { /* 流式写出,适合大导出 */ });

// 其他
Response.text("ok");
Response.redirectResponse(301, "/new");
Response.abort(404);                 // 抛 HttpException,交给 ExceptionHandler
Response.abortIf(cond, 403, "No permission");
```

### 4.1 返回值归一化

| 控制器返回 | 处理 |
|---|---|
| `Response` | 原样 |
| `View` | 渲染模板 → 200 text/html |
| `Redirect` | 302 + Location |
| `JsonResource` / `ResourceCollection` | 序列化 → 200 application/json |
| `Paginated<T>` | 序列化含 `data`/`links`/`meta` |
| `String` | 200 text/plain(若以 `<` 开头则 text/html) |
| `void` / `null` | 204 No Content |
| 任意 POJO / `Map` / `Coll` | Jackson 序列化 → 200 application/json |
| `CompletableFuture<T>` | 等待后按上述规则(虚拟线程下直接 join) |

---

## 5. Session、Cookie、CSRF

### 5.1 Session

```java
Session.put("key", value);
Session.get("key", String.class);
Session.pull("key", String.class);      // 取出并删除
Session.flash("status", "Saved!");      // 只在下一个请求可用
Session.reflash();  Session.keep("status");
Session.has("key");  Session.forget("key");  Session.flush();
Session.regenerate();                   // 登录后防会话固定攻击
Session.token();                        // CSRF token
```

驱动:`cookie`(加密)/ `redis` / `database` / `file` / `memory`。详见 [09-cache-session.md](09-cache-session.md)。

### 5.2 Cookie

```java
Cookie.queue("locale", "zh_CN", Duration.ofDays(365));   // 自动附加到响应
Cookie.forget("locale");
Response.json(data).cookie(Cookie.make("a", "b").httpOnly().secure().sameSite(LAX));
```

默认所有 cookie 用 `APP_KEY` 加密(`EncryptCookies` 中间件),白名单可排除。

### 5.3 CSRF

```html
<form method="POST" action="/posts">
  @csrf
  ...
</form>
```

`@csrf` 编译为 `<input type="hidden" name="_token" value="...">`。
`VerifyCsrfToken` 中间件校验 POST/PUT/PATCH/DELETE,支持 `X-CSRF-TOKEN` 与 `X-XSRF-TOKEN` 头。排除路径:

```java
public class VerifyCsrfToken extends BaseVerifyCsrfToken {
    @Override protected List<String> except() { return List.of("/webhook/*", "/api/*"); }
}
```

---

## 6. 内部架构

```
JaravelHandlerMapping implements HandlerMapping, Ordered
├── order = Ordered.HIGHEST_PRECEDENCE + 100   (早于 RequestMappingHandlerMapping)
├── RouteCollection  (不可变,启动后冻结)
│   ├── staticRoutes:  Map<String(method+uri), Route>     O(1) 命中
│   └── dynamicTree:   前缀树,节点含 PathPattern          含参数路由
└── getHandler(req) → JaravelHandler | null (null 则回落给 Spring)

JaravelHandlerAdapter implements HandlerAdapter
├── 1. new RequestImpl(servletRequest)
├── 2. RequestScope.runIn(scope, () -> ...)
├── 3. resolveMiddlewares(route)  →  Pipeline
├── 4. bindArguments(route, request)  ← 用 APT 生成的 ParamSpec[]
├── 5. invoker.invoke(controller, args)   ← 无反射
├── 6. normalizeReturnValue(result)
└── 7. writeResponse
```

### 6.1 路由匹配性能

- 静态路由(无参数)走 `HashMap`,O(1);
- 含参数路由走前缀树,按路径段逐级下降,复用 Spring 的 `PathPattern` 做段匹配;
- 生产模式下 `route:cache` 把树序列化,启动时直接反序列化。

目标:10000 条路由下匹配耗时 < 1μs。

---

## 7. 逃生口

| 需求 | 途径 |
|---|---|
| Jaravel 路由不够用 | 照常写 `@RestController`,未命中自动回落 |
| 需要 Servlet 原语 | `request.raw()` / `response.raw()` |
| 需要 Spring `HandlerInterceptor` | 照常注册,在 Jaravel 管道之外执行 |
| 需要 WebSocket / SSE | SSE:`Response.stream()`;WebSocket:用 Spring 原生,v0.6 提供 Jaravel 风格封装 |
| 需要自定义参数解析器 | 注册 `ArgumentResolver` 到容器 |

---

## 8. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| 控制器引用 | `'UserController@show'` 字符串 | `UserController::show` 方法引用 | 编译期校验 |
| 路由文件 | `routes/web.php` 顶层语句 | `WebRoutes implements RouteDefinition` | Java 无顶层语句 |
| URL 生成 | `action('C@m')` | `Url.action(C::m, args)` | 类型安全 |
| 全局中间件 | 全在 Kernel 的 pipeline 里 | 拆为 Servlet Filter + 路由管道两层 | Filter 无法按路由绑定 |
| 注解路由 | 无 | 支持(次要风格) | Java 生态路径依赖 |
| 请求外 `session()` | 返回 null | 抛异常 | 消除静默 bug |

---

## 9. 待决问题

- [x] ~~APT 解析 `r.get("/u/{id}", C::m)` 表达式的可行性~~ —— **已验证可行**(2026-10-28)。`com.sun.source.util.Trees` 是 `jdk.compiler` 正式导出的受支持 API,无需 `--add-exports`。详见 [verification/v2-route-apt/RESULT.md](../verification/v2-route-apt/RESULT.md)。
      三条需修正的预期:① javac 自己就会报「方法不存在」,APT 不应重复;② `Messager` 无 `Tree` 重载,诊断只能锚定到 `define()` 方法,精确行号写进消息文本;③ 参数校验必须单向(路径参数需有同名方法参数,方法可有额外参数)。
- [ ] 是否投入 javac 插件(`com.sun.source.util.Plugin`)以获得精确诊断位置?发出定位诊断可能仍需内部 `Log` API,**未验证**。
- [ ] 带参数的中间件字符串形式(`"throttle:60,1"`)是否值得保留?它破坏了类型安全。倾向保留,因为 Kernel 的 `aliases()` 映射表集中可见,风险可控。
- [ ] `r.resource()` 的参数名推断(`{post}` 来自哪里):用控制器类名去掉 `Controller` 后缀 snake_case,还是用显式 `.parameters("post")`?倾向前者 + 后者可覆盖。
- [ ] 域名路由(`domain("{tenant}.example.com")`)与 Spring 的 `PathPattern` 不兼容,需自己匹配 Host 头。
