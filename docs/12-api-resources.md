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

---

## 7. 内部架构

```
jaravel-api
├── JsonResource<M> · ResourceCollection<M,R> · Json.map() builder
├── pagination/  Paginated<T> · SimplePaginated<T> · Cursored<T> · PaginationRenderer
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
