# Jaravel

> Laravel-grade developer experience, built on Spring Boot.

Jaravel is not another web framework and not a Spring replacement. It sits on
top of Spring Boot:

- **Downward**: reuses Spring IoC, AOP, transactions, the embedded container
  and the whole ecosystem;
- **Upward**: Laravel-style APIs with one obvious answer, checked at compile time;
- **Outward**: one starter, and you can still use any Spring library.

```java
@Component
public class WebRoutes implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/posts/{id}", PostController::show).name("posts.show");
    }
}
```

```java
@Model
@Fillable({"title", "body"})
public class Post extends PostBase {
    @Id public Long id;
    public String title;
    public String body;
    public Instant createdAt;
}
```

## Start here

- [Quickstart](quickstart.md)
- [Authentication](../authentication.md)
- [Routing](../routing.md)
- [Testing](../testing.md)
- [Deployment](../deployment.md)

## Status

v1.0 is feature-complete; the public release is pending. The default suite has
**2702 tests** green; parity worst case **1.29 / 1.30**.

See [V1.0-RESULT.md](https://github.com/MutouLee/Jaravel/blob/main/V1.0-RESULT.md)
for evidence and remaining release steps.
