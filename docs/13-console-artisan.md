# 13 · Artisan CLI 与 Tinker

`php artisan` 是 Laravel 体验的半壁江山。Java 侧没有等价物(`mvn spring-boot:run` 不是同一个东西)。本模块补齐这块,目标是**让 `jaravel` 命令的手感与 `artisan` 一致**。

---

## 设计目标

1. 命令启动快:`jaravel make:model Post` 应在 **100ms 内**完成,不能等 JVM + Spring 启动 3 秒;
2. 代码生成的 stub 可被用户覆盖(团队定制样板);
3. `tinker` 必须可用——交互式探索是 Laravel 开发流的核心环节;
4. 命令既能在终端跑,也能被程序调用(`Artisan.call("migrate")`)。

---

## 1. 双进程架构(关键设计)

**问题**:`make:model` 这类纯代码生成命令不需要应用上下文,但 `migrate`、`tinker`、`queue:work` 需要完整容器。如果所有命令都启动 Spring,`make:controller` 要等 3 秒,DX 不可接受。

**方案:命令分两类,走两条执行路径。**

```
jaravel <command>
   │
   ├─ 【离线命令】无需应用上下文
   │   new · make:* · route:list(读缓存) · config:cache · optimize
   │   key:generate · lang:check · openapi:diff · about
   │   │
   │   └─ 由 GraalVM native 编译的 jaravel CLI 直接执行
   │      启动 ~20ms,无 JVM 预热,手感等同 php artisan
   │
   └─ 【在线命令】需要容器 / 数据库 / 队列
       migrate · db:seed · tinker · queue:work · schedule:* · 自定义命令
       │
       └─ CLI 启动应用 JVM(或连接已运行的 dev server),在容器内执行
          启动 ~1.5s(或 0ms 若 dev server 已在运行)
```

### 1.1 `jaravel` 可执行文件

- 用 picocli + GraalVM native-image 编译;
- 分发:单文件二进制(Windows/macOS/Linux × x64/arm64),也提供 `jaravel.bat` / shell 包装脚本;
- 项目内也可通过 `./jaravelw`(类比 `mvnw`)调用,自动下载匹配版本;
- 安装:`scoop install jaravel` / `brew install jaravel` / `curl | sh`。

### 1.2 在线命令的加速:复用 dev server

```bash
jaravel serve          # 终端 A:启动开发服务器,同时开一个本地命令通道(unix socket / 命名管道)
jaravel migrate        # 终端 B:检测到通道存在 → 把命令发给已运行的进程执行,0 启动开销
```

这让 `jaravel migrate`、`jaravel tinker` 在开发期的响应时间降到接近 PHP。**这是本模块最重要的 DX 设计**。

通道不存在时自动回落为「冷启动一个 JVM 执行」。

---

## 2. 命令清单

### 2.1 脚手架

```bash
jaravel new <name>                      # 新项目,交互式选择:数据库/前端栈/认证脚手架
jaravel new <name> --preset=api         # 预设:api / web / minimal
jaravel install:auth                    # 在已有项目中加认证脚手架(登录/注册/重置)
jaravel install:api                     # 加 API 脚手架(token 认证 + 异常格式)
jaravel install:broadcasting            # v0.6
```

### 2.2 代码生成

```bash
jaravel make:model Post                 # app/Models/Post.java
jaravel make:model Post -m              #   + 迁移
jaravel make:model Post -mcrfsp         #   + 迁移/控制器/请求/工厂/seeder/policy
jaravel make:model Post --all

jaravel make:controller PostController
jaravel make:controller PostController --resource --model=Post
jaravel make:controller PostController --api --model=Post
jaravel make:controller PostController --invokable

jaravel make:migration create_posts_table
jaravel make:migration add_slug_to_posts --table=posts

jaravel make:request StorePostRequest
jaravel make:resource PostResource
jaravel make:middleware EnsureSubscribed
jaravel make:policy PostPolicy --model=Post
jaravel make:job SendInvoice
jaravel make:event OrderPaid
jaravel make:listener NotifyWarehouse --event=OrderPaid
jaravel make:mail OrderShipped
jaravel make:notification InvoicePaid
jaravel make:command SyncInventory
jaravel make:rule Uppercase
jaravel make:cast Money
jaravel make:factory PostFactory --model=Post
jaravel make:seeder PostSeeder
jaravel make:observer PostObserver --model=Post
jaravel make:scope PublishedScope
jaravel make:component Alert            # jblade 组件(类 + 模板)
jaravel make:view posts.index
jaravel make:test PostTest              # --unit / --feature
jaravel make:provider SmsServiceProvider
jaravel make:exception PaymentFailedException
jaravel make:channel VoiceChannel
jaravel make:enum UserStatus
jaravel make:interface PaymentGateway
jaravel make:record UserDto
```

### 2.3 数据库

```bash
jaravel migrate [--pretend|--step|--force|--seed]
jaravel migrate:rollback [--step=N|--batch=N]
jaravel migrate:reset · migrate:refresh · migrate:fresh · migrate:status
jaravel schema:dump [--prune]
jaravel db:seed [--class=PostSeeder]
jaravel db:wipe
jaravel db:show                         # 连接信息、表清单、行数、大小
jaravel db:table posts                  # 表结构、索引、外键
jaravel db:monitor --max=100            # 连接数告警
jaravel db                              # 打开数据库 CLI(mysql/psql),自动带上配置
jaravel model:show Post                 # 模型的字段/关联/作用域/观察者一览
jaravel model:prune                     # 清理可修剪的模型数据
```

### 2.4 运行时

```bash
jaravel serve [--port=8000 --host=0.0.0.0 --watch]
jaravel tinker
jaravel queue:work · queue:listen · queue:restart · queue:failed · queue:retry · queue:flush
jaravel queue:monitor · queue:prune-batches · queue:prune-failed
jaravel schedule:list · schedule:run · schedule:work · schedule:test · schedule:clear-mutexes
jaravel down --message="维护中" --retry=60 --secret=bypass-token
jaravel up
```

### 2.5 检视与优化

```bash
jaravel route:list [--method=GET --name=admin --path=api --except-vendor --json]
jaravel route:cache · route:clear
jaravel config:show database            # 解析后的最终配置(含 .env 覆盖结果)
jaravel config:cache · config:clear
jaravel view:cache · view:clear
jaravel event:list · event:cache
jaravel optimize                        # config + route + view + event 全量缓存
jaravel optimize:clear
jaravel about                           # 环境、版本、驱动、缓存状态总览
jaravel key:generate                    # 生成 APP_KEY
jaravel storage:link                    # 创建 public/storage 符号链接
jaravel lang:check                      # 校验语言包完整性(缺键/多余键)
jaravel openapi:generate · openapi:diff
jaravel inspire                         # 彩蛋,保留 Laravel 传统
```

### 2.6 诊断(Jaravel 特有)

```bash
jaravel doctor                          # 健康检查:配置/数据库/缓存/队列连通性 + 常见误配
jaravel doctor --fix                    # 自动修复可修复项
jaravel analyze:n-plus-one              # 扫描代码中可能的 N+1(未 with 的关联访问)
jaravel analyze:unused                  # 未使用的路由/视图/语言键
jaravel parity                          # 与等价 Laravel 代码的行数比(见 DESIGN.md §9)
```

`jaravel doctor` 的检查项示例:

```
✓ APP_KEY 已设置
✓ 数据库连接正常 (mysql 8.0.35)
✗ schedule.on-one-server 使用了 caffeine 缓存驱动
  → 多实例部署下 onOneServer() 将失效。改用 redis 驱动。
  → 修复: 在 config/cache.yml 中设置 stores.schedule.driver: redis
⚠ eloquent.prevent-lazy-loading 在非生产环境未开启
  → 建议开启以在开发期捕获 N+1
✓ 迁移全部已执行 (23/23)
✗ 3 个迁移的 checksum 与记录不符:CreateUsersTable, ...
  → 这些迁移在执行后被修改过,不同环境的表结构可能不一致
```

**这个命令是 Jaravel 的差异化加分项**:把框架使用中的常见陷阱变成可自动检测的项,而不是让用户踩坑后搜索。

---

## 3. 自定义命令

```java
@Command(name = "inventory:sync",
         description = "从供应商 API 同步库存")
public class SyncInventory extends ConsoleCommand {

    @Argument(description = "供应商代码,留空则全部")
    String supplier;

    @Option(names = {"-f", "--force"}, description = "忽略上次同步时间")
    boolean force;

    @Option(names = "--chunk", defaultValue = "500")
    int chunk;

    /** 依赖从容器注入 */
    public SyncInventory(SupplierClient client) { this.client = client; }

    @Override public int handle() {
        info("开始同步" + (supplier == null ? "全部供应商" : supplier));

        var items = client.fetch(supplier, force);

        // 进度条
        withProgressBar(items, item -> inventory.upsert(item));

        // 表格输出
        table(List.of("SKU", "数量", "状态"),
              items.map(i -> List.of(i.sku(), i.qty(), i.status())));

        // 交互
        if (confirm("是否重建索引?", false)) {
            call("search:reindex");                 // 调用其他命令
        }
        String env = choice("目标环境", List.of("staging", "production"), "staging");
        String token = secret("输入 API token");

        success("同步完成:" + items.size() + " 条");
        return 0;
    }

    /** 若该命令同时是定时任务 */
    @Override public void schedule(Schedule s) {
        s.command(this).everyTenMinutes().withoutOverlapping();
    }
}
```

### 3.1 输出 API

```java
info("...") · line("...") · comment("...") · question("...")
warn("...") · error("...") · success("...")
newLine(2)
table(headers, rows)
withProgressBar(items, consumer)
var bar = progressBar(total); bar.advance(); bar.finish();
spinner("处理中", () -> doWork())
components().twoColumnDetail("键", "值")
components().task("迁移数据库", () -> migrate())        // 带 ✓/✗ 标记
```

### 3.2 注册

`@Command` 由 APT 生成索引,自动注册。无需手动在 Kernel 列表里加。

### 3.3 程序化调用

```java
Artisan.call("migrate", Map.of("--force", true));
Artisan.call("inventory:sync", Map.of("supplier", "acme"));
int code = Artisan.call("db:seed");
String output = Artisan.output();
Artisan.queue("inventory:sync");                // 作为队列任务执行
```

---

## 4. Stub(代码模板)机制

### 4.1 stub 本身用 jblade 渲染

```
jaravel-console/src/main/resources/stubs/
├── model.jblade.java
├── model.pivot.jblade.java
├── controller.jblade.java
├── controller.resource.jblade.java
├── controller.api.jblade.java
├── migration.create.jblade.java
├── request.jblade.java
├── job.jblade.java
└── ...
```

```java
{{-- stubs/model.jblade.java --}}
@params(String pkg, String className, String tableName)
package {{ pkg }};

import io.jaravel.eloquent.Model;
import io.jaravel.eloquent.annotations.*;

@Model(table = "{{ tableName }}")
public class {{ className }} extends Model<{{ className }}> {

    @Id Long id;

    // TODO: 定义字段
}
```

**自举**:用 jblade 渲染 jblade 框架自己的 stub,顺带给模板引擎提供了持续的真实用例测试。

### 4.2 用户覆盖

```bash
jaravel stub:publish              # 复制全部 stub 到项目的 stubs/ 目录
jaravel stub:publish --only=model
```

项目 `stubs/model.jblade.java` 存在时优先使用。团队可定制:加公司版权头、加默认审计字段、加 Lombok 注解、改包结构约定。

### 4.3 生成后处理

- 自动按项目的 `.editorconfig` 格式化;
- 自动执行 import 整理;
- 若项目有 spotless/checkstyle 配置,生成的代码必须通过(CI 测试覆盖)。

---

## 5. Tinker(JShell 集成)

```bash
jaravel tinker
```

```
Jaravel 0.5.0 (Java 21.0.5) — 容器已就绪,门面已导入

jaravel> User.query().count()
$1 ==> 1523

jaravel> var u = User.find(1)
u ==> User{id=1, name=Tom, email=t@a.com}

jaravel> u.posts.get().pluck(Post::getTitle)
$3 ==> Coll[第一篇, 第二篇]

jaravel> DB.table("orders").where("status","paid").sum("amount")
$4 ==> 128400.00

jaravel> Cache.put("k", "v", ofMinutes(5))

jaravel> Mail.to("a@b.com").send(new OrderShipped(Order.find(1)))

jaravel> sql()                  // 打印本次会话执行过的所有 SQL
jaravel> :reload                // 重载应用类(代码改了之后)
jaravel> :exit
```

### 5.1 实现

基于 JDK 的 `jdk.jshell` API:

1. 启动应用容器(或连接 dev server);
2. 创建 `JShell` 实例,`LocalExecutionControlProvider` 使其与应用**同 JVM 同类加载器**(关键:否则拿不到容器实例);
3. 预执行一批 snippet:
   ```java
   import io.jaravel.facades.*;
   import io.jaravel.support.*;
   import com.app.models.*;         // 扫描项目的模型包
   import static java.time.Duration.*;
   ```
4. 提供 Jaravel 专属命令:`sql()`、`:reload`、`routes()`、`config("k")`、`dd(obj)`;
5. 结果打印用 Jaravel 的 `dump` 格式化器(模型打印字段而非 `Object@hash`,集合打印元素而非 `[Lcom...`)。

### 5.2 JShell 的现实限制(必须如实告知用户)

| 限制 | 影响 | 缓解 |
|---|---|---|
| 每个 snippet 独立编译,不能用 lambda 捕获可变局部 | 轻微 | 文档说明 |
| 泛型推断在 REPL 中偶尔比源码中弱 | 轻微 | 显式类型 |
| 无法重定义已加载的应用类 | 改了代码要重启 | `:reload` 用新 ClassLoader 重建容器 |
| 启动需加载全部应用类 | 首次 ~2s | dev server 复用 |
| 不支持 `var` 之外的类型推断糖 | — | — |

**与 `php artisan tinker` 的差距是真实的**(PHP 的 REPL 天然无编译步骤)。但 `:reload` + dev server 复用能把体验做到「可用且愉快」,这已足够——Java 生态此前根本没有可用的框架 REPL。

---

## 6. 内部架构

```
jaravel-cli(GraalVM native,独立进程)
├── picocli 命令树
├── OfflineCommands/              无需容器:new · make:* · key:generate · about
├── ChannelClient                 连接 dev server 的本地通道
├── JvmLauncher                   冷启动应用 JVM 执行在线命令
└── stubs/                        内嵌 stub 资源

jaravel-console(在应用进程内)
├── ConsoleKernel                 命令注册表(APT 索引)+ 调度定义
├── ConsoleCommand (abstract)     输出 API / 交互 API / 进度条 / 表格
├── CommandRegistry               @Command APT 索引
├── Artisan (facade)              程序化调用
├── commands/                     所有在线命令实现
├── generators/                   stub 渲染 + 命名推断 + 后处理
├── tinker/  JShellBootstrap · JaravelFormatter · ReloadableContainer
└── channel/ CommandChannelServer unix socket / 命名管道服务端
```

---

## 7. 与 Maven 的关系

CLI 与 Maven 不是竞争关系:

| 职责 | 工具 |
|---|---|
| 依赖管理、编译、打包、测试 | Maven |
| 模板预编译、APT、native 打包 | `jaravel-maven-plugin`(绑定到 Maven 生命周期) |
| 代码生成、数据库操作、运行时命令、REPL | `jaravel` CLI |

```xml
<plugin>
  <groupId>io.jaravel</groupId>
  <artifactId>jaravel-maven-plugin</artifactId>
  <executions>
    <execution>
      <goals>
        <goal>compile-views</goal>      <!-- jblade → Java,绑定 generate-sources -->
        <goal>verify-migrations</goal>  <!-- 迁移与模型漂移检查,绑定 verify -->
        <goal>openapi</goal>            <!-- 生成 openapi.json,绑定 package -->
      </goals>
    </execution>
  </executions>
</plugin>
```

`jaravel` CLI 内部也可代理 Maven 命令(`jaravel test` → `mvn test`,`jaravel build` → `mvn package`),让用户只需记一个命令入口。

---

## 8. 逃生口

| 需求 | 途径 |
|---|---|
| 不想装 CLI | 所有在线命令可通过 `mvn jaravel:run -Dcmd="migrate"` 执行 |
| 需要 Spring Boot CLI 行为 | 照常使用 |
| 需要 picocli 原生能力 | `ConsoleCommand` 就是 picocli 命令,所有注解可用 |
| 需要在 CI 中无交互执行 | 所有交互方法在 `--no-interaction` 下使用默认值;`--force` 跳过确认 |

---

## 9. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| 启动开销 | PHP 本身快 | 离线命令走 native CLI;在线命令复用 dev server | 补齐 JVM 的启动劣势 |
| 命令注册 | Kernel 数组 / 自动发现 | `@Command` + APT 索引 | 零扫描 |
| REPL | `tinker`(psysh) | `tinker`(JShell) | 能力略弱但可用 |
| stub | `.stub` 纯文本占位符替换 | jblade 模板 | 更强的条件/循环能力 |
| `doctor` / `analyze:*` | 无 | 有 | 把框架陷阱变成可检测项 |
| `parity` | 无 | 有 | 优雅性的量化门禁 |

---

## 10. 待决问题

- [ ] **GraalVM native CLI 的构建与分发成本**:6 个平台 × 每次发版。需要 GitHub Actions 矩阵构建。若成本过高,退化为「JVM 版 CLI + AppCDS + 分层编译 `-XX:TieredStopAtLevel=1`」,启动约 300~500ms,仍可接受。**v0.5 前决定。**
- [ ] 本地命令通道的实现:Unix domain socket(Java 16+ 支持)在 Windows 上需用命名管道。跨平台抽象需要写,工作量约 3 天。
- [ ] `make:*` 的包路径推断:如何知道 `make:model Post` 应该生成到 `com.app.models`?从 `pom.xml` 的 `groupId` + 约定?还是在 `config/app.yml` 中声明 `base-package`?倾向后者 + 首次运行时自动探测写入。
- [ ] `jaravel new` 的项目模板分发:内嵌在 CLI 二进制里(体积大但离线可用)还是从 GitHub 下载(需网络)?倾向内嵌 minimal + 可选下载完整 preset。
- [ ] Tinker 的 `:reload` 能否做到不丢失会话变量?JShell 的状态与 ClassLoader 绑定,重载后变量引用旧类的实例。可能只能提示用户重新赋值。
- [ ] `analyze:n-plus-one` 的静态分析可靠性:误报率高会让人关掉它。倾向以运行时数据为主(开发期收集查询模式),静态分析为辅。
