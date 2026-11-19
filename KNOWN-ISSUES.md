# 已知问题 / 开放项

> v1.0 验收要求「docs/ 中的待决问题已关闭或转为 issue」。这里集中登记
> **仍未实现**或**刻意不做**的项;各模块文档的「待决问题」链接到这里。

## 测试

| 项 | 状态 | 说明 |
|---|---|---|
| `@RefreshDatabase` 等隔离注解 | 未实现 | 当前只有默认事务回滚与 `@WithoutRollback` |
| `AppTest`(容器 + 数据库,无 HTTP) | 未实现 | 用 `@SpringBootTest` 或 `FeatureTest` 代替 |
| 工厂 `Post.factory()` APT / `make:factory` | 未实现 | 现有 `Factory.of(ModelMeta, ...)` 手写 |
| `Factory.Faker` 的 `datafaker` / locale | 未实现 | 内置最小 Faker |

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
| `@method` / `@vite` / `@inject` / `@once` / `@fragment` | 未实现 | docs/04 §1.8 的其余指令。`@method` 还缺服务端的 `_method` 伪装 |
| `DateX.diffForHumans()` 本地化 | 未实现 | 消息目录已有(`Lang`),但 `DateX` 在 support,比 core 低一层、不能依赖它;需要一个钩子 |
| `Mail.to(user).locale(...)` | 未实现 | 原语已有:`Lang.withLocale(...)` |
| 按请求切 locale 的中间件 | 未实现 | 手写:读 `Accept-Language` 后 `Lang.setLocale(...)` |
| `@aware` | 未实现 | |
| 具名 slot / 属性袋 | 未实现 | 匿名组件与类组件已落地 |

## 安全 / 账户

| 项 | 状态 | 说明 |
|---|---|---|
| 二步验证二维码 / 登录挑战页 | 未实现 | TOTP 原语与用户侧默认方法已有(docs/08 §6.4) |
| 默认安全响应头(CSP / X-Frame-Options / Referrer-Policy / HSTS) | 未实现 | 应用自行加 |
| 第三方安全审计 | 不做 | 当前范围外 |
| OSV / SBOM 扫描 | 不做 | 当前范围外 |

## 缓存

| 项 | 状态 | 说明 |
|---|---|---|
| `Cache.get(key, TypeRef<...>)` 泛型容器重载 | 未实现 | docs/09 §1.5;缓存一个包装 record 代替 |
| `Cache.rememberLocked` 跨进程防击穿 | 未实现 | docs/09 §1.6;`Cache.lock(...).get(...)` 代替 |

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
