# 17 · 路线图与工作量

---

## 0. 总览

| 版本 | 主题 | 单人工期 | 累计 | 关键验收 |
|---|---|---|---|---|
| **v0.0** | 技术验证(4 个高风险点) | 3 周 | 3 周 | 四项验证全部通过或有明确退路 |
| **v0.1** | 垂直切片 | 10 周 | 13 周 | CRUD 页面代码量 ≤ Laravel 的 1.3 倍 |
| v0.2 | Eloquent + 迁移 + 验证 | 16 周 | 29 周 | 博客应用零裸 SQL |
| v0.3 | 认证 + 会话 + 缓存 + 热重载 | 12 周 | 41 周 | 登录全链路;改代码 2s 内生效 |
| v0.4 | 队列 + 事件 + 调度 + 通信 | 10 周 | 51 周 | 异步下单通知流程打通 |
| v0.5 | Artisan + 测试 + API 资源 | 10 周 | 61 周 | CLI 手感对齐 artisan |
| v0.6~0.8 | 完善与增强 | 14 周 | 75 周 | 调试栏、广播、浏览器测试、IDE 插件 |
| v0.9 | 性能 + native + 安全审计 | 8 周 | 83 周 | native 启动 < 100ms;外部审计通过 |
| **v1.0** | 文档 + 示例 + 发布 | 8 周 | **91 周** | 外部开发者 30 分钟跑通 Todo |

**单人全职 ≈ 91 周 ≈ 21 个月。**
**3~4 人团队 ≈ 9~12 个月**(并行度受限于 Eloquent 与 jblade 两条主线难以拆分)。

不含 IntelliJ 插件(额外 8~12 周,可并行)。

---

## v0.0 · 技术验证(3 周)

**不写框架,只验证四个会推翻架构的技术点。** 每项失败都有预设退路,目的是避免在错误的地基上盖 20 个月的房子。

| # | 验证项 | 方法 | 成功标准 | 失败退路 |
|---|---|---|---|---|
| 1 | **模型访问器生成方案**([06](06-eloquent.md) §2.2) | 用 ByteBuddy 在 Maven `process-classes` 给一个只有字段的类织入 getter/setter;在 IntelliJ 中验证代码提示 | IDE 能提示生成的 getter,或确认写插件可解决 | 退到方案 A:用户写 `class User extends UserBase` |
| 2 | **路由 APT 表达式解析**([03](03-routing-http.md) §1.2) | 写一个 APT,解析 `r.get("/u/{id}", C::m)` 并校验方法存在 | 能稳定解析 90% 的常见写法 | 退到运行时反射 + 启动期校验(编译期→启动期,DX 略降) |
| 3 | **jblade 自研 vs 基于 jte** | 用 jte 的 API 尝试表达 `@extends` + `<x-component>` + slot | 判定 jte 能否承载,或确认需自研 Emitter | 自研(+5 周工作量,已计入估算) |
| 4 | **L2 热重载覆盖率**([15](15-devx-hot-reload.md) §9) | 在一个真实 Spring Boot 项目上记录 2 周的变更分类 | 方法体修改占比 ≥ 50% | 放弃 L2,全力把 L3 压到 1s |

**另外在这 3 周内必须完成**:`benchmark/laravel-parity/01-crud-blog/laravel/` 的 Laravel 参照实现(作为后续所有 API 设计的标尺)。

---

## v0.1 · 垂直切片(10 周)

**目标**:一个能跑的博客首页 + 文章 CRUD,用最少的模块。

### 范围

| 模块 | 内容 | 周 |
|---|---|---|
| `jaravel-core` | 容器(委托 Spring)、6 个核心门面、Pipeline、Config + `.env` | 2 |
| `jaravel-support` | `Coll`、`Str`、`Arr`、`DateX` 的 80% 常用方法 | 1 |
| `jaravel-routing` + `http` | 路由 DSL、`HandlerMapping`/`Adapter`、中间件、Request/Response | 2.5 |
| `jaravel-view` | jblade:输出/控制结构/`@extends`/`@include`,**运行时编译**,错误行号映射 | 3 |
| `jaravel-database` | 查询构造器(MySQL + SQLite 方言)、`DB.transaction` | 1.5 |
| `starter` | 自动装配 + 虚拟线程默认开启 | — |

**本阶段不做**:Eloquent、迁移、认证、队列、APT 优化、组件系统、生产模式预编译。

### 验收(硬性)

1. `examples/todo/` 能跑:列表、创建、编辑、删除、表单校验(手写校验即可);
2. **`parity-check` 对 `01-crud-blog` 的比值 ≤ 1.3**;
3. jblade 模板中拼错属性名 → 报错指向**模板文件的正确行号**并给出相似方法建议;
4. 冷启动 < 1.5s;
5. ArchUnit 边界规则生效且通过。

### 退出判断(项目是否继续)

> 若第 2 项超过 1.5,或第 3 项做不到,**停下来重新设计 API,不要进入 v0.2**。

v0.1 结束时应该做一次正式的 go/no-go 评审。这是整个项目唯一的、也是最后的低成本退出点——过了 v0.2 就是 sunk cost。

---

## v0.2 · Eloquent + 迁移 + 验证(16 周)

| 任务 | 周 |
|---|---|
| `@Model` 解析 + 元模型 `User_` 生成 | 2 |
| 访问器生成(按 v0.0 的结论) | 1~3 |
| 类型化查询器生成 + 便捷方法 | 2 |
| 关联:`HasOne/HasMany/BelongsTo/BelongsToMany` | 2.5 |
| 关联:`*Through` / `Morph*` | 2 |
| 预加载 + N+1 三道防线 + 身份映射 | 2 |
| 脏跟踪 + `save`/`delete` + 软删除 | 1.5 |
| 作用域(局部/全局)+ 模型事件 + 观察者 | 1.5 |
| 类型转换系统 | 1 |
| **APT 错误信息 + compile-testing 测试套件** | 3 |
| Schema DSL + 迁移 + checksum + Seeder + 工厂 | 3 |
| 验证器(60 条规则 + FormRequest + 本地化) | 3 |
| PostgreSQL 方言 | 1 |

> 合计略超 16 周(约 25 周),因为这是两个重模块叠加。**若人力有限,建议把验证器(3 周)和 PG 方言(1 周)挪到 v0.3**,把 v0.2 聚焦在 Eloquent 上。

### 验收

1. `examples/blog/` 全部用 Eloquent,**零裸 SQL**;
2. `parity-check` 对 `01-crud-blog` ≤ 1.2(有了 Eloquent 应该更接近 Laravel);
3. [06](06-eloquent.md) §6 列出的 5 类 APT 报错场景全部有测试且信息友好;
4. 开启 `prevent-lazy-loading` 后 `examples/blog/` 全部测试通过(证明没有隐藏的 N+1);
5. 单条主键查询耗时 ≤ Spring Data JPA 的 70%。

### 实测结果(v0.2 完成后回填)

| # | 标准 | 结果 |
|---|---|---|
| 1 | `examples/blog` 零裸 SQL | **达成** —— 全部经 Eloquent;端到端 33/33 |
| 2 | parity ≤ 1.2 | **达成** —— 切片 01 **1.05**(v0.1 为 1.27),切片 02 **1.03** |
| 3 | APT 报错场景有测试且友好 | **达成** —— 24 条 compile-testing 测试;原文第 1 类错误已被设计掉,见 [06](06-eloquent.md) §6.1 |
| 4 | `prevent-lazy-loading` 下全绿 | **达成** —— 显式开启后 33/33,日志零违规 |
| 5 | 单条主键查询 ≤ JPA 的 70% | **未达成,且标准本身定错了** —— 见下 |

**关于第 5 条。** 实测(同一 SQLite 文件、同一 HikariCP 连接池、JPA 侧每次
`clear()` 清一级缓存):

| | 单次 | 框架附加开销 |
|---|---|---|
| 裸 JDBC(下界) | 13.9 µs | — |
| Spring Data JPA | 15.9 µs | +1.9 µs |
| Jaravel Eloquent | 15.9 µs | +2.0 µs |

比值 ≈ **1.00**,框架开销与 Hibernate 在测量噪声内持平。

但 0.70 这个目标**任何实现都达不到**:裸 JDBC 已经是 JPA 的 88%,
框架开销降到零也只能到 0.88。JDBC + SQLite 的固有成本占了总耗时的 88%,
「单条主键查询」这条路径上框架能动的只有剩下那 12%。标准当初隐含假设
「框架开销占主导」,实测不成立。

修正后的标准应该是:**框架附加开销不高于 Hibernate**(当前 2.0 vs 1.9 µs,
基本持平),或者换一条框架开销真正占主导的路径去量(如「取 1000 行并映射」)。

过程中做的两项优化都有记录:
- 把 `ResultSetMetaData` 的列标签解析提出逐行循环(原先 1000 行 × 4 列 =
  4000 次多余的 JDBC 元数据调用),并去掉每行两次 `LinkedHashMap` 拷贝 ——
  对单行查询无感,对大结果集是实打实的;
- **按主键查询的预编译 SQL 快路径**。实测「构造 Query + 用 Grammar 拼字符串」
  单次 1.5 µs,占框架开销三成,而且每次拼的是一模一样的 SQL。
  加缓存后 Jaravel 从 17.8 µs 降到 15.9 µs —— 降幅与诊断值吻合。
  (Hibernate 对 `find(id)` 同样缓存 SQL,不缓存等于白送一个三成的差距。)

---

## v0.3 · 认证 + 会话 + 缓存 + 热重载(12 周)

| 任务 | 周 |
|---|---|
| 缓存(Caffeine/Redis/DB/File + 标签 + 锁 + 限流) | 2 |
| 会话(cookie/redis/db + flash + CSRF) | 1.5 |
| 认证:`SessionGuard` + `TokenGuard` + `UserProvider` + Hash | 2 |
| 认证:`JwtGuard` + `SanctumGuard` | 1.5 |
| 授权:Gate + Policy + `@Policy` APT + 中间件 | 1.5 |
| 密码重置 + 邮箱验证 + 登录节流 | 1 |
| **`jaravel-security-bridge`**(Spring Security 桥接) | 1 |
| **热重载 L1 + L3**(资源 + 类加载器重启 + 请求挂起重放) | 2.5 |
| 开发期错误页(含 SQL 面板 + 修复建议知识库 v1) | 2 |
| 安全清单测试([08](08-auth.md) §7.2 逐条) | 1 |

### 验收结果

| # | 条目 | 结果 | 证据 |
|---|---|---|---|
| 1 | `install:auth` 的登录/注册/重置流程端到端可用 | ✅ | `examples/auth` 的 `AuthFlowTest`,27 条真 HTTP 测试 |
| 2 | [08](08-auth.md) §7.2 的 12 项安全检查全部有测试 | ✅ | `AuthSecurityTest`,12 个嵌套类 / 47 条断言 |
| 3 | 改方法体 → 0 重启;新增类 → < 2s;改模板 → < 100ms | ⚠️ **部分** | 见下 |
| 4 | 改代码后登录状态不丢失 | ✅ | `HotReloadAcceptanceTest`,两个先后启动的真实上下文共用会话目录 |
| 5 | `jaravel-security-bridge` 下 `Auth.user()` 读 Spring Security 的结果 | ✅ | `SecurityBridgeTest`,37 条测试 |

**第 3 条的实际情况,逐项说明:**

| 目标 | 实际 | 说明 |
|---|---|---|
| 改模板 → < 100ms | ✅ 0 重启 | L1:jte 的 devMode 按 mtime 重编译,`ViewEngine.invalidate()` 丢缓存 |
| 改配置 → 0 重启 | ✅ | 同上。但 `.env` 与 `pom.xml` 需要重启 JVM(前者在 Spring 启动之前就被读进系统属性),`ChangeClassifier` 会明确告知 |
| 新增类 → < 2s | ✅ 实测 136 ms | 量的是**容器重建**,它是 L3 的主要成本。真实重启还要加上增量编译 —— 那部分取决于改了多少文件,没有测到 |
| **改方法体 → 0 重启** | ✅ **v0.4 已补上** | v0.3 时只有**分类**逻辑(`ChangeClassifier` 的 `HOTSWAP` 路径),执行是空的 —— `onHotswap` 钩子没有任何赋值点,所以永远降级到 L3。v0.4 接上了真正的 `redefineClasses`,见 docs/15 §3.4 |

v0.3 没做 L2 的原因是它的收益与风险不成比例:标准 HotSwap 只支持方法体,
而「改方法体」在有 APT 的项目里经常伴随注解变更(那时必须走 L3),
于是实际覆盖率远低于 docs/15 §1 估计的 60%。**这一项当时列入 v0.4**,
并且 docs/15 §3.4 原本就写明「不把项目的核心体验押在非标准 JVM 上 ——
L3 的 < 2s 重启是兜底保证」。

> **v0.4 补记。** L2 做完了(docs/15 §3.4),上面那个顾虑**成立**:
> 带 APT 注解的类一律降级到 L3,而那在这个框架里是一大类改动。
> 所以 L2 的实际收益集中在「控制器/服务里的纯方法体修改」。
>
> 补的过程中还发现一件更严重的事:devtools 自己**文件一变就重启**,
> 于是不只 L2 无意义,v0.3 承诺的「按需重启(连续编辑 10 个文件只
> 重启一次)」也**从未成立**。修法是接管重启时机,见 docs/15 §3.4。

**v0.3 额外交付**(不在原计划里,写测试时发现必须做):

- `FileSessionStore` —— 没有它「改代码后登录状态不丢失」做不到;
- `SessionLifecycle` —— 异常路径上的会话写回。原先 302/401/419/校验失败
  会丢掉 `url.intended` 与闪存的校验错误,也就是说 `@error` / `@old`
  **从来没有真正工作过**;
- `PendingCookies` —— remember cookie 原先从未下发到浏览器;
- `FixSuggestion` 知识库 v1 —— 20 个异常类型的可执行修复建议;
- `SourceSnippet` —— 错误页的源码片段与编辑器跳转。

---

## v0.4 · 队列 + 事件 + 调度 + 通信(10 周)

| 任务 | 周 |
|---|---|
| 队列:契约 + database/redis/sync 驱动 + JSON payload | 2 |
| Worker:虚拟线程并发 + 心跳 + 优雅停机 + 失败重试 | 2 |
| 队列:chain / batch / job 中间件 / 唯一性 | 1.5 |
| 事件:总线 + `@Listener` APT + 队列化监听器 + Spring 桥接 | 1.5 |
| 调度:Schedule DSL + 内置调度器 + 多实例去重 | 1.5 |
| 邮件:Mailable + SMTP/log/array + 邮件组件 | 1.5 |
| 通知:多渠道 + database/mail/slack + Notifiable | 1 |
| 存储:Filesystem + local/s3 + 上传安全 | 1.5 |
| multipart 上传:`UploadedFile` + `request.file(...)` + 五条文件校验规则 + `config/uploads.yml` | 0.5 |
| 二进制响应:`Response.download/inline/bytes` + 框架自注册的临时 URL 路由 | 0.5 |
| L2 热重载:`HotSwapper` + 自挂载 agent + APT 守卫 + 接管 devtools 的重启时机 —— **v0.3 复盘里挪过来的** | 0.5 |
| `Http` 客户端门面 | ~~0.5~~ **已在 0.4 完成** —— docs/14 §4.3 说的是 v0.4,与这一行矛盾;按 §4.3 做了,理由见那里 |

### 验收

1. 「下单 → 异步发票 → 邮件 + 站内通知」流程打通 ——
   **补测时发现「站内通知」那一半在这个示例里的覆盖是零**。
   零件分开都测过（队列/邮件在 `examples/queue`，通知在
   `examples/auth`），但没有任何一条测试把两个出口接在
   **同一条流程**上 —— 而「各自都对、接起来不对」正是这个项目里
   最常见的缺陷类型。现已补上：`examples/queue` 增了
   `Customer`（Notifiable）与 `InvoiceReady`（database 渠道），
   `QueueFeatureTest.OrderToMailAndNotification` 4 条走完整链路：
   HTTP 下单 → 投递 → worker → 任务里又入队一封邮件 →
   通知系统 → notifications 表；
2. `--concurrency=200` 下 worker 内存增长 < 100MB(验证虚拟线程优势)——
   **已实测:并发 200 + 400 个阻塞任务,堆增长 2.5 MB**
   (`WorkerConcurrencyBudgetTest`)。
   ⚠ 量的是 **Java 堆**,不是 RSS —— 平台线程的成本主要在栈,而栈不在堆里。
   所以这一条证明的是「worker 自己不会把堆吃掉」,
   **不是**「虚拟线程比平台线程省内存」的对照实验(那需要 OS 级的 RSS
   测量,在单元测试里做不可靠)。这个限定必须跟着这个数字一起说。
   ⚠ `--concurrency=200` 的**命令行开关**不存在(CLI 是 v0.5),
   当前只能按编程方式 `WorkerOptions.withConcurrency(200)`;
3. 调度器在 3 个实例下 `onOneServer()` 任务只执行一次 ——
   **补测时发现原先不成立,已修**(两个缺陷:锁 key 不含调度时刻 +
   跑完就释放;以及 `DatabaseStore.add` 清理过期行的竞态)。
   见 docs/10 §3.4。验证:`SchedulerMultiInstanceTest` +
   `DatabaseLockRaceTest`;
4. 上传安全项(内容嗅探、路径穿越、随机命名)全部有测试 ——
   **并且有一条真实的 multipart HTTP 路径走过它们**
   (`examples/auth` 的 `UploadFlowTest`,13 条)。
   单元测试喂给存储层的是手工构造的 `Upload` 对象,
   中间整层(容器怎么切 part、文件怎么进 `all()` 从而被校验看到、
   参数怎么绑)不在它的覆盖范围里 —— 而「每个零件都对、
   接起来不工作」是这个项目里最常见的一类缺陷。

### v0.4 末尾的文档↔现实对账

v0.4 的四条验收里有三条在补测时发现**原先不成立**(第 1、2、3 条)。
那个命中率说明问题不在某一条,而在「文档说了什么」与「代码做了什么」
之间没有人对过账。所以 v0.4 收尾时专门做了一次对账,逐条核实
v0.1~v0.4 里**具体可查**的声明(点名的驱动、点名的类、点名的注解)。

查出来的脱节如下。每一条都已在对应文档的**原地**标注,不只记在这里 ——
一个只写在路线图末尾的勘误没人会读到。

| 文档声明 | 现实 | 失败方式 | 已标注于 |
|---|---|---|---|
| `redis` 是缓存的「生产默认」 | **不存在** | 启动报错并列出已注册驱动 | docs/09 §1.4 |
| `redis` 是会话的「生产默认」 | 转给 cache 驱动,而那个不存在 | 同上 | docs/09 §2 |
| `DateX`(Carbon 等价,有完整 API 一节) | **不存在** | 编译错误 | docs/02 §6.3 |
| `SanctumGuard`、`BasicGuard` | **不存在** | 启动报错并列出可用驱动 | docs/08 §1.1 |
| `@HasOneThrough`、`@MorphOne`、`@MorphToMany` | **不存在**(10 个关联实现了 7 个) | 编译错误 | docs/06 §4.1 |
| 组件系统 `<x-...>`(整节) | **不存在** | ⚠ **原先静默** —— 已改成报错 | docs/04 §1.7 |
| 「仅 redis/caffeine 支持标签」 | **写错了**,所有驱动都支持 | 不适用 | docs/09 §1.2 |

两点值得单独说:

**① 绝大多数都是响亮失败的。** 编译错误或启动报错 —— 配错了会立刻知道。
唯一的例外是 jblade 的 `<x-...>`:它原先会被当成普通 HTML 原样输出,
而浏览器对未知标签不报错,只是什么都不渲染。
症状是「那块内容是空的」,与真正的原因之间没有线索。
**这次把它改成了编译期报错**,这是对账过程中唯一动到的生产代码。

**② 「响亮失败」不等于「可以不标注」。** 一个照着 docs/09 配
`driver: redis` 的人会在启动时撞墙 —— 而文档读起来像是他配错了。
错误消息说的是「没有注册 redis」,它没法告诉读者
「这份文档承诺了一个还不存在的东西」。

### v0.4 之后的 parity 复测

对账顺手跑了一遍 parity 门禁,结果是**v0.4 自己把它弄坏过**:

| 切片 | 不含 CSS | 门禁 1.30 |
|---|---|---|
| 01:Todo CRUD | 1.054 | 通过 |
| 02:Blog(关联/校验/迁移) | 1.033 | 通过 |
| 03:认证(guard/Policy/重置/验证) | **1.291** | 通过(余量只有 0.009) |
| 04:队列/邮件/通知/调度 **(v0.4 新增)** | 1.142 | 通过 |

**新增切片 04,专为回答「v0.4 的 API 设计保住论点了吗」。**
v0.4 加的 API 表面是四个版本里最大的,而它对 parity 的影响此前
完全没被测量过。覆盖:投递(立即/延迟/同步)、唯一性任务、重试与
backoff、失败回调、链式、批量(含 `then`/`finally`/`allowFailures`)、
入队邮件、站内通知、计划任务。Laravel 参照实现在
`benchmark/laravel-parity/04-queue/laravel/`。

结论是 **1.142 —— 保住了**,而且明细说明代价在哪:

| 维度 | Jaravel | Laravel | 说明 |
|---|---|---|---|
| 控制器 | 103 | 100 | **基本持平** —— 投递 API 的表达力没有损失 |
| 六个任务类 | 149 | 167 | **Jaravel 更短**(`StatementsDone` 15 vs 24) |
| 邮件 + 通知 | 55 | 57 | 持平 |
| 模型 | 47 | 16 | Java 的字段声明是 O(n) —— 已知的结构性成本 |
| 调度 | 47 | 29 | Jaravel 更长(见下) |
| 配置 | 41 | **0** | Laravel 的 `config/*.php` 随框架发布且默认值可用 |

两点值得单说:

- **任务类上 Jaravel 反而更短。** 这个结果出乎预期 —— Java 的
  `implements ShouldQueue` + 一个 `handle` 方法比 Laravel 的
  `use Queueable` + 构造器里 `onQueue(...)` 更省,而 Jaravel 的
  `@Override public String queue() { return "mail"; }` 是一行。
- **配置那 41 行是最大的单项差距**,而它与 API 设计无关:
  Laravel 的 `config/queue.php` / `mail.php` 随框架发布。
  去掉配置后比值是 **1.048**。

口径上四条决定都写在 `parity.py` 的切片 04 注释里,其中两条
**刻意选了对 Jaravel 不利的那一侧**(Jaravel 的配置计入 /
Laravel 的不计入;批次回调 Laravel 用闭包、Jaravel 必须用任务类 ——
后者是 Java 的 lambda 跨不过序列化边界的真实代价)。

> ⚠ **一笔被排除但真实存在的账:** 队列的三张基础设施表
> (`jobs` / `failed_jobs` / `job_batches`)在 Laravel 侧由
> `artisan queue:table` 生成,开发者一行不写;Jaravel 的 CLI 是 v0.5,
> 所以 `examples/queue` **手写了 58 行**。
> 切片把两侧都排除了(计入它量的是「还没有 artisan」这个暂时差距,
> 不是 API 设计),但今天的用户真的要写那 58 行 ——
> 算进去的话切片 04 是 **1.275**,仍然通过但余量很薄。
> 这是 v0.5 的 `make:*` / `queue:table` 那条线要还的债。

> ⚠ **parity 量的是代码量,不是能力。** 一处具体的例子:
> Jaravel 的 `Queue.dispatch(...)` 在被唯一性约束去重时返回 null,
> 所以接口能把 `"deduplicated"` 告诉调用方;Laravel 的 `dispatch()`
> 永远返回 `PendingDispatch`,去重是静默的,调用方无从得知。
> 那是 Jaravel 的一个真实优势,而比值因此**对 Jaravel 不利**
> (它多写了那行判断)。

v0.4 的上传演示把三条 `/avatar` 路由加进了
`examples/auth/.../WebRoutes.java` —— 而那个文件是**切片 03 的被测
对象**,它对面的 Laravel 参照实现里没有上传功能。
实测后果:切片 03 从 **1.294(通过)变成 1.3015(超标)**。

那比「超标」本身更糟:退化不来自「Jaravel 写同样的功能更费代码」,
它来自**两侧在比不同的功能**。一个不再 apples-to-apples 的基准会给出
看起来精确的错数字,而 parity 是这个项目**唯一**用来量化
「Laravel 级开发体验」这个论点的仪器。

**修法**:上传路由移到独立的 `UploadRoutes.java`
(框架本来就支持多个 `RouteDefinition` bean)。切片 03 回到 1.291。
顺带让示例结构更诚实 —— 上传属于 v0.4,而切片 03 量的是 v0.3 的
「加个登录 = 3 行」。

**两条该记住的规矩:**

1. **往 `examples/` 里加新功能前先查它是不是 parity 的被测文件。**
   被测文件只能跟着对应的 Laravel 参照一起改。
2. **切片 03 的余量只有 0.009** —— 认证是 Laravel 最省代码的地方
   (`parity.py` 的注释里把这一点讲得很清楚:挑一个 Jaravel 占优的
   切片去量只会得到自我安慰的数字)。那个文件再多三行就会超标。

顺带修了仪器本身的一个问题:比值原先按两位小数显示,于是 1.3015
会打印成「1.30 超标」—— 一个看起来自相矛盾的结论,读者会以为脚本坏了。
改成三位小数。

> **这次对账**只覆盖了「点名的驱动/类/注解」这类能 grep 的声明。
> **行为性**的声明(「这个规则在这种输入下返回什么」)没有系统地对过 ——
> 而 v0.4 修掉的那一批缺陷(`ThrottlesExceptions` 从不打开、
> `maxExceptions` 完全失效、批次计数恒为 0、`onOneServer` 完全无效)
> 全部属于那一类。它们只能靠测试发现,而那是 v0.5 的
> 「测试支持」那条线要解决的事。

---

## v0.5 · Artisan + 测试 + API 资源(10 周)

| 任务 | 周 |
|---|---|
| Artisan 内核 + `@Command` APT + 输出/交互/进度条 API | 1.5 |
| 全部 `make:*` 命令 + stub 机制 + `stub:publish` | 2 |
| 数据库/运行时/检视命令(约 40 个) | 1.5 |
| `jaravel-cli`(picocli)+ 本地命令通道 + 离线/在线分流 | 2 |
| `tinker`(JShell 集成 + 格式化器 + `:reload`) | 1.5 |
| `jaravel doctor` + `analyze:*` | 1 |
| ~~`jaravel-testing`:三层基类 + HTTP 断言 + 数据库断言~~ **已在 v0.3 后提前交付** | ~~2.5~~ 1 |
| API 资源 + 分页 + 异常映射 + Problem JSON | 2 |
| OpenAPI 生成 + diff | 1.5 |

> 同样超出 10 周(约 15.5 周)。CLI 的 native 编译可推到 v0.9。

### 验收

1. `jaravel make:model Post -mcrf` < 100ms(离线命令);
2. `jaravel migrate` 在 dev server 运行时 < 200ms;
3. `tinker` 中能查模型、发邮件、`:reload`;
4. 框架自身测试覆盖率 > 70%;
5. 一个功能测试 ≤ 5 行(对照 [14](14-testing.md) 的示例)。

---

## v0.6 ~ v0.8 · 完善(14 周)

| 版本 | 内容 | 周 |
|---|---|---|
| v0.6 | jblade 组件系统 + 上下文感知转义;调试工具栏;广播(WebSocket)+ broadcast 渠道;二步验证;`LazyColl` | 5 |
| v0.7 | `jaravel-dashboard`(Horizon 等价);幂等性中间件;`jaravel-permissions`(RBAC);Kafka/RabbitMQ/SQS 驱动 | 5 |
| v0.8 | 浏览器测试(Playwright);多租户支持包;Inertia 适配;并行测试 | 4 |

并行进行:**IntelliJ 插件**(8~12 周,独立人力)。

---

## v0.9 · 性能、native、安全(8 周)

| 任务 | 周 |
|---|---|
| jblade 生产模式预编译(Maven 插件) | 1.5 |
| 全链路零反射审计 + native-image 构建通过 | 2 |
| JMH 基准 + 性能调优(路由匹配、转义、查询构造) | 2 |
| **第三方安全审计** + 修复 | 2 |
| 依赖收敛 + 供应链扫描 + SBOM | 0.5 |

### 验收(DESIGN.md §7 的全部指标)

1. native 启动 < 100ms;
2. 简单 JSON 接口吞吐 ≥ 裸 Spring MVC 的 90%;
3. 模板渲染 ≥ Thymeleaf 的 3 倍;
4. 外部安全审计无高危项。

---

## v1.0 · 发布(8 周)

| 任务 | 周 |
|---|---|
| 文档站(中英双语,对标 laravel.com 的组织方式) | 3 |
| 4 个完整示例项目(todo / blog / api-only / multi-tenant) | 2 |
| starter kit(`install:auth` 的 Breeze 等价物,含前端) | 1.5 |
| 迁移指南:从 Spring Boot 迁移 / 从 Laravel 迁移 | 1 |
| 发布流程:Maven Central、CLI 分发、版本策略、安全响应流程 | 0.5 |

### 验收

1. 外部开发者(未参与开发)照文档 **30 分钟内**跑通 Todo 应用;
2. 文档覆盖所有公开 API;
3. 所有 `docs/` 中的「待决问题」已关闭或转为 issue;
4. `parity-check` 全部场景 ≤ 1.3。

---

## 1. 并行化建议(3~4 人团队)

| 人员 | 主线 | 说明 |
|---|---|---|
| A(最强) | core → **Eloquent + APT** | 项目胜负点,需要最强的人,全程不切换 |
| B | routing/http → **jblade** → IDE 插件 | 另一条重模块主线 |
| C | database → validation → auth → cache/session | 依赖 A 的 core,但可早期并行 |
| D | queue/events/scheduler → mail/notifications/storage → console → testing | 依赖最少,可最晚加入 |

**关键路径**:core(2 周)→ Eloquent(16~25 周)。这条线决定了项目最短工期,无法通过加人压缩。

**最早可并行时点**:core 的容器与门面机制完成后(第 2 周),B/C/D 即可开工。

---

## 2. 里程碑之间的硬性门禁

每个版本发布前必须通过:

```
□ ArchUnit 边界规则(core 无 Spring 依赖)
□ parity-check ≤ 1.3
□ 冷启动 < 1.5s
□ 性能基准无 > 5% 回退
□ APT 错误信息测试全通过
□ 该版本新增模块的「逃生口」均有文档与测试
□ docs/ 中对应模块文档已更新(含待决问题的关闭或转移)
□ CHANGELOG 完整
```

**门禁不达标就不发版**,而不是「先发了后面补」。框架一旦有用户,质量债的偿还成本会指数上升。

---

## 3. 风险对工期的影响

| 风险 | 概率 | 工期影响 | 应对 |
|---|---|---|---|
| 模型访问器方案失败(方案 B 不可行) | 中 | +0(退方案 A),但优雅性下降 | v0.0 验证 |
| jblade 需完全自研 | 中 | +5 周 | 已计入 |
| 路由 APT 解析不可靠 | 中 | +0(退运行时) | v0.0 验证 |
| 增量 APT 做不了,改模型重载 > 10s | 中高 | DX 受损,可能需额外 4 周做旁路 | v0.3 评估 |
| 安全审计发现架构级问题 | 低 | +4~8 周 | 从 v0.3 起按清单自查 |
| GraalVM native CLI 构建成本过高 | 中 | +0(退 AppCDS JVM 版) | v0.5 决策 |
| 关键人员流失(Eloquent 主线) | — | **致命** | 代码评审 + 设计文档强制;这正是本 docs/ 存在的理由之一 |

---

## 4. 什么情况下应该停止这个项目

诚实列出退出条件,比假装没有风险更有价值:

1. **v0.1 结束时 `parity-check` > 1.5**,且经过一轮 API 重设计仍无改善 → Java 的语法约束使「Laravel 级优雅」不可达,项目前提不成立;
2. **模型访问器只能用方案 C**(用户必须手写 getter/setter)→ Eloquent 相对 Spring Data JPA 的优势大幅缩水,需重新评估价值主张;
3. **v0.3 后 L3 重启稳定 > 5s** → DX 摩擦会抹平所有 API 优势;
4. **v1.0 发布 6 个月后 GitHub star < 500 且无真实生产用户** → 生态引力无法克服,应考虑转为内部框架或归档。

第 4 条尤其需要事先想清楚:这个项目的技术可行性是高的,**真正的风险是「做出来了但没人用」**。因此 v1.0 的「文档 + 示例 + 迁移指南」8 周不是可砍的收尾工作,而是与 Eloquent 同等重要的投资。
