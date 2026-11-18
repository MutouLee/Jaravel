# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/)。版本遵循 SemVer。

## [Unreleased] — v1.0.0

### Added

- **`jaravel new`** —— 内嵌模板生成可运行项目(`web` / `api` / `minimal`
  preset),含 pom、`app`/`routes`、配置、`.env`、烟雾测试与 README。
- **`jaravel install:auth`** —— 生成 User 模型、`users` /
  `password_reset_tokens` 迁移、注册/登录/登出/忘记密码/重置控制器与
  jblade 页面、`config/auth.yml` / `session.yml` / `hashing.yml` / `mail.yml`。
- **`examples/api-only`** —— JSON API 示例:JsonResource、校验、404、
  Problem JSON。
- **`examples/multi-tenant`** —— 共享库 + `tenant_id` + `@GlobalScope` +
  请求作用域租户上下文(ADR-016 的接线示例)。
- **文档站** `docs-site/`(MkDocs Material,中文 + English quickstart)
  与迁移指南(从 Laravel / 从 Spring Boot)。
- **发布流程**:`release` Maven profile(source/javadoc/GPG/Central
  Publishing)、`RELEASE.md`、`SECURITY.md`、`KNOWN-ISSUES.md`。
- **视图引擎修复**:从 fat jar 运行时即使当前目录有 `views/` 也用预编译
  引擎,避免 `java -jar` 从项目根目录启动时报「必须预编译」。

### Changed

- `JaravelDefaultKernel` 仍为默认;多租户示例展示如何扩展 `aliases()`。
- 文档站与 README 补充 `jaravel new` / `install:auth` 的 30 分钟路径。

### Security

- 登录后回跳只存相对路径(`Intended`)。
- `Redirect.back` 校验 Referer 同源。
- 不采信 `X-Forwarded-For`。
- CORS「通配来源 + 凭据」启动失败。
- 文件会话并发写:分片锁 + move 重试。
- 依赖升级:Spring Boot 3.5.16、Tomcat 10.1.59、Jackson 2.21.8、
  Netty 4.1.137.Final、RabbitMQ 5.38.0、Guava 33.8.0-jre、jte 3.1.16。

## [0.9.0] — 2026-11-18

### Added

- jblade 构建期预编译:`jaravel-maven-plugin:compile-views`。
- jaravel-cli native 镜像(Linux x86_64,启动中位 ~86ms)。
- JMH 基准、路由前缀索引与单参数快路径。
- 内部安全检查表、依赖收敛、测试与文档对账。

## [0.8.0] — 2026-11-17

### Added

- 浏览器测试(Playwright)、并行测试(`jaravel test`)、Testcontainers
  自动注入(`@UseDatabase`)。
- 多租户移出核心(ADR-016)。

## [0.7.0] — 近期

### Added

- Redis cache/session/queue/broadcast、dashboard、幂等性中间件、RBAC、
  RabbitMQ/SQS 驱动。
