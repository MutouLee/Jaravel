# 16 · 模块结构、构建与项目骨架

---

## 1. 仓库结构

```
jaravel/                                  (git 仓库根)
├── pom.xml                               parent pom,聚合全部模块
├── DESIGN.md  ·  docs/  ·  README.md  ·  CONTRIBUTING.md
├── jaravel-bom/                          依赖版本统一管理
├── jaravel-support/
├── jaravel-core/
├── jaravel-core-processor/                @JaravelProvider / @ConfigProps 的 APT
├── jaravel-routing/
├── jaravel-routing-processor/
├── jaravel-http/
├── jaravel-view/
├── jaravel-view-processor/                Views 门面生成
├── jaravel-database/
├── jaravel-eloquent/
├── jaravel-eloquent-processor/            ★ 最重要的 APT
├── jaravel-validation/
├── jaravel-auth/
├── jaravel-cache/  ·  jaravel-session/
├── jaravel-queue/  ·  jaravel-events/  ·  jaravel-scheduler/
├── jaravel-mail/  ·  jaravel-notifications/  ·  jaravel-storage/
├── jaravel-api/
├── jaravel-console/
├── jaravel-testing/
├── jaravel-devtools/                      热重载、错误页、调试栏(provided scope)
├── jaravel-spring-boot-starter/           ★ 唯一依赖 Spring 的模块
│   ├── jaravel-spring-boot-starter-web
│   ├── jaravel-spring-boot-starter-data
│   ├── jaravel-spring-boot-starter-redis
│   └── jaravel-spring-boot-starter-all    一站式(默认推荐)
├── jaravel-maven-plugin/
├── jaravel-cli/                           picocli + GraalVM native
├── bridges/
│   ├── jaravel-jpa-bridge/
│   ├── jaravel-security-bridge/
│   └── jaravel-bean-validation-bridge/
├── jaravel-archunit-rules/                架构约束测试(CI 用)
├── benchmark/
│   ├── jmh/                               性能基准
│   └── laravel-parity/                    ★ 与 Laravel 等价代码的行数对比
├── examples/
│   ├── todo/  ·  blog/  ·  api-only/  ·  multi-tenant/
└── starters/                              jaravel new 的项目模板
    ├── minimal/  ·  web/  ·  api/
```

### 1.1 模块依赖图

```
                    jaravel-support
                          │
                    jaravel-core ──────────────────┐
                    (contracts)                    │
          ┌───────────┬────────┬─────────┬─────────┤
      routing      database   cache   events    view
          │            │        │        │         │
        http      eloquent   session  queue   (processor)
          │            │        │        │
     ┌────┴────┐  validation scheduler │
    api    (auth)       │              │
                        └──── auth ────┘
                               │
              mail · notifications · storage · console · testing
                               │
                  jaravel-spring-boot-starter
                      (唯一 import Spring)
```

**硬约束**:
- 箭头方向不可反转;
- 没有环;
- 除 starter 与 bridges 外,任何模块不得出现 `org.springframework.*` 或 `jakarta.servlet.*` 引用;
- 由 `jaravel-archunit-rules` 在 CI 中强制(阻断式)。

---

## 2. 版本与依赖管理

### 2.1 jaravel-bom

```xml
<project>
  <groupId>io.jaravel</groupId>
  <artifactId>jaravel-bom</artifactId>
  <version>${revision}</version>
  <packaging>pom</packaging>

  <dependencyManagement>
    <dependencies>
      <!-- 全部 jaravel-* 模块 -->
      <!-- 以及三方依赖的统一版本 -->
    </dependencies>
  </dependencyManagement>
</project>
```

用户 pom:

```xml
<properties>
  <java.version>21</java.version>
  <jaravel.version>0.5.0</jaravel.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.jaravel</groupId>
      <artifactId>jaravel-bom</artifactId>
      <version>${jaravel.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.jaravel</groupId>
    <artifactId>jaravel-spring-boot-starter-all</artifactId>
  </dependency>

  <!-- APT:必须显式声明(或由 jaravel-maven-plugin 自动配置) -->
  <dependency>
    <groupId>io.jaravel</groupId>
    <artifactId>jaravel-eloquent-processor</artifactId>
    <scope>provided</scope>
  </dependency>

  <dependency>
    <groupId>io.jaravel</groupId>
    <artifactId>jaravel-devtools</artifactId>
    <scope>provided</scope>
  </dependency>

  <dependency>
    <groupId>io.jaravel</groupId>
    <artifactId>jaravel-testing</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

### 2.2 三方依赖策略

| 用途 | 选用 | 理由 |
|---|---|---|
| IoC / HTTP / 事务 | Spring Boot 3.x | 决策一 |
| JSON | Jackson | Spring 已带,生态标准 |
| 连接池 | HikariCP | Spring Boot 默认 |
| Redis | Lettuce | 异步、线程安全、Spring 默认 |
| 密码哈希 | spring-security-crypto | 经过审计,**仅用这一个** |
| JWT | nimbus-jose-jwt | 成熟、算法全 |
| CLI | picocli | native-image 友好 |
| Faker | datafaker | 维护活跃,多 locale |
| 日志 | SLF4J + Logback | 标准 |
| 字节码(若需) | ByteBuddy | API 友好 |
| MIME 嗅探 | Apache Tika Core | 轻量版 |
| HTTP 客户端 | `java.net.http.HttpClient` | JDK 内置,零依赖 |
| 测试 | JUnit 5 + AssertJ + ArchUnit + Testcontainers | 标准 |
| 浏览器测试 | Playwright | 现代、快 |

**原则**:
1. 密码学、MIME 编码、SQL 驱动、HTTP 协议等**易错且已有成熟实现的领域一律用现成库**;
2. API 层、DX 层、抽象层自己写(这才是 Jaravel 的价值);
3. 总依赖数要控,每新增一个三方依赖需在 PR 中说明理由(供应链与升级成本)。

---

## 3. starter 分层

| starter | 包含 | 适用 |
|---|---|---|
| `jaravel-spring-boot-starter` | core + routing + http + view + api | 最小 Web |
| `-starter-data` | + database + eloquent + validation | 加数据层 |
| `-starter-auth` | + auth + session + cache | 加认证 |
| `-starter-async` | + queue + events + scheduler | 加异步 |
| `-starter-comms` | + mail + notifications + storage | 加通信 |
| **`-starter-all`** | 全部 | **默认推荐** |

分层的目的是让 API-only / 微服务场景能裁剪体积,但**文档默认推荐 `-all`** —— 让用户纠结引哪个 starter 是 Spring Boot 的典型摩擦,不应复制。

### 3.1 自动装配

```java
@AutoConfiguration
@EnableConfigurationProperties
@ConditionalOnClass(Application.class)
public class JaravelAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    Container jaravelContainer(ConfigurableApplicationContext ctx) { return new SpringContainer(ctx); }

    @Bean @ConditionalOnMissingBean
    Application jaravelApplication(Container c, ConfigRepository cfg) { ... }

    @Bean JaravelHandlerMapping jaravelHandlerMapping(Router router) { ... }
    @Bean JaravelHandlerAdapter jaravelHandlerAdapter(...) { ... }

    @Bean @ConditionalOnProperty(name = "jaravel.cache.default", havingValue = "redis")
    CacheStore redisCacheStore(...) { ... }
    // ... 按配置选驱动
}
```

默认属性(`starter` 的 `additional-spring-configuration-metadata.json` + 默认 properties):

```properties
spring.threads.virtual.enabled=true
spring.main.banner-mode=off
spring.jackson.default-property-inclusion=non_null
spring.mvc.throw-exception-if-no-handler-found=true
server.error.whitelabel.enabled=false
```

---

## 4. jaravel-maven-plugin

```xml
<plugin>
  <groupId>io.jaravel</groupId>
  <artifactId>jaravel-maven-plugin</artifactId>
  <version>${jaravel.version}</version>
  <extensions>true</extensions>
</plugin>
```

`<extensions>true</extensions>` 让插件自动绑定全部目标,用户无需写 `<executions>`:

| goal | 绑定阶段 | 作用 |
|---|---|---|
| `configure` | `validate` | 自动注册 APT 路径、额外 source root(`app/`、`routes/`、`config/`)、`-parameters` 编译参数 |
| `compile-views` | `generate-sources` | jblade → Java 源码 |
| `verify-migrations` | `verify` | 迁移 checksum + 与模型定义的漂移检查 |
| `openapi` | `package` | 生成 `openapi.json` |
| `openapi-diff` | `verify` | 与基线对比,破坏性变更则失败(可配) |
| `parity-check` | `verify` | 与 Laravel 等价代码行数比(见 §6) |
| `native` | `package`(profile) | GraalVM native-image 打包 |
| `run` | — | `mvn jaravel:run -Dcmd="migrate"`(无 CLI 时的后备) |

### 4.1 额外 source root

为了让目录结构接近 Laravel:

```
project/
├── app/                      ← 插件注册为 source root
│   ├── Http/Controllers/
│   ├── Models/
│   └── Providers/
├── routes/                   ← source root
├── database/                 ← source root(migrations/seeders/factories)
├── config/                   ← resource root
├── resources/
│   ├── views/                ← jblade 模板
│   ├── lang/
│   └── assets/
├── storage/
├── tests/                    ← test source root
├── public/
├── .env
└── pom.xml
```

Java 包声明仍然必需(`package com.app.models;`),所以实际路径是 `app/Http/Controllers/com/app/http/controllers/...`?**不行**,这样很丑。

**解决方案**:source root 设为 `app`,包名从 `app` 下的路径推导:

```
app/models/User.java          → package app.models;      ✅ 路径与包一致
app/http/controllers/...      → package app.http.controllers;
routes/WebRoutes.java         → package routes;
database/migrations/...       → package database.migrations;
```

即:**用全小写的目录名 + 以项目目录为包根**。生成的包名是 `app.models.User` 而非 `com.example.app.models.User`。

| 方案 | 包名 | 目录 | 评价 |
|---|---|---|---|
| A. 项目目录为包根 | `app.models.User` | `app/models/User.java` | 最接近 Laravel;但包名无组织前缀,多项目依赖时可能冲突 |
| B. 标准 Maven 布局 | `com.example.app.models.User` | `src/main/java/com/example/app/models/User.java` | 符合 Java 惯例;路径深 |
| C. 混合:标准布局 + 短包名 | `app.models.User` | `src/main/java/app/models/User.java` | 折中 |

**倾向方案 C**:保留标准 Maven 布局(不与任何 Java 工具链冲突),但用短包名 `app.*`。应用代码不会被当作库发布,所以无需组织前缀;`jaravel new` 可询问是否用完整包名。

**这是一个需要用户验证的决策** —— 见 §7 待决问题。

---

## 5. 应用骨架(`jaravel new blog` 产物)

```
blog/
├── pom.xml
├── .env  ·  .env.example  ·  .gitignore  ·  .editorconfig
├── jaravelw  ·  jaravelw.cmd                 包装脚本(类比 mvnw)
├── README.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── Application.java              main() 入口
│   │   │   ├── app/
│   │   │   │   ├── http/
│   │   │   │   │   ├── HttpKernel.java       中间件注册
│   │   │   │   │   ├── controllers/
│   │   │   │   │   │   ├── HomeController.java
│   │   │   │   │   │   └── auth/
│   │   │   │   │   ├── middleware/
│   │   │   │   │   ├── requests/
│   │   │   │   │   └── resources/
│   │   │   │   ├── models/User.java
│   │   │   │   ├── services/
│   │   │   │   ├── jobs/  ·  events/  ·  listeners/  ·  policies/
│   │   │   │   ├── console/ConsoleKernel.java
│   │   │   │   ├── exceptions/Handler.java
│   │   │   │   └── providers/
│   │   │   │       ├── AppServiceProvider.java
│   │   │   │       ├── AuthServiceProvider.java
│   │   │   │       ├── EventServiceProvider.java
│   │   │   │       └── RouteServiceProvider.java
│   │   │   ├── routes/
│   │   │   │   ├── WebRoutes.java
│   │   │   │   ├── ApiRoutes.java
│   │   │   │   └── ConsoleRoutes.java
│   │   │   └── database/
│   │   │       ├── migrations/CreateUsersTable.java
│   │   │       ├── seeders/DatabaseSeeder.java
│   │   │       └── factories/UserFactory.java
│   │   └── resources/
│   │       ├── config/
│   │       │   ├── app.yml  ·  database.yml  ·  auth.yml  ·  cache.yml
│   │       │   ├── session.yml  ·  queue.yml  ·  mail.yml  ·  filesystems.yml
│   │       │   ├── logging.yml  ·  cors.yml  ·  schedule.yml
│   │       ├── views/
│   │       │   ├── layouts/app.jblade.html
│   │       │   ├── components/
│   │       │   ├── errors/404.jblade.html  ·  500.jblade.html
│   │       │   └── home.jblade.html
│   │       ├── lang/
│   │       │   ├── zh_CN/{validation,auth,pagination,passwords}.yml
│   │       │   └── en/...
│   │       └── assets/{css,js}/
│   └── test/java/
│       ├── feature/HomeTest.java
│       └── unit/
├── public/                    静态资源 + 入口(storage 符号链接)
└── storage/
    ├── app/  ·  framework/{cache,sessions,views}/  ·  logs/
```

### 5.1 入口

```java
// src/main/java/Application.java
@JaravelApplication
public class Application {
    public static void main(String[] args) {
        Jaravel.run(Application.class, args);
    }
}
```

`Jaravel.run` 内部就是 `SpringApplication.run` + Jaravel 的引导,但用户看不到 Spring。

### 5.2 .env.example

```dotenv
APP_NAME=Jaravel
APP_ENV=local
APP_KEY=
APP_DEBUG=true
APP_URL=http://localhost:8000

DB_CONNECTION=mysql
DB_HOST=127.0.0.1
DB_PORT=3306
DB_DATABASE=jaravel
DB_USERNAME=root
DB_PASSWORD=

CACHE_DRIVER=caffeine
SESSION_DRIVER=cookie
QUEUE_CONNECTION=database
MAIL_MAILER=log
FILESYSTEM_DISK=local
```

与 Laravel 的 `.env` 几乎一致——**这是刻意的**,降低 Laravel 开发者的迁移成本。

---

## 6. CI 与质量门禁

```yaml
# .github/workflows/ci.yml 的检查项
- mvn verify                          # 编译 + 单测 + 集成测试
- ArchUnit 边界规则                    # 阻断:core 不得依赖 Spring
- 性能基准回退检查                      # 阻断:关键指标回退 > 5%
- DX 基准                             # 阻断:冷启动 > 1.5s、L3 重启 > 2s
- parity-check                        # 警告:与 Laravel 代码行数比 > 1.3
- APT 错误信息测试(compile-testing)   # 阻断
- 安全清单测试(AuthSecurityTest)     # 阻断
- 多方言测试矩阵                       # MySQL 8 / PG 16 / SQLite(Testcontainers)
- 多 JDK 矩阵                         # 21 / 25
- native-image 构建                   # 阻断(保证反射零依赖不被破坏)
- 文档链接检查                         # docs/ 内的相对链接有效性
```

### 6.1 parity-check(优雅性门禁)

`benchmark/laravel-parity/` 存放等价功能的两份实现:

```
benchmark/laravel-parity/
├── 01-crud-blog/
│   ├── laravel/          (PHP 源码,仅作参照,不执行)
│   └── jaravel/          (Java 源码,实际编译测试)
├── 02-auth-flow/
├── 03-api-resource/
├── 04-queue-job/
└── report.md             自动生成的行数/噪音对比
```

统计口径:有效代码行(去空行与注释)、token 数、import 行数。目标:**Java 版 ≤ Laravel 版的 1.3 倍**(DESIGN.md 中的 1.2 是理想值,1.3 是门禁线)。

超标时报告指出是哪个场景、哪部分代码膨胀,作为 API 改进的输入。**这个机制把「优雅」从主观感受变成可追踪的指标**,是本项目最重要的质量基础设施之一。

---

## 7. 待决问题

- [ ] **应用包名方案 A/B/C**(§4.1)。这个决策影响所有文档与示例,需要尽早定。建议做一次小范围用户调研(向 10~20 个 Java + Laravel 双栖开发者展示三种布局)。倾向 C。
- [ ] 是否支持 Gradle?Java 生态中 Gradle 占比不低(尤其 Android 背景的团队与新项目)。不支持会挡掉一部分用户。倾向:v1.0 只支持 Maven(APT 配置、source root、插件生命周期都要重做一遍),v1.1 加 Gradle 插件。**需在 README 中明确说明**,避免用户试用后才发现。
- [ ] `jaravel-bom` 是否固定 Spring Boot 版本?固定会限制用户升级 Spring;不固定会出现版本不兼容。倾向:BOM 声明一个兼容区间 + CI 测试矩阵覆盖该区间的首尾版本。
- [ ] `jaravel-devtools` 用 `provided` scope 是否足够?Spring Boot devtools 用 `optional` + 运行时检测。需要确保生产打包时不包含(安全:错误页会泄漏源码与配置)。应在 `native`/`package` 时主动检测并拒绝包含 devtools。
- [ ] 模块数量(约 25 个)是否过多?维护成本与发布复杂度都随之上升。考虑合并:`cache`+`session`、`events`+`queue`+`scheduler`、`mail`+`notifications`。倾向在 v0.1 阶段用更少的模块(约 8 个)快速推进,v0.5 再按需拆分——拆分比合并容易。
