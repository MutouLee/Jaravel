# Jaravel 设计文档

总纲见仓库根目录的 [DESIGN.md](../DESIGN.md)。本目录是各模块的细化设计。

> **v0.0 技术验证已完成**(2026-10-28),四项全部通过。**阅读 `04`(模板引擎)与 `06`(Eloquent)之前,
> 请先读 [verification/README.md](../verification/README.md)** —— 这两篇的部分内容已被验证结论取代,
> 文档顶部有相应提示。

## 阅读顺序

**第一次阅读**,按以下顺序:

1. [DESIGN.md](../DESIGN.md) — 可行性结论与三条顶层决策
2. [01-architecture.md](01-architecture.md) — 分层、边界、请求生命周期
3. [02-core-container.md](02-core-container.md) — 容器与门面(其他一切的地基)
4. [16-module-layout.md](16-module-layout.md) — 代码放在哪里
5. [17-roadmap.md](17-roadmap.md) — 先做什么

**实现某个模块前**,读对应文档 + [18-risks-and-decisions.md](18-risks-and-decisions.md) 中相关的 ADR。

## 文档清单

### 地基层

| 文档 | 内容 | 实现里程碑 |
|---|---|---|
| [01-architecture.md](01-architecture.md) | 分层架构、与 Spring 的边界、请求生命周期、并发模型、`ScopedValue` 请求作用域 | v0.1 |
| [02-core-container.md](02-core-container.md) | 容器、ServiceProvider、门面机制、Pipeline、Config/.env、契约命名约定 | v0.1 |

### Web 层

| 文档 | 内容 | 实现里程碑 |
|---|---|---|
| [03-routing-http.md](03-routing-http.md) | 路由 DSL、资源路由、参数绑定、中间件、Request/Response、CSRF | v0.1 |
| [04-view-jblade.md](04-view-jblade.md) | jblade 语法全集、编译器三阶段、组件、布局继承、双模式编译 | v0.1 / v0.9 |
| [12-api-resources.md](12-api-resources.md) | JsonResource、集合资源、分页、统一异常转 JSON、OpenAPI 生成 | v0.5 |

### 数据层

| 文档 | 内容 | 实现里程碑 |
|---|---|---|
| [05-database.md](05-database.md) | 查询构造器、方言 Grammar、连接管理、事务、Schema DSL、迁移、Seeder | v0.1 / v0.2 |
| [06-eloquent.md](06-eloquent.md) | 模型定义、APT 生成物规格、关联全集、预加载、作用域、软删除、模型事件 | v0.2 |
| [07-validation.md](07-validation.md) | 规则引擎、内置规则表、FormRequest、自定义规则、本地化消息 | v0.2 |

### 应用服务层

| 文档 | 内容 | 实现里程碑 |
|---|---|---|
| [08-auth.md](08-auth.md) | Guard 抽象、Session/Jwt/Token Guard、UserProvider、Gate、Policy、Hash | v0.3 |
| [09-cache-session.md](09-cache-session.md) | 缓存契约与驱动、标签、原子锁、会话驱动、限流器 | v0.3 |
| [10-queue-events-scheduler.md](10-queue-events-scheduler.md) | 队列契约与驱动、Worker 模型、失败重试、事件总线、调度器 | v0.4 |
| [11-mail-notifications-storage.md](11-mail-notifications-storage.md) | Mailable、通知多通道、文件存储抽象与驱动 | v0.4 |

### 工具链

| 文档 | 内容 | 实现里程碑 |
|---|---|---|
| [13-console-artisan.md](13-console-artisan.md) | Artisan 内核、命令全清单、stub 机制、Tinker/JShell | v0.5 |
| [14-testing.md](14-testing.md) | HTTP 测试 DSL、数据库断言、模型工厂、假驱动、Faker | v0.5 |
| [15-devx-hot-reload.md](15-devx-hot-reload.md) | 三层热重载、`jaravel serve` 实现、开发期错误页 | v0.3 |
| [16-module-layout.md](16-module-layout.md) | Maven 多模块结构、BOM、starter 分层、Maven 插件、应用骨架 | v0.1 |

### 规划与决策

| 文档 | 内容 |
|---|---|
| [17-roadmap.md](17-roadmap.md) | 七个里程碑的任务拆解、验收标准、工作量估算 |
| [18-risks-and-decisions.md](18-risks-and-decisions.md) | ADR 架构决策记录(含被否决的方案及原因)、风险登记册 |

### 验证报告(v0.0)

| 报告 | 验证对象 | 结论 |
|---|---|---|
| [verification/README.md](../verification/README.md) | 总览 | 四项全部通过 |
| [V1 模型访问器](../verification/v1-model-accessor/RESULT.md) | ADR-010 | 方案 B 证伪;采纳方案 E' |
| [V2 路由 APT](../verification/v2-route-apt/RESULT.md) | `03` §9 | 可行;需下调诊断定位预期 |
| [V3 jblade 基于 jte](../verification/v3-jblade-jte/RESULT.md) | ADR-011 | 可行;14 周 → 7.5 周 |
| [V4 热重载覆盖率](../verification/v4-hotreload-coverage/RESULT.md) | `15` §9 | 增强 HotSwap 覆盖 87% |

## 文档约定

- **代码示例均为设计草图**,签名可能在实现中微调;API 的「形状」与「意图」是契约,具体命名不是。
- 每个模块文档包含固定小节:**设计目标 → 公开 API → 内部架构 → 逃生口 → 与 Laravel 的差异 → 待决问题**。
- 「逃生口」小节是硬性要求:任何模块都必须提供绕过抽象直接操作底层的合法途径,否则抽象一旦不够用,用户只能撬开框架——这是优雅性破产的开始。
- 「与 Laravel 的差异」小节用于记录**有意的偏离**,避免后来者误以为是缺陷而「修」回去。
