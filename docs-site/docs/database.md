# 数据库与 Eloquent

## 连接

`src/main/resources/config/database.yml`:

```yaml
database:
  default: ${DB_CONNECTION:sqlite}
```

连接 URL 在 `application.yml` / `.env`:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:sqlite:app.db}
```

支持的方言:SQLite、MySQL、PostgreSQL。

## 模型

```java
@Model
@Fillable({"title", "body", "published"})
public class Post extends PostBase {

    @Id public Long id;
    public String title;
    public String body;
    public boolean published;
    public Instant createdAt;
    public Instant updatedAt;
}
```

APT 生成 `PostBase`、`Post_`(列元模型)与类型化 `PostQuery`。

```java
Post post = Post.findOrFail(1L);
post.title = "新标题";
post.save();

var list = Post.query()
        .where(Post_.published, true)
        .orderByKeyDesc()
        .get();
```

## 迁移

```java
public class CreatePostsTable implements Migration {

    @Override public void up() {
        Schema.create("posts", t -> {
            t.id();
            t.string("title", 200);
            t.text("body");
            t.boolean_("published").default_(false);
            t.timestamps();
        });
    }

    @Override public void down() { Schema.dropIfExists("posts"); }
}
```

迁移类放在 `src/main/java/database/migrations/m<yyyy_MM_dd_HHmmss>/`,
时间戳在**包名**上。`config/jaravel.yml` 的 `jaravel.migrate.on-boot: true`
让开发启动时自动迁移;生产走 `jaravel migrate`。

## 关联 / 作用域 / 软删除

```java
@BelongsTo(name = "author", target = User.class)
@HasMany(name = "comments", target = Comment.class)
@BelongsToMany(name = "tags", target = Tag.class, pivot = "post_tag")
```

```java
@Scope public static Query<Post> published(Query<Post> q) {
    return q.where(Post_.published, true);
}

@GlobalScope public static Query<Post> tenant(Query<Post> q) {
    return TenantContext.current().map(t -> q.where(Post_.tenantId, t.id)).orElse(q);
}
```

全局作用域可用 `withoutGlobalScope("tenant")` 显式绕过。
多租户示例见 `examples/multi-tenant`:共享库 + `tenant_id` + 中间件 + `@GlobalScope`。

## 更多

- 查询构造器 / Schema / 迁移:[docs/05-database.md](https://github.com/MutouLee/Jaravel/blob/main/docs/05-database.md)
- Eloquent 关联 / 预加载 / 工厂:[docs/06-eloquent.md](https://github.com/MutouLee/Jaravel/blob/main/docs/06-eloquent.md)
