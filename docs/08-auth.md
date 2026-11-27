# 08 · 认证与授权

**本模块明确不以 Spring Security 为底座**。Spring Security 的配置复杂度与「给接口加个登录校验」这个需求严重不匹配,是 Java Web 开发体验最大的痛点之一,也正是用户要逃离的东西。见 §8 的决策论证。

---

## 设计目标

1. 「加个登录」这件事的成本 ≤ 3 行代码;
2. 多种认证方式(会话、JWT、API Token)通过统一的 Guard 抽象切换,业务代码不变;
3. 授权用 Gate(简单规则)+ Policy(模型相关规则)两层,与 Laravel 一致;
4. 安全默认值正确:密码哈希强度、会话固定攻击防护、Token 哈希存储、时序攻击防护;
5. 为需要企业级 SSO 的场景提供 Spring Security 桥接,但不作为默认路径。

---

## 1. Guard 抽象

```java
public interface Guard {
    Optional<Authenticatable> user();
    Optional<Object> id();
    boolean check();
    boolean guest();
    boolean validate(Map<String, Object> credentials);     // 只验证,不登录
    void setUser(Authenticatable user);
}

public interface StatefulGuard extends Guard {             // 会话型
    boolean attempt(Map<String, Object> credentials, boolean remember);
    boolean once(Map<String, Object> credentials);
    void login(Authenticatable user, boolean remember);
    Optional<Authenticatable> loginUsingId(Object id, boolean remember);
    void logout();
    void logoutOtherDevices(String password);
    boolean viaRemember();
}
```

### 1.1 内置 Guard

| Guard | 场景 | 凭据来源 |
|---|---|---|
| `SessionGuard` | 传统 Web 应用 | Session + remember cookie |
| `TokenGuard` | 简单 API | `Authorization: Bearer <token>`,token 哈希存库 |
| `JwtGuard` | 无状态 API / 微服务 | JWT(HS256 / RS256 / EdDSA) |
| `SanctumGuard` | SPA + API 混合 | 同源请求走 Session,跨域走 Token |
| `BasicGuard` | 内部工具 | HTTP Basic |

> ### ⚠ v0.4 实现状态:只有前三个
>
> **已实现**:`SessionGuard`、`TokenGuard`、`JwtGuard`。
> **未实现**:`SanctumGuard`、`BasicGuard`。
>
> 两者都会**响亮失败** —— `auth.yml` 里写 `driver: sanctum` 时
> 启动直接报错并列出可用的驱动(见
> `JaravelAuthAutoConfiguration.unknownDriver`),不会静默退回。
>
> docs/17 的 v0.3 范围行写的是「认证:`JwtGuard` + `SanctumGuard`」,
> 而当时只做了前者 —— 此前没有任何地方标注这件事。
>
> **`SanctumGuard` 的难点不在实现而在设计**:它要判定「这个请求算
> 同源吗」,而那个判定(`stateful-domains` 配置)是 Laravel Sanctum
> 最常见的困惑来源 —— 本文 §7 的待决清单里原本就列着这一条。
> 判错的后果是安全问题:把跨域请求当成同源会让 CSRF 防护失效。
> 所以它不该为了「表格里不留空」而草率实现。
>
> **当前的替代**:SPA 用 `JwtGuard` 或 `TokenGuard`
> (`examples/auth` 演示了用会话身份换一个无状态令牌的做法)。

### 1.2 配置

```yaml
# config/auth.yml
auth:
  defaults:
    guard: web
    passwords: users

  guards:
    web:
      driver: session
      provider: users
    api:
      driver: jwt
      provider: users
      jwt:
        algorithm: RS256
        ttl: 2h
        refresh-ttl: 14d
        private-key: ${JWT_PRIVATE_KEY}
        public-key: ${JWT_PUBLIC_KEY}
    admin:
      driver: session
      provider: admins

  providers:
    users:
      driver: eloquent
      model: com.app.models.User
    admins:
      driver: eloquent
      model: com.app.models.Admin
    legacy:
      driver: database
      table: old_users

  passwords:
    users:
      provider: users
      table: password_reset_tokens
      expire: 60m
      throttle: 60s

  password-timeout: 3h
  hashing:
    driver: bcrypt            # bcrypt | argon2id
    bcrypt:
      rounds: 12
    argon2id:
      memory: 65536
      time: 4
      threads: 2
```

代码式配置(等价,供需要动态逻辑者使用):

```java
// AuthServiceProvider#boot
Auth.extend("ldap", (app, name, cfg) -> new LdapGuard(cfg));
Auth.provider("custom", (app, cfg) -> new MyUserProvider());
```

---

## 2. 使用

> **登录后回跳的安全口径(v0.9)**:`url.intended` 只存**相对路径**
> (path + query),不存完整 URL —— 后者会把请求的 Host 头带进会话,
> 登录后的 302 可被外部域名劫持。`Intended.pull(...)` 会再校验一次:
> 绝对 URL、`//host`、带反斜杠或换行的值一律回落到 fallback。

```java
// 登录
if (Auth.attempt(Map.of("email", email, "password", password), remember)) {
    Session.regenerate();                    // 防会话固定攻击
    return Redirect.to(Intended.pull("/dashboard"));
}
return Redirect.back(request, "/login").withInput().withErrors(Map.of("email", "凭据不匹配"));

// 带额外条件
Auth.attempt(Map.of("email", e, "password", p, "status", "active"));

// 其他方式
Auth.login(user);
Auth.loginUsingId(1L);
Auth.once(creds);                            // 单次请求,不写 session
Auth.logout();
Auth.logoutOtherDevices(currentPassword);

// 读取
Optional<User> u = Auth.user(User.class);
User me = Auth.userOrFail(User.class);
Object id = Auth.id();
boolean in = Auth.check();
boolean out = Auth.guest();

// 指定 guard
Auth.guard("admin").check();
Auth.guard("api").user();
Auth.shouldUse("admin");                     // 本请求内切换默认 guard
```

### 2.1 中间件

```java
r.get("/dashboard", DashboardController::index).middleware("auth");
r.get("/admin", AdminController::index).middleware("auth:admin");
r.get("/api/me", MeController::show).middleware("auth:api");
r.get("/login", LoginController::show).middleware("guest");
r.post("/settings", SettingsController::update).middleware("auth", "password.confirm");
r.get("/verify", X::y).middleware("auth", "verified");
```

未认证时的行为:

```java
public class Authenticate extends BaseAuthenticate {
    @Override protected Response unauthenticated(Request req, List<String> guards) {
        return req.expectsJson()
            ? Response.json(Map.of("message", "Unauthenticated.")).status(401)
            : Redirect.guest(Url.route("login"));     // 记录 intended URL
    }
}
```

### 2.2 `Auth.user()` 的上下文约束

`Auth` 是**请求级门面**。在队列任务或控制台命令中调用会抛 `OutsideRequestException`(见 [01-architecture.md](01-architecture.md) §4.4),而不是静默返回 null——这是对 Laravel 的有意改进。

队列任务需要用户上下文:

```java
Queue.dispatch(new GenerateReport(params));           // 自动捕获 Auth.id() 进 payload
// Worker 侧由框架用 JobScope.runAs(userId, ...) 包裹,任务内 Auth.user() 可用
```

---

## 3. UserProvider

```java
public interface UserProvider {
    Optional<Authenticatable> retrieveById(Object id);
    Optional<Authenticatable> retrieveByToken(Object id, String token);
    void updateRememberToken(Authenticatable user, String token);
    Optional<Authenticatable> retrieveByCredentials(Map<String, Object> credentials);
    boolean validateCredentials(Authenticatable user, Map<String, Object> credentials);
    void rehashPasswordIfRequired(Authenticatable user, Map<String, Object> credentials);
}
```

### 3.1 EloquentUserProvider

```java
@Model(table = "users")
public class User extends Model<User> implements Authenticatable {
    @Id Long id;
    String email;
    @Hidden String password;
    @Hidden String rememberToken;

    @Override public Object getAuthIdentifier()       { return id; }
    @Override public String getAuthPasswordName()     { return "password"; }
    @Override public String getAuthPassword()         { return password; }
    @Override public String getRememberToken()        { return rememberToken; }
}
```

多数情况下 `Authenticatable` 的实现由 APT 根据 `@Model` + `@AuthModel` 自动生成,用户只需:

```java
@Model(table = "users")
@AuthModel(identifier = "id", password = "password")
public class User extends Model<User> { ... }
```

同一套机制也覆盖二步验证:`@AuthModel` 里写出
`twoFactorSecret` / `twoFactorEnabled` / `twoFactorRecoveryCodes` / `account`
四个列名,模型侧就只留字段(见 §6.4)。

### 3.2 时序攻击防护

`retrieveByCredentials` 找不到用户时,**仍然执行一次哈希校验**(用一个固定的 dummy hash),使响应时间与「用户存在但密码错」一致,防止通过响应时间枚举已注册邮箱。这是 Laravel 做了的事,必须照做。

---

## 4. 哈希

```java
String hash = Hash.make("secret");
String hash2 = Hash.make("secret", Map.of("rounds", 14));
boolean ok = Hash.check("secret", hash);
boolean needsRehash = Hash.needsRehash(hash);         // 配置的 cost 提高后自动升级
HashInfo info = Hash.info(hash);
```

- 默认 `bcrypt` cost 12(2026 年的合理值,随硬件演进需定期复核);
- 支持 `argon2id`(OWASP 当前首选);
- 登录成功后若 `needsRehash()`,自动用新参数重哈希并保存(透明升级);
- 实现用 Spring Security Crypto 的 `BCryptPasswordEncoder` / `Argon2PasswordEncoder` —— **这是一个独立的 crypto 库,不引入 Spring Security 的 Filter 链和配置体系**,只是复用经过审计的哈希实现。不自己写密码学代码。

---

## 5. 授权:Gate 与 Policy

### 5.1 Gate(简单规则)

```java
// AuthServiceProvider#boot
Gate.define("view-admin", (User u) -> u.isAdmin());
Gate.define("update-post", (User u, Post p) -> u.getId().equals(p.getUserId()));
Gate.define("delete-post", (User u, Post p) -> u.isAdmin() || p.getUserId().equals(u.getId()));

// 使用
Gate.allows("update-post", post);
Gate.denies("update-post", post);
Gate.authorize("update-post", post);          // 失败抛 AuthorizationException → 403
Gate.forUser(otherUser).allows("update-post", post);
Gate.any(List.of("update-post", "delete-post"), post);
Gate.none(List.of(...), post);

// 带原因的响应
Gate.define("publish", (User u, Post p) ->
    p.isComplete() ? Allow.allow() : Allow.deny("文章尚未完成", 422));

// 前置/后置拦截
Gate.before((User u, String ability) -> u.isSuperAdmin() ? Optional.of(true) : Optional.empty());
Gate.after((u, ability, result, args) -> ...);
```

### 5.2 Policy(模型相关规则)

```java
@Policy(Post.class)
public class PostPolicy {
    public boolean viewAny(User u)             { return true; }
    public boolean view(User u, Post p)        { return p.isPublished() || owns(u, p); }
    public boolean create(User u)              { return u.isVerified(); }
    public boolean update(User u, Post p)      { return owns(u, p); }
    public boolean delete(User u, Post p)      { return owns(u, p) || u.isAdmin(); }
    public boolean restore(User u, Post p)     { return u.isAdmin(); }
    public boolean forceDelete(User u, Post p) { return u.isSuperAdmin(); }

    /** 对所有 ability 生效的前置钩子 */
    public Optional<Boolean> before(User u, String ability) {
        return u.isSuperAdmin() ? Optional.of(true) : Optional.empty();
    }

    private boolean owns(User u, Post p) { return u.getId().equals(p.getUserId()); }
}
```

`@Policy(Post.class)` 由 APT 生成索引并自动注册(无需手动在 provider 里写映射表)。APT 同时校验:policy 方法的第二个参数类型必须是 `Post`。

### 5.3 调用方式

```java
// 1. 中间件(路由级,最简洁)
r.put("/posts/{post}", PostController::update).middleware("can:update,post");
r.post("/posts", PostController::store).middleware("can:create," + Post.class.getName());

// 2. 注解(控制器级)
@Can("update, post") public Response update(Post post, UpdatePostRequest req) { ... }

// 3. 显式(业务层)
Gate.authorize("update", post);

// 4. 通过用户对象
Auth.userOrFail(User.class).can("update", post);
user.cannot("delete", post);

// 5. FormRequest 的 authorize()
@Override public boolean authorize() { return Gate.allows("create", Post.class); }

// 6. 模板中
@can('update', post) <a href="...">编辑</a> @endcan
```

### 5.4 角色与权限(可选扩展)

Jaravel 核心**只提供 Gate/Policy**,不内置 RBAC 表结构(与 Laravel 一致)。官方提供 `jaravel-permissions` 扩展包(对应 `spatie/laravel-permission`):

```java
user.assignRole("editor");
user.givePermissionTo("publish-posts");
user.hasRole("editor");
user.hasPermissionTo("publish-posts");

r.get("/admin", X::y)
 .middleware("web", "auth", "role:admin", "permission:view-dashboard");
```

> ### v0.7:已实现(`jaravel-permissions`)
>
> **两种用法**:
>
> ```java
> // 1. 模型 implements HasPermissions —— 默认方法,无需写一行权限代码
> public class User extends UserBase implements HasPermissions {
>     @Id public Long id;      // public id 是默认方法取主键的约定
> }
> user.assignRole("editor");
>
> // 2. 不改模型,用静态门面
> Permissions.assignRole(user, "editor");
> Permissions.givePermissionTo(user, "publish-posts");
> ```
>
> 主体可以是有 public `id` 的模型、{@code Authenticatable} 包装体
> (`Auth.user()`),或显式 `(modelType, modelId)`。type 默认是**类名** ——
> 两个用户类型不会互相继承角色。
>
> **中间件**:`role:admin,editor` 与 `permission:publish-posts` 都是
> **any** 语义;`auth` 必须先跑(未登录这里返回 401,不是 403)。
> 基于会话的认证还要把 `web`(StartSession)放在最前 —— 少了它的症状是
> 500 `NoSessionException`,不是 401;`DefaultKernel.priority()` 只固定
> 已知中间件的相对顺序,不会替你补上没声明的 `StartSession`。
> `permission` 会同时看直接授予与角色继承。
>
> **存储**:
> - `DatabasePermissionStore`(默认):五张表,多实例共享;
> - `ArrayPermissionStore`:进程内,测试/单实例 —— 重启即丢,
>   starter 退回它时**明确警告**。
>
> 建表走应用自己的迁移:
>
> ```java
> public class CreatePermissionTables implements Migration {
>     public void up()   { PermissionSchema.create(); }
>     public void down() { PermissionSchema.drop(); }
> }
> ```
>
> **`assignRole` / `givePermissionTo` 会自动创建不存在的角色/权限** ——
> 拼错会多一个没人拥有的角色,但「加个角色」不需要先跑 seeder。
> 在意拼写就显式 `Permissions.createRole("editor", "publish-posts")`。
>
> **还没做**:Gate/Policy 的自动桥接(权限名不会自动变成 Gate ability,
> 需要自己 `Gate.define` 或走 `hasPermissionTo`)、团队/多租户作用域、
> 权限列表缓存、管理 UI。示例:`examples/auth` 的
> `PermissionFeatureTest`(4 条,真实 HTTP + SQLite 权限表)。

---

## 6. 其他能力

### 6.1 密码重置

```java
// 发送重置链接
Password.broker().sendResetLink(Map.of("email", email));
// 重置
Password.broker().reset(credentials, (user, newPassword) -> {
    user.setPassword(Hash.make(newPassword));
    user.save();
    Event.dispatch(new PasswordReset(user));
});
```

Token 哈希后存库,带过期与节流。

### 6.2 邮箱验证

```java
@Model(table = "users")
@MustVerifyEmail
public class User extends Model<User> { ... }

r.get("/verify/{id}/{hash}", VerificationController::verify).middleware("auth", "signed", "throttle:6,1");
```

验证链接用 `Url.signedRoute()` 签名,防篡改。

### 6.3 二次密码确认

```java
r.post("/settings/danger", X::y).middleware("auth", "password.confirm");
```

### 6.4 二步验证(TOTP)

```java
user.enableTwoFactor();                  // 生成 secret + 恢复码
String qr = user.twoFactorQrCodeSvg();
boolean ok = user.confirmTwoFactor(code);
```

列入 v0.6。

> ### v0.6:原语 + 用户侧流程已实现(19 个测试)
>
> `net.dobeen.jaravel.auth.twofactor`:
>
> ```java
> String secret = Totp.generateSecret();                 // 20 字节 → Base32
> String uri = Totp.otpauthUri("MyApp", "tom@example.com", secret);
> boolean ok = Totp.verify(secret, userInput);           // 默认 ±1 个 30 秒窗口
>
> List<String> codes = RecoveryCodes.generate();         // ABCDE-FGHIJ × 10
> List<String> stored = codes.stream().map(RecoveryCodes::hash).toList();
> ```
>
> - 默认 **SHA-1 / 6 位 / 30 秒** —— 与主流验证器客户端一致,不做配置界面;
> - 所有方法都收 `Instant`:测试可以用 RFC 6238 的固定时间点,
>   窗口边界与跨步长才测得到;
> - 校验用常数时间比较;恢复码只存 SHA-256,输入接受大小写/连字符差异。
>
> 用户侧的七个访问器**不用写** —— 列名写进 `@AuthModel`,provider 包装模型时
> 就把它们补齐了(与 `Authenticatable` 同一套机制):
>
> ```java
> @Model
> @AuthModel(twoFactorSecret = "two_factor_secret",
>            twoFactorEnabled = "two_factor_enabled",
>            twoFactorRecoveryCodes = "two_factor_recovery_codes",
>            account = "email")                       // 二维码里显示的账号标签
> public class User extends UserBase {
>     public String twoFactorSecret;                  // 模型侧只有字段
>     public boolean twoFactorEnabled;
>     @Cast.Use(AsList.class)
>     public List<String> twoFactorRecoveryCodes = List.of();
> }
>
> var setup = Auth.twoFactor().enableTwoFactor("记账本");   // 当前用户
> Auth.userOrFail(User.class).save();                      // 这里不替你落库
>
> boolean ok = Auth.twoFactor().confirmTwoFactor(code);     // 确认后 enabled=true
> TwoFactorAuthenticatable.of(user).verifyTwoFactor(code);  // 任意一个模型
> ```
>
> 这一段是被 parity 门禁逼出来的:第一版要求模型实现那个接口,
> 于是 `examples/auth` 的 `User` 上多了七个样板访问器 ——
> 切片 03 的比值从 1.28 跳到 1.46,而样板正是这个框架要消灭的东西。
>
> 存储形状仍然由应用决定(与 `Authenticatable` 同一个取舍);
> 形状特殊(比如密钥在另一张表)时照样可以让模型自己实现
> `TwoFactorAuthenticatable` —— `of(...)` 优先认模型自己的实现。
> `useRecoveryCode` 会**删掉用过的哈希** —— 同一个码不能用两次。
>
> `@AuthModel` 里写了列名却拼错时**启动即报错**(与 `password` 同级):
> 症状否则会是「二维码扫了但验证码永远不对」,那比启动失败难查得多。
> 一个列都没写、却有待验证挑战时,`TwoFactorChallenge.complete` **报错**
> 而不是返回「验证码不对」—— 静默当成验证失败的话,用户看到的是
> 「验证码不正确」,于是没人会去看代码。
>
> ### 二维码与挑战步骤:两件都做了(后补)
>
> ```java
> String svg = Auth.twoFactor().twoFactorQrCodeSvg("记账本");    // 内联进模板
> ```
>
> **自己写了一个二维码编码器**(`QrCode`,字节模式 / 纠错 M / 版本 1–10)。
> 原先登记「不做」的理由是「不引入一整个 QR 实现」—— 那只对了一半:
> 不该引的是一个覆盖二十多种条码格式的库,而这里要的是它的一个子集,
> 有完整规范(ISO/IEC 18004)、输入固定、**可被机器验证**。
>
> 最后一条是做这个决定的前提。手写编码器最危险的失败形态是「错得很细」:
> 掩码罚分算错一位、交织顺序差一个码字 —— 图看起来完全正常,而用户扫不出来,
> 那种缺陷不会有人报告,只会被当成「这个二维码不好扫」。所以测试里用
> **zxing 把生成的码解回来**比对原文,而 zxing 只在 `test` 作用域 ——
> 运行时依然零依赖。它当场抓住了一个真错:版本 7 起对齐图案有三个中心,
> 而第一版用「这格被占了就跳过」做近似,于是落在 timing 线上的两个
> 该画的图案被误跳 —— 短内容全对、长内容(也就是真实的 otpauth URI)全错。
>
> **返回 SVG 而不是 PNG**:SVG 内联进 HTML 就不需要一条返回图片的路由,
> 而那条路由必须鉴权(二维码里是 TOTP 密钥,等同于密码)——
> 一条忘了鉴权的图片路由是一个密钥泄露接口,而内联的东西不可能忘。
>
> 超出版本 10(213 字节)时**报错**而不是自动换更高纠错级别:
> 输入是我们自己生成的 URI,长度可预期;悄悄换编码会让「同一个账号两次
> 生成的二维码不一样」。
>
> ```java
> // 控制器:密码对了,但这个账号开了二步验证
> if (user.twoFactorEnabled) {
>     TwoFactorChallenge.start(user, remember);
>     return Redirect.to("/two-factor");          // 还没登录
> }
> Auth.login(user, remember);
> ```
>
> **挑战步骤**由 `TwoFactorChallenge` 提供状态机(发起 / 待验证用户 / 完成 /
> 放弃),UI 仍然是应用的 —— `examples/auth` 里有一套可照抄的页面与路由。
> 三个要点:
>
> - **★ 挑战期间没有登录。** 会话里只有一个待验证的用户 id,`Auth.check()`
>   仍然是 false。反过来(先登录再要求补验证码)的话,任何一个忘了挂中间件的
>   路由都是绕过口 —— 而忘了挂一定会发生;没登录的话,绕过的后果只是看到登录页;
> - **状态在会话里,不在缓存里。** 挑战属于**这个浏览器**。按用户 id 存缓存的
>   实现里,A 在手机上发起的挑战能被 B 在另一台设备上完成(只要 B 拿到了码,
>   而 TOTP 码会被钓鱼)。`jaravel-auth-tests` 里有一条「另一个会话完不成它」;
> - **⚠ 挑战页自己挂节流。** 6 位数字只有一百万种,`throttle:5,1` 是调用方的事
>   —— 框架不替你挂,但示例的路由上写着,而且注释说了为什么。
>
> 恢复码通过之后**调用方必须把用户存回库**(`useRecoveryCode` 只是把哈希从
> 模型的列表里删掉)。解包用 `principal.as(User.class)` 而**不是**强转:
> guard 里的主体是 provider 包装过的 `Authenticatable`,而 `@AuthModel` 的模型
> 并不实现那个接口 —— 示例里真写错过一次,表现是一个 500 与一个指向框架内部
> 类名的 ClassCastException。

### 6.5 API Token(TokenGuard)

```java
NewToken t = user.createToken("mobile-app", List.of("posts:read", "posts:write"));
String plain = t.plainText();            // 只在创建时可见,库里存 SHA-256
user.tokens().get();
user.currentToken().can("posts:write");
user.tokens().where(Token_.NAME, "mobile-app").delete();

r.get("/api/posts", X::y).middleware("auth:api", "ability:posts:read");
```

### 6.6 限流

```java
r.post("/login", LoginController::store).middleware("throttle:5,1");   // 每分钟 5 次

// 自定义限流器
RateLimiter.for_("login", req ->
    Limit.perMinute(5).by(req.input("email").orElse(req.ip()))
         .response(r -> Response.json(Map.of("message", "太频繁了")).status(429)));
```

登录失败节流默认开启(Laravel 的 `ThrottlesLogins` 等价),按 `email + IP` 组合计数。

---

## 7. 内部架构

```
jaravel-auth
├── AuthManager                 guard 工厂与缓存(请求级)
├── guards/
│   ├── SessionGuard            Session + remember cookie + 会话固定防护
│   ├── TokenGuard              Bearer token,SHA-256 比对,constant-time
│   ├── JwtGuard                JWT 签发/校验,基于 nimbus-jose-jwt
│   ├── SanctumGuard            同源 → Session,跨域 → Token
│   └── BasicGuard
├── providers/
│   ├── EloquentUserProvider
│   └── DatabaseUserProvider
├── Authenticatable (interface)
├── hashing/   BcryptHasher · Argon2Hasher(委托 spring-security-crypto)
├── access/
│   ├── Gate                    ability → 闭包/Policy 方法的注册表
│   ├── PolicyRegistry          @Policy APT 索引
│   └── Response/Allow          带原因的授权结果
├── passwords/  PasswordBroker · TokenRepository
├── middleware/ Authenticate · Authorize · EnsureEmailVerified · RequirePassword
│                · ThrottleRequests · ValidateSignature
└── events/     Login · Logout · Failed · Lockout · Registered · PasswordReset
```

### 7.1 Guard 的请求级缓存

`Auth.user()` 在一次请求内多次调用只查库一次(缓存在 `RequestScope`)。这是必须的——否则模板里每个 `@auth` 都会查库。

### 7.2 安全检查清单(v0.3 已验收)

实现状态见 `jaravel-auth-tests` 的 `AuthSecurityTest` —— 每一条对应一个
同名的嵌套类(`C01_SessionRegeneration` … `C12_PasswordNeverSerialized`),
编号与下表严格一致。共 47 条断言。

| # | 条目 | 状态 | 测试类 |
|---|---|---|---|
| ① | 登录成功后 `Session.regenerate()`(防会话固定) | ✅ | `C01_SessionRegeneration` |
| ② | remember token 随机 60 字节,哈希存库,登出时失效 | ✅ | `C02_RememberToken` |
| ③ | API token 明文只在创建时返回,库中存 SHA-256 | ✅ | `C03_ApiTokenStorage` |
| ④ | token 比对用 `MessageDigest.isEqual`(constant-time) | ✅ | `C04_ConstantTimeComparison` |
| ⑤ | 用户不存在时仍执行 dummy 哈希(防时序枚举) | ✅ | `C05_TimingAttackOnMissingUser` |
| ⑥ | 登录失败节流默认开启 | ✅ | `C06_LoginThrottling` |
| ⑦ | 密码重置 token 单次有效 + 过期 + 节流 | ✅ | `C07_PasswordResetToken` |
| ⑧ | JWT 拒绝 `alg: none`,拒绝算法混淆 | ✅ | `C08_JwtAlgorithmAttacks` |
| ⑨ | 签名 URL:HMAC-SHA256 + 过期 + constant-time 比对 | ✅ | `C09_SignedUrls` |
| ⑩ | `logout()` 清空 session 并重新生成 CSRF token | ✅ | `C10_Logout` |
| ⑪ | cookie 默认 `HttpOnly` + `Secure`(非 local)+ `SameSite=Lax` | ✅ | `C11_CookieFlags` |
| ⑫ | `@Hidden` 的 password 绝不出现在任何 JSON 序列化中 | ✅ | `C12_PasswordNeverSerialized` |

**写这组测试时发现并修掉的两个真实缺口**(都在第 ② 条):

1. remember 令牌只有 32 字节,而清单要求 60 —— 且
   `AbstractUserProvider` 的注释已经声称是 60 字符。
2. 令牌**明文存库**。任何只读的数据库泄露(SQL 注入、备份文件、
   只读账号)都直接产出一批可用的登录凭据 —— 攻击者不需要密码,
   拼个 `id|token` 的 cookie 就是那个用户。而 remember 令牌的有效期
   以月计,危害比 API token 更大。

   修复:新增 `RememberToken`(生成 / SHA-256 哈希 / 定时安全比对),
   写入端存摘要,读取端哈希后比对。**升级影响**:库里已有的
   `remember_token` 全部失效,受影响用户需重新登录一次。
   「同时接受明文与哈希」等于没修,所以没有兼容路径。

第 ④ 条按**字节码**断言(检查这些类是否真的引用了
`MessageDigest.isEqual`)而不是测耗时:定时攻击的差异在纳秒级,
在 JIT + GC 的 JVM 上写「跑 1000 次比平均耗时」只会得到一个随机失败的
flaky 测试,比不测更糟。

---

## 8. 为什么不用 Spring Security(架构决策)

**决策:自研认证授权,仅复用 `spring-security-crypto` 的哈希实现。**

### 8.1 问题

Spring Security 的设计目标是「覆盖所有企业认证场景的可扩展框架」,代价是:

1. **配置复杂度与需求不匹配**。给一个接口加登录校验,需要理解 `SecurityFilterChain`、`AuthenticationManager`、`UserDetailsService`、`AuthenticationProvider`、`SecurityContextHolder`、`GrantedAuthority`、`RequestMatcher` 的交互关系。Laravel 只需 `->middleware('auth')`。
2. **Filter 链无法按路由绑定**。Spring Security 按 URL pattern 匹配,无法表达「同一 URL 的 GET 公开、POST 需要权限」,也无法访问已匹配的路由参数(`can:update,post` 需要路由参数 `post`)。
3. **授权模型与 Policy 不匹配**。`@PreAuthorize("hasPermission(#post, 'update')")` 使用 SpEL 字符串,无类型安全、无 IDE 支持、运行时解析。
4. **与 Jaravel 的请求作用域冲突**。`SecurityContextHolder` 基于 `ThreadLocal`,与 Jaravel 的 `ScopedValue` 请求作用域是两套上下文,双重维护必然出现不一致。
5. **错误信息不可控**。要把 401/403 的响应体改成 Jaravel 统一格式,需要定制 `AuthenticationEntryPoint` 和 `AccessDeniedHandler`,又是两个概念。

### 8.2 自研的成本与收益

| 项 | 评估 |
|---|---|
| 工作量 | 约 8000~10000 行(5 个 Guard + Gate/Policy + 中间件 + 密码重置) |
| 技术风险 | **中**。认证是安全敏感代码,自研意味着承担安全责任 |
| 缓解 | ① 密码哈希、JWT 签名等密码学原语一律用成熟库(`spring-security-crypto`、`nimbus-jose-jwt`),绝不自己实现;② §7.2 的安全清单逐条测试;③ 每次发布前按 §7.2 清单复核 |
| 收益 | 「加个登录 = 3 行」这个体验是 Jaravel 存在的核心理由之一 |

### 8.3 桥接方案(不放弃企业场景)

`jaravel-security-bridge` 提供双向桥接,用于需要 SAML / OIDC Provider / 企业 SSO / 复杂 OAuth2 的场景:

```java
// 让 Jaravel 的 Auth 读取 Spring Security 的认证结果
// config/auth.yml
auth:
  guards:
    web:
      driver: spring-security
      provider: users
```

此时 `Auth.user()` 从 `SecurityContextHolder` 取,`Gate`/`Policy` 照常工作。企业可以用 Spring Security 做认证(接 SSO),用 Jaravel 做授权(Policy 更好用)。

**这个桥接必须在 v0.3 与自研 Guard 同时交付**,否则 Jaravel 会被判定为「玩具框架,无法用于企业项目」。

见 ADR-006。

---

## 9. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| `auth()->user()` | 请求外返回 null | 抛 `OutsideRequestException` | 消除静默 bug |
| Policy 注册 | provider 里写映射数组 | `@Policy(Post.class)` + APT 自动注册 | 减少样板,编译期校验 |
| Gate 闭包 | `function ($user, $post)` | `(User u, Post p) -> ...` 带泛型 | 类型安全 |
| Sanctum/Passport | 独立包 | `SanctumGuard` 内置;**OAuth2 授权服务器不做**(所有者决定,2026-11-25)—— 它是一个独立产品(授权/令牌端点、四种 grant、同意页、密钥轮换、客户端管理),不是一个 guard。真要发 OAuth2 令牌时,Jaravel 骑在 Spring Boot 上(ADR-001)—— 直接并排跑 **Spring Authorization Server**,用 `jaravel-security-bridge` 对接 | 核心需求内置 |
| 二步验证 | Fortify 包 | 内置(含二维码编码器与挑战状态机) | 二维码不引库:手写一个字节模式/纠错 M 的编码器,用 zxing 在测试里解回来验证(§6.4) |
| Spring Security | — | 可选桥接 | 企业场景必需 |

---

## 10. 待决问题

- [ ] `Authenticatable` 是否强制用户实现?倾向用 `@AuthModel` 注解 + APT 生成实现,用户零样板。
- [ ] JWT 的刷新机制:黑名单(需存储)vs 短 TTL + refresh token(无状态)。倾向后者为默认,黑名单作为可选(`jwt.blacklist.enabled`)。
- [ ] `SanctumGuard` 的同源判定逻辑(`stateful-domains` 配置)需仔细设计,这是 Laravel Sanctum 的常见困惑点。
- [ ] `can:update,post` 中间件里的 `post` 如何解析成模型实例?依赖隐式模型绑定已完成(中间件优先级必须让 `SubstituteBindings` 在 `Authorize` 之前)。需在 `priority()` 中硬编码此顺序并写测试。
- [ ] 安全审计的时机与预算。建议 v0.9 前委托第三方做一次,费用列入项目成本。
