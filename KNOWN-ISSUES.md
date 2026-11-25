# 已知问题 / 开放项

> v1.0 验收要求「docs/ 中的待决问题已关闭或转为 issue」。这里集中登记
> **仍未实现**或**刻意不做**的项;各模块文档的「待决问题」链接到这里。

## 测试

| 项 | 状态 | 说明 |
|---|---|---|
| `@WithoutDatabase` | **不做** | 它表达的「纯单元测试」已有更快的写法:不继承基类(docs/14 §5 第一层)。而「有上下文但没有库」要求每个子系统都补无库降级路径 —— `Connection` bean 刻意是启动即失败的 |
| `datafaker` / locale | **不做** | 10MB locale 数据 + 一串传递依赖,换来的只是更像真话的字符串。`io.jaravel.support.Faker` 是内置最小实现;要更真实的数据在 `definition` 里换自己的(docs/14 §3.4) |

## 控制台

| 项 | 状态 | 说明 |
|---|---|---|
| `install:*`(除 auth / api / broadcasting) | 未实现 | Laravel 那边还有 `install:horizon` / `install:telescope` 之类,对应的东西 Jaravel 没有(队列面板是内置的 `/_jaravel/dashboard`) |
| `optimize` 的四种缓存 | **不做** | 那四种缓存里三种在 Jaravel 已经是**构建期产物**(view 由 `compile-views` 预编译成 class、event 由 APT 生成索引、config 就是启动时读几个 YAML),第四种 route **做不到** —— Laravel 能序列化路由是因为 handler 是 `"Ctrl@index"` 字符串,而这里是方法引用。于是 `optimize` 只会是一条什么都没做的命令(docs/13 §10) |
| `analyze:*` | **不做** | N+1 由 `preventLazyLoading`(开发期直接报错)+ `assertQueryCount`(CI 回归)覆盖,边界由 `jaravel-archunit-rules` 覆盖,运行期误配由 `doctor` 覆盖 —— 三者都是阻断式的,而静态扫描器给出的清单会被忽略(docs/13 §10) |

## 视图 / 前端

| 项 | 状态 | 说明 |
|---|---|---|
| `@vite` | 未实现 | 需要 manifest 解析与 dev server 约定(前端工具链耦合) |
| `@inject` | 不做 | docs/04 §1.8 自己就写着「不鼓励」—— 模板里从容器取服务会让「这个页面依赖什么」不可见;用控制器传 |
| `DateX.diffForHumans()` 本地化 | 未实现 | 消息目录已有(`Lang`),但 `DateX` 在 support,比 core 低一层、不能依赖它;需要一个钩子 |
| `Mail.to(user).locale(...)` | 未实现 | 原语已有:`Lang.withLocale(...)` |

## 安全 / 账户

| 项 | 状态 | 说明 |
|---|---|---|
| CSP / HSTS 默认值 | **不做** | `X-Content-Type-Options` / `X-Frame-Options` / `Referrer-Policy` 已默认发(`SecurityHeadersFilter`)。CSP 与 HSTS 必须显式配:框架猜的 CSP 会挡掉应用自己的脚本,而 HSTS 是粘的 —— 开错了用户浏览器会记住(docs/03 §2.6)|
| 第三方安全审计 | 不做 | 当前范围外 |
| OSV / SBOM 扫描 | 不做 | 当前范围外 |

## 队列 / 事件

| 项 | 状态 | 说明 |
|---|---|---|
| Kafka 队列驱动 | **不做** | 事件流语义,需独立设计;见 V0.7-RESULT §2.4 |
| RabbitMQ / SQS 的 failed_jobs / job_batches | **按设计(问错了问题)** | 归档与批次账本**不属于队列驱动** —— 它们是独立的 `queue.failed.driver` / `queue.batching.driver`(与 Laravel 一样),任何队列驱动都能配 database / redis。此前确实没有测试证明这个组合能跑,现在有:`BrokerArchiveTest`(broker 队列 + 库里的 failed_jobs/job_batches,含「归档之后消息必须从 broker 上消失」)|
| RabbitMQ / SQS 的 attempts 持久化 | **已实现** | SQS 取 payload 与 `ApproximateReceiveCount` 的较大值;RabbitMQ 取 payload 与 `x-delivery-count` 的较大值(**要 quorum 队列**,配 `queue-type: quorum`)。于是「worker 崩了之后的重投」也算一次尝试,重试上限成立 |
| RabbitMQ classic 队列的 attempts | **不做** | classic 队列没有 `x-delivery-count`,broker 侧拿不到投递次数,而消息又不能原地改 —— 只能退回 payload 里的旧计数(崩溃循环会永远重投)。要严格计数就用 quorum 队列;额外挂一个 Redis/DB 计数器等于要求 SQS/Rabbit 用户再配一套存储,代价比换队列类型大 |
| DynamoDB 的 failed_jobs | 未实现 | Laravel 有 `dynamodb` 失败存储(给无库的 serverless 部署)。挡在这里的是**接口的 id 类型**:`FailedJobStore` 用 `long id`(`queue:retry <id>` 也是),而 Dynamo 侧的自然主键是 uuid —— 要先决定「把 id 改成 String」还是「在 Dynamo 上维护一个计数器」,那是个会动 CLI 表面的设计决定 |

## native

| 项 | 状态 | 说明 |
|---|---|---|
| Web 应用 native | **不做** | 需要应用级反射 hints;ADR-019 |
| CLI native(除 Linux x86_64) | 未实现 | 只有 Linux 构建脚本 |
| Argon2/bcrypt 的 native hints | 未实现 | CLI 不使用;Web native 不做 |

## 发布 / 文档

| 项 | 状态 | 说明 |
|---|---|---|
| Maven Central 发布 | 未执行 | 流程见 RELEASE.md;命名空间/密钥就绪后执行 |
| 文档站英文完整版 | 部分 | English 目前是 index + quickstart;其余页面中文 |
| IntelliJ 插件 | 未开始 | 独立项目,见 docs/15 |
| 多租户 opt-in 包 | 不做 | 只有示例;ADR-016 |
