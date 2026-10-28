# 18 · 架构决策记录(ADR)与风险登记册

---

# 第一部分:ADR

每条记录格式:**背景 → 决策 → 理由 → 被否决的方案 → 代价 → 复审条件**。

---

## ADR-001 · 骑在 Spring Boot 上,但核心零 Spring 依赖

**状态**:已采纳 · **日期**:2026-10-28

**背景**
目标用户是 Java 开发者,Spring Boot 是事实标准。既要给出 Laravel 式体验,又不能让用户放弃 Spring 生态。

**决策**
`jaravel-core` 及所有功能模块零 Spring 依赖,只依赖自定义契约;Spring 仅作为可替换的 runtime adapter 存在于 `jaravel-spring-boot-starter`。由 ArchUnit 在 CI 中阻断式强制。

**理由**
1. 用户零成本保留整个 Spring 生态(Actuator、Micrometer、Spring Cloud、任意三方 starter);
2. Spring Boot 大版本升级的冲击被收窄到一个模块;
3. 保留迁移到 Quarkus / Micronaut / 纯 Netty 的可能;
4. API 设计不被 Spring 的概念污染——这是「优雅」的前提条件。

**被否决的方案**
- *完全独立的框架*:失去全部 Spring 生态,采纳曲线陡到不可行;
- *深度集成 Spring*:API 会被 Spring 的概念渗透,且版本耦合;
- *Fork Spring Boot*:维护成本不可承受。

**代价**
适配层需要重复定义一些 Spring 已有的抽象(`Container`、`ConfigRepository`)。约 2000 行的重复,换取架构自由度,划算。

**复审条件**
若发现适配层超过 5000 行,或频繁需要让 core「知道」Spring 的存在,说明边界画错了,需重新评估。

---

## ADR-002 · 不自研 IoC 容器,委托 Spring

**状态**:已采纳

**决策**
`Container` 接口的 Spring 实现直接委托 `BeanFactory`,不自己实现依赖图解析与生命周期管理。

**理由**
写一个 DI 容器只需两周,但会立刻失去:Spring 的全部 starter 生态、AOP、`@Transactional` 的事务传播语义、Actuator、配置体系。**这是本项目性价比最低的自研项**。

**被否决的方案**
自研轻量容器(类似 Laravel 的 `Illuminate\Container`)。

**代价**
容器的某些 Laravel 特性(上下文绑定、`extend` 装饰)需要在委托层之上自己维护一张表。已在 [02](02-core-container.md) §1.2 设计。

---

## ADR-003 · 用编译期代码生成(APT)替代运行时反射与魔术方法

**状态**:已采纳 · **这是整个项目的技术主心骨**

**决策**
凡 Laravel 用 `__get`/`__call`/字符串反射实现的地方,一律用 APT 在编译期生成等价代码:模型元模型与访问器、类型化查询器、路由调用适配器、jblade 模板类、配置绑定、API 序列化器、provider/command/listener/policy 索引。

**理由**
1. **类型安全**:列名、路由、模板变量、关联路径拼错都在编译期暴露;
2. **零运行时反射**:启动快、吞吐高、GraalVM native-image 原生友好;
3. **IDE 可用**:跳转、补全、重命名联动;
4. 这是 Java 相对 PHP 的**真实优势**,不是模仿,是超越。

**被否决的方案**
- *运行时反射*(最接近 Laravel 的做法):失去类型安全,native 不友好,启动有扫描开销;
- *字节码织入为唯一手段*:调试困难,工具链兼容性风险大(但作为访问器生成的候选保留,见 ADR-010);
- *要求用户手写全部样板*:信息密度崩塌,违背项目前提。

**代价**
1. **APT 的错误信息质量成为头号风险**。生成器出错时若报错指向生成后的代码,DX 直接崩塌。因此 [06](06-eloquent.md) §6 的错误信息测试套件优先级高于功能实现;
2. 编译变慢(模型多时 APT 耗时上升),需要增量处理;
3. 「改模型字段」的热重载需重跑 APT,是 DX 的短板([15](15-devx-hot-reload.md) §9)。

**复审条件**
若 APT 的错误信息无法做到友好,或编译时间在 100 个模型时超过 20s,需考虑退到运行时方案。

---

## ADR-004 · 不以 Hibernate / JPA 为 ORM 底座

**状态**:已采纳 · **详细论证见 [05](05-database.md) §7**

**决策**
自研查询构造器(多方言 Grammar)+ Active Record,不以 Hibernate 为底座。提供 `jaravel-jpa-bridge` 供需要 JPA 的场景共存。

**理由**
1. **模型冲突**:Hibernate 的 Session/脏检查/延迟刷新与 Active Record 的「对象即一行,save() 即写库」是两套心智模型,叠加产生最差结果;
2. Hibernate 的复杂度**正是用户要逃离的东西**(`LazyInitializationException`、detached/merge、flush 时机);
3. SQL 可控性:Jaravel 承诺「写什么 SQL 心里有数」;
4. native-image 启动目标与 Hibernate 的元模型构建冲突;
5. 自研成本可控(约 1.5 万行,确定性工程,无技术风险)。

**被否决的方案**
- *Hibernate/JPA 为底座,上面包一层 Laravel 风格 API*:抽象泄漏不可避免,用户最终还是要理解 Session;
- *MyBatis 为底座*:MyBatis 的 XML/注解 SQL 与 Active Record 不匹配;
- *jOOQ 为底座*:jOOQ 优秀但其代码生成与 API 风格已成体系,包一层会很别扭,且商业许可对部分数据库有限制。

**代价**
失去二级缓存、自动级联持久化、JPA 生态工具(QueryDSL 等)。缓解方案见 [05](05-database.md) §7 的对照表。

**复审条件**
若多方言支持的维护成本超出预期(例如 PG/MySQL 的差异导致频繁 bug),考虑收窄到只支持 MySQL + PostgreSQL。

---

## ADR-005 · 不以 Jakarta Bean Validation 为验证底座

**状态**:已采纳 · **详细论证见 [07](07-validation.md) §7**

**决策**
自研验证器(约 60 条规则 + 字符串规则解析 + 本地化)。提供 `jaravel-bean-validation-bridge`。

**理由**
Bean Validation 是**声明式领域约束**(实体永远满足什么);Laravel 验证是**命令式输入校验**(这次请求的数据是否合法)。两者解决不同问题。强行套用会让 `unique:users,email,except_id`、`required_with:a,b`、`items.*.qty` 这类常见需求变得极其繁琐。

**代价**
自研约 7000 行;与 Bean Validation 生态(如 OpenAPI 从注解生成约束)不自动互通,需桥接。

---

## ADR-006 · 不以 Spring Security 为认证底座,但提供桥接

**状态**:已采纳 · **详细论证见 [08](08-auth.md) §8**

**决策**
自研 Guard/Gate/Policy 体系;密码哈希、JWT 签名等密码学原语一律复用成熟库(`spring-security-crypto`、`nimbus-jose-jwt`),**绝不自己实现密码学**。同时在 v0.3 交付 `jaravel-security-bridge`。

**理由**
1. Spring Security 的配置复杂度与「给接口加个登录」严重不匹配——这是 Java Web 开发体验最大的痛点之一;
2. 其 Filter 链无法按路由绑定,无法访问已匹配的路由参数(`can:update,post` 需要);
3. `@PreAuthorize` 的 SpEL 字符串无类型安全;
4. `SecurityContextHolder` 的 ThreadLocal 与 Jaravel 的 `ScopedValue` 请求作用域是两套上下文,双重维护必然不一致。

**代价与风险**
**这是本项目安全责任最重的决策**。缓解:
- 密码学原语不自研;
- [08](08-auth.md) §7.2 的 12 项安全清单逐条写测试;
- v0.9 前做第三方安全审计(预算必须保留);
- 建立安全响应流程(v1.0 前)。

**复审条件**
若安全审计发现架构级问题,或出现 CVE 级漏洞,应重新评估是否改为「默认桥接 Spring Security,自研作为可选」。

---

## ADR-007 · 强制虚拟线程-per-request + ScopedValue 请求作用域

**状态**:已采纳 · **基线定为 Java 21**(2026-10-28 由项目所有者决定)· **详细论证见 [01](01-architecture.md) §4**

**决策**
基线 **Java 21**(LTS,覆盖面最广)。starter 默认 `spring.threads.virtual.enabled=true`;请求上下文经 `ContextHolder` 抽象隔离——Java 21 下用 `ThreadLocal` + 强制 try-finally,Java 25+ 自动切到正式版 `ScopedValue`。抽象接口只有三个方法,成本极低。

**理由**
Laravel 的全局门面(`Auth::user()`)依赖 PHP 的每请求进程隔离。Java 要还原这个心智模型,必须有可靠的请求作用域。`ScopedValue` 的结构化绑定使泄漏在语法上不可能,这是 `ThreadLocal` 做不到的。

**连带决策:明确不支持 WebFlux / 响应式**
支持响应式会迫使所有 API 返回 `Mono`/`Flux`,直接摧毁优雅性。虚拟线程已解决阻塞 I/O 的伸缩性问题,响应式的价值大幅降低。**这是一个明确的非目标**,需在 README 中写明,避免用户期待落空。

**代价**
- 绑定 Java 21+(放弃 Java 8/11/17 用户,这是相当大的一部分存量市场);
- 虚拟线程下的 `synchronized` 固定(pinning)问题需审计框架内部代码(Java 24 已大幅改善);
- 无 WebFlux 支持会挡掉一部分用户。

---

## ADR-008 · 提供路由 DSL 与注解路由两种风格

**状态**:已采纳(妥协)

**决策**
主推 `RouteDefinition` + Fluent DSL;同时支持 `@Get`/`@Post` 注解路由,共享同一路由表与中间件管道。

**理由**
违背了「唯一正确答案」原则,但 Java 团队对注解式路由有强路径依赖,强制 DSL 会直接挡住采纳。文档明确推荐 DSL(集中可见、易分组),注解式定位为迁移友好路径。

**代价**
两套风格的文档与测试成本;团队内可能风格分裂。缓解:提供 `jaravel doctor` 检查项提示混用。

**复审条件**
若实际使用中注解式占比 > 70%,说明 DSL 的设计有问题,需重新审视 DSL 的书写成本。

---

## ADR-009 · 应用包名用短前缀 `app.*` + 标准 Maven 布局

**状态**:**已采纳(方案 C)** · 2026-10-28 由项目所有者决定 · 见 [16](16-module-layout.md) §4.1

**决策**:保留标准 Maven 布局(`src/main/java/`,不与任何 Java 工具链冲突),用短包名 `app.*` 降低噪音。

```
src/main/java/app/models/User.java              → package app.models;
src/main/java/app/http/controllers/...          → package app.http.controllers;
src/main/java/routes/WebRoutes.java             → package routes;
src/main/java/database/migrations/...           → package database.migrations;
src/main/resources/config/app.yml
src/main/resources/views/posts/index.jblade.html
```

**被否决**:A(项目目录即包根)—— 需 Maven 插件注册多个 source root,与部分 IDE/工具的默认假设冲突;B(`com.example.app.models.User`)—— 路径过深,与 Laravel 的目录观感差距最大。

**理由**:应用代码不作为库发布,无需组织前缀;同时不牺牲工具链兼容性。`jaravel new` 可询问是否改用完整包名。

---

## ADR-010 · 模型访问器的生成方式

**状态**:**已采纳(方案 E')** · 2026-10-28 经 v0.0 验证确定
**验证报告**:[verification/v1-model-accessor/RESULT.md](../verification/v1-model-accessor/RESULT.md)

**决策**:标量列用 **public 字段**;脏跟踪用**快照比对**;关联用**类级注解声明 + APT 生成访问器方法**(状态存 `Model` 基类)。

```java
@Model(table = "users")
@HasMany(name = "posts", target = Post.class)
public class User extends UserBase {
    @Id public Long id;
    public String name;
    public String email;
}

user.name = "Jerry";      // 与 $user->name = 'Jerry' 等长
user.save();              // 与快照比对,只 UPDATE 变化的列
user.posts().get();       // 关联走生成的方法
```

### 被否决的方案

**方案 B(ByteBuddy 编译后字节码织入)—— 技术上不可行,已证伪。**

javac 在 `compile` 阶段编译**全部**源文件,而织入发生在 `process-classes`(编译之后)。实测:

```
error: cannot find symbol  method setName(String)
error: cannot find symbol  method getName()
```

同模块内任何调用 getter 的源码都无法通过编译。残存路径只有 Lombok 式的 javac 内部 API AST 篡改(需 `--add-opens`、绑定 `com.sun.tools.javac.*`、随 JDK 升级可能崩),风险不可接受。

**方案 A(抽象声明 + APT 生成具体子类)** —— 可行但样板量 `2n+4`(E 为 `n+3`),且用户类必须 `abstract`(`new User()` 不可用)、运行时暴露 `User_Impl`。

**方案 C(手写 getter/setter)** —— 样板量 `3n+4`,30 字段模型需 94 行,信息密度崩塌。

**方案 D(record 不可变)** —— 与 Active Record 需要的可变性冲突。

### 另一个被证伪的子设计:关联不能是 public 字段

原设想 `public Rel<Post> posts;`。两条实测否决:① public 字段无法拦截懒加载;② 生成的父类构造器初始化子类字段**会被用户的显式初始化器静默覆盖**:

```
无显式初始化器 : INITIALIZED
有显式初始化器 : null          ← 写一个 = null 就让框架初始化失效
```

无声、依赖初始化顺序、用户无从察觉。故改为类级注解 + 生成方法。

### 附带收益

消除了一项 IDE 插件依赖。原本 [15](15-devx-hot-reload.md) §6 把「字节码增强生成的 getter 的代码提示」标为**高优先级、决定模型方案可行性**;方案 E' 的字段是真实字段,IDE 支持原生完整,该项作废。

### 代价

| 代价 | 严重度 | 对策 |
|---|---|---|
| public 可变字段违反 Java 封装正统 | 中 | 文档正面论证;提供 `@Model(accessors = true)` 开关生成 getter/setter |
| 每个已加载实例多一个 `Object[]` 快照 | 低 | 20 字段 ≈ 160 字节 |
| 可变对象字段(List/Map)原地修改检测不到 | 低 | `@Cast` 字段快照存序列化形态;**Hibernate 有完全相同的问题** |
| 无法在写入时做修改器 | 低 | 改为 `saving` 钩子 |

---

## ADR-011 · 模板引擎自研 vs 基于 jte

**状态**:**已采纳(基于 jte)** · 2026-10-28 经 v0.0 验证确定
**验证报告**:[verification/v3-jblade-jte/RESULT.md](../verification/v3-jblade-jte/RESULT.md)

**决策**:基于 jte 构建,形态为 **Blade 前端(自研)+ jte 后端(复用)+ JbContext 运行时(自研)**。

**关键限定:不使用 jte 的布局组合模型。** jte 原生布局是「子模板调用布局、整页塞进 `content` 参数」,导致深层组件无法向外层布局贡献内容,`@stack`/`@push` 无法表达。

**解法**(已验证):每个模板接收一个 `JbContext`,且 `section` 存入时**立即渲染**成字符串——此刻触发全部嵌套 `@push` 的副作用,布局随后渲染即可拿到。这正是 Blade 的两遍语义,body 只渲染一次。

**收益**:工作量 14 周 → **7.5 周**;并白得 jte 已内置的**上下文感知转义**(原列为 v0.6 愿望)、开箱的热重载(实测 **39ms** 重编译)与准确的模板行号映射(`error at errtest/typo.jte:6`)。

**代价**:
- 核心依赖 jte 的上游演进(维护活跃、API 稳定、仅 3 个小 jar);
- 中间产物 `.jte` 多一层(放 `target/`,错误已映射回 `.jblade.html`);
- **HTML 必须良构**——Blade 常见的「跨 partial 开闭标签」会编译失败。这是真实的迁移摩擦,但也正是上下文感知转义的前提。

---

## ADR-012 · 只支持 Maven(v1.0)

**状态**:已采纳(有保留)

**决策**
v1.0 只支持 Maven,v1.1 加 Gradle 插件。README 中明确说明。

**理由**
APT 配置、额外 source root、插件生命周期、模板预编译都要为 Gradle 重做一遍,约 4~6 周。在资源有限时优先把 Maven 路径做到完美。

**风险**
Gradle 在新项目与 Android 背景团队中占比不低,会挡掉一部分用户。**必须在 README 首屏说明**,避免用户试用后才发现——这种挫败感的传播成本很高。

---

## ADR-013 · 不提供 Kafka 队列驱动(v1.0)

**状态**:已采纳 · 见 [10](10-queue-events-scheduler.md) §7

**决策**
v1.0 不提供 Kafka 队列驱动。

**理由**
Kafka 是**事件流**而非**任务队列**:无单任务重试、无延迟消息、offset 语义与 ack 不同。强行纳入统一的 `QueueDriver` 抽象会产生错误预期(用户以为 `tries()` 生效,实际不生效),比不提供更糟。

**替代**
文档中给出「用 Jaravel 的事件系统 + 原生 Kafka 客户端」的集成指引。

---

# 第二部分:风险登记册

按「概率 × 影响」排序。

---

## R-01 · 生态引力:做出来了但没人用

**概率**:高 · **影响**:致命 · **类型**:市场

**描述**
技术可行性是高的,**真正的风险是无人采纳**。Java 开发者的默认选择是 Spring Boot,切换成本包括:学习新 API、团队说服、对新框架长期维护的信任、招聘时的技能匹配。

**缓解**
1. **ADR-001 的「骑在 Spring Boot 上」是最重要的缓解措施**——用户不需要放弃任何东西,这把「替换」变成了「增量采用」;
2. 支持渐进采用:一个项目里可以只有新模块用 Jaravel 路由,老代码照常用 `@RestController`;
3. 明确的目标人群画像:**从 PHP/Laravel 转 Java 的团队**(他们最痛)、**全栈小团队**(他们最需要 DX)、**内部工具/中后台项目**(决策成本低)。不要一开始就瞄准大型企业核心系统;
4. v1.0 的「文档 + 示例 + 迁移指南」8 周是与 Eloquent 同等重要的投资,**不可砍**;
5. 做一个杀手级 demo:同一个应用的 Spring Boot 版 vs Jaravel 版并排对比(代码行数、文件数、启动时间),这比任何文字论证都有说服力。

**监控指标**
v1.0 后 6 个月:GitHub star、Maven Central 下载量、至少 3 个公开的生产使用案例。见 [17](17-roadmap.md) §4 的退出条件。

---

## R-02 · APT 错误信息质量不达标

**概率**:中高 · **影响**:高 · **类型**:技术/DX

**描述**
代码生成的全部收益建立在「出错时报错清楚」之上。若用户看到的是生成后 Java 文件的第 847 行的类型错误,DX 直接崩塌,且会让用户产生「这框架很黑魔法」的印象——这种印象一旦形成极难挽回。

**缓解**
- [06](06-eloquent.md) §6 的 5 类错误场景 + [04](04-view-jblade.md) §2.4 的模板行号映射,**测试优先于实现**;
- 用 `compile-testing` 库为每类错误写断言测试,断言错误文本包含预期提示;
- 每个生成器都必须保留 source position 映射;
- 收集真实用户的「看不懂的报错」并持续补充知识库([15](15-devx-hot-reload.md) §4.2)。

---

## R-03 · 维护负担超出团队承受能力

**概率**:中高 · **影响**:高 · **类型**:资源

**描述**
ORM(2.5 万行)+ 模板引擎(1.5 万行)+ 迁移工具 + 验证器 + 认证 + CLI + IDE 插件——**任何一个单拎出来都是一个完整开源项目的体量**。总计 6~10 万行需要长期维护,外加多方言、多 JDK、多 Spring 版本的测试矩阵。

**缓解**
- 在易错且已有成熟实现的领域坚决用现成库(密码学、MIME、SQL 驱动、HTTP 协议、Faker、JWT);
- [16](16-module-layout.md) §7 的建议:v0.1 用约 8 个模块而非 25 个,拆分比合并容易;
- v1.0 的方言范围收窄到 MySQL / PostgreSQL / SQLite;
- 尽早建立贡献者文档与模块 owner 机制;
- **接受「不做某些功能」**:Kafka 驱动、memcached、WebFlux、OAuth2 Server 都明确不做。

---

## R-04 · 热重载达不到可用水平

**概率**:中 · **影响**:高 · **类型**:技术/DX

**描述**
若「改一行代码等 15 秒」,所有 API 上的优雅都会被这个摩擦抹平。用户会回到 Spring Boot——既然都要等重启,不如用熟悉的东西。

**缓解**
见 [15](15-devx-hot-reload.md) 的四层机制。关键是 L3(类加载器重启)必须稳定在 2s 内,这是不依赖非标准 JVM 的兜底保证。请求挂起 + 自动重放的设计让体验接近「刷新即生效」。

**监控**
DX 基准进 CI,阻断式门禁。

---

## R-05 · 抽象泄漏导致优雅性破产

**概率**:中 · **影响**:中高 · **类型**:设计

**描述**
一旦用户为了实现某个需求必须「绕过 Jaravel 直接写 Spring」,且这种情况频繁发生,Jaravel 就从「框架」退化为「一层不彻底的糖」,此时它既增加了认知负担又没带来简化。

**缓解**
- 每个模块文档强制包含「逃生口」小节,逃生口是**设计的一部分**,要有文档和测试,而不是「用户自己想办法」;
- 逃生口要设计得「优雅地退出」而不是「撬开框架」:`request.raw()`、`Storage.disk("s3").raw()`、`DB.select(sql)` 都是一等公民 API;
- 收集真实项目中用到逃生口的场景,频繁用到的说明抽象有缺口,应补进主 API。

---

## R-06 · 安全责任(自研认证)

**概率**:低~中 · **影响**:高 · **类型**:安全

**描述**
ADR-006 决定自研认证授权,意味着承担安全责任。一个认证绕过漏洞对框架声誉的损害是毁灭性的。

**缓解**
- 密码学原语绝不自研;
- [08](08-auth.md) §7.2 的 12 项清单逐条写测试,进 CI 阻断;
- v0.9 前第三方安全审计(**预算必须预留,不可因进度压力取消**);
- v1.0 前建立安全响应流程(security.md、私密报告渠道、CVE 流程、补丁发布 SLA);
- 提供 `jaravel-security-bridge`,让高安全要求的用户可以用 Spring Security 做认证。

---

## R-07 · Spring 版本漂移

**概率**:中 · **影响**:中 · **类型**:技术

**描述**
Spring Boot 的大版本升级(3.x → 4.x)可能改变 `HandlerMapping`、自动装配、Servlet 抽象等 Jaravel 依赖的内部结构。

**缓解**
- ADR-001 的适配层收窄 + ArchUnit 强制,使冲击面限制在一个模块;
- CI 测试矩阵覆盖 BOM 声明的兼容区间首尾版本;
- 不依赖 Spring 的内部 API(`org.springframework.*.internal`、非 public 类);
- `HandlerMapping` 的集成方式优先用公开扩展点。

---

## R-08 · 关键人员流失

**概率**:中 · **影响**:致命(若是 Eloquent 主线) · **类型**:资源

**描述**
Eloquent + APT 是关键路径(16~25 周),且技术复杂度最高。若负责人离开,接手成本极高。

**缓解**
- **本 `docs/` 目录的存在本身就是这项风险的主要缓解措施**——设计意图、被否决的方案、待决问题都已书面化;
- 强制代码评审,关键模块至少 2 人熟悉;
- 每个 ADR 记录「被否决的方案及原因」,防止接手者重复走弯路;
- 生成器的测试套件是活文档。

---

## R-09 · Java 21 基线挡掉存量市场

**概率**:高 · **影响**:中 · **类型**:市场

**描述**
大量企业仍在 Java 8/11/17。Java 21 基线(ADR-007 的连带结果)会挡掉这部分用户。

**缓解**
- 目标人群本就是新项目与愿意用新技术的团队,Java 8 用户不是目标;
- Java 21 是 LTS(2023 年发布),到 v1.0 时已有相当的采纳率;
- 不降级:降到 Java 17 会失去虚拟线程的默认可用性,而那是 ADR-007 的基础;
- 在 README 首屏明确写明基线要求。

---

## R-10 · 待决问题积压

**概率**:中 · **影响**:中 · **类型**:流程

**描述**
`docs/` 各模块共有约 **60 条待决问题**。若不管理,会在实现时被随意决定(而非经过论证),导致架构不一致。

**缓解**
- 每个版本的发布门禁包含「该版本涉及模块的待决问题已关闭或转为 issue」;
- 重要的待决问题提升为 ADR(如 ADR-009 ~ ADR-011);
- v1.0 发布前所有待决问题必须有明确结论。

**v0.1 前必须解决的 7 条,已于 2026-10-28 全部关闭**(详见 [verification/README.md](../verification/README.md)):

| # | 问题 | 结论 | 依据 |
|---|---|---|---|
| 1 | 模型访问器生成方案 | ✅ 方案 E'(public 字段 + 快照 + 关联注解);方案 B 证伪 | V1 验证 / ADR-010 |
| 2 | 应用包名与目录布局 | ✅ 方案 C(标准 Maven 布局 + `app.*` 短包名) | 所有者决定 / ADR-009 |
| 3 | jblade 自研 vs 基于 jte | ✅ 基于 jte(Blade 前端 + jte 后端 + JbContext 编排) | V3 验证 / ADR-011 |
| 4 | 路由 APT 表达式解析可行性 | ✅ 可行(`com.sun.source.util.Trees`,受支持 API) | V2 验证 |
| 5 | Java 基线 21 vs 25 | ✅ Java 21(经 `ContextHolder` 抽象隔离 `ScopedValue`) | 所有者决定 / ADR-007 |
| 6 | 验证规则 Map 的顺序问题 | ✅ 直接定:用 `LinkedHashMap` / `Rules.of(...)` 有序构造器 | 工程细节 |
| 7 | 元模型常量命名大小写 | ✅ 小写 `User_.email` | 所有者决定 |

**新增的待决问题**(由验证产出,优先级低于上表,不阻塞 v0.1):

| # | 问题 | 来源 |
|---|---|---|
| 8 | 是否投入 javac 插件以获得精确诊断位置(`Messager` 无 `Tree` 重载) | [V2](../verification/v2-route-apt/RESULT.md) §5.1 |
| 9 | jblade→jte 行号映射策略:行对齐 vs 显式映射表 | [V3](../verification/v3-jblade-jte/RESULT.md) §8 |
| 10 | Spring bean 定义/注解变更导致「字节码可 HotSwap 但容器需刷新」的影响 | [V4](../verification/v4-hotreload-coverage/RESULT.md) §4.2 |
| 11 | `Blade.directive()` 的用户扩展点如何在**编译期**生效(jte 不支持自定义指令语法) | [V3](../verification/v3-jblade-jte/RESULT.md) §8 |

---

## 风险矩阵总览

```
影响
致命 │              R-08         R-01
     │
 高  │   R-06    R-02  R-03  R-04
     │
中高 │         R-05
     │
 中  │         R-07  R-10     R-09
     │
     └────────────────────────────────→ 概率
         低        中         高
```

**最需要主动管理的两个**:
- **R-01(无人采纳)** —— 技术之外的风险,但影响最大。应对手段是 ADR-001 的架构选择 + v1.0 的文档投入 + 精准的目标人群;
- **R-02(APT 错误信息)** —— 完全在团队控制范围内,靠「测试优先于实现」即可缓解,没有做好的借口。
