# 12 · RestFul API:资源、分页、异常、OpenAPI

Laravel 做 API 的核心体验是:`JsonResource` 控制输出形状 + 统一的异常转 JSON + 标准化分页结构。本模块复现这三件事,并补上 Java 能做得更好的一件:**从代码生成 OpenAPI 文档**。

---

## 1. 资源(Resource)

### 1.1 单个资源

```java
public class UserResource extends JsonResource<User> {

    public UserResource(User user) { super(user); }

    @Override public Map<String, Object> toArray(Request req) {
        return Json.map()
            .put("id",         model.getId())
            .put("name",       model.getName())
            .put("email",      model.getEmail())
            .put("created_at", model.getCreatedAt())

            // 条件字段:仅管理员可见
            .putWhen(req.user().filter(User::isAdmin).isPresent(),
                     "internal_note", model::getInternalNote)

            // 仅当关联已预加载时才输出(避免资源层触发 N+1)
            .putWhenLoaded("posts", model.posts, PostResource::collection)
            .putWhenLoaded("profile", model.profile, ProfileResource::new)

            // 多对多的 pivot 数据
            .putWhenPivotLoaded("role_user", "granted_at", p -> p.get("granted_at"))

            // 聚合
            .putWhenCounted("posts_count", model)

            // 合并一组字段
            .mergeWhen(model.isVendor(), () -> Map.of(
                "vendor_id", model.getVendorId(),
                "payout_ready", model.isPayoutReady()))
            .build();
    }

    /** 顶层附加信息 */
    @Override public Map<String, Object> with(Request req) {
        return Map.of("meta", Map.of("version", "1.0"));
    }
}
```

`putWhenLoaded` 是关键设计:**资源层绝不触发懒加载**。配合 [06-eloquent.md](06-eloquent.md) §4.3 的懒加载禁止,API 层的 N+1 从架构上被消除。

### 1.2 集合资源

```java
// 默认:逐个包装
return UserResource.collection(users);

// 自定义集合(需要集合级元数据)
public class UserCollection extends ResourceCollection<User, UserResource> {
    @Override public Map<String, Object> toArray(Request req) {
        return Map.of(
            "data",  mapped(req),
            "stats", Map.of("total_admins", collection.filter(User::isAdmin).size())
        );
    }
}
```

### 1.3 输出包裹

```json
{ "data": { "id": 1, "name": "Tom" } }
```

默认带 `data` 包裹(与 Laravel 一致)。可全局关闭:

```java
// AppServiceProvider#boot
JsonResource.withoutWrapping();
```

### 1.4 类型安全的替代方案:record 投影

对于不需要条件字段的简单场景,直接用 `record` 更干净:

```java
public record UserDto(Long id, String name, String email, Instant createdAt) {
    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getName(), u.getEmail(), u.getCreatedAt());
    }
}

public Coll<UserDto> index() {
    return User.query().selectInto(UserDto.class, User_.ID, User_.NAME, User_.EMAIL, User_.CREATED_AT);
}
```

`selectInto` 只查需要的列,是性能更优的路径。**文档应明确指引**:

- 输出形状固定、无条件字段 → 用 `record` + `selectInto`;
- 需要条件字段、关联嵌套、权限相关输出 → 用 `JsonResource`。

这是对 Laravel 的改进(PHP 没有 record,所以 Laravel 只有 Resource 一条路)。

> ### v0.5:资源已实现(21 个测试,`jaravel-api` 模块)
>
> `JsonResource<M>` · `ResourceCollection<M,R>` · 字段构建器 · `data` 包裹 ·
> 与分页结合。两个真实响应把这一节最重要的那条规则摆在一起
> (`examples/blog`,真 HTTP):
>
> ```
> GET /api/posts/resources   列表(预加载 author,**没有**预加载 comments)
>   data[0] = { id, title, slug, views, published_at, author: {id, name} }
>                                      ↑ 没有 comment_count
>
> GET /api/posts/1           详情(两个都预加载了)
>   { "data": { …, "author": {…}, "comment_count": 3 }, "meta": {"version":"1.0"} }
> ```
>
> **★ 字段缺失是「没预加载」的可见提示。** 列表接口里没有 `comment_count`,
> 不是 bug —— 资源层绝不触发懒加载。一个列表返回 50 条、资源里碰一下
> 没预加载的关联就是 51 条查询,而那个 bug 在开发期的三条测试数据上
> **完全看不出来**,它只在生产的分页列表上出现。
> 前端拿不到那个字段,就知道这个接口没预加载它。
>
> 配套的一条区分:**「加载了但是空的」输出 `null`,而不是跳过**。
> 「没预加载」是接口的实现细节(跳过),「加载了但没有」是数据的事实(null)。
>
> #### 四处与本节原文的偏差,都有具体原因
>
> | 原文 | 实现 | 原因 |
> |---|---|---|
> | `Json.map().put(...)` | `map(req).put(...)`(父类上的静态方法) | 框架里已经有两个用户会碰到的 `Json`(`@Cast(Json.class)`、缓存那个)。第三个同名类会让「我 import 的是哪个 Json」变成真实困惑。顺带不用 import |
> | `UserResource.collection(users)` | `collection(users, UserResource::new)` | **Java 的静态方法不是多态的** —— `UserResource.collection` 实际调用基类那个,而基类不知道要 new 哪个子类。反射找构造器能绕过去,代价是运行时才炸 + native 要配反射。显式传方法引用换来编译期检查 |
> | `putWhenPivotLoaded` / `putWhenCounted` | **没有** | 它们依赖的东西不存在:`withCount` 聚合、以及 pivot 数据的访问器。不做一个永远返回空的方法 |
> | 条件字段 `putWhen(cond, name, value)` | 值是 `Supplier` | 条件字段的取值常常有代价或有前提(解密、再查一次)。直接传值会让它**无论条件成不成立都被求值一次** —— 而「只给管理员看」的字段恰恰不该为普通用户去算 |
>
> #### ★ 一个在测试里撞出来的设计修正
>
> 第一版把嵌套的资源**对象**原样放进字段里,让序列化器到那一层再展开。
> 能工作 —— 直到测试去断言 `toArray(req)` 的内容:那个 Map 里躺着一个
> `UserResource`,于是**那个方法的返回值不是 JSON 的形状**,只是中间态。
>
> 后果不只是测试难写:任何不经过 Jackson 的用途都坏了 ——
> 把响应体写进日志、存成快照做契约测试、在命令里打印一个资源。
> 而「这个方法返回这个接口的形状」是资源类唯一该有的契约。
> 所以 `Fields` 现在**递归摊平**嵌套的资源 / 资源集合 / 分页器,
> 代价是 `map()` 要知道当前 `Request`(于是变成 `map(req)`)。
>
> #### `data` 包裹只在根位置加一次
>
> 一个资源既可能是整个响应体,也可能是分页 `data` 数组里的一个元素、
> 或者另一个资源的嵌套字段 —— 只有第一种该包裹,否则会出现
> `{"data":[{"data":{…}},…]}`。
>
> Laravel 的做法是让分页器主动拆包(每一种容器都要记得拆)。
> 这里换了个判据:序列化器问 Jackson「我现在在根位置吗」
> (`getOutputContext().inRoot()`)—— 只问一次,而且对将来任何容器都成立。

---

## 2. 分页

### 2.1 三种分页器

```java
Paginated<User> p = User.query().paginate(15);           // 带总数
Paginated<User> s = User.query().simplePaginate(15);     // 不查总数(快)
Cursored<User> c  = User.query().cursorPaginate(15);     // 游标(大表/无限滚动)
```

### 2.2 JSON 结构

```json
{
  "data": [ ... ],
  "links": {
    "first": "https://api.example.com/users?page=1",
    "last":  "https://api.example.com/users?page=10",
    "prev":  null,
    "next":  "https://api.example.com/users?page=2"
  },
  "meta": {
    "current_page": 1,
    "from": 1,
    "last_page": 10,
    "path": "https://api.example.com/users",
    "per_page": 15,
    "to": 15,
    "total": 150
  }
}
```

与 Laravel 完全一致 —— 前端分页组件可直接复用。

> ### v0.5:分页已实现(25 个测试)
>
> `paginate(perPage)` · `paginate(perPage, page)` · `simplePaginate(...)`,
> 加上 `Paginated<T>` 与上面那个 JSON 形状。
> **`cursorPaginate` 还没有** —— 它要内省排序列、再把游标编码成不透明串,
> 是一道独立的设计题,不是这一节的变体。
>
> 实测(`examples/blog` 的 `/api/posts/paged?page=2`,真 HTTP):
>
> ```json
> "links": { "first": "…?page=1", "last": "…?page=2",
>            "prev": "…?page=1", "next": null },
> "meta":  { "current_page": 2, "from": 6, "last_page": 2,
>            "path": "http://localhost:8001/api/posts/paged",
>            "per_page": 5, "to": 7, "total": 7 }
> ```
>
> #### ★ 三处实现决定
>
> **一个 `Paginated` 而不是两个分页器。** Laravel 分
> `LengthAwarePaginator` / `Paginator`(后者不查总数),这里是一个类加一个
> **可空的 total** —— 因为 §2.1 给 `paginate` 与 `simplePaginate` 写的返回类型
> 都是 `Paginated<User>`。两个类会让那段文档撒谎,而且调用方要为
> 「少一个 total」换一次类型。没有 total 时**不输出** `total` / `last_page`,
> 而不是输出一个猜的值:一个编出来的 `last_page` 会让前端渲染出
> 一堆点不动的页码。
>
> **`simplePaginate` 多取一条来判断「还有没有下一页」。** 那是不查总数的
> 全部办法:要 `perPage + 1` 条,取到了就说明还有下一页,然后把它丢掉。
> 值得做的理由是 `count(*)` 在大表上是一次全表扫描,而无限滚动这种界面
> 根本不显示总页数 —— 为一个不显示的数字付一次全扫是纯粹的浪费。
>
> **页码与链接走 resolver,不走参数。** `User.query().paginate(15)` 这一句里
> 没有 `Request`,而页码在 HTTP 场景下必然来自 `?page=N`。做成参数
> (`paginate(15, request)`)会把**每一个**分页调用点都绑上 HTTP,
> 包括那些不在请求里的(队列任务里导出报表、命令行里分批处理)。
> 所以与 Laravel 一样用静态 resolver,由 starter 在启动时装上;
> 不在请求里时当成第 1 页 —— 那时**不报错**,因为 worker 里分批处理
> 拿到第一批正是对的。
>
> #### ⚠ 两个只有真跑才会发现的
>
> **`links.next` 整个键消失过。** 第一版的序列化器是
> `writeObject(toMap())`,而 `examples/blog` 的 `ObjectMapper` 配了
> 「不输出 null 字段」(API 响应的常见设置)—— 于是最后一页没有 `next`、
> 第一页没有 `prev`。前端组件靠 `links.next === null` 判断能不能翻,
> 键不存在时拿到 `undefined`,在 JS 里**也是假值** —— 所以多数组件还能用。
> 那正是最糟的那种 bug:不报错,只在某些组件上表现为按钮状态不对。
> 而上面那句「前端分页组件可直接复用」兑现不了。
>
> 处置:**信封**(`links` / `meta`)逐字段显式写、null 就写 null;
> 而 `data` 里的元素仍然走应用自己的 mapper —— 那是用户的数据,
> 用户的 inclusion 策略在那里是对的。
> (同一条理由在 docs/13 的 `route:list --json` 那里出现过一次:
> **一个机器可读的契约不该随一条无关配置变形**。)
>
> **链接里必须排掉旧的 `page`。** 留着它的话 `/posts?page=2` 的下一页会变成
> `/posts?page=2&page=3` —— 哪一个生效取决于服务端实现,也就是说翻页会
> **有时**不动。其它查询参数反过来必须留着:丢了它们,
> 用户一翻页筛选条件就没了。
>
> #### 还没有的
>
> `cursorPaginate`(§2.1)· `links()` 模板辅助(§2.4)·
> `JsonResource` 与资源集合(§1)· 统一异常处理(§3)· OpenAPI(§5)。

### 2.3 与 Resource 结合

```java
public Response index() {
    return UserResource.collection(User.query().with(User_.PROFILE).paginate(15));
}
```

分页信息自动合并进 `links` / `meta`,`data` 为资源数组。

### 2.4 模板中的分页链接

```html
{{ users.links() }}                        {{-- 默认样式 --}}
{{ users.links('pagination.tailwind') }}   {{-- 自定义视图 --}}
```

---

## 3. 统一异常处理

### 3.1 Handler

```java
// app/Exceptions/Handler.java
public class Handler extends ExceptionHandler {

    /** 不上报的异常类型 */
    @Override protected List<Class<? extends Throwable>> dontReport() {
        return List.of(ValidationException.class, AuthenticationException.class,
                       ModelNotFoundException.class, HttpException.class);
    }

    /** 从日志上下文中剔除的敏感字段 */
    @Override protected List<String> dontFlash() {
        return List.of("password", "password_confirmation", "token", "secret");
    }

    /** 上报(日志 / Sentry / 自定义) */
    @Override public void report(Throwable e) {
        if (e instanceof PaymentGatewayException pe) {
            Log.channel("payments").error("网关错误 code={}", pe.code(), pe);
            return;
        }
        super.report(e);
    }

    /** 渲染 */
    @Override public Optional<Response> render(Request req, Throwable e) {
        if (e instanceof InsufficientStockException ise) {
            return Optional.of(Response.json(Map.of(
                "message", "库存不足",
                "sku", ise.sku(),
                "available", ise.available()
            )).status(409));
        }
        return Optional.empty();      // 交给默认处理
    }

    /** 自定义上下文(附加到所有异常日志) */
    @Override protected Map<String, Object> context() {
        return Map.of("tenant", Tenancy.current().map(T::id).orElse(null));
    }
}
```

### 3.2 默认映射表

| 异常 | 状态码 | JSON 响应 |
|---|---|---|
| `ValidationException` | 422 | `{message, errors: {field: [...]}}` |
| `AuthenticationException` | 401 | `{message: "Unauthenticated."}` |
| `AuthorizationException` | 403 | `{message}`(含 Policy 返回的原因) |
| `ModelNotFoundException` | 404 | `{message: "Resource not found."}` |
| `RouteNotFoundException` | 404 | 同上 |
| `MethodNotAllowedException` | 405 | `{message}` + `Allow` 头 |
| `ThrottleException` | 429 | `{message}` + `Retry-After` 头 |
| `TokenMismatchException`(CSRF) | 419 | `{message: "CSRF token mismatch."}` |
| `HttpException(code, msg)` | code | `{message}` |
| 其他 | 500 | 生产:`{message: "Server Error."}`;开发:含类型/消息/堆栈 |

### 3.3 内容协商

```
Accept: application/json  或  路由在 "api" 分组  →  JSON 错误体
否则                                             →  HTML 错误页
```

HTML 错误页:
- 生产模式:`resources/views/errors/{code}.jblade.html`(用户可覆盖),无内部信息泄漏;
- 开发模式:富错误页(见 [15-devx-hot-reload.md](15-devx-hot-reload.md) §4)——堆栈、源码片段、本次请求的全部 SQL、请求参数、session、路由信息。

### 3.4 RFC 7807 Problem Details(可选)

```yaml
api:
  error-format: laravel      # laravel | problem-json
```

企业/对外 API 常要求 RFC 7807:

```json
{
  "type": "https://api.example.com/problems/insufficient-stock",
  "title": "库存不足",
  "status": 409,
  "detail": "SKU-123 仅剩 2 件",
  "instance": "/orders/456"
}
```

两种格式通过配置切换,错误定义代码不变。这是对 Laravel 的增强(Java 企业场景的实际需求)。

> ### v0.5:§3 收口(10 个测试)
>
> 这一节此前**已经实现了一大半** —— 内容协商、开发/生产错误页、状态码映射、
> `report` / `renderCustom` 覆写点都在(`JaravelExceptionHandler`,446 行)。
> 这一轮补的是缺的三块:
>
> | 补了什么 | 之前 |
> |---|---|
> | **Problem JSON**(`api.error-format: problem-json`) | 没有 |
> | `dontReport` / `dontFlash` / `context` 三个钩子(§3.1) | 没有 |
> | **405 + `Allow`**(§3.2 那张表里的一行) | 返回的是 **404** |
>
> #### §3.2 那张表与现实的对账
>
> 表里点名的六个异常类型里,**四个并不存在** —— 但对应的行为是对的:
> `AuthorizationException`(403)与 `TokenMismatchException`(419)
> 直接**继承 `HttpException`**,而 401 / 429 是
> `new HttpException(401/429, …, headers)` 抛出来的
> (429 连 `Retry-After` 都带着)。
>
> 也就是说那张表描述的是**状态码与响应体**,而实现选择了「少几个类型、
> 多用一个带状态码的异常」。这个取舍是对的:为每个状态码造一个类
> 只是在重复 `HttpException` 已经表达的事。
> 表里剩下的 `RouteNotFoundException` / `MethodNotAllowedException`
> 同理不存在 —— 405 现在由路由层直接产出一个 `HttpException(405)`。
>
> #### ★ 405 这条路径查出两个真 bug
>
> **① 它此前根本不存在。** 路径存在但方法不对时,`JaravelHandlerMapping`
> 返回 `null`,于是落进 Spring 的默认处理、最终是 **404**。
> 而 `RouteCollection.allowedMethods(path)` 这个方法就是为 405 写的
> (注释里写着「用于 405 与 Allow 头」),一直**只有单元测试在用**。
>
> 404 说的是「这个地址不存在」,405 说的是「这个地址存在,但你不能用 POST
> 访问它」—— 对调用方是两件完全不同的事:前者去查 URL 拼错了没,
> 后者去查自己用的动词。而 `Allow` 头直接给出答案(RFC 9110 要求它必须带)。
>
> **② 第一版 `throw` 在错误的位置。** 适配器的 `try` 只包着
> `runPipeline`,而 405 是在那之前判出来的 —— 于是那个异常会一路冒到
> Spring,**绕过整个统一异常处理**(内容协商、Problem JSON、错误页模板)。
> 第一次跑真 HTTP 测试拿到的就是一个裸的 `HttpException` 冒出来。
> 现在它走与正常路径同一套收尾。
>
> 顺带暴露第三处:**`jaravel-testing` 的 `FeatureTest.execute` 是适配器派发
> 逻辑的第二份实现**,它把 handler 强转成具体类型,于是新加的那种
> handler 让它抛 `ClassCastException` —— 症状与「405 没实现」毫无关系。
> 改成原样交给适配器去分支。
>
> #### ★ Problem JSON 也查出一个真 bug:媒体类型到不了客户端
>
> 第一版用 `header("Content-Type", "application/problem+json")` 设的。
> 那是**死代码**:`ResponseWriter` 用的是 `response.contentType()`,
> 而且它那里就写着「Tomcat 对 Content-Type 做了特判,`setHeader` 不管用」。
>
> 后果:响应体是 RFC 7807 的形状,而 `Content-Type` 还说它是普通 JSON ——
> 而中间层与客户端**就是靠那个媒体类型**认出一份 problem document 的。
>
> 另一处:`title` 用 Spring 的状态码短语表,而它在 **419** 上会说错话 ——
> 给的是 WebDAV 的老含义「Insufficient Space On Resource」,
> 而在 Laravel 里 419 是 CSRF 令牌不匹配。`title` 恰恰是给人读的那一行,
> 说错话比没有更糟,所以那一个码单独覆盖。
>
> `type` 默认 `about:blank`(RFC 7807 §4.2 的规定)——
> 编一个 `https://example.com/problems/500` 出来反而是错的:
> 规范说那个 URI 应当可解引用,而那个 URL 不存在。
>
> #### 还没有的
>
> §4 版本化 · §5 OpenAPI · §6 的条件请求 / CORS / 请求 ID / 幂等性。

---

## 4. 版本化

```java
r.prefix("/api/v1").middleware("auth:api").group(api -> {
    api.apiResource("/users", v1.UserController.class);
});
r.prefix("/api/v2").middleware("auth:api").group(api -> {
    api.apiResource("/users", v2.UserController.class);
});
```

资源层通过继承复用:

```java
public class UserResourceV2 extends UserResourceV1 {
    @Override public Map<String, Object> toArray(Request req) {
        var m = new LinkedHashMap<>(super.toArray(req));
        m.remove("email");                           // v2 移除
        m.put("contact", Map.of("email", model.getEmail()));   // v2 重构
        return m;
    }
}
```

头部版本化(`Accept: application/vnd.api+json;version=2`)也支持:

```java
r.get("/api/users", UserController::index).versioned(Map.of(
    "1", v1.UserController::index,
    "2", v2.UserController::index));
```

---

## 5. OpenAPI 生成(Java 的优势项)

Laravel 生成 OpenAPI 要靠注解或第三方包扫描,信息不全。Jaravel 有编译期信息,可以做得更好:

```bash
jaravel openapi:generate --output=public/openapi.json
```

数据来源:

| OpenAPI 字段 | 来源 |
|---|---|
| 路径与方法 | 路由表(APT 已固化) |
| 路径参数类型 | 控制器方法签名 |
| 请求体 schema | `FormRequest` 的 `rules()` 或 `@Body` DTO 的 record 组件 |
| 请求体校验约束 | 验证规则 → `minLength` / `maximum` / `pattern` / `enum` |
| 响应 schema | `JsonResource` 的 `toArray` 返回键 + 返回类型的 record 结构 |
| 响应状态码 | 异常映射表 + 控制器返回类型 |
| 安全要求 | 路由上的 `auth:*` 中间件 |
| 描述与示例 | javadoc + `@ApiDoc` 注解 |

```java
/**
 * 创建一篇文章。
 * 需要 `posts:write` 权限。
 */
@ApiDoc(summary = "创建文章", tags = "Posts",
        responses = { @ApiResponse(201, PostResource.class), @ApiResponse(422) })
public JsonResource store(StorePostRequest req) { ... }
```

`JsonResource#toArray` 的键集合从字面量 `Json.map().put("id", ...)` 链中静态提取(APT 做轻量 AST 分析)。若提取失败则退化为「运行时采样」:开发模式下记录实际响应形状生成 schema。两条路径都要有。

**附带收益**:`jaravel openapi:diff` 可在 CI 中对比上一版 schema,检测破坏性 API 变更。这是企业场景的强需求,Laravel 生态里没有开箱方案。

---

## 6. 其他 API 能力

### 6.1 条件请求与缓存

```java
return Response.json(data)
    .etag()                                       // 自动计算 ETag,匹配则 304
    .lastModified(post.getUpdatedAt())
    .cacheControl("public, max-age=300");
```

### 6.2 内容协商

```java
return Response.negotiated(data)
    .json()
    .xml()
    .csv(c -> c.headers("id", "name"));           // 按 Accept 头选择
```

### 6.3 CORS

```yaml
# config/cors.yml
cors:
  paths: [ "api/*", "sanctum/csrf-cookie" ]
  allowed-methods: [ "*" ]
  allowed-origins: [ "https://app.example.com" ]
  allowed-origins-patterns: [ "https://*.example.com" ]
  allowed-headers: [ "*" ]
  exposed-headers: [ "X-RateLimit-Remaining" ]
  max-age: 3600
  supports-credentials: true
```

### 6.4 请求 ID 与链路追踪

每个请求自动分配 `X-Request-Id`(或沿用上游传入的),写入日志上下文(MDC)与错误响应:

```json
{ "message": "Server Error.", "request_id": "01JB3K..." }
```

与 Micrometer Tracing / OpenTelemetry 集成(starter 中自动配置,若 classpath 存在)。

### 6.5 幂等性

```java
r.post("/payments", PaymentController::store).middleware("idempotent");
```

客户端传 `Idempotency-Key` 头,同 key 的重复请求返回首次的响应(存 Cache,TTL 可配)。支付类接口的硬需求,Laravel 生态需要第三方包。列入 v0.7。

> ### v0.5:§6.1 / §6.3 / §6.4 已实现(21 个测试)
>
> 实测(`examples/blog`,真 curl):
>
> ```
> $ curl -D - /api/posts/cached
> HTTP/1.1 200     ETag: W/"6854387f4a9798cd"    Cache-Control: public, max-age=300
>                  X-Request-Id: 2d10de5c-…
>
> $ curl -H 'If-None-Match: W/"6854387f4a9798cd"' /api/posts/cached
> HTTP/1.1 304     ETag: W/"6854387f4a9798cd"    (正文 0 字节)
>
> $ curl -X OPTIONS -H 'Origin: https://app.example.com' >        -H 'Access-Control-Request-Method: POST' /api/posts
> HTTP/1.1 204     Access-Control-Allow-Origin: https://app.example.com
>                  Vary: Origin     Access-Control-Allow-Methods: POST
> ```
>
> **§6.2(XML / CSV 内容协商)与 §6.5(幂等性)没做** —— 后者文档自己就
> 写着「列入 v0.7」。
>
> #### ★ ETag 只能在写出字节的那一刻算
>
> `Response.etag()` 记的是一个**意愿**,摘要在 `ResponseWriter` 里算。
> 因为在 `Response` 那一层响应体还是一个没序列化的对象 —— 当时算要么
> 把序列化提前做一遍(多一次开销,而且用的可能不是应用自己配的
> ObjectMapper),要么对着 `hashCode` 算,而**那不是内容摘要**:
> 两个 equals 的对象可以序列化成不同字节(字段顺序、日期格式)。
>
> **一个算错的 ETag 比没有 ETag 糟得多**:它会让客户端缓存住旧内容,
> 而那种 bug 在开发期(总是强刷)完全看不见。
>
> 用弱 ETag(`W/` 前缀)是诚实的:强 ETag 要求字节级相同,而这里的字节
> 来自 JSON 序列化。比较时忽略 `W/`(RFC 9110 §8.8.3.2 的弱比较)——
> 只做字符串相等的话,一旦经过某个会改写前缀的代理就永远不命中,
> 而那不会报错。
>
> #### ★ CORS 与请求 ID 是 Servlet Filter,不是中间件
>
> 这条分层早就写在 `Middleware` 的类注释里:「真正需要全局拦截的横切
> 关注点(CORS、代理头、维护模式)由 starter 注册为一个 Servlet Filter」。
> 这一轮验证了它的具体后果:
>
> **预检用 `OPTIONS`,而应用通常只注册了 GET/POST** —— 走路由匹配的话
> 它会撞上 405(v0.5 刚实现的那条路径),于是浏览器把整个跨域请求判为失败。
> 而且预检不带 Cookie 也不带 Authorization,走完整管道只会在某个中间件
> 里被拒掉。
>
> **CORS 默认是关的**:没有 `config/cors.yml`(或 `cors.paths` 为空)时
> 整个 filter 不启用。CORS 是**放宽**同源策略 —— 一个默认带着
> `Allow-Origin: *` 的框架会让每个不知道这件事的项目把 API 对全世界打开。
> 那是安全默认值问题,不是便利性问题。
>
> 三处细节各有一条测试:**回显具体的 Origin 而不是 `*`**(带凭据时浏览器
> 拒绝 `*`)并带 `Vary: Origin`(否则 CDN 可能把 A 站的响应发给 B 站);
> **通配转正则必须先转义**(`https://*.trusted.com` 不转义的话
> `https://evil-trusted.com` 也能通过 —— 一个真实的绕过);
> **上游的 `X-Request-Id` 要校验格式**(带换行的值可以往响应里注入额外的头、
> 往日志里注入伪造的行)。
>
> 请求 ID 进**错误响应体**(`request_id`)—— 那是它最有价值的一处:
> 用户截图发来一个「Server Error.」,而那条消息在生产环境下刻意不含
> 任何内部信息;没有 id 的话,从那张截图到日志里的那一条异常只能靠
> 时间戳去猜。
>
> #### ★ 顺带补上 jaravel-testing 的两个缺口
>
> 这两个功能**在真 HTTP 上全对,而在 `FeatureTest` 里一条断言都过不去**:
>
> 1. **`FeatureTest` 不跑 Filter。** 它直接调 handlerMapping + handlerAdapter,
>    那是整条管道的**后半段**;Filter 在前半段,于是任何用 Filter 实现的
>    功能在测试框架里完全不可见(预检拿到 405、响应里没有 X-Request-Id)。
>    不补的话,今后每个 Filter 功能都只能靠手工 curl 验证 ——
>    而手工验证不留痕迹。
> 2. **没有 `options(...)`。** 于是预检那条路径在测试里根本发不出来。

---

## 7. 内部架构

> ### v0.5:`jaravel-api` 模块已建,但**分页不在里面**
>
> `JsonResource` / `ResourceCollection` / `Fields` 在 `jaravel-api`
> (它同时依赖 http 与 eloquent —— 那正是它要单独成一个模块的理由:
> 资源要 `Request` 判断条件字段,要 `Rel` 判断关联有没有预加载)。
>
> **但 `Paginated` 在 `jaravel-support`,不在这里** —— 下面这张图画的位置
> **结构上不可能**:`Query.paginate()` 在 `jaravel-eloquent` 里,
> 而 `jaravel-api` 依赖 `jaravel-eloquent`。分页器放进 api 就意味着
> eloquent 要依赖 api,而 api 又依赖 eloquent —— 一个环。
>
> 所以它落在零依赖的 `jaravel-support`:查询层造它、HTTP 层序列化它、
> 视图层(将来)渲染它的链接,三者都够不到彼此,只够得到 support。
> 代价是它不知道当前请求,靠静态 resolver 补(与 Laravel 同一个结构)。
>
> `exceptions/` · `openapi/` · `negotiation/` · `middleware/` ·
> `conditional/` 这几个子包还没有。

```
jaravel-api
├── JsonResource<M> · ResourceCollection<M,R> · Fields(字段构建器)
├── pagination/  ⚠ 实际在 jaravel-support —— 理由见上
├── exceptions/
│   ├── ExceptionHandler (可覆写)
│   ├── DefaultExceptionMapper       异常 → 状态码 + 响应体
│   ├── LaravelErrorFormat · ProblemJsonFormat
│   └── DevErrorPageRenderer
├── openapi/  SchemaCollector(APT)· OpenApiGenerator · SchemaDiff
├── negotiation/  ContentNegotiator · XmlWriter · CsvWriter
├── middleware/  HandleCors · RequestId · Idempotent
└── conditional/  ETagSupport
```

---

## 8. 逃生口

| 需求 | 途径 |
|---|---|
| 不想用 Resource | 直接返回 record / Map / `ResponseEntity` |
| 需要 Spring 的 `@ResponseBody` 行为 | 返回任意 POJO 即可(Jackson 序列化) |
| 需要完全控制 JSON | `Response.json(rawJsonString)` 或注入 `ObjectMapper` |
| 需要 springdoc-openapi | 照常引入,与 Jaravel 的生成器并存(对注解式路由有效) |
| 需要自定义异常处理 | 覆写 `Handler`;或照常用 `@ControllerAdvice`(对回落到 Spring 的路由有效) |

---

## 9. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| 简单输出 | 只有 Resource | Resource + `record` 投影 | Java 有 record,更轻更快 |
| 条件字段 | `$this->when(...)` | `putWhen(...)` builder | 等价 |
| 关联输出 | `whenLoaded` | `putWhenLoaded` + 懒加载默认禁止 | 架构上消除 N+1 |
| 错误格式 | 固定 Laravel 风格 | 可切 RFC 7807 | 企业场景需求 |
| OpenAPI | 第三方扫描,信息不全 | 编译期信息生成 + CI diff | Java 的结构优势 |
| 幂等性 | 第三方包 | 内置中间件(v0.7) | 支付场景硬需求 |
| 请求 ID / 追踪 | 第三方包 | 内置 + OTel 集成 | 企业可观测性要求 |

---

## 10. 待决问题

- [ ] `Json.map().put(...)` 这个 builder 是否过于啰嗦?Laravel 的 `return ['id' => $this->id, ...]` 更短。Java 的替代方案:`Map.of(...)` 不支持 null 值且无条件字段;`LinkedHashMap` 需要变量。倾向保留 builder,但提供 `Json.of("id", id, "name", name)` 变参简写用于无条件字段的情况。
- [ ] `JsonResource#toArray` 的键集合静态提取(用于 OpenAPI)的可靠性。复杂逻辑下必然失败。倾向:静态提取做 best-effort,失败时在 `openapi:generate` 输出中明确标注「该 schema 由运行时采样得出,可能不完整」,而不是静默生成错误文档。
- [ ] 默认是否带 `data` 包裹?Laravel 带,但很多团队关掉。倾向跟随 Laravel(带),文档显著说明如何关闭。
- [ ] `Cursored` 分页的 cursor 编码(base64 的 JSON vs 不透明字符串)与排序列的约束(必须包含唯一列)。需要在 API 层给出清晰错误而非返回错误数据。
- [ ] OpenAPI 生成是 Maven 插件(构建期)还是 Artisan 命令(运行期)?两者都需要:插件用于 CI diff,命令用于本地快速查看。
