# 安全审计(v0.9,内部)

> **日期**:2026-11-18 · **范围**:`net.dobeen.jaravel.*` 的认证、会话、HTTP 边界、
> 文件上传/存储、队列与配置;**方法**:代码审阅 + 现有测试复核 + 依赖升级。
>
> 本文件是**内部安全检查记录**,不是对外合规证明。

---

## 1. 结论

| 级别 | 数量 | 状态 |
|---|---|---|
| 高危(已确认并修复) | 3 | 修复 + 回归测试 |
| 中危(已确认并修复) | 3 | 修复 + 回归测试 |
| 依赖漏洞(高危/严重) | 若干 | 升级 + 显式覆盖,见 §5 |
| 未修复 / 待决 | 见 §6 | 明确列出 |

所有修复都在 v0.9 分支上,并有对应的回归测试;没有「改了但没测」的项。

---

## 2. 已修复的发现

### F-01 · 登录后回跳的开放重定向(中/高)

**位置**:`Authenticate.unauthenticated`、`ConfirmPassword`、示例的
`intendedUrl()`。

**问题**:中间件把 `request.fullUrl()` 存进 `url.intended` —— 那含请求的
`Host` 头。攻击者用 `Host: evil.example` 触发一次跳登录,登录成功后
`Location` 可能指向外部域名。即使 Host 头被容器规范化,**把绝对 URL 存进
会话再原样跳转**也是一个没有必要的信任。

**修复**:

- `Request.pathWithQuery()` 只返回 path + query;
- 新增 `net.dobeen.jaravel.auth.Intended`:`remember()` 只存相对地址;
  `pull(fallback)` 拒绝绝对 URL、`//host`、反斜杠、`%5C`、CR/LF;
- 中间件改用 `Intended.remember`。

**测试**:`IntendedTest`(9 个恶意/合法值)。

### F-02 · `Redirect.back` 的 Referer 开放重定向(中)

**位置**:`net.dobeen.jaravel.http.Redirect.back(Request, String)`。

**问题**:直接 `request.header("Referer").orElse(fallback)`。恶意站点上的
表单 POST 到本站,校验失败后 302 到攻击者站点(钓鱼)。

**修复**:`safeReferer` 只接受相对路径;绝对 URL 仅在 host、scheme、port
全同源时归约成相对路径;其余回落 fallback。

**测试**:`RedirectBackTest`(相对、同源绝对、跨域、协议相对、反斜杠、
协议/端口不一致、无 Referer)。

### F-03 · `X-Forwarded-For` 可伪造,绕过按 IP 的防护(高)

**位置**:`ServletRequestAdapter.clientIp`。

**问题**:无条件取 `X-Forwarded-For` 第一跳。客户端自带这个头就能让
`Request.ip()` 变成任意值 —— 登录节流(`LoginThrottle` 的 per-IP 计数)、
按 IP 的审计与授权都可绕过。

**修复**:只取 `getRemoteAddr()`;部署在反向代理后时使用 Spring Boot 的
`server.forward-headers-strategy=NATIVE|FRAMEWORK`(其信任边界可配置)。

**测试**:`ServletRequestAdapterIpTest` —— 带伪造 XFF 时仍返回 remoteAddr。

### F-04 · CORS「通配来源 + 凭据」会放行任意站点(高,取决于配置)

**位置**:`CorsFilter`。

**问题**:配置 `allowed-origins: ["*"]` + `supports-credentials: true` 时,
filter 回显请求的 `Origin` 并加 `Access-Control-Allow-Credentials: true`。
浏览器会接受(回显具体 Origin 是合法的)—— 任意站点都能带着用户 Cookie
读取响应。注释里写了「`*` 在带凭据时无效」,但实现是回显,不是 `*`。

**修复**:启动时抛 `IllegalStateException`,拒绝该组合
(`allowed-origins` 或 `allowed-origins-patterns` 为 `*`)。

**测试**:`ApiMiddlewareTest.Cors.wildcardOriginWithCredentialsIsRejected`。

### F-05 · 依赖中的已知高危漏洞(高)

**问题**:升级前的依赖树里,Spring Framework 6.2.1、Spring Security 6.4.2、
Tomcat 10.1.34、Spring Boot 3.4.1 等版本存在多条已知高危/严重漏洞
(含 Tomcat 的多个 RCE/DoS 与 Spring WebMVC 的高危项)。

**修复**:Spring Boot **3.4.1 → 3.5.16**(Spring 6.2.19 / Security 6.5.11 /
Tomcat 10.1.55 / Jackson 2.21.4),AssertJ **3.27.0 → 3.27.7**,
Testcontainers **1.21.3 → 1.21.4**。

**复扫**:见 §5。

### F-06 · 文件会话并发写抛 500(中,可用性)

**位置**:`FileSessionStore.write`。

**问题**:Windows 上目标会话文件被另一个线程/进程打开时,`ATOMIC_MOVE`
抛 `AccessDeniedException`;`concurrentWritesAreAtomic` 的写线程实际在
后台抛异常,只是测试没断言写线程。生产症状是并发请求写同一会话时 500,
用户被登出。

**修复**:同 JVM 内按会话 id 分片加锁;`move` 在 `AccessDenied` 时重试
(最多 5 次,退避 10/20/30/40ms),临时文件在失败时清理。

**测试**:同一用例现在同时断言「读到截断内容的次数为 0」与
「写线程异常为 0」。

---

## 3. 已验证的控制

| 控制 | 证据 |
|---|---|
| 密码哈希 | PBKDF2-HMAC-SHA512,**210,000** 次迭代,salt,常数时间比较;Argon2id/bcrypt 可选且**不静默回落** |
| 会话固定 | 登录、remember 恢复、二次确认后都 `Session.regenerate()` |
| CSRF | `VerifyCsrfToken` 用 `MessageDigest.isEqual`;会话 regenerate 同时换 token |
| Cookie | `HttpOnly=true`、`SameSite=Lax` 默认、非 local 环境 `Secure=true`;`SameSite=None` 强制 `Secure` |
| remember me | 60 字节随机、库存哈希、登录轮换、常数时间比较 |
| 密码重置 | 32 字节随机、库存 SHA-256、单次有效、过期 + 60s 节流、常数时间比较、不泄露邮箱是否存在 |
| JWT | 算法由服务端配置决定,拒绝 token 指定 alg / `none`;校验 exp/nbf |
| 登录节流 | 账号维度 + IP 维度;用户不存在时空转哈希,避免时序枚举 |
| 文件上传 | 内容嗅探、路径穿越防护、随机命名;临时 URL 带 `nosniff` |
| SQL | 值全走绑定;标识符走 Grammar 白名单;raw 方法名显式警示 |
| 批量赋值 | `@Model` 的 fillable/guarded |
| CORS | 默认关闭;未配置不添加任何头;来源回显 + `Vary: Origin` |
| 客户端 IP | 不采信 `X-Forwarded-For`(v0.9 修复) |

---

## 4. 回归测试

| 测试 | 覆盖 |
|---|---|
| `IntendedTest` | 回跳地址白名单 |
| `RedirectBackTest` | Referer 同源 |
| `ServletRequestAdapterIpTest` | XFF 不采信 |
| `ApiMiddlewareTest.Cors.*` | CORS 默认关、通配+凭据失败 |
| `FileSessionStoreTest.Robustness.concurrentWritesAreAtomic` | 并发写不截断、不 500 |
| `AuthSecurityTest`(examples/auth, v0.3 起) | docs/08 §7.2 的 12 项安全检查 |
| `JwtTest` / `SessionGuardTest` 等 | alg 混淆、会话固定、remember 轮换 |

---

## 5. 依赖升级与覆盖

下面是本次已经落地的依赖升级与覆盖。

**显式安全覆盖**(`pom.xml` 与 `jaravel-bom`;同一 patch/minor 线):

| 组件 | 覆盖到 | 修复的级别 |
|---|---|---|
| Tomcat embed | 10.1.59 | 多个 critical(auth bypass / HTTP2 SNI) |
| Jackson BOM | 2.21.8 | 多个 high(解析 DoS) |
| Netty BOM | 4.1.137.Final | critical(SNI 绕过)+ high |
| RabbitMQ amqp-client | 5.38.0 | high(ValueReader OOM / JSON-RPC Class.forName) |
| Guava | 33.8.0-jre | high(反序列化) |
| commons-compress / lang3 / log4j-api | 1.28.0 / 3.22.0 / 2.26.1 | moderate |

> 应用侧必须把 `jaravel-bom` 放在 `spring-boot-dependencies` **之前** import
> (示例 pom 已改)。Maven 的同名依赖管理以第一次声明为准;反过来会把
> 这些覆盖顶掉。

---

## 6. 未做 / 待决

| 项 | 状态 | 说明 |
|---|---|---|
| 默认安全响应头 | **部分做了** | `X-Content-Type-Options: nosniff` / `X-Frame-Options: SAMEORIGIN` / `Referrer-Policy: strict-origin-when-cross-origin` 由 `SecurityHeadersFilter` 默认发(docs/03 §2.6)。**CSP 与 HSTS 仍需应用显式配**:框架猜的 CSP 会挡掉应用自己的脚本,而 HSTS 是粘的 —— 在没全站 https 的域上开一次,一部分用户再也打不开站点 |
| 受信代理配置 | 委派给 Spring Boot | 框架不解析 XFF;`server.forward-headers-strategy` 的信任范围由应用配置 |

---

## 7. 审计边界

- 只覆盖本仓库第一方代码与直接依赖,不包含 JDK、容器镜像、数据库引擎;
- 没有做渗透测试、模糊测试、并发压测(除会话写的一条例外);
- 行为性安全属性(「这个输入会返回什么」)依赖现有测试,未做全仓
  行为对账;
- **本文件不是合规证明。**
