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
- **`UploadedFile.fake()`**(docs/14 §4.4)—— `image(name[, w, h])` 用
  `ImageIO` 编一张**真图片**,`create(name, kb)` 按扩展名写对应魔数再填充,
  `withContent(...)` 用来构造坏输入。内容是真的这件事是刻意的:框架的
  类型判断一律按内容嗅探,一个内容是随机字节的假文件会让 `image` /
  `mimes:png` 在测试与生产里行为不同。没有魔数的类型(txt/csv)**如实**
  嗅探不出来,不伪造。一条测试核对魔数表与 `ContentSniffer` 一致;
  `examples/auth` 的 `UploadFlowTest`(真 multipart + 真磁盘)改用它
  生成素材,顺带把它放进端到端验收。
- **`Clock` 时间源**(`io.jaravel.support.Clock`,docs/14 §4.2)——
  `freeze` / `travel` / `travelTo` / `withFrozen` / `reset`。框架内 **106 处**
  领域时间读取(模型时间戳、令牌与签名过期、缓存 TTL、任务可见时刻、
  调度器下次执行)改走它;存活性计时(worker 的 `maxTime` 与关停宽限、
  WebSocket 心跳)显式走 `Clock.realNow()` —— 冻结时钟会让一个本该退出的
  循环挂死。这条边界由 ArchUnit 阻断式强制(`noDirectClockReads`,
  白名单逐条写明理由),并补齐了 `jaravel-archunit-rules` 的 classpath:
  不在 classpath 上的模块,规则对它们是静默通过的。
  端到端证据:`examples/auth` 里「重置令牌 61 分钟后失效 / 59 分钟仍有效」
  两条测试 —— 此前这类规则只能靠睡一小时或把过期配成 1 毫秒来「测」。
- **`Queue.fake()`**(docs/14 §4)—— 投递被记录而不入队,断言写在任务
  **实例**上(`queue.assertPushed(SendInvoice.class, j -> j.invoiceId().equals(id))`);
  覆盖 `assertPushed`(三个重载)/ `assertPushedOnQueue` / `assertNotPushed` /
  `assertPushedCount` / `assertNothingPushed` / `assertChained` / `assertBatched`,
  以及部分 fake(`Queue.fake(SendInvoice.class)`,其余照常入队)。
  刻意**不拦** `dispatchSync` —— 它的语义是「现在、在我的调用栈上执行」,
  拦下等于让被测代码什么都没做而测试绿了。`FeatureTest` 每个测试前退出
  fake 模式,避免 fake 漏进下一个测试。
- **`Cache.fake()`**(docs/14 §4)—— 默认 store 换成一个**记录型**的空缓存,
  可断言 `assertHit` / `assertMissed` / `assertPut` / `assertForgotten` /
  `assertHas` / `assertMissing` / `assertNothingPut`。它比「换成 array 驱动」
  多出来的是命中记录 ——「这段代码加了缓存」唯一值得测的断言是
  「第二次没有再去算」,而那从返回值上看不出来。
  边界:换的是 manager 的默认 store,**构造注入了 `Repository`** 的代码
  (框架自己的会话、限流、队列唯一性锁)持有旧实例、不受影响。
- **`Cache` 静态门面**(`io.jaravel.cache.Cache`)—— docs/09 §1.1 与
  docs/02 §2.3 一直在写它,但代码里只有 `CacheManager` / `Repository`。
  starter 启动时绑定,`FeatureTest` 每个测试前重绑到当前上下文。
- **公开 API 文档覆盖核对**:`docs-site/tools/api_coverage.py`
  (每个公开类型都有 Javadoc + 每个模块在 `api-reference.md` 的模块索引里
  + 索引不指向死类型名),以 `--strict` 进 CI 的 docs 任务。
- **冷环境 quickstart 计时**:`verification/v10-quickstart-cold`
  —— 干净容器里照文档逐条敲 13 步并计时(含注册/登录/登出/重置的
  完整流程断言)。

### Changed

- `JaravelDefaultKernel` 仍为默认;多租户示例展示如何扩展 `aliases()`。
- 文档站与 README 补充 `jaravel new` / `install:auth` 的 30 分钟路径。
- `api-reference.md` 的模块索引补齐 11 个此前缺席的模块,并修掉
  `jaravel-queue` 那个指向已改名类型的 `Worker`(实际是 `QueueWorker`)。
- quickstart 加一条「首次构建会下载数百 MB 依赖」——实测机器时间只有
  2.5 分钟,30 分钟预算在慢网络下是被下载吃掉的。
- docs/09 的 `Cache.get(key, TypeRef<...>)` 与 `Cache.rememberLocked`
  就地标注「尚未实现」并登记进 KNOWN-ISSUES(此前读起来像已有的 API)。

### Fixed

- **3xx 的 `HttpException` 不再渲染错误页**:未登录访问受保护页面时,
  `Authenticate` 用 `HttpException(302, Location)` 表达重定向,而异常处理器
  此前把它当错误渲染 —— 开发模式下那个 302 带回一张 13KB 的开发错误页
  (标题「HttpException」),生产模式下带回一张错误页。现在 3xx 走
  `Redirect.to(location, status)`,body 为空。

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
