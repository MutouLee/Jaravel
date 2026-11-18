# 认证

Jaravel 的认证是**可选脚手架**:框架提供 guard/provider/hash/password reset/CSRF,
`install:auth` 把用户模型、迁移、控制器、路由和页面写进你的项目。

## 生成

```bash
jaravel install:auth
```

生成:

| 文件 | 说明 |
|---|---|
| `app/models/User.java` | `@Model` + `@AuthModel`;password/rememberToken 带 `@Hidden` |
| `database/migrations/m.../CreateAuthTables.java` | `users`、`password_reset_tokens` |
| `app/http/controllers/AuthController.java` | 注册、登录、登出、忘记密码、重置 |
| `routes/AuthRoutes.java` | 独立的路由定义,不修改你的 `WebRoutes` |
| `resources/views/auth/*.jblade.html` | 登录/注册/忘记密码/重置页面 |
| `config/auth.yml` | guard、provider、密码重置、节流 |
| `config/session.yml` / `hashing.yml` / `mail.yml` | 会话、密码哈希、邮件 |

## 路由

| 方法 | 路径 | 中间件 |
|---|---|---|
| GET/POST | `/login` | `web` + `guest`;POST 带 `throttle:10,1` |
| GET/POST | `/register` | `web` + `guest` |
| GET/POST | `/forgot-password` | `web`;POST 带 `throttle:5,1` |
| GET/POST | `/reset-password` | `web` |
| GET | `/dashboard` | `web` + `auth` |
| POST | `/logout` | `web` + `auth` |

## 配置

`config/auth.yml` 的最小形态:

```yaml
auth:
  default: web
  guards:
    web:
      driver: session
      provider: users
      remember: P30D
  providers:
    users:
      driver: eloquent
      model: app.models.User
  passwords:
    provider: users
    table: password_reset_tokens
    expire: PT1H
    throttle: PT60S
```

API token / JWT guard 与 Gate/Policy 见仓库根目录
[docs/08-auth.md](https://github.com/MutouLee/Jaravel/blob/main/docs/08-auth.md)。

## 密码重置邮件

默认 `MAIL_MAILER=log`:

- 邮件正文(含重置链接)写进应用日志;
- 生产改成 `smtp` 并配置 `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` 等;
- `config/mail.yml` 的 `mailers.log.body: true` 控制是否打印正文。

## 安全默认值

- 密码哈希:PBKDF2-HMAC-SHA512,210,000 次迭代(可用 bcrypt/argon2id);
- 登录后 `Session.regenerate()`,防会话固定;
- CSRF token 常数时间比较,登录/登出轮换;
- 重置 token:32 字节随机、库存 SHA-256、单次有效、过期 + 节流;
- 登录失败按账号 + IP 双维度节流;
- remember token 60 字节随机、库存哈希、登录轮换;
- Cookie:`HttpOnly`、`SameSite=Lax`、非 local 环境 `Secure`。

## 自定义

- 改页面:直接改 `resources/views/auth/*.jblade.html`;
- 改流程:改 `AuthController` / `AuthRoutes`;
- 换 guard:`config/auth.yml`;高安全场景可用 `jaravel-security-bridge`;
- 邮箱验证、二步验证:见 docs/08 §6,目前不在 `install:auth` 生成范围内。

## 测试

生成项目自带 `FeatureTest`;认证接口的测试示例见 `examples/auth`
(`AuthFeatureTest` 覆盖注册/登录/重置/记住我/CSRF/节流)。
