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
- **`@once` / `@fragment` 与片段渲染**(docs/04 §1.8)——
  `@once` 让组件自带的 `<script>` 在被用 N 次时只输出一次,去重键是
  **模板名 + 行号**(用内容做键会把两段不同的声明合成一段;用自增计数
  会在同一模板被 `@include` 两次时从头开始,那正是要解决的场景)。
  `@fragment('row')` 照常出现在整页里,同时可被单独渲染:
  `View.of("notes.page").fragment("note-" + id)` —— htmx / turbo 的局部
  刷新回的 HTML 与整页里那一行是同一段模板代码,不会分叉。
  渲染片段时**整页照样渲染一遍**再取那一段:要从模板中间进入就得把它
  切成多个入口,而片段用到的循环变量与外层条件都来自它上面的代码,
  切开之后表现是「局部刷新时变量是 null」。片段名不存在时报错并列出
  有哪些;`ViewEngine.renderFragment` 的默认实现也报错而不回落到整页
  (静默回落会把一整个 `<html>` 塞进一个 `<tr>` 里)。
- **`@method` + 服务端的 `_method` 方法伪装**(docs/04 §1.8)——
  浏览器表单只会发 GET / POST,而 REST 路由需要 PUT / PATCH / DELETE。
  `MethodOverrideFilter` 按 `_method` 字段(或 `X-HTTP-Method-Override` 头)
  改写方法,字段名与 Laravel 一致,照抄过来的表单直接能用。
  **它必须是 Filter**:路由按方法匹配,而匹配在 Spring 的 HandlerMapping
  里发生 —— 中间件版本会在「已按 POST 匹配过」之后才改写,
  于是 `r.delete(...)` 永远匹配不上(症状是 404/405)。
  三条安全约束各有测试:只改写 POST(否则一个链接就能删数据)、
  只能伪装成 PUT/PATCH/DELETE(伪装成 GET 等于绕过 CSRF,`@method('GET')`
  在转写期就报错)、非表单体只认头(在 filter 里读 multipart 参数会
  触发容器解析,而那该由上传层带着大小上限去做)。
  端到端:`examples/auth` 的演示页加了一个真的
  `DELETE /notes/{id}` 路由与 POST + `@method('DELETE')` 的表单,
  并钉住「伪装后仍要 CSRF(少令牌 419)」与「伪装不绕过授权(别人的 403)」。
- **生成式模型工厂 `@UseFactory` + `make:factory`**(docs/14 §3)——
  模型上标 `@UseFactory(PostFactory.class)`,APT 生成 `PostFactoryBase`:
  **按列的类型化 setter**(`.title("X").views(777)`)、关联构建器
  (`forAuthor(author)` / `forAuthor(Author.factory())` / `hasPosts(3)` /
  `hasPosts(3, f -> f.draft())`)、以及 count / state / sequence / seed /
  make / makeOne / create / createOne,外加模型上的 `Post.factory()`。
  用户只写两样:默认值(`definition(Faker, Post)`)与具名状态(`draft()`)。
  **不走 Laravel 的 `Map<String,Object>`**:docs/14 §10 为「`definition()`
  缺少类型安全」登记过三个版本的开放项,这次关掉它 —— 列名写错、类型写错
  都在编译期暴露。真正会被反复写的那一行
  (`Post.factory().draft().count(3).create()`)与 Laravel 一模一样。
  几个刻意的边界各有理由(§3.2):注解标在**模型**上而不是工厂上
  (标在工厂上会让 `Post.factory()` 在一次增量编译后静默消失);
  工厂必须与模型同一次编译(写在 `src/test/java` 会编译不过,
  处理器直接报错 —— 顺带:seeder 也是工厂的使用者,它本属于 main);
  链式方法的自类型靠生成时写死 + 一次向下转型,换掉
  `F extends ModelFactory<M, F>` 那串泛型噪音;名字与机制方法冲突的列
  (`count` / `state` / `seed` …)不生成 setter 但**报 warning**
  (生成了会变成重载歧义,javac 按参数类型静默挑一个);
  `forAuthor(Author.factory())` 只建**一个**父对象给 count 条共用;
  中间表关联不生成构建器(外键不在模型字段上),登记进 KNOWN-ISSUES。
  四条报错路径(工厂不存在 / 没继承生成的基类 / abstract / 没有无参构造)
  都落在**模型那一行**上,而不是 `target/generated-sources` 里。
  `jaravel make:factory PostFactory --model=Post` 生成骨架,并在输出里
  给出模型上要加的那行 —— 命令不去改用户写过的文件,但少了那行工厂
  编译不过,所以另一半必须说清且能照抄。
  `Factory.Faker` 提升为 `io.jaravel.support.Faker`(工厂基类因此住在
  `jaravel-eloquent`,生成的代码不再依赖 jaravel-migration);
  `datafaker` **不做**,理由记在 KNOWN-ISSUES。
  端到端:`examples/blog` 的 `database/factories` 下四个真工厂,
  `BlogSeeder` 用它们重写(前后对照),`FactoryFeatureTest` 17 条在真库上
  验收默认值、状态叠加顺序、序列轮转、种子可复现、关联两侧与 `make()`
  不入库。顺带钉住一个真陷阱:`Faker` 的序号是**每个实例一份**
  (固定种子要可复现就不能跨实例共享),所以 UNIQUE 列的默认值要自己带
  进程级计数器 —— 否则第二次 `Post.factory()` 撞 UNIQUE,而报错是
  SQLite 的约束冲突,与「工厂」三个字毫无关系。
- **`AppTest` 与数据库隔离策略注解**(docs/14 §2.1、§5)——
  `AppTest` 是三层测试基类中间的那一层:容器 + 数据库 + 执行作用域,
  **没有发请求的方法**。它的分层在 API 面上而不是启动成本上 ——
  `@SpringBootTest` 配置与 `FeatureTest` 一致,于是 Spring 的上下文缓存
  命中同一个上下文,整个测试 JVM 只启动一次(用 `webEnvironment = NONE`
  省那个 mock servlet 环境会换来第二个缓存键,省毫秒花秒)。
  它值得存在的理由是让一次失败只有一种原因:同一条授权规则,
  `FeatureTest` 版本的失败可能来自路由、中间件、认证或 Policy,
  而 `examples/auth` 新增的 `NoteAuthorizationAppTest` 只剩 Policy 一种。
  `ScopeExtension` 把测试方法包进 `ExecutionScope`,于是
  `actingAs(user)` → `Auth.user()` / `Gate` 与请求里一致(此前 docs/14 §10
  的开放项)。两条边界是刻意的:**不造合成会话**(服务层用 `Session`
  本身就是缺陷,让它在测试里能跑的代价是上线后在 worker 里炸),
  作用域只覆盖测试方法本身(在 `@BeforeEach` 里 `actingAs` 直接报错,
  而不是静默设进一个随即丢弃的 guard)。
  隔离策略注解三个:`@DatabaseTransactions`(默认,显式声明)/
  `@RefreshDatabase`(每个测试前 reset + migrate)/ `@DatabaseMigrations`
  (前 migrate、后 reset)。方法压过类,`@Nested` 外层类与自写基类上
  标了也算,同一层标两个在测试开始前报错。两个迁移型策略**不在事务里**
  —— DDL 在一部分数据库里不参与事务,而迁移测试要断言「表真的建出来了」。
  `@WithoutDatabase` **不做**:它表达的「纯单元测试」已有更快的写法
  (不继承基类),理由记在 `KNOWN-ISSUES.md`。
  数据库断言提到 `DatabaseTestSupport` 接口,`FeatureTest` 与 `AppTest`
  共用同一份实现。
- **i18n 底座 + `@lang` / `@choice`**(docs/02 §2.3、docs/04 §1.8)——
  `Translator` / `Lang` / `LangLoader` 落在 `jaravel-core`;消息放
  `resources/lang/<locale>/<group>.yml`,**键的第一段永远是文件名**
  (与 Laravel 一致,于是「这条消息在哪」能从键读出来)。占位符用
  `:name`,替换是**一次扫描**(用户填的内容里含 `:other` 不会被二次替换,
  否则那是一个注入点);复数支持 `单|复` 两段、`0|1|其余` 三段,
  以及 `{0}` / `[1,19]` / `[20,*]` 显式区间。
  starter 从 `app.locale` / `app.fallback-locale` 装配,消息同时从
  classpath 的 `lang/`(按 `_groups.yml` 索引 —— jar 里无法枚举目录)
  与磁盘的 `resources/lang/` 装,磁盘后装所以覆盖 jar 里的。
  找不到键、甚至没装消息目录时都**输出键本身**而不是报错或留空 ——
  漏翻是翻译工作的正常中间状态。`Lang.withLocale(l, () -> ...)` 可在
  一段里切语言并还原(嵌套安全),这是「按收件人语言发邮件」的原语。
- **条件指令 `@auth` / `@guest` / `@can` / `@cannot` / `@env` / `@production`**
  (docs/04 §1.8)—— `@auth('api')` 可指定 guard;`@can('update', post)` 走
  `Gate`、**按对象判**(与 `middleware("can:update,note")` 和
  `Gate.authorize(...)` 同一条路径、同一个 Policy),还有 `@elsecan` /
  `@elsecannot`。没有请求时 `@auth` 为 false、`@can` 一律拒绝;
  取不到 guard/Gate 时也不抛异常(布局里的 `@auth` 会被错误页共用,
  在那里再抛一次会得到无限递归的错误页)—— 与 `@csrf` 刻意相反,
  后者报错是因为空令牌的后果是 419。
  端到端验收在 `examples/auth` 的新页面 `GET /notes/page`:它列出所有人的
  笔记,而「编辑」只出现在自己的那几条上(真 `NotePolicy`)。
- **`@csrf` / `@error` / `@old` 模板指令**(docs/04 §1.8)——
  `@csrf` 输出整个隐藏 input(手写它的人会把 `name` 写错,症状是提交后
  419);`@error('email') {{ message }} @enderror` 块内有局部变量
  `message`,字段名只写一次,嵌套会在转写期报错而不是让 javac 抱怨
  生成码里的变量重名;`@old('title', post.title)` 回填用户刚填的值。
  数据都在会话里,所以视图那边留了 `ViewRequestState.Source` SPI,
  starter 装 `SessionViewRequestState` —— `jaravel-view` 不依赖
  `jaravel-session`(ArchUnit 钉着这条边界)。**没有 ShareErrors 中间件**,
  指令自己读会话,理由见 docs/04 §1.8。
  `install:auth` 生成的五个模板改用指令:`@params` 从四五个参数降到一个,
  控制器不再需要 `error()` / `old()` 两个辅助方法。
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

- **跑完之后才炸的任务不再被记成失败**:worker 的出队(`DELETE jobs`)
  可能撞上存储层的瞬时冲突(SQLite 共享缓存的 `SQLITE_LOCKED_SHAREDCACHE`、
  MySQL 死锁检测随机挑牺牲者),而那个异常此前会掉进任务失败分支 ——
  一个**已经跑完、副作用已经发生**的任务于是:`tries=1` 时直接进
  `failed_jobs`(批次因此永远不触发 `then`),`tries>1` 时被放回队列、
  副作用再发生一次。现在出队带有界重试,并且「执行已成功」这个事实
  被记下来:之后的记账失败只吵不改判。这是 CI 上那条批处理偶发失败的
  根因,由上一个提交加的现场诊断定位到(`failed_jobs` 的根因那一行)。
- **校验失败后的旧输入终于真的回填了**:异常处理器把它闪存在 `"old"` 键下,
  而 `Session.oldInput(key)` 读的是 `_old_input`(`SessionData.flashInput`
  写的那个)—— 两边从来没对上,所以「校验失败回跳后表单全空」一直是
  现状,而错误消息是正确的,这让它看起来像设计如此。现在走
  `Redirect.withInput()`,顺带删掉了处理器里那份与
  `SessionLifecycle.absorbFlash` 重复的密码排除清单(两份清单里漏掉一个
  字段的那一份就是泄露路径)。是 `@old` 的端到端测试把它逼出来的。
- **`examples/blog` 的页面路由放进 `web` 组**:此前那一组是裸的,
  于是控制器里的 `Redirect.with("status", "已发布")` 静默失效(闪存需要
  会话),表单 POST 也完全没有 CSRF 防护 —— 一个会被照抄的示例不该这样。
  `/api` 那组刻意留在外面。
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
