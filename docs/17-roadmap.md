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

### 验收

1. `install:auth` 生成的登录/注册/重置流程端到端可用;
2. [08](08-auth.md) §7.2 的 12 项安全检查全部有测试且通过;
3. 改方法体 → 0 重启;新增类 → < 2s;改模板 → < 100ms;
4. 改代码后**登录状态不丢失**;
5. `jaravel-security-bridge` 下 `Auth.user()` 能读取 Spring Security 的认证结果。

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
| `Http` 客户端门面 | 0.5 |

### 验收

1. 「下单 → 异步发票 → 邮件 + 站内通知」流程打通;
2. `--concurrency=200` 下 worker 内存增长 < 100MB(验证虚拟线程优势);
3. 调度器在 3 个实例下 `onOneServer()` 任务只执行一次;
4. 上传安全项(内容嗅探、路径穿越、随机命名)全部有测试。

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
| `jaravel-testing`:三层基类 + HTTP 断言 + 数据库断言 + 全部 fake | 2.5 |
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
