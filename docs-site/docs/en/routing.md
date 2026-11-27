# Routing and HTTP

## Defining a route

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

Controllers are referenced with **method references**, not strings: renaming a
method breaks the build at compile time.

## Route parameters

```java
r.get("/users/{id}", UserController::show).whereNumber("id");
r.get("/posts/{slug}", PostController::show).whereSlug("slug");
r.get("/files/{path}", FileController::show);       // does not allow /
r.get("/catch/{rest*}", WildcardController::show);  // allows /
```

Controller parameters are bound by name:

```java
public Object show(Long id) { ... }
public Object show(Request request, Long id) { ... }
```

## Groups and middleware

```java
r.prefix("/admin").middleware("web", "auth").group(admin -> {
    admin.get("/dashboard", AdminController::dashboard);
});
```

Built-in groups and aliases come from `DefaultKernel`: `web` (session + CSRF),
`api`, `auth`, `guest`, `can`, `verified`, `throttle`, `password.confirm`, and
so on.

Middleware order is fixed by the kernel's `priority()`. Writing middleware in a
different order on a route does not change the actual order.

## Responses

```java
public View page()            { return View.of("posts.index").with("posts", posts); }
public Response json()        { return Response.json(Map.of("ok", true)); }
public Response redirect()    { return Redirect.to("/posts"); }
public Response created()     { return Response.json(resource).status(201); }
public Object resource(Post p){ return new PostResource(p); }
```

- `View` → a jblade template;
- `Response` → explicit status code/headers;
- `JsonResource` → wrapped in `{ "data": ... }`;
- record / Map → JSON.

## Forms and CSRF

The `web` group validates CSRF. In templates, use `@csrf` to emit the hidden
field:

```html
<form method="post" action="/login">
  @csrf
  ...
</form>
```

`@csrf` expands to a hidden input carrying the current token. Writing that
input by hand invites a wrong `name` (the symptom is a 419 on submit) or a
missing token in a layout. Without a session, `@csrf` **fails loudly** instead
of emitting an empty token.

The equivalent hand-written form is
`<input type="hidden" name="_token" value="{{ csrf }}">` — the token itself
comes from `Session.token()`, and the controller has to pass it to the
template.

## More

- Middleware, parameter binding, signed URLs, content negotiation: see
  [docs/03-routing-http.md](https://github.com/MutouLee/Jaravel/blob/main/docs/03-routing-http.md)
  at the repository root
- JSON resources and pagination:
  [docs/12-api-resources.md](https://github.com/MutouLee/Jaravel/blob/main/docs/12-api-resources.md)
