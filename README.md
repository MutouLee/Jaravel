# Jaravel

> Laravel 级开发体验,构建在 Spring Boot 之上的 Java 应用框架。

**当前状态:v0.1 垂直切片已完成。** 8 个模块、244 个测试通过、示例应用端到端跑通。
距离可用还很远(没有 Eloquent / 认证 / 队列),但「路由 + 模板 + 数据库」这条主干已经通了。
详见 [V0.1-RESULT.md](V0.1-RESULT.md)。

| 项 | 值 |
|---|---|
| 语言基线 | Java 21 (LTS) |
| 运行时底座 | Spring Boot 3.x(可替换的 adapter,非硬绑定) |
| 构建工具 | Maven(Gradle 支持列入 v1.1) |
| 许可 | Apache-2.0 |

---

## 这是什么

PHP 生态里 Laravel 几乎是默认选择——不是因为功能多,而是因为它**把一个 Web 应用需要的 80% 决策都替开发者做完了**,并且做得一致。

Java 侧的 Spring Boot 是事实标准,但定位是「企业级、可配置到极致、面向架构师」:一个简单 CRUD 需要六层;Spring Security 的配置复杂度与「给接口加个登录校验」严重不匹配;改一行代码要重启。

**Jaravel 不是另一个 Web 框架,也不是 Spring 的替代品**,而是骑在 Spring Boot 之上的一层 DX 框架:

- 向下:复用 Spring 的 IoC、AOP、事务、嵌入式容器、Actuator、整个三方生态;
- 向上:提供 Laravel 风格、有唯一正确答案、简洁且**编译期类型安全**的 API;
- 对外:用户只需引入一个 starter,照常能用任何 Spring 库。

---

## 设计预览

```java
// routes/WebRoutes.java —— 方法引用,编译期校验
public class WebRoutes implements RouteDefinition {
    @Override public void define(Router r) {
        r.get("/posts/{id}", PostController::show).name("posts.show");
        r.resource("/posts", PostController.class).except("destroy");
    }
}
```

```java
// app/models/Post.java —— 标量列是 public 字段,关联用类级注解
@Model(table = "posts")
@SoftDeletes
@BelongsTo(name = "author", target = User.class)
@HasMany(name = "comments", target = Comment.class)
public class Post extends PostBase {
    @Id public Long id;
    public String title;
    public String body;

    @Scope public static Query<Post> published(Query<Post> q) {
        return q.whereNotNull(Post_.publishedAt);
    }
}
```

```java
var list = Post.query()
        .published()
        .with(Post_.author, Post_.comments)      // 预加载,从根上解决 N+1
        .orderByDesc(Post_.createdAt)
        .paginate(15);

Post p = Post.findOrFail(1L);
p.title = "新标题";        // 与 $post->title = '...' 等长
p.save();                  // 与快照比对,只 UPDATE 变化的列
```

```html
{{-- resources/views/posts/index.jblade.html --}}
@params(Paginator<Post> posts)
@extends('layouts.app')

@section('content')
  @forelse (posts as post)
    <x-post-card :post="post" />
  @empty
    <p>@lang('posts.empty')</p>
  @endforelse
  {{ posts.links() }}
@endsection
```

完整 API 预览见 [DESIGN.md §4](DESIGN.md#4-核心-api-预览)。

---

## 仓库内容

```
DESIGN.md                 设计总纲:可行性结论、三条顶层决策、模块结构、路线图
V0.1-RESULT.md            v0.1 验收结果(含未达成项与修掉的 bug)
docs/                     18 篇模块细化设计(约 8700 行)
verification/             v0.0 技术验证:四个会推翻架构的技术点,含可运行代码

jaravel-support/          Coll / Str / Arr / Cast
jaravel-core/             容器 / 门面 / 请求作用域 / Pipeline / 配置
jaravel-http/             Request / Response / View / 中间件
jaravel-routing/          路由 DSL(方法引用)
jaravel-view/             jblade 模板引擎(Blade 前端 + jte 后端)
jaravel-database/         查询构造器 + 三方言 Grammar
jaravel-spring-boot-starter/   自动装配与 Spring 适配(唯一接触 Spring 的模块)
jaravel-archunit-rules/   ADR-001 的阻断式强制

examples/todo/            v0.1 垂直切片的示例应用
benchmark/laravel-parity/ parity 门禁:与等价 Laravel 实现的代码量对比
```

## 快速开始

```bash
mvn install                                  # 构建 + 244 个测试
cd examples/todo && mvn spring-boot:run      # → http://localhost:8000
python verify-todo.py                        # 35 项端到端验收
python benchmark/laravel-parity/parity.py    # parity 门禁
```

### 文档入口

| 从这里开始 | 内容 |
|---|---|
| **[DESIGN.md](DESIGN.md)** | 可行性结论与三条顶层架构决策 |
| [docs/README.md](docs/README.md) | 18 篇模块文档的索引与阅读顺序 |
| **[verification/README.md](verification/README.md)** | v0.0 技术验证的全部结论与实测数据 |
| [docs/18-risks-and-decisions.md](docs/18-risks-and-decisions.md) | 13 条 ADR(含被否决的方案及原因)+ 10 项风险 |
| [docs/17-roadmap.md](docs/17-roadmap.md) | 七个里程碑、验收标准、工作量估算 |

---

## v0.0 技术验证结论(2026-10-28)

在写框架代码之前,先验证四个**会推翻架构**的技术点。**四项全部通过,无一需要启用退路。**

| | 验证项 | 结论 |
|---|---|---|
| [V1](verification/v1-model-accessor/RESULT.md) | 模型访问器方案 | **字节码织入方案证伪**;采纳 public 字段 + 快照脏检查 |
| [V2](verification/v2-route-apt/RESULT.md) | 路由 APT 表达式解析 | 可行(`com.sun.source.util.Trees`,受支持 API) |
| [V3](verification/v3-jblade-jte/RESULT.md) | 模板引擎基于 jte | 可行;工作量 14 周 → **7.5 周** |
| [V4](verification/v4-hotreload-coverage/RESULT.md) | 热重载覆盖率 | 增强 HotSwap 实测覆盖 **87%** |

三个最有价值的发现:

1. **编译后字节码织入(ByteBuddy)无法提供同模块可见的 getter** —— javac 在织入之前就编译完了全部源文件。这否决了原定的模型方案,逼出了更好的 public 字段 + 快照方案(代码量 `n+3` vs 原方案的 `2n+4`)。
2. **Blade 的 `@stack`/`@push` 可以在 jte 之上实现** —— 关键是 `section` 存入时**立即渲染**,让嵌套组件的 push 副作用在布局渲染前全部发生。这正是 Blade 的两遍语义。
3. **纯新增(加方法/加字段)占结构性变更的约 89%**,而纯新增正是 JetBrains Runtime 的增强 HotSwap 能处理的。因此 JBR 应从「可选增强」提升为**推荐的默认开发运行时**。

验证代码均可复现:

```bash
bash verification/v1-model-accessor/run.sh
bash verification/v2-route-apt/run.sh
bash verification/v3-jblade-jte/run.sh          # 需 Maven 联网
bash verification/v4-hotreload-coverage/run.sh  # 需网络克隆样本仓库
```

---

## 核心架构决策

三条主心骨(详见 [DESIGN.md §3](DESIGN.md#3-三条顶层架构决策)):

1. **骑在 Spring Boot 上,但不嫁给它** —— `jaravel-core` 及所有功能模块零 Spring 依赖,Spring 仅作为可替换的 runtime adapter,由 ArchUnit 在 CI 中阻断式强制;
2. **用编译期代码生成(APT)替代 PHP 的魔术方法** —— 零运行时反射、GraalVM native 友好、IDE 可跳转可重构;
3. **强制虚拟线程-per-request** —— 这是在 Java 上还原 Laravel 全局门面心智模型的前提。

四项明确的**非目标**:不支持 WebFlux / 响应式;不以 Hibernate 为 ORM 底座;不以 Bean Validation 为验证底座;不以 Spring Security 为认证底座(但提供桥接)。每一条的论证与代价都记录在 [ADR](docs/18-risks-and-decisions.md) 中。

---

## 诚实的风险说明

- **工作量**:单人全职约 21 个月到 v1.0;3~4 人团队约 9~12 个月。关键路径是 core → Eloquent,加人无法压缩。
- **最大风险不是技术,是无人采纳**。技术可行性已验证;真正的风险是「做出来了但没人用」。退出条件已写进 [docs/17 §4](docs/17-roadmap.md)。
- **Gradle 暂不支持**(v1.1 计划)。
- **Java 21+ 基线**,不支持 Java 8/11/17。

---

## v0.1 垂直切片结果(2026-10-29)

8 个模块、**244 个测试通过**、示例应用**35 项端到端验收通过**。详见 [V0.1-RESULT.md](V0.1-RESULT.md)。

| 验收项 | 结果 |
|---|---|
| 示例应用端到端可跑 | ✅ 35/35 |
| parity 比值 ≤ 1.3 | ✅ **1.27**(勉强。差距几乎全来自「还没有 Eloquent」) |
| 模板错误定位到 `.jblade.html` 行号 + 相似度建议 | ✅ |
| ArchUnit 边界规则生效 | ✅ 7/7,且已验证能抓住违规 |
| 冷启动 < 1.5 s | ❌ **1.73 s**,超标 15% |

parity 的 1.27 里,`TodoRepository`(43 行,Laravel 侧完全不存在)是全部差距来源 ——
去掉它是 0.98。v0.2 的 Eloquent 落地后这一项会消失。

**已知缺口**:无 Eloquent / 无验证器(v0.2);无认证 / 会话 / CSRF(v0.3);
fat jar 需要模板预编译(v0.9,目前用展开 classpath 部署)。

---

## 下一步

进入 **v0.2**:Eloquent + APT + 迁移 + 验证器。

v0.1 的 go/no-go 判断已通过,但通过得勉强。v0.2 必须让 parity 比值明显下降,
否则需要重新审视 API 设计,而不是继续往上堆模块。

---

## 许可

Apache-2.0
