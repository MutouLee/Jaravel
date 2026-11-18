# 测试

## 一个功能测试

```java
class PostTest extends FeatureTest {

    @Test void lists_published_posts() {
        Post.create(Map.of("title", "第一篇", "body", "正文内容足够长", "published", true));

        get("/posts")
            .assertOk()
            .assertSee("第一篇")
            .assertDontSee("草稿");
    }

    @Test void validates_input() {
        post("/posts", Map.of("title", ""))
            .assertStatus(422)
            .assertJsonValidationErrors("title");
    }
}
```

`FeatureTest` 走完整管道(路由 → 中间件 → 参数绑定 → 控制器 → 异常处理),
只是不经过 Servlet 容器;默认每个测试自动回滚数据库。

## JSON 断言

```java
getJson("/api/posts")
    .assertOk()
    .assertJsonPath("data.0.title", "第一篇")
    .assertJsonCount(1, "data");
```

## 命令

```bash
mvn test
jaravel test --parallel --processes=4
jaravel test --tests 'Post*'
```

并行是**进程级**的:每个 fork 一个测试库(SQLite 内存/文件、PostgreSQL/MySQL
自动派生),不会共享静态状态。

浏览器测试(Playwright)与 Testcontainers 默认关闭:

```bash
mvn -pl examples/todo -am test -Djaravel.browser.enabled=true
mvn test -Djaravel.testcontainers=true
```

## 测试基类现状

| 基类 | 状态 |
|---|---|
| `FeatureTest` | ✅ 容器 + 数据库 + 进程内 HTTP |
| `BrowserTest` | ✅ 真实浏览器(默认关闭) |
| `AppTest`(无 HTTP) | ⏳ 未实现;当前用 `@SpringBootTest` 或 `FeatureTest` |
| 隔离注解 `@RefreshDatabase` 等 | ⏳ 未实现;只有默认回滚与 `@WithoutRollback` |

更完整的测试 API 与限制见 [docs/14-testing.md](https://github.com/MutouLee/Jaravel/blob/main/docs/14-testing.md)。
