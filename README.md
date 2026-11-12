# Jaravel

> Laravel 级开发体验,构建在 Spring Boot 之上的 Java 应用框架。

**当前状态:v0.6 进行中(v0.5 已收口)。**
33 个模块、**2444 个测试**全绿,四个示例应用都在构建里
(`examples/{todo,blog,auth,queue}`)。
队列 / 事件 / 调度 / 邮件 / 通知 / 存储到位(v0.4),
终端上有 **40 条命令**、本地命令通道、冷启动启动器、`jaravelw`、`tinker`;
Resource 层、分页、统一异常处理、Problem JSON、内容协商(JSON / XML / CSV)、
`openapi:generate` / `openapi:diff`、`@ApiDoc` 与响应 schema 的 APT
都已到位(v0.5,docs/12)。
v0.6 进行中:jblade **类组件**已落地(`<x-post-card :post="post" />`);
`LazyColl` 与数据库 `lazy()` 已落地;调试工具栏已落地
(路由/视图/SQL/耗时/内存);广播、二步验证待做。
还差:native 二进制(v0.9)。

```bash
jaravel make:model Post -mcr     # 182ms,不启动任何容器
jaravel migrate                  # 应用跑着时 197ms(复用它的进程)
jaravel list                     # 全部命令,含应用自己定义的
```

Artisan 线的五条验收里 3 条达成、2 条部分达成(收口时覆盖率重测:指令 **75.2%** / 分支 66.3%),
过程中发现并修掉 **14 个真实缺陷** —— 其中三个是**自己的门禁与示例在说谎**
(两个示例按提交的样子起不来、`jaravel list` 列不全、parity 的度量对象没有测试)。

详见 [V0.5-RESULT.md](V0.5-RESULT.md) · [V0.3-RESULT.md](V0.3-RESULT.md) ·
[V0.2-RESULT.md](V0.2-RESULT.md) · [V0.1-RESULT.md](V0.1-RESULT.md)。
(v0.4 没有单独的结果文档 —— 它的验收逐条结果、文档↔现实对账表与 parity
复测都记在 [docs/17-roadmap.md](docs/17-roadmap.md) 的 v0.4 一节里。
再写一份会造出第二个会漂的出处。)

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
V0.5-RESULT.md            v0.5 结果:Artisan + API(含 14 个真缺陷与被打破又修回的门禁)
V0.3-RESULT.md            v0.3 落地结果(含 12 个真缺陷与 1 条部分未达成的验收)
V0.2-RESULT.md            v0.2 落地结果(含 8 个真 bug 与 1 条定错了的验收标准)
V0.1-RESULT.md            v0.1 验收结果(含未达成项与修掉的 bug)
docs/                     18 篇模块细化设计(约 8700 行)
verification/             v0.0 技术验证:四个会推翻架构的技术点,含可运行代码

jaravel-support/          Coll / Str / Arr / Cast
jaravel-core/             容器 / 门面 / 请求作用域 / Pipeline / 配置
jaravel-http/             Request / Response / View / 中间件
jaravel-routing/          路由 DSL(方法引用)
jaravel-view/             jblade 模板引擎(Blade 前端 + jte 后端)
jaravel-database/         查询构造器 + 三方言 Grammar
jaravel-eloquent/         Active Record、七种关联、预加载、软删除、作用域、事件
jaravel-eloquent-processor/    @Model 的 APT:元模型 / 基类 / 类型化 Query
jaravel-migration/        Schema DSL、迁移(含 checksum 漂移检测)、Seeder、工厂
jaravel-validation/       60+ 规则、嵌套通配符、本地化、FormRequest
jaravel-cache/            6 个驱动、标签失效、原子锁、限流器
jaravel-session/          4 个驱动、flash、CSRF、会话生命周期
jaravel-auth/             Session/Token/JWT guard、Gate/Policy、哈希、密码重置、邮箱验证
jaravel-queue/            队列契约、database/sync 驱动、虚拟线程 worker、chain / batch / 唯一性
jaravel-events/           事件总线、队列化监听器、Spring 桥接
jaravel-events-processor/ @Listener 的 APT:强类型绑定 + 索引(运行时零反射、零扫包)
jaravel-api-processor/    JsonResource.toArray → 响应 schema 的 APT(写 api-resources.idx)
jaravel-scheduler/        Schedule DSL、进程内调度器、多实例去重(onOneServer)
jaravel-mail/             Mailable、SMTP/log/array、内置邮件组件 <x-mail::*>
jaravel-notifications/    多渠道通知(database / mail)、Notifiable
jaravel-storage/          Filesystem、local 驱动、上传安全(内容嗅探 / 路径穿越 / 随机命名)
jaravel-api/              API 资源:JsonResource / 资源集合 / 输出形状 / OpenAPI 注解与 schema 索引(分页在 support)
jaravel-http-client/      Http 客户端门面(重试、超时、假实现)
jaravel-console/          Artisan 内核:命令基类、参数绑定、注册表、stub、16 条生成命令
jaravel-cli/              jaravel 可执行入口:离线/在线分流、命令通道、冷启动启动器
jaravel-testing/          FeatureTest 三层基类、HTTP 断言、数据库断言、回滚隔离
jaravel-devtools/         热重载:文件监听、变更分类、重启闸门(请求挂起 + 按需重启)
jaravel-security-bridge/  Spring Security 双向桥接(企业 SSO 场景)
jaravel-spring-boot-starter/   自动装配与 Spring 适配(与桥接模块是唯一接触 Spring 的地方)
                               —— 20 条在线命令也在这里(它们的发现机制本来就是 Spring 的)
jaravel-archunit-rules/   ADR-001 的阻断式强制
jaravel-coverage/         覆盖率汇总(report-aggregate;指令 75.2% / 分支 66.3%)

examples/todo/            v0.1 的垂直切片(v0.2 已迁到 Eloquent)
examples/blog/            v0.2 切片:关联 + 预加载 + 迁移 + FormRequest,零裸 SQL
examples/auth/            v0.3 切片:注册/登录/记住我/密码重置/邮箱验证/Policy,端到端
examples/queue/           v0.4 切片:队列 + 邮件 + 通知 + 调度;v0.5 又加了三条自定义命令
benchmark/laravel-parity/ parity 门禁:与等价 Laravel 实现的代码量对比
benchmark/jpa-comparison/ 与 Spring Data JPA 的单条主键查询对照基准
```

## 快速开始

```bash
mvn install                                  # 构建 + 2444 个测试(-T1C)

# Todo 切片(:8000)
cd examples/todo && mvn spring-boot:run
python verify-todo.py                        # 35 项端到端检查

# Blog 切片(:8001)—— v0.2 的能力都在这里
cd examples/blog && SEED_ON_BOOT=true mvn spring-boot:run
python verify-blog.py                        # 33 项端到端检查

python benchmark/laravel-parity/parity.py --max 1.2
cd benchmark/jpa-comparison && mvn exec:java  # 与 JPA 的对照基准
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
