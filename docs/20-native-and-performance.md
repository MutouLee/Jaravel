# 20 · 构建期预编译、native 与性能基准(v0.9)

> 范围见 [17](17-roadmap.md) 的 v0.9 一行。
> 本文记录三件事:**jblade 的构建期预编译**、**GraalVM native 的现状**、
> 以及** JMH / 端到端性能数字**。所有「没做到」的地方都写在对应小节里,
> 不把计划写成现状。

---

## 0. 一句话

v0.9 交付了:

- `jaravel-maven-plugin` 的两个 goal:`compile-views`(jblade → class)
  与 `native-config`(为 `@Command` 生成 GraalVM reflect-config);
- **jaravel-cli 的 Linux native 镜像**,startup 中位 **~86ms**
  (n=30,bash `time`),`--version` 中位 ~87ms,`make:model` 可用;
- **JMH 基准模块**(`benchmark/jmh`),并把路由匹配从线性扫描改成
  字面量前缀索引 + 单参数快路径;
- **端到端 JSON 吞吐 ≈ 裸 Spring MVC 的 1.19 倍**(单机对照组),
  **模板渲染 ≈ 8.8× Thymeleaf**;
- 一批**安全修复与依赖升级**(`SECURITY-AUDIT.md`)。

**范围**:native 只做 `jaravel-cli`;Web 应用 native 不在当前范围内。

---

## 1. jblade 生产模式预编译

### 1.1 配置

在应用 `pom.xml` 里加一个插件:

```xml
<plugin>
  <groupId>io.jaravel</groupId>
  <artifactId>jaravel-maven-plugin</artifactId>
  <version>${jaravel.version}</version>
  <executions>
    <execution><goals><goal>compile-views</goal></goals></execution>
  </executions>
</plugin>
```

它绑定在 `process-classes`:

1. 扫 `src/main/resources/views` **以及依赖 jar 里的 `views/`**
   (邮件组件、dashboard 面板);
2. 用与开发模式**同一套** `TranspilingCodeResolver` 转写成 jte 源码;
3. 调 jte 的 `precompileAll(compilePath)` 生成并编译
   `gg.jte.generated.precompiled` 包下的 class,写进 `target/classes`;
4. 写 `META-INF/jaravel/jblade-metadata.properties`(行号映射 + `@params`)
   与 `META-INF/jaravel/jblade-precompiled` marker。

`examples/todo` 的实测:10 个模板(3 个应用 + 7 个依赖视图)、13 个 class。

### 1.2 运行时选哪个引擎

`JaravelAutoConfiguration#jaravelViewEngine` 的顺序:

| 条件 | 引擎 | 场景 |
|---|---|---|
| `view.root` 目录存在 | `forDirectory` | 开发 / `mvn spring-boot:run` / 测试 |
| classpath 有 marker | `forPrecompiled` | 打包后的 jar / native |
| 其余 | `forClasspath` | 展开 classpath 的兜底(运行时编译) |

**开发模式仍然是「改模板即生效」** —— 目录优先,预编译不会抢走热重载。
打包后没有目录,marker 生效,第一次渲染不再等 javac。

### 1.3 错误映射仍然成立

构建期编译错误会被 `ViewPrecompiler` 翻译回 `.jblade.html` 的文件名与行号
(用转写时产生的 `LineMap`),不是生成的 Java 行号。测试
`PrecompileTest.maps_compile_error_to_blade_line` 覆盖了这条。

运行期异常的行号来自步骤 4 写出的 metadata:预编译模式下
`JbladeEngine.forPrecompiled` 会把它读回 `LineMap`。

### 1.4 限制

- 预编译插件只处理**应用的 `src/main/resources/views` + 编译 classpath 上的
  `views/`**;运行时动态生成的模板(如有)仍会退回运行时编译;
- 依赖里的视图名如果与应用冲突,应用目录优先(与开发模式一致);
- 增量编译:每次 `process-classes` 会重新扫、重新编译全部模板。对几十个
  模板是毫秒级;上千个模板时需要做 mtime 增量(未做)。

---

## 2. jaravel-maven-plugin

模块:`jaravel-maven-plugin`(第 37 个框架模块,`maven-plugin` packaging)。

### 2.1 `compile-views`

见 §1。参数:

| 参数 | 默认 |
|---|---|
| `jaravel.views.source` | `${project.basedir}/src/main/resources/views` |
| `jaravel.views.output` | `${project.build.outputDirectory}` |
| `jaravel.views.skip` | `false` |

### 2.2 `native-config`

扫描项目编译产物(以及可选的依赖 jar)里带
`@io.jaravel.console.Command` 的类,生成
`META-INF/native-image/<groupId>/<artifactId>/reflect-config.json`:

- `allDeclaredConstructors` —— `CommandRegistry.register(Class)` 的反射构造;
- `allDeclaredFields` —— `ArgumentBinder` / `Usage` 读写 `@Argument` / `@Option`;
- 三个注解类型本身 —— 运行时要 `getAnnotation(...)`。

`jaravel-cli` 用 `scanDependencies=true` 扫到命令类(v0.9 测量时 **22 个**;
v1.0 新增 `new` / `install:auth` 后为 **24 个**);
`jaravel-console` 另有静态的 `resource-config.json`,把 `stubs/*` 打进镜像
(否则 `make:model` 在 native 里会报「没有 stub "model"」)。

### 2.3 为什么没有直接依赖 `jte-maven-plugin`

jte 的插件只认 `.jte` 源目录,而 jblade 的源码是 `.jblade.html`,还要走
转写、行号映射、组件属性袋。两段拼起来意味着用户要配两个插件、管中间目录;
放在 `jaravel-maven-plugin` 里,用户只看见一个 goal。实现上仍复用了 jte 的
`TemplateEngine#precompileAll`,`jaravel-maven-plugin` 自己**不发明**编译器。

---

## 3. GraalVM native

### 3.1 CLI 怎么构建

前置:Linux 环境或 Docker(Windows 主机没有 MSVC,不能直接 native-image)。

```bash
# 1) 构建 CLI 与依赖
mvn -DskipTests install
mvn -pl jaravel-cli dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/native-libs

# 2) 在 GraalVM 容器里编译(注意 --entrypoint sh)
docker run --rm --entrypoint sh \
  -v "$PWD":/work -w /work/jaravel-cli \
  ghcr.io/graalvm/native-image-community:21 \
  -c 'CP="target/jaravel-cli-0.1.0-SNAPSHOT.jar:$(find target/native-libs -name "*.jar" | tr "\n" ":")"; \
      native-image -cp "$CP" -o target/jaravel --no-fallback \
        --initialize-at-build-time=io.jaravel.cli -J-Xmx4g io.jaravel.cli.Jaravel'
```

> `--initialize-at-build-time=io.jaravel.cli` 把 CLI 自身的静态初始化搬到
> 构建期。不加时 `jaravel list` 的中位约 108ms(刚过 100ms);加上后
> **~86ms**。只对 `io.jaravel.cli` 打开 —— 对 `io.jaravel` 整体打开会
> 把 Jackson 的 `ObjectMapper` 也拉进构建期初始化,native-image 会拒绝。

产物是 Linux ELF:`jaravel-cli/target/jaravel`(~24MB)。
Windows 主机上它不能直接执行;用同一个镜像跑测量或发布到 Linux。

### 3.2 实测结果

环境:GraalVM CE 21.0.2(Docker,Linux x86_64),宿主 Windows 11 +
Docker 29。CLI 命令 `list`,容器内预热一次后 n=30:

| 指标 | 结果 |
|---|---|
| `list` 中位启动 | **~86ms** |
| `--version` 中位 | **~87ms** |
| `list` 最小 / 最大 | 71ms / 138ms |
| 二进制 | ~24MB |

测量用 bash 的 `time` 关键字(n=30),不在采样循环里额外 fork `date`。
不加 `--initialize-at-build-time=io.jaravel.cli` 时 `list` 中位约 108ms;
加上后稳定在 100ms 以下。

功能验收:

- `jaravel --version` → `Jaravel 0.1.0-SNAPSHOT (Java 21)`;
- `jaravel list` → 离线命令,分组正确(v0.9 测量时 22 条;v1.0 为 24 条);
- `jaravel help make:model` → 参数/选项表正确;
- `jaravel make:model Post` → 写出 `src/main/java/app/models/Post.java`。

### 3.3 反射审计(CLI 路径)

| 位置 | 用途 | native 处理 |
|---|---|---|
| `CommandRegistry.register(Class)` | 无参构造建命令实例 | `native-config`: `allDeclaredConstructors` |
| `ArgumentBinder` / `Usage` | 读写 `@Argument` / `@Option` 字段 | `native-config`: `allDeclaredFields` |
| `@Command` / `@Argument` / `@Option` | 运行时读注解 | 注解类型注册 |
| `Stub` 资源加载 | `getResourceAsStream("stubs/...")` | `resource-config.json` |
| `TestCommand` | `ProcessBuilder` 调 Maven | 无反射 |
| `OpenApiDiffCommand` | Jackson 读 JSON | 仅在该命令执行时加载;未做 Jackson hints,当前未在 native 里验收 |

**CLI 路径的结论**:命令元数据仍然通过反射读,但所需的反射面已穷尽并
写进镜像配置 —— native 下不依赖「碰巧能跑」。**不是**「零反射」,是
「反射面已审计、已配置」。

### 3.4 范围:只做 CLI

**Web 应用 native 不在当前范围内。** 它需要为应用自己的类生成
反射配置(路由 lambda、模型、查询结果映射、任务/监听器),而目标用户
不需要更快的 Web 冷启动或更省内存的部署 —— 成本与收益不匹配。
CLI 的反射面有限、收益明确,因此 native 交付以 `jaravel-cli` 为准。

---

## 4. 性能基准

### 4.1 模块

`benchmark/jmh`(独立模块,不进主构建):

```bash
mvn -DskipTests install
cd benchmark/jmh && mvn package
java -jar target/jmh-benchmarks.jar -f 1 -wi 5 -i 10 -r 1s
```

四个基准:

| 类 | 量什么 |
|---|---|
| `RouteMatchBenchmark` | 静态命中 / 动态命中 / 300 条动态表上的未命中 |
| `EscapeBenchmark` | jte OWASP 文本转义(纯字符串) |
| `QueryBuildBenchmark` | 查询构造器拼 SQL + 绑定(不执行) |
| `TemplateRenderBenchmark` | 同一模板:运行时编译缓存 vs 构建期预编译 |

### 4.2 结果

最终一轮:`-f 1 -wi 3 -i 5 -r 1s`(单位 ns/op,模板是 µs/op):

| 基准 | 优化前(线性扫描) | 优化后 |
|---|---|---|
| `RouteMatchBenchmark.staticHit` | 58.6 | **57.9** |
| `RouteMatchBenchmark.dynamicHit` | 1604.8 | **610.9** |
| `RouteMatchBenchmark.largeTableMiss` | 14143.3 | **139.5** |
| `EscapeBenchmark.textPlain` | — | **62.8** |
| `EscapeBenchmark.textHostile` | — | **294.0** |
| `EscapeBenchmark.textLong` | — | **9348.6** |
| `QueryBuildBenchmark.simpleSelect` | — | **1355.5** |
| `QueryBuildBenchmark.nestedWhere` | — | **2423.8** |
| `TemplateRenderBenchmark.runtimeCompiled` | — | **1.539 µs** |
| `TemplateRenderBenchmark.precompiled` | — | **1.710 µs** |
| `ThymeleafBenchmark.render` | — | **15.085 µs** |

**结论**:

- 动态命中 **1.60µs → 0.61µs**(约 2.6×),大表未命中
  **14.1µs → 0.14µs**(约 100×);
- 模板渲染 **15.085 / 1.710 ≈ 8.8× Thymeleaf**,超过验收的 3×;
- **预编译没有让单次渲染更快**(1.710 vs 1.539µs,差异在误差内)。
  它的收益是**启动期不编译**、打包后不依赖 javac,而不是吞吐;
  不要把它写成「预编译让渲染更快」。

### 4.3 路由优化做了什么

1. **字面量前缀索引**:`RouteCollection` 按「第一个参数段之前的字面量
   路径」分桶。`/api/items/{id}` 的前缀是 `api/items`;查找时从路径的
   最长前缀往短找,命中的桶里已经合并了更短前缀与「任意前缀」路由,
   并保持注册顺序。
2. **单参数快路径**:`/users/{id}` 这类一个参数、无约束、无可选/通配的
   路由,不再跑正则 —— `startsWith(prefix) + endsWith(suffix)` 加一次
   `substring`。有约束、可选参数、多参数时仍走正则。
3. **消除重复归一化**:`RouteCollection` 已经归一化过一次路径,
   `Route.matchNormalized` 不再对每条候选路由重复 `normalize`。

优先级没有变化:**仍然按注册顺序**。新增的三条测试分别覆盖
「前缀索引不改变注册顺序」「未知首段只查任意前缀桶」「方法隔离」。

### 4.4 端到端吞吐:与裸 Spring MVC 对照

`benchmark/http/` 的两个应用提供同一个 `GET /ping` → JSON:

| 目录 | 实现 | Tomcat |
|---|---|---|
| `bare/` | 裸 Spring MVC `@RestController` | 10.1.59 |
| `jaravel/` | Jaravel 路由 + 控制器 + `Response.json` | 10.1.59 |

压测用 `benchmark/http/LoadGen.java`(JDK HttpClient,64 个阻塞工作线程,
10s),在**宿主机**上跑,避免 Docker Desktop 的网络抖动。每个应用先
预热 10s,再测三次:

| 应用 | 三次(rps) | 中位 |
|---|---|---|
| 裸 Spring MVC | 31,299 / 31,440 / 31,677 | **31,440** |
| Jaravel | 37,797 / 37,527 / 36,158 | **37,527** |

**比值 ≈ 1.19(Jaravel / 裸 MVC)**,验收要求是 ≥ 0.90。

> ⚠ 这是一台 Windows 开发机上的**相对**对照,不是容量结论:
> 只测了一个无数据库、无模板的 JSON 端点;LoadGen 自身也可能在
> 36k~38k 附近成为瓶颈;JIT 已预热。它回答的是「Jaravel 的请求管道
> 有没有比裸 MVC 慢一个数量级」,答案是没有。

### 4.5 不要过度解读

- 以上数字来自单机、单 fork、Windows + Docker,不能当作跨机器结论;
- 模板转义只量了文本上下文;属性/URL/JS 上下文的成本在 jte 内部,
  尚未单列;
- Thymeleaf 对照只量了渲染本身(不含 Spring/Tomcat),与 jblade 的
  模板渲染同一口径;模板语义是等价的 10 项列表;
- 端到端吞吐只覆盖一个 JSON 端点;模板渲染、数据库、中间件的组合
  没有端到端测量。

---

## 5. 安全修复与依赖升级

完整的检查表与修复项在 [SECURITY-AUDIT.md](../SECURITY-AUDIT.md)。
摘要:

- **已修**:登录后回跳的开放重定向(只存相对路径 + `Intended.isSafe`);
  `Redirect.back` 的 Referer 同源校验;CORS「通配来源 + 凭据」启动失败;
  `X-Forwarded-For` 伪造;文件会话在 Windows 并发写下的 `AccessDenied`
  (分片锁 + move 重试);
- **依赖升级**:Spring Boot 3.4.1 → **3.5.16**,AssertJ 3.27.7,
  Testcontainers 1.21.4,jte 3.1.16;
- **同日补丁覆盖**:Tomcat 10.1.59 / Jackson 2.21.8 / Netty 4.1.137.Final /
  RabbitMQ 5.38.0 / Guava 33.8.0-jre / commons-compress 1.28.0 /
  commons-lang3 3.22.0 / log4j-api 2.26.1(见 `jaravel-bom` 与应用 import 顺序);
- **依赖收敛**:`mvn -Psecurity -DskipTests verify`(Maven Enforcer)通过。
