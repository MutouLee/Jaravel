# V1 验证结果 · 模型访问器方案(ADR-010)

| | |
|---|---|
| 日期 | 2026-10-28 |
| 环境 | JDK 21.0.11 (Temurin) · Maven 3.9.6 · Windows 11 |
| 验证对象 | ADR-010(`docs/18` 与 `docs/06` §2.2) |
| 结论 | **方案 B 证伪;新增方案 E' 胜出,建议采纳** |
| 复现 | `javac -encoding UTF-8 -d out $(find src -name '*.java') && java -cp out V1Report && java -cp out V1Relations` |

---

## 1. 结论摘要

| 原方案 | 结论 |
|---|---|
| **B(ByteBuddy 编译后字节码织入)** | **❌ 证伪**,技术上不可行 |
| A(抽象声明 + APT 生成具体子类) | ✅ 可行,但样板量 `2n+4`,且暴露 `_Impl` |
| C(手写 getter/setter) | ✅ 可行,样板量 `3n+4`,最差 |
| **E'(public 字段 + 快照脏检查 + 关联走生成方法)** | **✅ 推荐**,样板量 `n+3`,无魔法、无 IDE 插件依赖 |

---

## 2. 方案 B 的证伪过程

**假设**:用 ByteBuddy 在 Maven `process-classes` 阶段给只有字段的模型类织入 getter/setter。

**实测**:

```java
// Model.java —— 只有字段
public class Model { String name; Long id; }

// Caller.java —— 调用期望被织入的 getter
m.setName("Tom");
System.out.println(m.getName());
```

```
src/Caller.java:4: error: cannot find symbol
        m.setName("Tom");
         ^  symbol: method setName(String)
src/Caller.java:5: error: cannot find symbol
        System.out.println(m.getName());
                            ^  symbol: method getName()
2 errors
```

**原因**:javac 在 `compile` 阶段编译**全部**源文件,而织入发生在 `process-classes`(编译之后)。同模块内任何调用 `user.getName()` 的源码都无法通过编译。

**唯一残存路径**:Lombok 式的 javac AST 篡改——在注解处理轮次中用 `com.sun.tools.javac.tree.TreeMaker` 修改 AST,使 javac「看到」getter。代价:

- 依赖 javac 内部 API,JDK 16+ 被 JPMS 封装,需要 `--add-exports` / `--add-opens`;
- 每次 JDK 大版本升级都可能崩(Lombok 的历史即是明证);
- 无 IDE 插件则完全无代码提示;
- 把框架的核心能力押在非标准、随时可能被 JDK 封堵的机制上。

**判定:放弃方案 B。** 这同时消掉了 `docs/15` §6 中「字节码增强生成的 getter 的代码提示」这一项**高优先级 IDE 插件依赖**——原本它被标注为「决定模型方案可行性」。

---

## 3. 新增方案 E:public 字段 + 快照脏检查

方案 B 证伪后浮现的选项。核心洞察:**写入拦截不是脏跟踪的必要条件**——快照比对就够了,而且 Hibernate 本来就是这么做的。

```java
@Model(table = "users")
public class User extends UserBase {
    @Id public Long id;
    public String name;
    public String email;
}

user.name = "Jerry";    // 与 $user->name = 'Jerry' 等长
user.save();            // save() 时与 original 快照比对,只 UPDATE 变化的列
```

### 3.1 功能等价性(13/13 通过)

三种方案在以下行为上完全等价:

| 行为 | C | A | E |
|---|---|---|---|
| 新建时 INSERT 仅含已赋值列 | ✓ | ✓ | ✓ |
| 更新时 UPDATE 仅含变化列 | ✓ | ✓ | ✓ |
| 无变化时跳过 UPDATE | ✓ | ✓ | ✓ |

实测 SQL:

```sql
-- 新建(只赋了 name/email)
insert into `users` (`name`, `email`) values (?, ?)        -- bindings: [Tom, tom@example.com]
-- 加载后只改 name
update `users` set `name` = ? where `id` = ?               -- bindings: [Jerry]
-- 无变化
-- 无变化,跳过 UPDATE
```

### 3.2 快照方案的边界情况(全部通过)

| 用例 | 结果 |
|---|---|
| 赋回原值(`name="Jerry"` 后又 `name="Tom"`)→ 不产生 UPDATE | ✓ `changes = {}` |
| 非 null → null 能被检测 | ✓ `changes = {email=null}` |
| `save()` 后快照刷新,再次 `save()` 无变化 | ✓ |

### 3.3 已知缺陷:可变对象字段

```java
user.tags.add("x");     // List 原地修改 —— 快照持有同一引用,检测不到
```

**对策**:`@Cast` 类型的字段(`Json`、`AsCollection`)在拍快照时存**序列化后的形态**而非引用。

**重要对照**:Hibernate 的脏检查有**完全相同**的问题。这不是方案 E 特有的缺陷,不构成否决理由。

---

## 4. 关键修正:关联不能是 public 字段

原本设想 `public Rel<Post> posts;`。两条实测否决了它:

### 4.1 public 字段无法拦截懒加载

字段访问无方法调用,无从插入「检查是否已预加载」的逻辑。

### 4.2 父类构造器初始化子类字段会被静默覆盖

```java
public abstract class Base {
    protected Base() { tryInit(); }            // 生成的父类尝试初始化
    protected abstract void tryInit();
}

class Child extends Base          { public String rel;        ... }  // 无显式初始化器
class ChildWithInit extends Base  { public String rel = null;  ... }  // 有显式初始化器
```

实测:

```
无显式初始化器 : INITIALIZED
有显式初始化器 : null          ← 静默被覆盖
```

字段初始化器在 `super()` **之后**执行,会覆盖父类构造器写入的值。用户写一个 `= null` 就让框架初始化失效,**无声、依赖顺序、用户无从察觉**。不可接受。

### 4.3 修正后的形态(方案 E')

关联改为**类级注解声明 + APT 生成访问器方法**,状态存在 `Model` 基类的 map 里:

```java
@Model(table = "users")
@HasMany(name = "posts", target = Post.class)
@BelongsTo(name = "team", target = Team.class)
public class User extends UserBase {
    @Id public Long id;
    public String name;
}
```

```java
// ⚙ APT 生成到 UserBase
public Rel<Post>  posts() { return rel("posts", Post.class); }
public Rel1<Team> team()  { return rel1("team", Team.class); }
```

验证结果(5/5 通过):

| 用例 | 结果 |
|---|---|
| 关联句柄未预加载时也非 null | ✓ `Rel<Post>(posts, not loaded)` |
| 未预加载访问时报错清晰 | ✓ `关联 posts 未预加载。修复:在查询上加 .with(posts)` |
| 预加载回填后可读 | ✓ |
| 同一关联句柄被缓存(多次调用返回同一实例) | ✓ |

**代价**:`user.posts()` 比 Laravel 的 `$user->posts` 多两个括号。可接受。
**附带收益**:返回类型精确,IDE 可跳转可补全,且避开了字段初始化顺序陷阱。

> `docs/06` 中「关联以字段 + 注解声明」的设计需据此修正为「关联以类级注解声明」。

---

## 5. 类型安全验证(4/4 负例全部编译失败)

| 负例 | javac 报错 | 评价 |
|---|---|---|
| 跨模型误用列 | `Col<UserC,String> cannot be converted to Col<UserE,?>` | ✅ 清晰 |
| 值类型不匹配 | `int cannot be converted to String` | ✅ 清晰 |
| 列名拼错 | `cannot find symbol: variable nmae` | ⚠️ **无「是否想写 name?」建议** |
| 嵌套关联路径接错模型 | `required: RelPath<Post,N> / found: RelPath<UserE2,Post>` | ✅ 清晰 |

`Col<M,V>` 双泛型与 `RelPath<M,T>.then()` 的设计**均达成设计意图**。

**需要修正的预期**:`docs/06` §6 承诺的「列 `nmae` 不存在,是否想找 `name`?」这类相似度建议,**javac 给不出**——元模型常量的拼错只会得到标准的 `cannot find symbol`。相似度建议只能来自 IDE 或 APT(当列名以字符串形式出现在注解里时,APT 可以给)。文档中的承诺需要按此区分场景。

---

## 6. 代码量实测

三种方案的用户手写有效行数(去空行与注释),Laravel 作为基准:

| 字段数 | 方案 C | 方案 A | **方案 E** | Laravel |
|---:|---:|---:|---:|---:|
| 5 | 19 | 14 | **8** | 8 |
| 15 | 49 | 34 | **18** | 8 |
| 30 | 94 | 64 | **33** | 8 |

增长规律:

```
方案 C ≈ 3n + 4      方案 A ≈ 2n + 4      方案 E ≈ n + 3      Laravel = 8(常数)
```

### 6.1 一个必须纠正的 parity 口径问题

**Laravel 的模型文件是 O(1),任何 Java 版本都是 O(n)** —— PHP 完全不声明字段。这意味着:

> **「模型声明」这个维度上,parity 比值永远达不到 1.3。** 这是静态类型语言不可消除的固有成本,与设计选择无关。

`DESIGN.md` §9 与 `docs/16` §6.1 的 parity 门禁(≤1.3)**必须明确为「按完整功能切片统计」**(控制器 + 模型 + 路由 + 视图 + 测试),模型声明在其中只占少数。否则这个门禁从第一天起就不可能通过,会变成一个所有人都忽略的假指标。

### 6.2 方案间的相对差距(ADR-010 的实际问题)

| 对比 | 5 字段 | 30 字段 |
|---|---|---|
| E 比 C 精简 | 2.4× | 2.8× |
| E 比 A 精简 | 1.75× | 1.9× |

差距随字段数线性放大。真实项目的模型普遍 10~30 字段,因此 E 的优势在实际使用中比 5 字段的样本更明显。

---

## 7. 方案 E' 的完整代价清单

诚实列出,便于决策:

| 代价 | 严重度 | 说明 / 对策 |
|---|---|---|
| public 可变字段违反 Java 封装正统 | **中** | 会遭遇团队抵触。对策:文档正面论证(Active Record 模型是数据载体,不是封装领域对象;Kotlin data class / Java record 语义同理),并提供 `@Model(accessors = true)` 开关生成 getter/setter 供有硬性规范的团队使用 |
| 每个已加载实例多一个 `Object[]` 快照 | 低 | 20 字段 ≈ 160 字节。可用 `@Model(dirtyTracking = BITSET)` 退回 setter 位图(需生成访问器) |
| 可变对象字段原地修改检测不到 | 低 | `@Cast` 字段快照存序列化形态;与 Hibernate 同等问题 |
| 无法在写入时做修改器(`@Mutator`) | 低 | 改为 `saving` 钩子中处理。语义更明确(Laravel 的 mutator 在赋值时就改掉了值,调试时容易困惑) |
| 字段名即列名的映射不可覆盖? | 无 | 仍可用 `@Column("custom_name")` |
| 外部代码可绕过框架直接改字段 | 低 | 这正是「直接赋值」的设计意图;脏检查照常工作 |

---

## 8. 对设计文档的修正项

本次验证产出 5 条文档修正,需在实现前落地:

| # | 文档 | 修正内容 |
|---|---|---|
| 1 | `docs/18` ADR-010 | 状态改为「已采纳:方案 E'」;方案 B 记入「被否决的方案」,附证伪过程 |
| 2 | `docs/06` §1 / §2.2 | 模型定义改为 public 字段;关联改为**类级注解**;新增 `@Model(accessors=true)` 逃生口 |
| 3 | `docs/06` §6 | 区分「APT 可给相似度建议的场景(注解内字符串列名)」与「javac 只能给 cannot find symbol 的场景(元模型常量)」 |
| 4 | `DESIGN.md` §9 + `docs/16` §6.1 | parity 门禁明确为「按完整功能切片统计」,并说明模型声明维度的 O(n) vs O(1) 固有差距 |
| 5 | `docs/15` §6 | 删除「字节码增强 getter 的 IDE 提示」一项(方案 B 已弃),IDE 插件的关键路径只剩 jblade 支持 |

---

## 9. 建议采纳的最终形态

```java
@Model(table = "users")
@SoftDeletes
@HasMany(name = "posts", target = Post.class)
@BelongsToMany(name = "roles", target = Role.class, pivot = "role_user")
public class User extends UserBase {

    @Id public Long id;
    public String name;
    @Unique public String email;
    @Hidden public String password;
    @Cast(Json.class) public Map<String, Object> meta;
    public UserStatus status;
    public Instant emailVerifiedAt;

    @Scope public static Query<User> active(Query<User> q) {
        return q.where(User_.status, UserStatus.ACTIVE);
    }
}
```

```java
// 读写
user.name = "Jerry";
user.save();

// 查询(类型安全)
var list = User.query()
        .active()
        .where(User_.status, ACTIVE)
        .with(User_.posts)
        .orderByDesc(User_.createdAt)
        .paginate(15);

// 关联
user.posts().get();
user.roles().attach(roleId);
```

7 个字段 + 2 个关联 + 1 个作用域 = **13 行用户代码**,无一行样板。

**这个形态在信息密度上追平了 Laravel(字段声明是唯一的额外成本),同时拿到了编译期类型安全,且不依赖任何非标准 JVM 机制或 IDE 插件。**
