# 05 · 数据库:查询构造器、Schema、迁移

本模块是 Eloquent 的底座,**独立可用**(不用 ORM 也能用查询构造器)。

---

## 设计目标

1. 查询构造器的表达力对齐 Laravel 的 `Illuminate\Database\Query\Builder`;
2. 多方言支持(MySQL / PostgreSQL / SQLite / SQL Server / Oracle),方言差异收敛在 `Grammar`;
3. 不引入 Hibernate——**明确的架构决策**,见 §7;
4. 迁移是 Java 代码而非 SQL 文件,`up()`/`down()` 对称,支持回滚;
5. 事务 API 简洁:`DB.transaction(() -> {...})`。

---

## 1. 连接管理

```java
public interface DatabaseManager {
    Connection connection();                      // 默认连接
    Connection connection(String name);
    void purge(String name);
    List<QueryLog> queryLog();
    void enableQueryLog();  void flushQueryLog();
    void listen(Consumer<QueryExecuted> listener);
}
```

底层 `DataSource` 由 Spring 提供(HikariCP)。多连接时在 starter 里为每个 `config/database.yml` 的 connection 注册一个 `DataSource`。

```java
DB.connection("analytics").table("events").count();
DB.table("users").get();                          // 默认连接
```

### 1.1 读写分离

```yaml
database:
  connections:
    mysql:
      read:
        host: [ 10.0.0.2, 10.0.0.3 ]
      write:
        host: 10.0.0.1
      sticky: true        # 同一请求内写后读走主库
```

`sticky` 的实现依托请求作用域([01-architecture.md](01-architecture.md) §4):一旦本请求发生写操作,在 `RequestScope` 上打标,后续读走 write 连接。

---

## 2. 查询构造器

### 2.1 基本查询

```java
// SELECT
List<Row> users = DB.table("users").get();
Optional<Row> u  = DB.table("users").where("id", 1).first();
Row u2           = DB.table("users").find(1);
String email     = DB.table("users").where("id", 1).value("email", String.class);
List<String> es  = DB.table("users").pluck("email", String.class);

DB.table("users").select("id", "name", "email as mail").get();
DB.table("users").selectRaw("count(*) as cnt, status").groupBy("status").get();
DB.table("users").distinct().pluck("status", String.class);

// 聚合
long n          = DB.table("users").count();
BigDecimal sum  = DB.table("orders").sum("amount");
BigDecimal avg  = DB.table("orders").avg("amount");
Object max      = DB.table("orders").max("amount");
boolean exists  = DB.table("users").where("email", e).exists();
```

### 2.2 Where 子句

```java
DB.table("users")
  .where("status", "active")                       // = 省略
  .where("age", ">", 18)
  .orWhere("role", "admin")
  .whereIn("id", List.of(1, 2, 3))
  .whereNotIn("id", subQuery)
  .whereNull("deleted_at")
  .whereNotNull("email_verified_at")
  .whereBetween("created_at", from, to)
  .whereNotBetween("age", 13, 19)
  .whereDate("created_at", "2026-10-28")
  .whereMonth("created_at", 10)
  .whereYear("created_at", 2026)
  .whereTime("created_at", ">", "09:00")
  .whereColumn("updated_at", ">", "created_at")
  .whereLike("name", "%tom%")                      // 方言感知(PG 可选 ILIKE)
  .whereExists(q -> q.from("orders").whereColumn("orders.user_id", "users.id"))
  .whereJsonContains("meta->tags", "vip")
  .whereFullText(List.of("title", "body"), "jaravel")
  .where(q -> q.where("a", 1).orWhere("b", 2))     // 嵌套分组 → AND (a=1 OR b=2)
  .when(filter != null, q -> q.where("type", filter))   // 条件式
  .unless(all, q -> q.where("visible", true))
  .tap(q -> log.debug(q.toSql()))
  .get();
```

### 2.3 JOIN

```java
DB.table("users")
  .join("posts", "users.id", "=", "posts.user_id")
  .leftJoin("profiles", "users.id", "=", "profiles.user_id")
  .rightJoin(...)  .crossJoin("colors")
  .join("comments", j -> j.on("posts.id", "=", "comments.post_id")
                          .where("comments.approved", true))
  .joinSub(latestPosts, "lp", j -> j.on("users.id", "=", "lp.user_id"))
  .select("users.*", "posts.title")
  .get();
```

### 2.4 排序、分组、分页

```java
DB.table("users")
  .orderBy("created_at", "desc")
  .orderByDesc("id")
  .latest()  .oldest()  .inRandomOrder()
  .reorder()
  .groupBy("status", "role")
  .having("count(*)", ">", 10)
  .havingRaw("sum(amount) > ?", 1000)
  .limit(10).offset(20)
  .get();

// 分页
Paginated<Row> p   = DB.table("users").paginate(15);         // 带总数
Paginated<Row> sp  = DB.table("users").simplePaginate(15);   // 不查总数,快
Cursored<Row> cp   = DB.table("users").cursorPaginate(15);   // 游标分页,大表友好
```

### 2.5 大结果集

```java
DB.table("users").orderBy("id").chunk(1000, rows -> { /* 每批处理 */ });
DB.table("users").chunkById(1000, rows -> { ... });          // 按主键游走,不受并发插入影响
try (var cursor = DB.table("users").cursor()) {              // 流式,内存恒定
    cursor.forEach(row -> { ... });
}
LazyColl<Row> lazy = DB.table("users").lazy();
```

### 2.6 写入

```java
DB.table("users").insert(Map.of("name", "Tom", "email", "t@a.com"));
DB.table("users").insert(List.of(map1, map2));              // 批量
long id = DB.table("users").insertGetId(map);
DB.table("users").insertOrIgnore(rows);
DB.table("users").upsert(rows, List.of("email"), List.of("name", "updated_at"));

DB.table("users").where("id", 1).update(Map.of("name", "Jerry"));
DB.table("users").where("id", 1).updateOrInsert(matchMap, valuesMap);
DB.table("users").where("id", 1).increment("login_count");
DB.table("users").where("id", 1).decrement("balance", 100);
DB.table("users").where("id", 1).delete();
DB.table("users").truncate();
```

### 2.7 原生 SQL(逃生口)

```java
List<Row> rows = DB.select("select * from users where age > ?", 18);
List<UserDto> dtos = DB.select("select ...", UserDto.class, 18);     // 映射到 record
int affected = DB.update("update users set x = ? where id = ?", v, id);
DB.statement("create index ...");
DB.unprepared("...");

// 片段级原生
DB.table("users").select(DB.raw("count(*) as cnt")).get();
DB.table("users").whereRaw("age > ? and status = ?", 18, "active").get();
```

### 2.8 调试

```java
String sql = DB.table("users").where("id", 1).toSql();        // "select * from `users` where `id` = ?"
String full = DB.table("users").where("id", 1).toRawSql();    // 绑定值内联,便于复制到客户端
DB.table("users").dd();                                        // 打印并抛出
DB.table("users").dump();                                      // 打印并继续
```

---

## 3. 事务

```java
// 闭包式(推荐):异常自动回滚,正常自动提交
DB.transaction(() -> {
    order.save();
    Inventory.query().where(Inventory_.sku, sku).decrement("qty", 1);
});

// 带返回值
Order o = DB.transaction(() -> { ... return order; });

// 死锁重试
DB.transaction(() -> { ... }, 3);

// 手动
DB.beginTransaction();
try { ...; DB.commit(); } catch (Exception e) { DB.rollBack(); throw e; }

// 传播与隔离
DB.transaction(TxOptions.of(REQUIRES_NEW, READ_COMMITTED), () -> { ... });

// 保存点(嵌套事务)
DB.transaction(() -> {
    a.save();
    DB.transaction(() -> b.save());     // 自动用 SAVEPOINT
});

// 提交后回调(避免「事务未提交就发了消息」的经典 bug)
DB.afterCommit(() -> Queue.dispatch(new SendInvoice(order.getId())));
```

实现:`DB.transaction` 委托 Spring 的 `TransactionTemplate`,因此与 `@Transactional`、JPA、Spring Data 共享同一事务。`DB.afterCommit` 挂到 `TransactionSynchronizationManager`。

> `Queue.dispatch(...).afterCommit()` 是更常用的写法,默认行为可在 `config/queue.yml` 中设为全局 `after-commit: true`。

---

## 4. Schema DSL

```java
Schema.create("posts", t -> {
    t.id();                                       // bigIncrements("id")
    t.uuid("uuid").unique();
    t.ulid("ulid");
    t.foreignId("user_id").constrained("users").cascadeOnDelete();
    t.string("title", 200);
    t.string("slug").unique();
    t.text("body");
    t.mediumText("notes");  t.longText("raw");
    t.integer("views").unsigned().default_(0);
    t.bigInteger("bytes");
    t.decimal("price", 10, 2);
    t.float_("ratio", 8, 2);
    t.boolean_("published").default_(false);
    t.date("publish_on");
    t.dateTime("reviewed_at", 3);
    t.timestamp("published_at").nullable();
    t.time("open_at");
    t.json("meta").nullable();
    t.jsonb("doc");                               // PG;其他方言降级为 json/text
    t.enum_("status", List.of("draft", "published"));
    t.binary("blob");
    t.ipAddress("ip");  t.macAddress("mac");
    t.morphs("commentable");                      // commentable_id + commentable_type
    t.nullableMorphs("owner");
    t.softDeletes();                              // deleted_at
    t.timestamps();                               // created_at + updated_at
    t.rememberToken();

    t.primary(List.of("a", "b"));
    t.unique(List.of("user_id", "slug"));
    t.index("published_at");
    t.index(List.of("a", "b"), "idx_ab");
    t.fullText(List.of("title", "body"));
    t.spatialIndex("location");
    t.comment("文章表");
    t.engine("InnoDB");  t.charset("utf8mb4");  t.collation("utf8mb4_unicode_ci");
});

Schema.table("posts", t -> {
    t.string("subtitle").nullable().after("title");
    t.renameColumn("body", "content");
    t.dropColumn("notes");
    t.dropIndex("idx_ab");
    t.dropForeign("posts_user_id_foreign");
    t.dropSoftDeletes();
    t.string("title", 300).change();              // 修改列
});

Schema.rename("posts", "articles");
Schema.drop("posts");
Schema.dropIfExists("posts");
Schema.hasTable("posts");
Schema.hasColumn("posts", "title");
Schema.getColumnListing("posts");
Schema.disableForeignKeyConstraints();
Schema.withoutForeignKeyConstraints(() -> { ... });
Schema.connection("analytics").create(...);
```

> Java 的保留字冲突:`boolean` / `float` / `double` / `enum` / `long` / `char` 作为方法名非法,统一加尾部下划线(`boolean_`、`float_`、`enum_`),并提供别名 `bool()`、`floating()`、`enumeration()` 供偏好无下划线者使用。`default_` 同理,别名 `withDefault()`。

---

## 5. 迁移

### 5.1 文件与命名

```
database/migrations/
├── 2026_10_28_143000_create_users_table.java
├── 2026_10_28_143500_create_posts_table.java
└── 2026_10_29_091200_add_slug_to_posts_table.java
```

Java 类名不能以数字开头,因此:**文件名含时间戳,类名不含**。

```java
// 2026_10_28_143500_create_posts_table.java
public class CreatePostsTable extends Migration {
    public void up()   { Schema.create("posts", t -> { ... }); }
    public void down() { Schema.dropIfExists("posts"); }
}
```

一个源文件内只有一个 public 类,文件名与类名不一致 → javac 报错。解决方案:**迁移文件的时间戳放在包路径上,类名用驼峰**:

```
src/main/java/database/migrations/m2026_10_28_143500/CreatePostsTable.java
```

或者更简洁的方案(**推荐**):时间戳作为注解,文件名即类名:

```java
// database/migrations/CreatePostsTable.java
@MigrationAt("2026_10_28_143500")
public class CreatePostsTable extends Migration { ... }
```

`jaravel make:migration create_posts_table` 自动填入当前时间戳。APT 校验时间戳唯一性与格式。**这是对 Laravel 的必要偏离**,原因是 Java 的文件名约束。

### 5.2 命令

```bash
jaravel migrate                      # 执行未运行的迁移
jaravel migrate --pretend            # 只打印 SQL,不执行
jaravel migrate --step               # 每个迁移单独一个 batch,便于逐个回滚
jaravel migrate:rollback             # 回滚最后一个 batch
jaravel migrate:rollback --step=3
jaravel migrate:reset                # 回滚全部
jaravel migrate:refresh --seed       # reset + migrate + seed
jaravel migrate:fresh --seed         # drop 所有表 + migrate + seed(开发期最常用)
jaravel migrate:status
jaravel schema:dump --prune          # 导出当前 schema 为单个 SQL,精简历史迁移
```

### 5.3 版本表

```sql
create table jaravel_migrations (
  id        bigint auto_increment primary key,
  migration varchar(255) not null unique,
  batch     int not null,
  ran_at    timestamp not null default current_timestamp,
  checksum  varchar(64) not null              -- 防止已执行的迁移被篡改
);
```

`checksum` 是对 Laravel 的改进:迁移执行后再被修改,`migrate` 时给出明确警告(可用 `--allow-drift` 跳过)。这解决了团队协作中「某人改了已上线的迁移」导致环境不一致的常见问题。

### 5.4 迁移内的数据操作

```java
@MigrationAt("2026_10_29_091200")
public class BackfillPostSlugs extends Migration {
    @Override public boolean withinTransaction() { return false; }   // DDL 不支持事务的方言

    public void up() {
        DB.table("posts").orderBy("id").chunkById(500, rows -> {
            for (Row r : rows) {
                DB.table("posts").where("id", r.getLong("id"))
                  .update(Map.of("slug", Str.slug(r.getString("title"))));
            }
        });
    }
    public void down() { DB.table("posts").update(Map.of("slug", null)); }
}
```

**重要约束**(文档必须强调):迁移中**不得引用 Eloquent 模型**。模型会随代码演进,而迁移是历史快照——半年后模型字段改了,老迁移就跑不起来。一律用 `DB.table()`。这是 Laravel 社区踩了十年的坑,Jaravel 应在 `make:migration` 的 stub 注释里直接写明,并由 APT 发出警告。

### 5.5 Seeder 与工厂

```java
public class DatabaseSeeder extends Seeder {
    public void run() {
        call(UserSeeder.class, PostSeeder.class);
    }
}

public class UserSeeder extends Seeder {
    public void run() {
        User.factory().count(50).create();
        User.factory().admin().create(Map.of("email", "admin@example.com"));
        User.factory().count(10).hasPosts(3).create();
    }
}
```

```java
public class UserFactory extends Factory<User> {
    @Override public Map<String, Object> definition() {
        return Map.of(
            "name",     faker.name().fullName(),
            "email",    faker.internet().emailAddress(),
            "password", Hash.make("password"),
            "status",   "active"
        );
    }

    public UserFactory admin() { return state(Map.of("role", "admin")); }
    public UserFactory unverified() { return state(Map.of("email_verified_at", null)); }
}
```

Faker 用 `net.datafaker:datafaker`(维护活跃,支持中文 locale)。

---

## 6. 内部架构

```
jaravel-database
├── ConnectionResolver          ← Spring 适配提供 DataSource
├── Connection                  执行/事务/查询日志
├── query/
│   ├── QueryBuilder            流式 API,构建不可变的 QueryState
│   ├── QueryState (record)     from/wheres/joins/orders/limit/...
│   ├── Grammar (abstract)      QueryState → (sql, bindings)
│   │   ├── MySqlGrammar
│   │   ├── PostgresGrammar
│   │   ├── SQLiteGrammar
│   │   ├── SqlServerGrammar
│   │   └── OracleGrammar
│   └── Processor               ResultSet → Row / 映射到 record
├── schema/
│   ├── SchemaBuilder
│   ├── Blueprint               列与索引定义的收集器
│   └── SchemaGrammar (per 方言)
├── migrations/
│   ├── Migrator                发现 / 排序 / 执行 / 回滚
│   ├── MigrationRepository     jaravel_migrations 表读写
│   └── Migration (abstract)
└── seeder/ · factory/
```

### 6.1 Grammar 分层照搬 Laravel

Laravel 的 `Illuminate\Database\Query\Grammars` 设计经过十年多方言验证:一个抽象 `Grammar` 持有 `compileSelect` 的组件顺序数组,各方言只覆写差异部分。Jaravel **直接采用这个结构**,这是最省事也最可靠的选择。

```java
public abstract class Grammar {
    protected static final List<String> SELECT_COMPONENTS = List.of(
        "aggregate", "columns", "from", "joins", "wheres",
        "groups", "havings", "orders", "limit", "offset", "lock");

    public Compiled compileSelect(QueryState q) { ... }     // 按顺序拼接
    protected String compileWheres(QueryState q) { ... }
    protected String wrap(String value) { return "`" + value + "`"; }   // MySQL
    // PostgresGrammar 覆写 wrap 为双引号,覆写 compileLock、compileUpsert 等
}
```

### 6.2 参数绑定与 SQL 注入

**所有值一律走 `PreparedStatement` 绑定**,唯一例外是显式的 `raw()` / `whereRaw()` / `selectRaw()`。

安全约束:
- 列名、表名走 `wrap()`,并用白名单正则校验(`^[A-Za-z0-9_.\-> ]+$`),拒绝可疑标识符;
- `orderBy(direction)` 只接受 `asc`/`desc` 枚举,不接受任意字符串;
- `raw()` 系列 API 在文档和 IDE 注释中标注 `@UnsafeSql`,并由静态检查规则(ErrorProne/SpotBugs 插件)在传入非常量字符串时告警。

### 6.3 Row

```java
public interface Row {
    Object get(String column);
    String getString(String c);     Long getLong(String c);
    Integer getInt(String c);       BigDecimal getDecimal(String c);
    Boolean getBool(String c);      LocalDateTime getDateTime(String c);
    <T> T get(String c, Class<T> type);
    Map<String, Object> toMap();
    <T> T to(Class<T> recordType);       // 映射到 record / POJO
    boolean has(String c);
}
```

---

## 7. 为什么不用 Hibernate / JPA(架构决策)

**决策:自研查询构造器 + Active Record,不以 Hibernate 为底座。**

理由:

1. **模型冲突**。Hibernate 的核心是 Session + 身份映射 + 脏检查 + 延迟刷新。Active Record 的核心是「对象即一行,`save()` 即写库」。强行叠加会产生最差的结果:用户既要理解 Active Record 的直白语义,又要理解 Session 的生命周期(detached、merge、flush 时机、`LazyInitializationException`)。**这正是用户想逃离的复杂度本身。**
2. **SQL 可控性**。Jaravel 承诺「写什么 SQL 心里有数」。Hibernate 的自动刷新与级联会在不可预测的时机发 SQL。
3. **启动与运行开销**。Hibernate 的元模型构建、代理生成、字节码增强,与「native-image 启动 < 100ms」的目标相冲突。
4. **工作量其实不大**。查询构造器 + 多方言 Grammar 约 1.5 万行,是确定性工程,无技术风险。

**代价与缓解**:

| 失去 | 缓解 |
|---|---|
| 二级缓存 | 应用层用 `Cache` 门面显式缓存(更可控) |
| 自动脏检查 | Active Record 自己做字段级 dirty 跟踪(简单得多,见 [06](06-eloquent.md)) |
| 复杂对象图级联持久化 | 显式保存。这是刻意的——隐式级联是 bug 之源 |
| JPA 生态工具(QueryDSL 等) | `jaravel-jpa-bridge`:需要 JPA 的场景照常用,两套 ORM 共享事务 |
| 已有 JPA 项目的迁移成本 | 同上,支持渐进迁移,不要求一次性替换 |

见 ADR-004。

---

## 8. 逃生口

| 需求 | 途径 |
|---|---|
| 构造器表达不了的 SQL | `DB.select(sql, args)` / `whereRaw` / `selectRaw` |
| 存储过程 | `DB.statement()` / 注入 `JdbcTemplate` |
| 需要 JPA | `jaravel-jpa-bridge`,`@Entity` 照常工作,共享事务 |
| 需要 MyBatis | 照常用,`DB.transaction` 与之共享事务 |
| 需要 jOOQ | 照常用;`DB.connection().raw()` 可取到 `java.sql.Connection` |
| 方言不支持 | 实现 `Grammar` 子类注册进容器 |

---

## 9. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| 迁移文件名 | 时间戳即文件名 | 类名 + `@MigrationAt` 注解 | Java 类名不能以数字开头 |
| 迁移 checksum | 无 | 有,检测篡改 | 解决团队协作的环境漂移 |
| 保留字方法 | `$t->boolean()` | `t.boolean_()` + 别名 `bool()` | Java 关键字冲突 |
| `where('a','>',1)` | 字符串运算符 | 字符串 + `Op.GT` 枚举双形式 | 保留简洁同时提供类型安全 |
| 结果类型 | `stdClass` | `Row` 接口 / `record` 映射 | Java 无动态对象 |
| 事务 | 自己管理 | 委托 Spring,与 `@Transactional` 互通 | 复用生态 |
| `afterCommit` | 需手动 | 一等公民 API | 消除常见 bug |

---

## 10. 待决问题

- [ ] `where("age", ">", 18)` 的字符串运算符是否保留?倾向保留(简洁),同时提供 `where(Post_.age, Op.GT, 18)` 类型安全重载,文档推荐后者。
- [ ] `Row` 是否直接用 `Map<String,Object>`?倾向独立接口,以便提供类型化取值器与更好的错误信息(「列 `nmae` 不存在,是否想找 `name`?」)。
- [ ] 分页的总数查询优化:大表 `count(*)` 很慢。`simplePaginate` / `cursorPaginate` 已提供替代,是否还需要 `approximatePaginate`(用统计信息估算)?v0.6 再议。
- [ ] 读写分离的 `sticky` 在队列 Worker 中的语义(无 HTTP 请求作用域)。需要在 `JobScope` 上同样支持打标。
- [ ] Oracle / SQL Server 方言是否纳入 v1.0?倾向 v1.0 只保证 MySQL / PostgreSQL / SQLite 三种,其余社区贡献。
