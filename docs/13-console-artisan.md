# 13 · Artisan CLI 与 Tinker

`php artisan` 是 Laravel 体验的半壁江山。Java 侧没有等价物(`mvn spring-boot:run` 不是同一个东西)。本模块补齐这块,目标是**让 `jaravel` 命令的手感与 `artisan` 一致**。

> ### v0.5 实现状态:内核 + 14 条 make:* + jaravel 命令 + 通道 + 7 条在线命令(120 个测试)
>
> **已实现(`jaravel-console`)**
>
> | 能力 | 位置 |
> |---|---|
> | 命令基类:输出 / 交互 / 表格 / 进度条 | `ConsoleCommand`(§3.1) |
> | 参数绑定:`@Argument` / `@Option`、`-mcr` 合并短开关 | `ArgumentBinder`(§3) |
> | 命令注册表 + 「是否想写」提示 | `CommandRegistry`(§6) |
> | 程序化调用 `Artisan.call` / `output()` | `Artisan`(§3.3) |
> | stub 机制 + 项目覆盖 | `Stub`(§4) |
> | `make:model`(含 `-mcr`)· `make:migration` · `make:controller` | §2.2 |
> | `make:request` · `make:job` · `make:middleware` · `make:event` · `make:listener` · `make:policy` · `make:seeder` · `make:mail` · `make:notification` · `make:command` · `make:test` | §2.2 |
> | `list` · `help`(用法从注解生成) | `Usage`(§3.1) |
> | **`jaravel` 命令本身**:离线/在线分流、`--version` | `jaravel-cli`(§1) |
> | **本地命令通道**:在线命令在已跑着的应用进程里执行 | `ChannelServer` / `ChannelClient`(§1.2) |
> | `migrate` · `migrate:status` · `migrate:rollback` · `migrate:fresh` · `db:seed` | §2.3 |
> | `route:list`(带 `--method` / `--path` / `--name` 过滤)· `about` | §2.5 |
>
> **未实现**:JVM 启动器(通道不存在时的那条回落)、
> native 二进制、`tinker`、`queue:*` / `schedule:*` 的 CLI 入口
> (能力都在,缺命令壳子)、`db:show` / `db:table` / `config:show` /
> `optimize` 这一批、`doctor`、
> 其余 `make:*`(见下面那条注记)、
> `@Command` 的 APT 索引(应用的命令靠 Spring 扫到,框架自带的仍要显式
> `register`)、§4.3 的生成后处理。
>
> **终端上已经能用的**:
>
> ```
> $ jaravel make:model Post -mcr
> ✓ 模型:src/main/java/app/models/Post.java
> ✓ 迁移:src/main/java/database/migrations/m2026_11_04_150115/CreatePostsTable.java
> ✓ 控制器:src/main/java/app/http/controllers/PostController.java
> ```
>
> 实测 **0.2s**(JVM 启动占绝大部分)。离 §设计目标 的 100ms 还差一截,
> 那一截要靠 native 镜像(v0.9)—— 但与「等 Spring 启动三秒」已经不是
> 一个量级,而后者才是这一节开头要解决的问题。
>
> **在线命令走本地通道**(§1.2),应用跑着就能用。跨进程实测:
>
> ```
> 终端 A $ (应用启动)   [jaravel] 命令通道已开:…/.jaravel/dev.sock
> 终端 B $ jaravel migrate --force
>         正在迁移(force=true)
>           已执行 m2026_11_04_000101.CreateSomething     ← 逐行到达,间隔 300ms
>           已执行 m2026_11_04_000102.CreateSomething
>         ✓ 迁移完成,进程 PID=39508                       ← 服务端那个进程的 PID
> ```
>
> 输出是**逐段流过来**的(时间戳实测与服务端的节奏一致),不是攒完再发 ——
> 后者在一个慢迁移上与「卡住了」无法区分。
>
> ⚠ 通道不存在时(应用没跑、或者是上次崩溃残留的 socket 文件)
> 仍然报错退出(码 1),消息里说清出路。
> 不在 CLI 里硬跑它们的理由:那个进程没有容器,硬跑会得到
> 「数据库尚未装配」之类与真正原因无关的报错;而假装成功(退出码 0
> 什么都没做)在脚本里是成功的,更糟。
>
> 顺带:**不给 `migrate` 出「是否想写 make:migration ?」那种建议** ——
> 拼写建议对真的手误有用,对一条「还没接通」的命令只会把人带错方向。
>
> ⚠ `make:*` 在应用进程里跑时,项目根取的是**当前工作目录** ——
> 从项目目录启动(IDE / `mvn spring-boot:run`)才成立。打包后从别处启动
> 时会往那个目录写 `src/main/java/...`;看不到 `src/main/java` 时会提醒
> 一句,让「生成的文件去哪了」有线索。这不是缺陷而是范围:生成类命令
> 终将由 `jaravel-cli` 在项目目录里执行(§1)。
>
> **装配已接上**(`JaravelConsoleAutoConfiguration`):自带的 `make:*` 与
> 应用自己的 `ConsoleCommand` bean 都登记进注册表,`Artisan` 门面可用。
> 应用的命令是 Spring bean,所以**依赖直接由容器注入** —— 注册表存的是
> 工厂而不是实例,这里正好填进那个接缝。
> ⚠ 命令 bean 应当是 `prototype`:命令对象带状态(解析出来的参数就写在
> 字段上),单例会让第二条命令看到第一条留下的选项。发现单例时会警告。
>
> 顺带把 docs/10 §3.3 挂了很久的 `s.command("cache:clear")` 接通了 ——
> 那个 SPI(`TaskAction.CommandRunner`)早就在 scheduler 里,缺的只是
> 一个实现,而按模块依赖方向它只能由 starter 提供。
>
> 也就是说:**命令还只能从进程内调用**(`Artisan.call(...)` 或计划任务),
> 终端上的 `jaravel xxx` 要等 CLI 那一片。先做内核的理由是它在下面 ——
> CLI、tinker、每一条在线命令都站在 `ConsoleCommand` 与注册表上。
>
> 两处与下文设计不同的地方,各自在原处标了:
> **§4.1 的 stub 不用 jblade 渲染**(与 100ms / native 目标冲突),
> **迁移的时间戳在包名上而不是类名上**(Java 的类名不能以数字开头)。

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

> **与下面不同的一处:没有用 picocli。**
>
> 命令的参数解析已经在 `jaravel-console` 里(`@Argument` / `@Option` +
> `ArgumentBinder`),而它同时服务 `Artisan.call` 与调度器的命令通道。
> 再引一个 picocli 意味着**两套注解、两个解析器**,而两边注定分叉 ——
> 这个项目在别处(jblade 的组件复用 `@template`、邮件的渲染走 SPI)
> 反复用的就是同一条理由。picocli 在这里只会负责最顶层的 argv 切分
> (一个命令名 + 其余),那是三十行的事。
>
> ⚠ 但它的 native-image 价值是真的:picocli 的注解处理器会生成反射配置,
> 而 `ArgumentBinder` 靠反射读字段 —— 打 native 镜像时需要 `reflect-config`。
> 那一步与 `@Command` 的 APT 索引(§3.2)是同一件事:
> 索引生成时顺带把反射配置也生成出来。这笔账记在这里。

- 用 GraalVM native-image 编译(v0.9);
- 分发:单文件二进制(Windows/macOS/Linux × x64/arm64),也提供 `jaravel.bat` / shell 包装脚本;
- 项目内也可通过 `./jaravelw`(类比 `mvnw`)调用,自动下载匹配版本;
- 安装:`scoop install jaravel` / `brew install jaravel` / `curl | sh`。

### 1.2 在线命令的加速:复用已跑着的应用进程

```bash
#(终端 A)应用跑起来就会开通道 —— IDE 里 run 或 mvn spring-boot:run 都算
jaravel migrate        # 终端 B:检测到通道存在 → 发给那个进程执行,0 启动开销
```

这让 `jaravel migrate`、`jaravel tinker` 在开发期的响应时间降到接近 PHP。**这是本模块最重要的 DX 设计**。

> ### v0.5:通道已实现
>
> **不需要 `jaravel serve`** —— 通道由 starter 在应用启动时开
> (`JaravelConsoleAutoConfiguration`),所以 IDE 里 run 一下就有。
> `jaravel serve` 本身(带 `--watch` 的开发服务器)是另一条命令,还没写。
>
> **一种实现覆盖三个平台**:JDK 16 起 `AF_UNIX` 在 Windows 10 1803+ 上
> 也支持(本机 Windows 11 + JDK 21 实测可用)。所以没有
> 「unix 用 socket、Windows 用命名管道」的两条分支 —— 那两条会各自腐坏。
>
> 比 TCP 回环更好的地方是**权限**:socket 是个文件,受文件系统权限保护;
> 而 127.0.0.1 上的端口,同机任何进程都能连 —— 对一个「执行任意命令」的
> 通道来说,差别很大。
>
> #### ★ 这个通道就是远程代码执行,所以
>
> | 做法 | 为什么 |
> |---|---|
> | `app.env=production` 时**根本不创建** | 开发机上要的东西,生产环境里是后门 |
> | socket 放在**项目目录**下(`.jaravel/dev.sock`) | 受项目目录权限保护,而不是 `/tmp` 那种全局可写的地方 |
> | POSIX 上再收紧到 0600 | 多用户开发机上,一个所有人可写的 socket 等于把进程交出去 |
> | `console.channel.enabled=false` 可显式关掉 | 不想要就能关 |
>
> 生产环境里**不打「通道未启动」的日志** —— 那是正常状态,
> 一条恒定的警告只会训练人忽略警告。
>
> #### 几处「不这么做就会静默出错」的地方
>
> · **残留的 socket 文件**:上次被 `kill -9` 之后文件还在而没人监听。
>   服务端启动时先删掉它(不删的话 bind 报「地址已在使用」,
>   那条消息会把人送去查端口占用);客户端连不上就当通道不存在
>   (报「连接被拒绝」没用 —— 用户的问题是「我该怎么跑这条命令」)。
> · **收不到退出码就断线**:不能当成成功。退出码 0 会让脚本继续往下走,
>   而那条命令可能只跑了一半。现在报错并指向 dev server 的日志。
> · **参数里的换行要转义**:不转义的话它会把下一行当成新的帧,
>   而那种错乱在协议层很难定位 —— 每一帧单独看都是合法的。
> · **stdout 与 stderr 在协议上分开**:控制台的错误刻意走 stderr
>   (为了 `route:list --json | jq` 不被污染),合成一条流就分不回去了。
> · **颜色由客户端决定**:服务端在 dev server 那个终端里,
>   它的 stdout 是不是终端与客户端无关 —— 在服务端判断的话,
>   「我的终端支持颜色但 jaravel migrate 没有颜色」。
>
> #### ⚠ 通道上的命令不可交互
>
> `confirm()` / `ask()` 返回**默认值** —— 与管道、CI、`Artisan.call`
> 完全一致的既有约定。转发 stdin 要先解决「客户端 Ctrl-C 而服务端那条
> 命令还在等输入」,那是后面的事;当前行为是安全的那一侧
> (`migrate` 那种「要确认吗」默认答不)。

通道不存在时,docs 原本说的回落是「冷启动一个 JVM 执行」——
**那一步还没实现**,现在会报一条说清出路的错(退出码 1)。

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

> **迁移的时间戳在包名上,不在类名上**(已实现的那三个之一):
> `database/migrations/m2026_11_04_123000/CreatePostsTable.java`
>
> Laravel 是文件名带时间戳(`2026_11_04_123000_create_posts_table.php`),
> Java 不行:类名不能以数字开头,而且同名类不能共存 ——
> 两次 `add_slug_to_posts` 会撞车。
> 每个迁移一个带时间戳的包同时解决这两件事,执行顺序由包名字典序决定,
> 与 Laravel 的文件名排序等价。这个形态 `examples/blog` 已经在用。
>
> 建表还是改表由名字认出来(`create_x_table` / `add_y_to_x`),
> 显式的 `--create` / `--table` 优先。**猜不出来时不拒绝生成**:
> 名字是用户起的,不该为了迁就命名约定而拒绝写文件 ——
> 此时表名留空并提示一句,因为没填表名的 stub 一眼就能看出要改,
> 而一个被拒绝的 `make:migration` 只会让人去搜命名规则。
>
> 另外两件已实现的小事:生成目标**已存在时拒绝覆盖**并提示 `--force`
> (用户在那个文件里写的代码,一句 `make:model` 就能让它变回空壳);
> `--api` 自己就意味着资源控制器,不必再写 `--resource`
> (那是个一定会被漏掉的组合,而漏掉的结果是一个只有 index 的空壳)。

> ### 关于 stub 的内容:每一个都是照仓库里真实存在的用例写的
>
> `request` 照 `StorePostRequest`、`job` 照 `ArchiveInvoice`、
> `seeder` 照 `BlogSeeder`、`listener` 照 `AuthAudit`、
> `policy` 照 `NotePolicy`、`notification` 照 `WelcomeAboard`、
> `test.feature` 照 `AuthFeatureTest` —— 不是凭印象编的。
>
> **一个教错 API 的 stub 比没有这条命令更糟**,因为它会被照抄:
> 用户第一反应是「框架给我的代码编译不过」,而不是「这个 API 名字错了」。
> 写 `make:mail` 的 stub 时就当场撞出一个真陷阱(`Envelope` 的静态
> `subject` 快捷方式会让链式写法静默丢掉前面所有设置),那个 API 已经改掉。
>
> 另有一条测试把**每个生成出来的文件喂给 javac 做语法检查** ——
> 少一个括号的 stub、被替换坏的占位符都会在 CI 上失败。
> (只查语法:生成的代码引用的是应用自己的类,在控制台模块的 classpath
> 上一个都没有,真编译只会得到一堆与 stub 对不对无关的「找不到符号」。)
>
> **三条有意为之的选择**
>
> 1. `make:listener` 没给 `--event` 时生成 `handle(Object event)`,
>    而不是填一个不存在的 `ExampleEvent` —— 后者生成出来的是编译不过的文件。
>    生成的代码必须能编译,否则用户第一件事是修框架给他的报错。
> 2. `make:policy` 推不出模型时**报错要 `--model`**。这与
>    `make:migration` 猜不出表名仍然生成**刚好相反**:那里缺的是 stub 里
>    一个一眼能看见的空字符串,这里缺的是「这个类会不会生效」——
>    `@Policy(X.class)` 的那个 X 是注册键,没有它的策略永远不会被调用,
>    而症状是「授权规则没生效」。
> 3. `make:command` 的默认命令名带 `app:` 前缀
>    (`SyncInventory` → `app:sync-inventory`,与 Laravel 一致):
>    应用里一条叫 `migrate` 的命令会悄悄遮住框架那条,
>    而那种冲突在运行时表现为「migrate 的行为变了」。
>
> **还没有的几条,各有原因**:`make:resource` 要 `jaravel-api`
> (模块还不存在);`make:factory` 的「工厂类」在仓库里还没有先例
> (`BlogSeeder` 是在 seeder 里就地 `Factory.of(...)`),
> 给它一个 stub 等于凭空发明一个约定;`make:view` / `make:component`
> 生成的是 jblade 模板而不是 Java,落点与后处理都不同,
> 单独一批做。

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

> ### v0.5:迁移这一组 + db:seed 已实现
>
> `migrate`(`--pretend` / `--step` / `--allow-drift` / `--seed`)·
> `migrate:status` · `migrate:rollback --step=N` · `migrate:fresh` ·
> `db:seed --class=`。
>
> Migrator 的能力(含 checksum 漂移检测)早就齐了,这些命令是壳子 ——
> 真正值得记的是三件事:
>
> **1. 生产护栏,而且「不可交互时拒绝」**
>
> 这些命令会改库结构或删数据。它们跑在三种地方:开发者的终端(可交互)、
> 本地命令通道(不可交互)、`Artisan.call`(不可交互)。
> `confirm(..., false)` 在不可交互时返回默认值 `false` ——
> 于是**没有终端的情况下一律拒绝**,要做就显式写 `--force`。
> 反过来(默认放行)意味着一个部署脚本里的 `migrate` 会在生产库上
> 静默执行,而那正是 Laravel 当年加这个护栏的原因。
>
> ⚠ 但 `--pretend` **不需要确认** —— 它什么都不改,
> 而「想看看它会做什么」恰恰是生产环境里最该允许的操作。
>
> **2. `migrate:status` 会列出「库里有记录但代码里没有」的迁移**
>
> 标成 `?`。那通常意味着切了分支:当前分支少了一个已经执行过的迁移,
> 而表结构里还留着它改的东西。只看代码侧的列表看不出这件事,
> 而它恰恰是「我的库为什么和同事不一样」的常见答案。
>
> **3. 失败时输出不丢**
>
> `migrate` 在 `finally` 里把 `Migrator.output()` 打出来 ——
> 「第几个迁移炸的」是排查的第一个问题,而异常本身只说了原因。
> `Artisan.call` 也在 `finally` 里记录输出,所以炸了之后
> `Artisan.output()` 仍然拿得到那几行(调用方正要用它写日志)。
>
> `db:seed` 的一个取舍:有 `DatabaseSeeder` 就只跑它(尊重 Laravel 的
> 入口约定),没有就全跑 —— 否则一个只写了几个 seeder、没写入口的项目
> 会「什么都没发生」。

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

> ### v0.5:route:list 与 about 已实现
>
> `route:list` 的数据来自**编译后的路由表**,也就是请求真正走的那一张 ——
> 而不是重新解析一遍路由定义。两者不一致时(分组前缀、资源路由的展开、
> PATCH 的隐式注册),重新解析那份会说谎。
> 支持 `--method`(不分大小写)/ `--path` / `--name` 过滤,
> 过滤后会报「匹配 N / 共 M 条」——「我的过滤条件是不是写错了」
> 与「真的只有这么多」要能分开。
>
> `about` 报的是**配置解析之后**的实际值。一半的「为什么不生效」最后都是
> .env 没被读到、或者被另一个配置源覆盖了,而那件事只有解析完才看得出来 ——
> 看 yml 文件是看不出来的。没配的驱动显示「(未配置)」而不是空白。

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

### 4.1 stub 只做变量替换(**与原设计的偏差**)

```
jaravel-console/src/main/resources/stubs/
├── model.stub
├── controller.stub
├── controller.resource.stub
├── controller.api.stub
├── migration.create.stub
├── migration.update.stub
└── ...
```

```java
package {{ pkg }};

import io.jaravel.eloquent.annotations.Id;
import io.jaravel.eloquent.annotations.Model;

@Model(table = "{{ table }}")
public class {{ class }} extends {{ class }}Base {

    @Id public Long id;

    // TODO 字段按数据库列来写:public String title;
}
```

> ### 原设计是「用 jblade 渲染 stub(自举)」—— 实现时放弃了
>
> 它与本模块的**头号设计目标**冲突:`make:*` 是离线命令,
> 要在 100ms 内跑完,而且终将跑在 GraalVM native 的 `jaravel-cli` 里 ——
> **那里没有 javac**,jte 的运行时编译根本不可用,
> 而 jblade 的预编译排在 v0.9(docs/04)。
>
> 「自举能顺带给模板引擎提供真实用例」是真的收益,但它换不来
> 「`make:model` 要么慢 500ms、要么在 native 下直接不可用」。
>
> 所以 `Stub` 只认 `{{ 变量 }}`。它够用,因为这一节自己就是用
> **每个变体一个 stub 文件**(`controller.resource` / `controller.api` /
> `migration.create`)来避免模板里的条件分支的 —— 真正需要的只有插值。
> 代价:stub 里写不了循环与条件。真需要时的出路是再加一个 stub 文件。
>
> 这条由 `jaravel-archunit-rules` 把住:`io.jaravel.console` 依赖
> `io.jaravel.view` 会让 CI 失败 —— 而不是某天打 native 镜像时才发现。
>
> ⚠ 没替换掉的变量**报错**。留着那串 `{{ x }}` 字面量的话,生成出来的是
> 一个编译不过的 Java 文件,而 javac 的报错会指向用户的新文件,
> 与真正的原因(stub 的参数少给了一个)无关。

### 4.2 用户覆盖

```bash
jaravel stub:publish              # 复制全部 stub 到项目的 stubs/ 目录
jaravel stub:publish --only=model
```

项目 `stubs/model.stub` 存在时优先使用。团队可定制:加公司版权头、加默认审计字段、加 Lombok 注解、改包结构约定。

查找顺序(项目目录优先、再 classpath)与 jblade 的视图查找**刻意一致** ——
同一件事在两个地方有两种顺序,是个没人记得住的规则。

> `stub:publish` 本身还没实现(它属于 §2 的命令清单)。在那之前,
> 手动在项目里放一个同名 `.stub` 就是它的效果 ——
> 覆盖机制本身已经生效,`stub:publish` 只是帮你把文件拷过去。

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
