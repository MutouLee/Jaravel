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
| RabbitMQ / SQS 的 failed_jobs / job_batches | 未实现 | 用 database/redis |
| RabbitMQ / SQS 的 attempts 持久化 | 未实现 | 消息不可变 |

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
