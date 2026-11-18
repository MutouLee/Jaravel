# 已知问题 / 开放项

> v1.0 验收要求「docs/ 中的待决问题已关闭或转为 issue」。这里集中登记
> **仍未实现**或**刻意不做**的项;各模块文档的「待决问题」链接到这里。

## 测试

| 项 | 状态 | 说明 |
|---|---|---|
| `@RefreshDatabase` 等隔离注解 | 未实现 | 当前只有默认事务回滚与 `@WithoutRollback` |
| `AppTest`(容器 + 数据库,无 HTTP) | 未实现 | 用 `@SpringBootTest` 或 `FeatureTest` 代替 |
| `Queue.fake` / `Cache.fake` 与队列断言 | 未实现 | Mail/Event/Storage/Notifications/Broadcast/Http 已有 |
| `Clock` 时间控制 | 未实现 | 见 docs/14 §4.2 |
| `UploadedFile.fake()` | 未实现 | 测试里可直接 `new UploadedFile(...)` |
| 工厂 `Post.factory()` APT / `make:factory` | 未实现 | 现有 `Factory.of(ModelMeta, ...)` 手写 |
| `Factory.Faker` 的 `datafaker` / locale | 未实现 | 内置最小 Faker |
| `@error` / `@old` 模板指令与 ShareErrors 中间件 | 未实现 | `install:auth` 显式传 `error`/`old` |

## 控制台

| 项 | 状态 | 说明 |
|---|---|---|
| `queue:restart` / `queue:monitor` / `queue:prune-batches` / `queue:forget` | 未实现 | v0.7 起登记 |
| `install:*`(除 `install:auth`) | 未实现 | 只有 auth |
| `optimize` 的四种缓存 | 未实现 | |
| `analyze:*` | 未实现 | |
| `make:factory` | 未实现 | 见测试表 |

## 视图 / 前端

| 项 | 状态 | 说明 |
|---|---|---|
| `@csrf` 模板指令 | **未实现** | 生成器用显式 `<input name="_token">`;不要依赖 `@csrf` |
| `@aware` | 未实现 | |
| 具名 slot / 属性袋 | 未实现 | 匿名组件与类组件已落地 |

## 安全 / 账户

| 项 | 状态 | 说明 |
|---|---|---|
| 二步验证二维码 / 登录挑战页 | 未实现 | TOTP 原语与用户侧默认方法已有(docs/08 §6.4) |
| 默认安全响应头(CSP / X-Frame-Options / Referrer-Policy / HSTS) | 未实现 | 应用自行加 |
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
