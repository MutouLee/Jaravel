# 15 · 开发体验:热重载与错误页

**本模块的优先级等同于 Eloquent。** PHP 最大的体验优势是「改完刷新就生效」,这是 Laravel 开发流的基础假设。如果 Jaravel 的改一行代码要等 15 秒重启,所有 API 设计上的优雅都会被这个摩擦抹平——用户会回到 Spring Boot,因为既然都要等重启,不如用他们熟悉的东西。

---

## 1. 诚实的目标设定

**PHP 的零重启在 JVM 上做不到。** 不要承诺做不到的事。实际目标:

| 变更类型 | 目标 | 可达性 |
|---|---|---|
| jblade 模板 | **0 重启**,< 100ms | ✅ 确定可达 |
| `config/*.yml` / `.env` | 0 重启,< 50ms | ✅ 确定可达 |
| 语言包 | 0 重启 | ✅ 确定可达 |
| 静态资源 | 0 重启 | ✅ 确定可达 |
| **方法体修改** | **0 重启** | ⚠️ 需 HotswapAgent + DCEVM 或 JBR |
| 新增/删除方法、改签名 | < 2s 重启 | ✅ 可达(devtools 式类加载器) |
| 新增类 | < 2s 重启 | ✅ 可达 |
| 改模型字段(触发 APT) | < 4s(需重跑注解处理) | ⚠️ 需增量 APT |
| 改依赖(pom.xml) | 全量重启,~10s | 接受 |

「方法体修改 0 重启」覆盖了日常开发中约 **60%** 的改动(改业务逻辑、调条件、加日志),是性价比最高的一项。

---

## 2. 四层机制

```
┌─────────────────────────────────────────────────────────────┐
│ L1  资源热替换(模板/配置/语言包/静态文件)                    │
│     WatchService 监听 → 内存重编译 → 替换缓存               │
│     0 重启,毫秒级                                          │
├─────────────────────────────────────────────────────────────┤
│ L2  方法体热替换(HotSwap)                                   │
│     JVM 标准 HotSwap(JDI redefineClasses)仅支持方法体       │
│     + HotswapAgent/DCEVM 扩展到字段/方法增删                 │
│     0 重启                                                  │
├─────────────────────────────────────────────────────────────┤
│ L3  应用类加载器重启(结构性变更)                             │
│     双类加载器:base(三方 jar,不变) + app(应用类,可丢弃)  │
│     丢弃 app loader → 重建容器 → < 2s                        │
├─────────────────────────────────────────────────────────────┤
│ L4  全量 JVM 重启(依赖变更)                                 │
│     ~10s,仅在 pom.xml 改动时                                │
└─────────────────────────────────────────────────────────────┘
```

每次文件变更,按 L1 → L4 的顺序**选择成本最低的可行机制**。

---

## 3. `jaravel serve` 的实现

```bash
jaravel serve                      # 默认开启全部热重载层
jaravel serve --no-watch           # 关闭监听
jaravel serve --hotswap=off        # 关闭 L2(排查诡异问题时)
jaravel serve --port=8000
```

### 3.1 进程结构

```
jaravel serve(父进程,长生命周期)
├── FileWatcher          (虚拟线程)  监听 src/、resources/、config/、.env、pom.xml
├── ChangeClassifier                 判定变更类型 → 决定用 L1/L2/L3/L4
├── IncrementalCompiler  (javac API) 只编译变更文件及其依赖闭包
├── HotswapBridge        (JDI/Agent) L2:向 JVM 提交 redefineClasses
├── AppLoaderManager                 L3:管理 app ClassLoader 的生命周期
├── CommandChannelServer             接收 jaravel migrate/tinker 等命令(见 [13] §1.2)
└── 应用实例(base loader + app loader)
```

### 3.2 变更分类

```java
enum ReloadStrategy { RESOURCE, HOTSWAP, RESTART_LOADER, RESTART_JVM }

ReloadStrategy classify(Set<Path> changed) {
    if (changed.anyMatch(p -> p.endsWith("pom.xml")))              return RESTART_JVM;
    if (changed.allMatch(this::isResource))                        return RESOURCE;
    if (changed.anyMatch(this::triggersAnnotationProcessing))      return RESTART_LOADER;  // @Model/@Command/路由
    var diff = compileAndDiff(changed);
    if (diff.onlyMethodBodies() && hotswapAvailable)               return HOTSWAP;
    if (diff.onlyMethodBodiesOrFieldsOrMethods && dcevmAvailable)  return HOTSWAP;
    return RESTART_LOADER;
}
```

`compileAndDiff` 比较新旧 class 文件的结构(常量池、方法签名、字段),判断是否只有方法体变化。这决定了能否走 L2。

### 3.3 L3 的双类加载器

```
系统类加载器
    └── BaseClassLoader        所有三方依赖(Spring、Jackson、驱动…)
            │                  生命周期 = JVM,从不丢弃
            └── AppClassLoader  target/classes(应用类 + 生成的模板类)
                                每次 L3 重启丢弃并新建
```

重启流程:

1. 停止接受新请求(已有请求等待完成,最多 5s);
2. 关闭旧容器(`ApplicationContext.close()`,触发所有 `@PreDestroy`);
3. 丢弃旧 `AppClassLoader`;
4. 新建 `AppClassLoader`,重跑 Spring 自动装配(只装配应用 bean,三方 bean 定义已缓存);
5. 恢复接受请求。

**< 2s 的关键**:三方依赖的类不重新加载(它们占类加载时间的 80%+),Spring 的 bean definition 元数据可部分缓存。这正是 `spring-boot-devtools` 的原理,Jaravel 复用其思路,但做两项增强:

- **请求排队而非拒绝**:重启期间到来的请求挂起等待(最多 10s)而非返回 503。用户在浏览器刷新时的体验是「慢了 2 秒」而不是「报错了」;
- **重启后自动重放当前请求**:浏览器发起请求 → 检测到有待编译变更 → 先编译重启 → 再处理该请求。用户感知为「刷新一下,改动就生效了」,**与 PHP 的心智模型一致**。

第二点是关键设计:不需要用户等重启完成再手动刷新。

### 3.4 L2 的落地现实

JVM 标准 HotSwap(`Instrumentation.redefineClasses`)**只支持方法体修改**,不支持增删方法/字段。要突破这个限制需要:

| 方案 | 可用性 | 说明 |
|---|---|---|
| 标准 HotSwap | ✅ 任何 JDK（**v0.4 已实现**） | 仅方法体。实测覆盖 50%~78%，见 §9 |
| **JetBrains Runtime (JBR)** | ✅ 免费,随 IntelliJ 分发 | 增强的 HotSwap,支持加字段/方法 |
| DCEVM + HotswapAgent | ⚠️ 开源但对新 JDK 的支持滞后 | 能力最强 |
| JRebel | 💰 商业 | 能力最强,企业可选 |

**策略**:
- 标准 HotSwap 作为**零配置基线**(所有用户都能用,覆盖方法体修改);
- 检测到运行在 JBR 上 → 自动启用增强模式(`jaravel doctor` 会提示用户「用 JBR 可获得更好的热重载」);
- HotswapAgent / JRebel 作为可选增强,文档给出配置方法;
- **不把项目的核心体验押在非标准 JVM 上** —— L3 的 < 2s 重启是兜底保证。

> **v0.4 已实现 L2(标准 HotSwap)。** docs/17 的 v0.3 复盘把这一项挪进了 v0.4。
>
> 在这之前 `ReloadCoordinator` 的 `HOTSWAP` 分支有一个 `onHotswap` 钩子,
> 但**没有任何地方给它赋值** —— 于是它永远走 else 降级到 L3。
> 分类是对的,执行是空的。
>
> **Instrumentation 怎么拿到。** 两条路,按顺序试:
> ① 用户加了 `-javaagent:...jaravel-devtools.jar`;
> ② **运行时自挂载**(`jdk.attach` 挂到自己的 pid 上)。
>
> 第二条要 `-Djdk.attach.allowAttachSelf=true`(JDK 9 之后自挂载默认禁止),
> 并且要跑在 **JDK** 上(JRE 没有 `jdk.attach` 模块)。
> 两个条件都会在启动时**明确报出来**并给出照着做的修复步骤 ——
> 一个静默降级的 L2 会让用户以为「0 重启」生效了,然后困惑于
> 为什么每次都等两秒。
>
> **Instrumentation 放在 `System.getProperties()` 里。** 看起来脏,
> 但 agent 类由系统类加载器加载,而 `HotSwapper` 跑在应用的加载器里
> (spring-boot-devtools 把整个应用放进 `RestartClassLoader`)——
> 两边是**两个不同的 `HotSwapAgent` 类**,静态字段传不过去。
> `System.getProperties()` 是个 `Hashtable`,由引导类加载器持有,
> 对所有加载器可见。
>
> **★ 失败是常态,必须降级。** 增删方法/字段、改签名在开发期极其常见,
> 而它们一律被标准 HotSwap 拒绝。任何失败都转 L3 并打印原因。
> 不降级的后果是「文件已经改了、运行时还是旧的」且没有提示 ——
> 那是最难排查的一类状态,用户会开始怀疑自己的代码。
>
> **★ 带 APT 注解的类不走 L2。** HotSwap 不重跑注解处理,于是
> `XxxBase` / `Xxx_` 这些生成物会与新代码脱节,而那种不一致的报错
> **指向生成的代码**。`ChangeClassifier.triggersAnnotationProcessing`
> 看的是源码文本,而 L2 这条路走的是 `.class` 变更 —— 那时没有源码,
> 所以 `HotSwapper` 另外按**已加载 Class 上的注解**再查一遍。
> (接上 L2 之前,前者**没有任何生产调用方**,也就是说这个风险
> 此前是无人看守的。)
>
> **★ 顺带修了一个让 L2 与「按需重启」同时失效的问题。**
> devtools 自己监听 classpath,**文件一变就重启**。于是:刚换完字节
> 就被整体重启(L2 等于不存在),而 §3.3 承诺的「连续编辑 10 个文件
> 只重启一次」也从未成立。
>
> 修法是给 devtools 配一个 `trigger-file`(`JaravelDefaults` 里的
> 一个默认值),它只在那个文件变化时重启,而 Jaravel 通过
> `Restarter.restart()` 在自己选的时刻触发。那个文件从不需要存在。
> 不用 `spring.devtools.restart.enabled=false`:devtools 在
> `ApplicationStartingEvent` 上就从 **System property** 读它
> (早于 `EnvironmentPostProcessor`),而且关掉之后 `Restarter`
> 不初始化,L3 会一起没。
>
> **验证范围要说清。** `HotSwapperTest` 现场编译一个类的两个版本、
> 真的调 `redefineClasses`、再调用那个方法看返回值 —— 11 条,
> 含「加方法被拒」「加字段被拒」「带 `@Model` 的类降级」三条反例。
> ⚠ **「IDE 里保存 → 文件监听 → 分类 → 热替换」这条完整链路
> 没有自动化测试** —— 它需要一个真实的 IDE 增量编译与一次
> devtools 重启。starter 里那段降级逻辑是靠人工验证的。

### 3.5 状态保持

L3 重启会丢失内存状态。为让体验连贯:

- **Session**:用 `cookie` 或 `redis` 驱动时天然保持;`memory` 驱动在开发模式下改为落盘到 `storage/framework/sessions`;
- **缓存**:`caffeine` 驱动在开发模式下可选落盘;
- **数据库连接池**:由 base loader 持有,不随 app loader 丢弃(避免每次重启重建连接池,这是重启耗时的大头);
- **登录状态**:因 session 保持而保持 —— 这点很重要,否则每次改代码都要重新登录,体验崩塌。

---

## 4. 开发期错误页

Laravel 的 Ignition 错误页是其 DX 的标志性组件。Jaravel 需要等价物,且**可以做得更好**(Java 有完整的编译期信息)。

```
┌──────────────────────────────────────────────────────────────────┐
│ ❌ LazyLoadingViolationException                                  │
│ 试图在未预加载的情况下访问 User#posts                              │
├──────────────────────────────────────────────────────────────────┤
│ 📍 app/Http/Controllers/UserController.java:24                   │
│                                                                   │
│    22 │ public View index() {                                    │
│    23 │     var users = User.query().get();                      │
│  → 24 │     return View.of("users.index").with("posts",          │
│       │                user.posts.get());                        │
│    25 │ }                                                         │
│                                                                   │
│ 💡 修复建议                                                        │
│    User.query().with(User_.POSTS).get()                          │
│    [一键应用]  ← 点击直接修改源文件                                │
├──────────────────────────────────────────────────────────────────┤
│ 堆栈 │ 请求 │ SQL (14) │ 路由 │ Session │ 配置 │ 日志            │
├──────────────────────────────────────────────────────────────────┤
│ SQL 标签页:                                                      │
│   1.  0.8ms  select * from users                                 │
│   2.  0.4ms  select * from posts where user_id = 1    ⚠️ N+1     │
│   3.  0.4ms  select * from posts where user_id = 2    ⚠️ N+1     │
│   ... (12 条相似查询,疑似 N+1)                                   │
│   [查看聚合]  [定位调用点]                                        │
└──────────────────────────────────────────────────────────────────┘
```

### 4.1 必备能力

| 能力 | 说明 |
|---|---|
| 源码片段 | 异常位置前后 5 行,语法高亮,应用代码与框架代码分色 |
| 栈帧折叠 | 默认折叠框架/三方栈帧,只展开应用代码(Laravel 的做法) |
| SQL 面板 | 本次请求的全部 SQL + 耗时 + 绑定参数 + 调用栈 + **N+1 检测标注** |
| 请求面板 | 方法/URL/路由名/中间件链/参数/头/Cookie/Session/认证用户 |
| 配置面板 | 当前生效的配置(敏感值脱敏) |
| 日志面板 | 本次请求产生的日志 |
| **修复建议** | 针对已知异常类型给出具体修复代码 + 一键应用 |
| **编辑器跳转** | 点击文件路径用 `idea://` / `vscode://` 协议打开到具体行 |
| 模板错误映射 | jblade 异常显示模板文件与行号(见 [04](04-view-jblade.md) §2.4) |
| 分享 | 一键生成可分享的错误快照(脱敏),便于团队协作排查 |

### 4.2 修复建议的知识库

把框架的常见错误都配上可操作的建议:

| 异常 | 建议 |
|---|---|
| `LazyLoadingViolationException` | 在查询上加 `.with(X_.REL)`,给出完整表达式 |
| `ModelNotFoundException` | 提示用 `tryFind` 返回 `Optional`,或路由加约束 |
| `MassAssignmentException` | 提示在 `@Guarded` 中移除该字段,或用显式 setter |
| `ValidationException`(开发期) | 直接列出每个失败字段与规则 |
| `OutsideRequestException` | 提示在队列/CLI 中显式传递用户对象 |
| `TokenMismatchException` | 提示表单缺 `@csrf`,或路由应加入 CSRF 排除列表 |
| `ColumnNotFoundException` | 「列 `nmae` 不存在,表 `users` 有相似列 `name`」+ 提示可能缺迁移 |
| `QueueConnectionException` | 提示检查 `QUEUE_CONNECTION` 与 Redis 连通性,给出 `jaravel doctor` 命令 |
| `BindingResolutionException` | 列出容器中已注册的相似类型,提示可能缺 `@Component` 或 provider 注册 |

**这个知识库是 Jaravel 最容易被低估、但对新手留存影响最大的部分**。应作为独立任务持续积累:每当有用户报告「这个报错看不懂」,就加一条。

### 4.3 调试工具栏(v0.6)

页面底部的浮动工具栏(对应 Laravel Debugbar):SQL 条数与总耗时、渲染的视图与数据、路由信息、内存占用、缓存命中率、队列投递、本次请求事件。

---

## 5. 日志与 dump

```java
Log.info("订单已创建 id={}", order.getId());
Log.channel("payments").error("网关失败", e);
Log.withContext(Map.of("tenant", tid)).info("...");
Log.stack(List.of("daily", "slack")).critical("...");

// 调试输出
dump(user);                  // 格式化打印到控制台/错误页/调试栏
dd(user);                    // 打印并中止请求(Laravel 的招牌)
ray(user);                   // 发送到 Ray 桌面应用(若安装)
```

`dump` 的格式化器要能把 Eloquent 模型打印成可读形式:

```
User {
  id: 1
  name: "Tom"
  email: "t@a.com"
  password: <hidden>
  meta: { "city": "北京" }
  posts: <not loaded>
  profile: Profile { id: 1, ... }
  [dirty: name]
}
```

而不是 `com.app.models.User@3f2a1b`。这需要为每个模型生成 `dump` 支持(APT 顺带生成)。

日志驱动:`single` / `daily` / `stack` / `slack` / `syslog` / `stderr` / `json`(结构化,生产推荐)。底层用 SLF4J + Logback,`Log` 门面只是加上通道、上下文、格式化参数的糖。

---

## 6. IDE 集成

要让体验完整,需要一个 IntelliJ 插件(`jaravel-idea-plugin`):

| 功能 | 优先级 |
|---|---|
| jblade 语法高亮 + 补全 + 格式化 | **高** |
| jblade 中 `{{ user.name }}` 的属性跳转与重命名联动 | **高** |
| `View.of("posts.index")` → 模板文件跳转 | 高 |
| `Url.route("posts.show")` → 路由定义跳转 | 中 |
| 字节码增强生成的 getter/setter 的代码提示(若采用方案 B) | **高**(决定模型方案可行性) |
| `@Scope` / `@HasMany` 的 gutter 图标与导航 | 中 |
| `jaravel` 命令的运行配置 | 中 |
| 迁移文件的时间戳生成 | 低 |

**这是一笔必须计入的成本**(约 8~12 周)。没有 IDE 支持,模板引擎和模型增强的体验都会打折。VS Code 插件优先级次之(Java 开发者以 IntelliJ 为主)。

---

## 7. 性能基准与 CI 门禁

把 DX 指标变成会让 CI 失败的测试:

```java
class DevExperienceBenchmark {
    @Test void template_change_reflects_under_100ms() { ... }
    @Test void method_body_change_requires_no_restart() { ... }
    @Test void new_class_restart_under_2s() { ... }
    @Test void cold_start_under_1500ms() { ... }
}
```

这些数字在 DESIGN.md §7 中已列为验收标准,必须可自动验证——否则会在开发过程中悄悄劣化。

---

## 8. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 说明 |
|---|---|---|---|
| 代码变更生效 | 0 重启 | 方法体 0 重启;结构性变更 < 2s | **无法完全追平**,如实告知 |
| 重启期间的请求 | — | 挂起等待 + 自动重放 | 让体验接近「刷新即生效」 |
| 登录状态 | 保持 | 保持(session 外部化) | 关键细节 |
| 错误页 | Ignition | 同级 + 编译期信息 + 修复建议一键应用 | 可以做得更好 |
| 调试栏 | Debugbar(第三方) | 内置(v0.6) | |
| IDE 支持 | 无需(PHP 动态) | **必需一个插件** | Java 的额外成本 |
| N+1 可见性 | 需装包 | 内置检测 + 错误页标注 + 测试断言 | 三层防护 |

---

## 9. 待决问题

- [x] ~~**L2 的实际覆盖率需要实测**~~ —— **已实测**(2026-10-28),扫描 spring-petclinic(200 commit)与 halo(300 commit)的真实历史。详见 [verification/v4-hotreload-coverage/RESULT.md](../verification/v4-hotreload-coverage/RESULT.md)。
      · 标准 HotSwap 覆盖 **50%~78%**(波动大)—— 原估计 60% 落在区间内,**L2 的投入合理**;
      · 增强 HotSwap(JBR/DCEVM)覆盖 **87%**(两个项目高度一致),真正必须重启的只有 **13%**;
      · 因为**纯新增占结构性变更的约 89%**,而纯新增正是增强 HotSwap 能处理的。
      · **行动项**:JBR 应从「检测到就启用的增强项」提升为**推荐的默认开发运行时**(§3.4 需据此修正),并加入 `jaravel doctor` 检查项。
- [ ] **Spring bean 定义 / 注解变更的影响未量化**。改一个 `@Bean` 方法体虽然字节码可 HotSwap,但容器里已注入的实例不会变 → 实际仍需 L3。这会把真实的「0 重启」体验拉低到 87% 以下,需在 v0.3 实现 L2 时实测。
- [ ] 增量 APT 的可行性:改一个模型字段是否能只重跑该模型的注解处理?标准 APT 是全量的。Gradle 有增量注解处理支持,Maven 较弱。可能需要自己实现一个轻量的「变更模型 → 只生成对应产物」的旁路。这直接影响「改模型字段」的重载耗时。
- [ ] 请求挂起 + 自动重放的实现细节:POST 请求的 body 需要缓存才能重放;文件上传的重放不现实(需直接放行或提示)。需要明确哪些请求可重放。
- [ ] 错误页的「一键应用修复」需要写入源文件,存在风险(覆盖用户未保存的编辑)。需要先检测文件是否被外部修改,并提供 diff 预览而非直接改。
- [ ] IntelliJ 插件的开发资源:这是一个独立的子项目(Kotlin + IntelliJ Platform SDK)。是否在 v1.0 前完成?倾向:v0.5 交付 jblade 语法高亮与补全(最小可用),v1.0 交付完整版。若模型采用字节码增强方案,getter 提示必须在 v0.2 就可用,否则模型方案不可行。
- [ ] `dd()` 在 Java 中如何「中止请求并输出」?抛一个特殊异常由错误页渲染器捕获。但在队列/CLI 中语义不同,需分别处理。
