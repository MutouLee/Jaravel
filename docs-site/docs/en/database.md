# Database and Eloquent

## Connections

`src/main/resources/config/database.yml`:

```yaml
database:
  default: ${DB_CONNECTION:sqlite}
```

The connection URL lives in `application.yml` / `.env`:

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:sqlite:app.db}
```

Supported dialects: SQLite, MySQL, PostgreSQL.

## Models

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

The APT generates `PostBase`, `Post_` (the column metamodel) and a typed
`PostQuery`.

```java
Post post = Post.findOrFail(1L);
post.title = "New title";
post.save();

var list = Post.query()
        .where(Post_.published, true)
        .orderByKeyDesc()
        .get();
```

## Migrations

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

Migration classes live in
`src/main/java/database/migrations/m<yyyy_MM_dd_HHmmss>/` — the timestamp is
part of the **package name**. Setting `jaravel.migrate.on-boot: true` in
`config/jaravel.yml` runs migrations automatically on development startup; in
production use `jaravel migrate`.

## Relations / scopes / soft deletes

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

A global scope can be bypassed explicitly with `withoutGlobalScope("tenant")`.
For a multi-tenant example see `examples/multi-tenant`: shared database +
`tenant_id` + middleware + `@GlobalScope`.

## More

- Query builder / Schema / migrations:
  [docs/05-database.md](https://github.com/MutouLee/Jaravel/blob/main/docs/05-database.md)
- Eloquent relations / eager loading / factories:
  [docs/06-eloquent.md](https://github.com/MutouLee/Jaravel/blob/main/docs/06-eloquent.md)
