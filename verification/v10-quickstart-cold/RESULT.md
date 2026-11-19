# v10 · 冷环境照 quickstart 跑一遍(计时)

> 对应 [V1.0-RESULT](../../V1.0-RESULT.md) §2 第 1 条:
> 「外部开发者照文档 30 分钟跑通 Todo」。v1.0 当时只能标 ⚠️ ——
> 两条路都是**本项目自己手工走完**的,没有任何计时数据。
>
> 这里补的是**可复算的机器时间**:一个干净容器(空 `~/.m2`、空工作
> 目录、只有 JDK 21 + Maven + git + curl),从 GitHub clone,
> 照 `docs-site/docs/quickstart.md` 一步一步敲,每步计时。
>
> **它不等于「外部开发者 30 分钟」**:脚本不读文档、不思考、不打错字。
> 它回答的是另一个更先决的问题 —— 文档里那串命令在一台没有任何本机
> 状态的机器上**到底能不能跑通,以及机器时间占掉多少预算**。
> 真人计时验收仍然开放(见下面「仍然开放」)。

## 怎么跑

```powershell
# 真·全冷:计时里含第三方依赖下载(强依赖当时带宽)
pwsh verification/v10-quickstart-cold/run-docker.ps1

# 隔离出构建/生成/测试本身:先把一份已有 maven 仓库拷进容器(拷贝不计时),
# 拷完删掉其中的 io/jaravel —— 框架必须从这次 clone 真的构建出来
pwsh verification/v10-quickstart-cold/run-docker.ps1 -SeedM2 "D:\apache-maven-3.9.6\repository"
```

产物:`out/`(全冷)与 `out-seeded/`(每步日志、抓到的页面 HTML、
cookie jar、从应用日志里捞出的重置链接)—— 两者都不入库(`.gitignore`)。
入库的只有两份汇总:[`summary-seeded.txt`](summary-seeded.txt) 与
[`summary-cold.txt`](summary-cold.txt)。

## 结果(2026-11-19,20 核 / 15.5GB / maven:3.9-eclipse-temurin-21)

**13 步全绿,退出码全 0。** quickstart §6 的六条检查表逐条被断言覆盖
(打开页面换成 `curl` + 内容断言,注册/登录/登出/重置走 cookie jar
与表单里的 `_token`,与浏览器同一条路)。

| 步骤 | quickstart | 依赖已缓存 | 全冷(含下载) |
|---|---|---|---|
| 01 clone | §1 | 9s | 7s |
| 02 `mvn -DskipTests install`(45 模块) | §1 | 43s | **2772s** |
| 03 跑 `examples/todo` + 首页断言 | §1 | 9s | 9s |
| 04 构建 CLI + `jaravel --version` | installation.md | 42s | ✗ 网络中断(见下) |
| 05 `jaravel new myapp` | §2 | 1s | — |
| 06 启动生成的项目 + 首页断言 | §2 | 9s | — |
| 07 `jaravel install:auth` | §3 | 0s | — |
| 08 注册→控制台→登出→再登录→重置链接 | §3 | 14s | — |
| 09 `jaravel make:model Post -mc` | §4 | 0s | — |
| 10 `jaravel migrate` | §4 | 7s | — |
| 11 `mvn test`(生成项目自带烟雾测试) | §5 | 7s | — |
| 12 `mvn package` | §6 | 3s | — |
| 13 `java -jar` 启动 + 首页断言 | §6 | 6s | — |
| **合计(从源码,Central 未发布)** | | **150s** | |
| **合计(只算用户步骤 05–13)** | | **47s** | |

「依赖已缓存」那一列是 13 步**全绿**的那次(`out-seeded/`);
「全冷」那一列跑到第 4 步被网络打断(`out/`),原因见下。

「只算用户步骤」是**发布之后**的那条路:artifact 从 Central 取,
CLI 是发布页的二进制,于是 02/03/04 都不需要。今天 Central 还没发布,
所以两个数字都给出来。

### 全冷那一次:瓶颈是下载,而且下载本身会失败

不带 `-SeedM2` 时:

- **02 用了 2772s(46 分钟)**,而同样这一步在依赖已缓存时是 **43s** ——
  也就是说 **98.5% 的时间是下载**。`~/.m2` 最终约 400MB,
  平均不到 150 KB/s。
- 03(跑 `examples/todo`)在全冷下也只用 **9s**,与缓存版一致 ——
  再次确认慢的只有下载。
- **04 直接挂了**,不是产品问题:
  `Could not transfer artifact org.apache.maven.doxia:doxia-logging-api:jar:1.12.0
  from/to central: Remote host terminated the handshake`。
  这条链路上的 TLS 握手在长时间大量下载后被掐断。
  之后 05–13 全部连带失败(没有 CLI → 没有 `myapp` 目录)。

结论:

- quickstart 的 30 分钟预算在带宽正常(≥ 2MB/s)时**宽裕**
  —— 机器时间只有 2.5 分钟;
- 在 150 KB/s 这种链路上,**光下载就吃掉 46 分钟,而且还会中途断**。
  这既不是文档的问题也不是框架的问题,但它是「照文档 30 分钟跑通」
  在真实世界里最常见的失败原因,所以 quickstart 里补了一条提示
  (首次构建会下载数百 MB;慢网络下换镜像或先预热 `~/.m2`)。
- 对**发布后**的用户,02/03/04 这三步根本不存在:
  artifact 从 Central 取、CLI 是发布页的二进制,
  他要付的只有 05–13 的 **47s** 加上一次依赖下载。

原始输出:`out/`(全冷,04 起失败)与 `out-seeded/`(13 步全绿)。

## 顺带发现并修掉的

- **未登录请求的 302 带着一整张开发错误页**。
  `out-seeded/08-auth.guest-dashboard.html` 是 13KB,标题
  `<title>HttpException</title>` —— 因为 `Authenticate.unauthenticated`
  用 `HttpException(302, Location)` 表达重定向,而
  `JaravelExceptionHandler` 把**所有**异常都按「错误」渲染。
  浏览器看不见(它跟着 `Location` 走了),但用 curl / devtools 调试的
  人看见的是一个什么都没崩的崩溃页;生产模式下同样会带一张错误页。
  已改:3xx 的 `HttpException` 走 `Redirect.to(location, status)`,
  body 为空。测试 `AuthFeatureTest.guestRedirectHasNoErrorPageBody`。
  ——**这条只有「真的用 HTTP 客户端走一遍」才会发现**:
  既有测试断言的是状态码与 `Location`,没有人看过那个 body。

- `docs/09` §1.1 整节用 `Cache.get(...)` / `Cache.put(...)` 写 API,
  而代码里**没有** `Cache` 这个门面 —— 只有 `CacheManager` / `Repository`。
  docs/02 §2.3 的门面清单里也列着它。已补
  `io.jaravel.cache.Cache`(+ starter 绑定 + 测试侧重绑 + 两个模块的测试)。
- `docs/09` 的 `Cache.get(key, TypeRef<...>)` 与 `Cache.rememberLocked`
  确实不存在,已就地标 ⚠ 并登记进 [KNOWN-ISSUES](../../KNOWN-ISSUES.md)。

## 仍然开放

- **真人计时**:一个没参与开发的人,照文档从零走一遍并计时。
  机器时间只占 2.5 分钟(依赖已缓存),剩下的全是读文档与手敲 ——
  这条脚本证明不了,也不该假装证明。
- **带宽说明**:quickstart 没提首次下载量,建议补一句(上面已说明理由)。
