# 路由与 HTTP

## 定义一个路由

```java
@Component
public class WebRoutes implements RouteDefinition {

    @Override
    public void define(Router r) {
        r.get("/", HomeController::index).name("home");
        r.get("/posts/{id}", PostController::show).whereNumber("id").name("posts.show");
        r.post("/posts", PostController::store).name("posts.store");
    }
}
```

控制器用**方法引用**,不是字符串:重命名方法时编译期就会断链。

## 路由参数

```java
r.get("/users/{id}", UserController::show).whereNumber("id");
r.get("/posts/{slug}", PostController::show).whereSlug("slug");
r.get("/files/{path}", FileController::show);       // 不允许 /
r.get("/catch/{rest*}", WildcardController::show);  // 允许 /
```

控制器参数按名字绑定:

```java
public Object show(Long id) { ... }
public Object show(Request request, Long id) { ... }
```

## 分组与中间件

```java
r.prefix("/admin").middleware("web", "auth").group(admin -> {
    admin.get("/dashboard", AdminController::dashboard);
});
```

内置组与别名由 `DefaultKernel` 提供:`web`(session + CSRF)、`api`、
`auth`、`guest`、`can`、`verified`、`throttle`、`password.confirm` 等。

中间件顺序由内核的 `priority()` 固定,路由上写反不会改变实际顺序。

## 响应

```java
public View page()            { return View.of("posts.index").with("posts", posts); }
public Response json()        { return Response.json(Map.of("ok", true)); }
public Response redirect()    { return Redirect.to("/posts"); }
public Response created()     { return Response.json(resource).status(201); }
public Object resource(Post p){ return new PostResource(p); }
```

- `View` → jblade 模板;
- `Response` → 显式状态码/头部;
- `JsonResource` → `{ "data": ... }` 包裹;
- record / Map → JSON。

## 表单与 CSRF

`web` 组会校验 CSRF。模板里用 `@csrf` 输出隐藏域:

```html
<form method="post" action="/login">
  @csrf
  ...
</form>
```

`@csrf` 展开成一个带当前令牌的隐藏 input —— 手写它容易把 `name` 写错
(症状是提交后 419),或者在布局里漏掉。没有会话时它**报错**,
而不是输出空令牌。

需要手写时,等价形式是
`<input type="hidden" name="_token" value="{{ csrf }}">` —— 令牌本身来自
`Session.token()`,得由控制器传给模板。

## 更多

- 中间件、参数绑定、签名 URL、内容协商:根目录 [docs/03-routing-http.md](https://github.com/MutouLee/Jaravel/blob/main/docs/03-routing-http.md)
- JSON 资源与分页:[docs/12-api-resources.md](https://github.com/MutouLee/Jaravel/blob/main/docs/12-api-resources.md)
