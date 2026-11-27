# Testing

## A feature test

```java
class PostTest extends FeatureTest {

    @Test void lists_published_posts() {
        Post.create(Map.of("title", "First", "body", "Long enough body", "published", true));

        get("/posts")
            .assertOk()
            .assertSee("First")
            .assertDontSee("Draft");
    }

    @Test void validates_input() {
        post("/posts", Map.of("title", ""))
            .assertStatus(422)
            .assertJsonValidationErrors("title");
    }
}
```

`FeatureTest` runs the full pipeline (routing → middleware → parameter binding
→ controller → exception handling) without going through the servlet
container. Each test rolls the database back by default.

## JSON assertions

```java
getJson("/api/posts")
    .assertOk()
    .assertJsonPath("data.0.title", "First")
    .assertJsonCount(1, "data");
```

## Commands

```bash
mvn test
jaravel test --parallel --processes=4
jaravel test --tests 'Post*'
```

Parallelism is **process-level**: each fork gets its own test database (SQLite
in-memory/file; PostgreSQL/MySQL derived automatically), so no static state is
shared.

Browser tests (Playwright) and Testcontainers are off by default:

```bash
mvn -pl examples/todo -am test -Djaravel.browser.enabled=true
mvn test -Djaravel.testcontainers=true
```

## Test base classes today

| Base class | Status |
|---|---|
| `FeatureTest` | ✅ container + database + in-process HTTP |
| `BrowserTest` | ✅ a real browser (off by default) |
| `AppTest` (no HTTP) | ⏳ not implemented; use `@SpringBootTest` or `FeatureTest` for now |
| Isolation annotations such as `@RefreshDatabase` | ⏳ not implemented; only the default rollback and `@WithoutRollback` |

For the full testing API and its limits, see
[docs/14-testing.md](https://github.com/MutouLee/Jaravel/blob/main/docs/14-testing.md).
