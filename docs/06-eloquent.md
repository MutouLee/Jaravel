# 06 · Eloquent ORM(项目胜负点)

> ⚠️ **本文档部分内容已被 v0.0 验证取代。**
> 模型定义形态已由 ADR-010 最终确定为**方案 E'**:标量列用 public 字段、脏跟踪用快照比对、
> 关联用**类级注解**声明(不是字段)。原 §1 的字段级关联注解与 §2.2 的方案 B(字节码织入)**已作废**。
> 结论与实测数据见 [verification/v1-model-accessor/RESULT.md](../verification/v1-model-accessor/RESULT.md)。
> 本文档的全面改写列入 v0.2 的文档任务。

这是 Jaravel 最难、也最具决定性的模块。**如果这里做不到「比 Spring Data JPA 明显更爽」,整个项目就没有存在意义。**

---

## 设计目标

1. 模型定义的信息密度对齐 Laravel(一个字段一行,不要 getter/setter 噪音);
2. 查询的简洁度对齐 Laravel,**同时类型安全**(列名拼错编译期报错);
3. **零运行时反射**——全靠 APT 在编译期生成;
4. 关联与预加载从根上解决 N+1,并在开发期主动告警;
5. 生成代码的错误信息必须友好(这是 DX 的生死线)。

---

## 1. 模型定义

```java
@Model(table = "users")
@SoftDeletes
public class User extends Model<User> {

    @Id Long id;

    String name;
    @Unique String email;
    @Hidden String password;                      // 序列化时排除
    @Cast(Json.class) Map<String, Object> meta;
    @Cast(Encrypted.class) String ssn;
    UserStatus status;                            // enum ↔ 字符串自动转换
    Instant emailVerifiedAt;                      // 驼峰 ↔ email_verified_at 自动映射

    @Default("0") int loginCount;

    // 关联
    @HasOne(Profile.class)                      Rel1<Profile> profile;
    @HasMany(Post.class)                        Rel<Post> posts;
    @BelongsTo(Team.class)                      Rel1<Team> team;
    @BelongsToMany(value = Role.class,
                   pivot = "role_user",
                   foreignKey = "user_id",
                   relatedKey = "role_id",
                   withPivot = {"granted_at"})  Rel<Role> roles;
    @HasManyThrough(value = Comment.class, through = Post.class) Rel<Comment> comments;
    @MorphMany(value = Image.class, name = "imageable")         Rel<Image> images;

    // 查询作用域
    @Scope public static Query<User> active(Query<User> q) {
        return q.where(User_.status, UserStatus.ACTIVE);
    }
    @Scope public static Query<User> olderThan(Query<User> q, int age) {
        return q.where(User_.age, Op.GT, age);
    }

    // 全局作用域
    @GlobalScope public static Query<User> notBanned(Query<User> q) {
        return q.where(User_.banned, false);
    }

    // 访问器 / 修改器
    @Accessor public String fullName() { return name + " <" + email + ">"; }
    @Mutator("name") public String setNameValue(String v) { return Str.title(v); }

    // 追加到 JSON 输出的计算属性
    @Appends public boolean isAdmin() { return status == UserStatus.ADMIN; }
}
```

### 1.1 约定(与 Laravel 一致)

| 约定 | 规则 | 覆盖方式 |
|---|---|---|
| 表名 | 类名 snake_case 复数:`User` → `users` | `@Model(table = "...")` |
| 主键 | `id`,`Long`,自增 | `@Id(name = "uuid", incrementing = false)` |
| 时间戳 | `created_at` / `updated_at` 自动维护 | `@Model(timestamps = false)` |
| 列名 | 字段名 snake_case:`emailVerifiedAt` → `email_verified_at` | `@Column("custom_name")` |
| 外键 | `{model}_id`:`Post.user_id` | 注解参数显式指定 |
| 中间表 | 两表名字典序 snake_case 单数拼接:`role_user` | `@BelongsToMany(pivot = "...")` |

---

## 2. APT 生成物规格(核心)

`jaravel-eloquent-processor` 处理 `@Model`,为每个模型生成四件东西。

### 2.1 元模型 `User_`

```java
public final class User_ {
    public static final String TABLE = "users";

    public static final Col<User, Long>    ID                = Col.of("id", Long.class);
    public static final Col<User, String>  NAME              = Col.of("name", String.class);
    public static final Col<User, String>  EMAIL             = Col.of("email", String.class);
    public static final Col<User, Instant> EMAIL_VERIFIED_AT = Col.of("email_verified_at", Instant.class);
    public static final Col<User, UserStatus> STATUS         = Col.of("status", UserStatus.class);

    public static final RelPath<User, Post>    POSTS   = RelPath.of("posts");
    public static final RelPath<User, Profile> PROFILE = RelPath.of("profile");

    // 小写别名,供偏好低噪音写法者使用
    public static final Col<User, String> name = NAME;
    public static final Col<User, String> email = EMAIL;
    // ...
}
```

`Col<M, V>` 带两个类型参数:`M` 保证列属于正确的模型(不能把 `Post_.TITLE` 用在 `User.query()` 上),`V` 保证比较值类型正确(`where(User_.NAME, 123)` 编译失败)。

**这是整个 ORM 类型安全的支点。**

### 2.2 访问器实现

生成 getter/setter、`equals`/`hashCode`、`toString`、属性读写表:

```java
// 注入到 User 的能力(通过生成的 UserAccessor 或字节码织入)
public String getName() { ... }
public void setName(String v) { markDirty("name"); this.name = v; }
```

**实现路径选择**(待决,见 §9):

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. 用户继承生成的基类 | APT 生成 `UserBase`,用户写 `class User extends UserBase` | 标准 APT,无魔法 | 字段必须声明在生成的基类里,用户写模型时要分两处 |
| B. 字节码增强 | Maven 插件在 `process-classes` 阶段织入 getter/setter | 用户只写字段,最干净 | 需要字节码工具(ByteBuddy/ASM),IDE 需插件才能提示 getter |
| C. 要求用户写 getter/setter | APT 只生成元模型 | 零魔法 | 噪音大,违背「信息密度」目标 |
| D. record + 伴生可变 Builder | 不可变模型 | 现代、安全 | Active Record 需要可变性,冲突 |

**倾向方案 B + IDE 插件**,因为「用户只写字段」是 Laravel 优雅感的核心;Lombok 已证明这条路在 Java 生态可被接受,且 IntelliJ 对字节码增强的 getter 有成熟的插件支持机制。**v0.1 先用方案 A 验证整体架构(不卡在工具链上),v0.2 切 B。**

### 2.3 类型化查询器 `UserQuery`

```java
public final class UserQuery extends Query<User> {
    // 从 @Scope 生成
    public UserQuery active() { return (UserQuery) User.active(this); }
    public UserQuery olderThan(int age) { return (UserQuery) User.olderThan(this, age); }

    // 从字段生成的便捷条件
    public UserQuery whereName(String v)            { return where(User_.NAME, v); }
    public UserQuery whereNameLike(String v)        { return whereLike(User_.NAME, v); }
    public UserQuery whereEmail(String v)           { return where(User_.EMAIL, v); }
    public UserQuery whereStatusIn(UserStatus... v)  { return whereIn(User_.STATUS, v); }
    public UserQuery whereLoginCountGt(int v)       { return where(User_.LOGIN_COUNT, Op.GT, v); }
    public UserQuery orderByCreatedAtDesc()         { return orderByDesc(User_.CREATED_AT); }
    // ... 按字段类型生成对应的运算符变体(数值型生成 Gt/Gte/Lt/Lte/Between,字符串生成 Like/StartsWith)
}
```

**生成便捷方法的数量控制**:每个字段生成 2~6 个方法,20 字段的模型约生成 80 个方法。这是可接受的(Spring Data 的 `findByXxxAndYyy` 派生查询是运行时解析方法名,Jaravel 是编译期生成真实方法,IDE 自动完成体验更好)。可通过 `@Model(generateHelpers = false)` 关闭。

### 2.4 关联元数据与序列化器

- `UserRelations`:关联的 SQL 构建与预加载逻辑,无反射;
- `UserJsonSerializer`:Jackson 序列化器,编译期固化 `@Hidden` / `@Appends` / 字段顺序。

### 2.5 模型索引

生成 `META-INF/jaravel/models.idx`,启动时读取,**零类路径扫描**。

---

## 3. 查询 API

```java
// 静态入口(生成在 Model 基类,通过 @Model 的 APT 注入)
User u          = User.find(1L);                   // null 若不存在
Optional<User> o = User.tryFind(1L);
User u2         = User.findOrFail(1L);             // 抛 ModelNotFoundException → 404
Coll<User> all  = User.all();
UserQuery q     = User.query();

// 链式
Coll<User> list = User.query()
        .active()                                   // @Scope 生成
        .where(User_.LOGIN_COUNT, Op.GT, 5)
        .whereIn(User_.STATUS, ACTIVE, PENDING)
        .whereNull(User_.DELETED_AT)
        .whereHas(User_.POSTS, p -> p.where(Post_.PUBLISHED, true))   // 关联存在性
        .whereDoesntHave(User_.BANS)
        .withCount(User_.POSTS)                     // posts_count
        .with(User_.POSTS, User_.PROFILE)           // 预加载
        .with(User_.POSTS, p -> p.latest().limit(5))// 带约束的预加载
        .orderByDesc(User_.CREATED_AT)
        .get();

// 单条
Optional<User> first = User.query().where(User_.EMAIL, e).first();
User firstOr = User.query().where(User_.EMAIL, e).firstOrFail();
User created = User.query().firstOrCreate(Map.of("email", e), Map.of("name", n));
User updated = User.query().updateOrCreate(Map.of("email", e), Map.of("name", n));
User newOne  = User.query().firstOrNew(Map.of("email", e));

// 聚合与分页
long n = User.query().active().count();
Paginated<User> page = User.query().paginate(15);
Cursored<User> cur = User.query().cursorPaginate(15);

// 大数据集
User.query().chunkById(1000, batch -> { ... });
try (var c = User.query().cursor()) { c.forEach(...); }
LazyColl<User> lazy = User.query().lazy();

// 批量写
User.query().where(User_.STATUS, PENDING).update(Map.of("status", ACTIVE));
User.query().where(User_.CREATED_AT, Op.LT, cutoff).delete();

// 投影到 DTO(避免取全列)
Coll<UserCard> cards = User.query().selectInto(UserCard.class, User_.ID, User_.NAME);
```

### 3.1 实例操作

```java
User u = new User();
u.setName("Tom");
u.setEmail("t@a.com");
u.save();                      // INSERT,自动填 created_at/updated_at,回填自增 id

u.setName("Jerry");
u.save();                      // UPDATE,且只更新脏字段:update users set name=?, updated_at=? where id=?

u.fill(Map.of("name", "X"));   // 批量赋值(受 @Guarded 保护)
u.refresh();                   // 重新从库读
u.replicate();                 // 复制为新实例(不含主键)
u.delete();                    // 软删除(有 @SoftDeletes)或硬删除
u.forceDelete();
u.restore();
boolean dirty = u.isDirty();
boolean dirtyName = u.isDirty(User_.NAME);
Map<String,Object> changes = u.getChanges();
Object original = u.getOriginal(User_.NAME);
boolean exists = u.exists();
boolean was = u.wasRecentlyCreated();
```

### 3.2 脏字段跟踪

每个 setter 调用 `markDirty("col")`,维护一个 `BitSet`(字段数通常 < 64,所以用 `long` 位图,零分配)。`save()` 时只 UPDATE 脏字段。

相比 Hibernate 的脏检查(需要快照整个对象并逐字段比较,或字节码增强),这个方案**简单一个数量级**且语义明确:**只有你调用了 setter 的字段会被写回**。

`@Model(fullDirtyCheck = true)` 可开启「保存时与原始快照比较」的模式(用于 `fill()` 后或直接改字段的场景)。

### 3.3 批量赋值保护

```java
@Model(table = "users")
@Guarded({"id", "role", "is_admin"})       // 白名单模式用 @Fillable
public class User extends Model<User> { ... }
```

`u.fill(request.all())` 时自动过滤被保护字段,防止权限提升攻击。与 Laravel 一致,且 APT 可在编译期校验 `@Guarded` 的列名存在。

---

## 4. 关联

### 4.1 关联类型

| 注解 | 对应 Laravel | 返回类型 |
|---|---|---|
| `@HasOne` | `hasOne` | `Rel1<T>` |
| `@HasMany` | `hasMany` | `Rel<T>` |
| `@BelongsTo` | `belongsTo` | `Rel1<T>` |
| `@BelongsToMany` | `belongsToMany` | `Rel<T>`(带 pivot) |
| `@HasOneThrough` | `hasOneThrough` | `Rel1<T>` |
| `@HasManyThrough` | `hasManyThrough` | `Rel<T>` |
| `@MorphOne` | `morphOne` | `Rel1<T>` |
| `@MorphMany` | `morphMany` | `Rel<T>` |
| `@MorphTo` | `morphTo` | `Rel1<Object>` + 类型解析器 |
| `@MorphToMany` | `morphToMany` | `Rel<T>` |

### 4.2 `Rel<T>` 的设计

```java
public interface Rel<T> extends Iterable<T> {
    Coll<T> get();                      // 已预加载 → 返回缓存;否则触发查询
    Query<T> query();                   // 继续加条件:user.posts().query().published().get()
    Optional<T> first();
    long count();
    boolean isLoaded();

    // 写操作
    T create(Map<String, Object> attrs);
    void save(T model);
    void saveMany(Collection<T> models);
    void attach(Object... ids);                       // 多对多
    void attach(Object id, Map<String, Object> pivot);
    void detach(Object... ids);
    void sync(Collection<?> ids);
    void toggle(Collection<?> ids);
    void updateExistingPivot(Object id, Map<String, Object> pivot);
}
```

### 4.3 N+1 防御(对 Laravel 的重要改进)

三道防线:

**第一道:预加载是显式且类型安全的**

```java
User.query().with(User_.POSTS, User_.PROFILE).get();
```

**第二道:开发模式下懒加载告警**

```yaml
# config/database.yml
eloquent:
  prevent-lazy-loading: ${APP_DEBUG:false}
```

开启后,访问未预加载的关联直接抛异常:

```
LazyLoadingViolationException:
  试图在未预加载的情况下访问 User#posts。
  位置: PostController.index (PostController.java:24)
  修复: User.query().with(User_.POSTS) ...
  或在 config/database.yml 中关闭 eloquent.prevent-lazy-loading
```

Laravel 8.43+ 有 `Model::preventLazyLoading()`,但默认关闭且常被忽略。Jaravel **默认在 debug 模式开启**——让 N+1 在开发阶段就无法通过,而不是上线后看慢查询日志。

**第三道:查询计数告警**

单请求 SQL 条数超阈值(默认 50)时,在开发期错误页/日志中列出全部 SQL 与调用栈。

### 4.4 预加载实现

```java
// 查主表
Coll<User> users = select * from users where ...;          // 1 条
// 收集主键,批量查关联
List<Long> ids = users.pluck(User::getId).toList();
Coll<Post> posts = select * from posts where user_id in (?, ?, ...);   // 1 条
// 分组回填
Map<Long, Coll<Post>> grouped = posts.groupBy(Post::getUserId);
users.each(u -> u.posts.setLoaded(grouped.getOrDefault(u.getId(), Coll.empty())));
```

嵌套预加载 `with("posts.comments.author")` → 逐层递归。类型安全形式:

```java
User.query().with(User_.POSTS.then(Post_.COMMENTS).then(Comment_.AUTHOR)).get();
```

`RelPath` 的 `then` 方法由 APT 生成,带泛型约束,**跨模型的路径拼错编译期报错**。这是 Laravel 的字符串 `"posts.comments.author"` 做不到的。

`in (?, ...)` 的参数数量上限(Oracle 1000、SQL Server 2100)自动分批。

### 4.5 身份映射(请求级)

同一请求内,同一主键的同一模型只实例化一次(存在 `RequestScope` 的 `IdentityMap` 中)。好处:

- 预加载回填后,`post.author` 与 `user` 是同一对象;
- 避免同一行被查多次。

可通过 `@Model(identityMap = false)` 关闭。

---

## 5. 作用域、软删除、事件

### 5.1 全局作用域

```java
@GlobalScope public static Query<User> notBanned(Query<User> q) { return q.where(User_.BANNED, false); }
```

绕过:

```java
User.query().withoutGlobalScope("notBanned").get();
User.query().withoutGlobalScopes().get();
```

典型用途:多租户。

```java
@Model(table = "posts")
public class Post extends Model<Post> {
    @GlobalScope public static Query<Post> tenant(Query<Post> q) {
        return Tenancy.current()
                .map(t -> q.where(Post_.TENANT_ID, t.id()))
                .orElse(q);
    }
}
```

### 5.2 软删除

```java
@SoftDeletes  // → deleted_at 列 + 自动全局作用域
public class Post extends Model<Post> { ... }

Post.query().get();                      // 自动排除已删
Post.query().withTrashed().get();
Post.query().onlyTrashed().get();
post.delete();  post.restore();  post.forceDelete();
boolean t = post.isTrashed();
```

### 5.3 模型事件与观察者

```java
public class UserObserver implements Observer<User> {
    public void creating(User u)  { u.setUuid(Str.uuid()); }
    public void created(User u)   { Queue.dispatch(new SendWelcomeEmail(u.getId())); }
    public void updating(User u)  { }
    public void updated(User u)   { }
    public void saving(User u)    { }
    public void saved(User u)     { }
    public void deleting(User u)  { }
    public void deleted(User u)   { }
    public void restored(User u)  { }
    public void retrieved(User u) { }
}

// AppServiceProvider#boot
User.observe(UserObserver.class);
// 或注解自动发现
@ObservesModel(User.class) public class UserObserver implements Observer<User> { }
```

`creating` / `updating` / `deleting` 返回 `false` 或抛 `AbortException` 可中止操作(与 Laravel 一致)。

事件默认**在事务内同步触发**。需要事务提交后执行的副作用用 `DB.afterCommit()` 或 `Queue.dispatch(...).afterCommit()`。

批量操作(`User.query().update(...)`)**不触发模型事件**——与 Laravel 一致,且在 javadoc 中明确警告。

### 5.4 类型转换

```java
@Cast(Json.class)         Map<String, Object> meta;
@Cast(Encrypted.class)    String ssn;
@Cast(AsCollection.class) Coll<String> tags;
@Cast(value = Money.class, args = {"CNY"}) BigDecimal price;
UserStatus status;        // enum 自动(按 name();@EnumValue 可指定按值)
boolean active;           // tinyint(1)/boolean 自动
Instant createdAt;        // timestamp 自动
LocalDate birthday;       // date 自动
```

自定义:

```java
public class Money implements CastsAttributes<BigDecimal, Long> {
    public BigDecimal fromDb(Long cents) { return BigDecimal.valueOf(cents, 2); }
    public Long toDb(BigDecimal yuan)    { return yuan.movePointRight(2).longValueExact(); }
}
```

---

## 6. 生成代码的错误信息(头号质量要求)

APT 必须对以下场景给出精确、可操作的报错:

```
[ERROR] app/Models/User.java:14
        @HasMany(Post.class) Rel<Comment> comments;
                                 ^^^^^^^
        @HasMany 声明的目标类型是 Post,但字段泛型是 Comment。
        修复: 改为 Rel<Post>,或把注解改为 @HasMany(Comment.class)。
```

```
[ERROR] app/Models/Post.java:9
        @BelongsTo(User.class) Rel1<User> author;
        ^^^^^^^^^^
        推断的外键 `user_id` 在模型 Post 上不存在(按 @BelongsTo 约定应为 {目标表单数}_id)。
        已声明的字段: id, title, body, authorId, createdAt
        修复: @BelongsTo(value = User.class, foreignKey = "author_id")
              或把字段 authorId 改名为 userId。
```

```
[ERROR] app/Models/User.java:20
        @Guarded({"id", "rolee"})
                        ^^^^^^^
        列 `rolee` 不存在。是否想写 `role`?
```

```
[ERROR] app/Http/Controllers/PostController.java:31
        Post.query().where(User_.EMAIL, e)
                           ^^^^^^^^^^^
        不兼容的类型: Col<User,String> 无法转换为 Col<Post,?>
        (Jaravel 的 Col 带模型类型参数,防止跨模型误用列)
```

最后一条是 javac 的天然报错,只需 `Col<M,V>` 的泛型设计正确即可获得。

**验收标准**:为上述每类错误编写一个编译失败测试(`compile-testing` 库),断言错误信息包含预期提示。这组测试是本模块的第一优先级,早于功能实现。

---

## 7. 逃生口

| 需求 | 途径 |
|---|---|
| 查询构造器表达不了 | `User.query().raw(sql, args)` / `DB.select(...)` 后 `User.hydrate(rows)` |
| 不想用 Active Record | 用 [05](05-database.md) 的查询构造器 + `record` DTO |
| 需要 JPA 的能力 | `jaravel-jpa-bridge` |
| 模型需要复杂构造逻辑 | `@Model` 不限制你写任意方法与构造函数 |
| APT 生成的便捷方法太多 | `@Model(generateHelpers = false)`,只留元模型 |
| 需要绕过所有作用域 | `withoutGlobalScopes()` / `DB.table()` |

---

## 8. 与 Laravel Eloquent 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| 列引用 | `'email'` 字符串 | `User_.EMAIL`(带模型泛型) | 编译期校验,核心卖点 |
| 嵌套预加载 | `'posts.comments'` | `POSTS.then(COMMENTS)` | 同上 |
| 动态属性 | `$user->any_column` | 必须声明字段 | Java 静态类型;换来 IDE 与重构 |
| 属性访问 | `$user->name` | `user.getName()` | 字节码增强生成;无法做到无括号 |
| 懒加载 | 默认允许 | **debug 模式默认禁止** | N+1 应在开发期暴露 |
| 脏检查 | 全字段快照比较 | setter 位图跟踪(可选全量) | 更简单、零分配 |
| 关联声明 | 方法返回 `HasMany` | 字段 + 注解 | 字段形式信息密度更高,且 APT 可校验 |
| 身份映射 | 无 | 请求级 IdentityMap | 消除重复查询与对象不一致 |
| 模型工厂 | `UserFactory` | 同名同形 | — |

---

## 9. 工作量与风险

| 子项 | 估算 | 风险 |
|---|---|---|
| `@Model` 解析 + 元模型生成 | 2 周 | 低 |
| 访问器生成(方案 A) | 1 周 | 低 |
| 字节码增强(方案 B) | 3 周 | **高**,IDE 集成 + 多构建工具兼容 |
| 类型化查询器生成 | 2 周 | 中,方法组合爆炸需控制 |
| 关联(10 种)+ 预加载 | 5 周 | 中高,多态关联与 through 关联最复杂 |
| 脏跟踪 + save/delete 流程 | 1.5 周 | 低 |
| 作用域(局部/全局)+ 软删除 | 1.5 周 | 中,全局作用域与预加载的交互 |
| 模型事件 + 观察者 | 1 周 | 低 |
| 类型转换系统 | 1 周 | 低 |
| **错误信息质量 + compile-testing 测试** | **3 周** | 不可省 |
| 身份映射 | 1 周 | 中,与请求作用域耦合 |
| 工厂 + Faker 集成 | 1 周 | 低 |
| **合计** | **约 23 周 / 1 人**(方案 B 则 25 周) | |

**最大风险**:§2.2 的访问器生成方案。若方案 B(字节码增强)的 IDE 体验搞不定,模型定义就得退回到方案 A 或 C,信息密度下降,优雅性受损。

**缓解**:v0.1 阶段花 3 天做方案 B 的技术验证——用 ByteBuddy 在 Maven `process-classes` 阶段给一个类加 getter,验证 IntelliJ 是否能识别(需要配套 IDE 插件,参照 Lombok 插件的实现方式)。若验证失败,**果断用方案 A**(用户写 `class User extends UserBase`),这个妥协是可接受的——Spring Data 用户早已习惯更重的样板。

---

## 10. 待决问题

- [ ] **访问器生成方案 A/B/C 的最终选择**(见 §2.2 与 §9)。这是本模块最重要的待决项,v0.1 必须验证。
- [ ] 元模型常量命名:`User_.EMAIL`(大写,JPA 风格)还是 `User_.email`(小写,更短)?目前生成两套别名,但两套并存会造成代码风格不一致。倾向主推小写 `User_.email`,大写作为兼容别名。
- [ ] `Rel<T>` 字段的初始化:用户 `new User()` 时 `posts` 字段为 null。需要字节码增强在构造函数末尾注入初始化,或在首次访问时惰性创建(需要访问器拦截)。这与方案 A/B 的选择强耦合。
- [ ] 便捷方法生成的数量上界:50 字段的模型会生成 200+ 方法,IDE 自动完成列表可能过长。考虑只为标注 `@Queryable` 的字段生成,或按类型精简(字符串只生成 `whereX`/`whereXLike`)。
- [ ] 身份映射与长事务/队列任务的交互:无请求作用域时 IdentityMap 挂在哪里?倾向挂在 `JobScope`,无作用域时禁用。
- [ ] 多态关联(`@MorphTo`)的类型安全:返回 `Rel1<Object>` 很丑。考虑生成 sealed interface + pattern matching:
  ```java
  switch (image.imageable().get()) {
      case Post p -> ...;
      case User u -> ...;
  }
  ```
  需要 `@MorphTo(types = {Post.class, User.class})` 声明可能的类型。倾向采用,这是 Java 21 模式匹配的好用例。
