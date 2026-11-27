# Authentication

Authentication in Jaravel is an **optional scaffold**: the framework supplies
guards, providers, hashing, password reset and CSRF, and `install:auth` writes
the user model, migration, controller, routes and pages into your project.

## Generate

```bash
jaravel install:auth
```

It generates:

| File | Description |
|---|---|
| `app/models/User.java` | `@Model` + `@AuthModel`; password/rememberToken carry `@Hidden` |
| `database/migrations/m.../CreateAuthTables.java` | `users`, `password_reset_tokens` |
| `app/http/controllers/AuthController.java` | register, log in, log out, forgot password, reset |
| `routes/AuthRoutes.java` | a separate route definition; your `WebRoutes` is left untouched |
| `resources/views/auth/*.jblade.html` | login / register / forgot-password / reset pages |
| `config/auth.yml` | guard, provider, password reset, throttling |
| `config/session.yml` / `hashing.yml` / `mail.yml` | session, password hashing, mail |

## Routes

| Method | Path | Middleware |
|---|---|---|
| GET/POST | `/login` | `web` + `guest`; POST is `throttle:10,1` |
| GET/POST | `/register` | `web` + `guest` |
| GET/POST | `/forgot-password` | `web`; POST is `throttle:5,1` |
| GET/POST | `/reset-password` | `web` |
| GET | `/dashboard` | `web` + `auth` |
| POST | `/logout` | `web` + `auth` |

## Configuration

The minimal form of `config/auth.yml`:

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

For API token / JWT guards and Gate/Policy, see
[docs/08-auth.md](https://github.com/MutouLee/Jaravel/blob/main/docs/08-auth.md)
at the repository root.

## Password reset email

`MAIL_MAILER=log` is the default:

- the message body (including the reset link) is written to the application log;
- in production switch to `smtp` and set `MAIL_HOST` / `MAIL_PORT` /
  `MAIL_USERNAME`, etc.;
- `mailers.log.body: true` in `config/mail.yml` controls whether the body is
  printed.

## Security defaults

- Password hashing: PBKDF2-HMAC-SHA512, 210,000 iterations (bcrypt/argon2id are
  available);
- `Session.regenerate()` after login, to prevent session fixation;
- CSRF tokens are compared in constant time and rotated on login/logout;
- Reset tokens: 32 random bytes, stored as SHA-256, single use, with expiry and
  throttling;
- Failed logins are throttled by **both** account and IP;
- Remember tokens: 60 random bytes, stored hashed, rotated on login;
- Cookies: `HttpOnly`, `SameSite=Lax`, `Secure` outside local environments.

## Customizing

- Change the pages: edit `resources/views/auth/*.jblade.html` directly;
- Change the flow: edit `AuthController` / `AuthRoutes`;
- Change the guard: `config/auth.yml`; for high-security scenarios use
  `jaravel-security-bridge`;
- Email verification and two-factor authentication: see docs/08 §6. They are
  not part of what `install:auth` generates today.

## Testing

A generated project ships with `FeatureTest`; for authentication endpoint tests
see `examples/auth` (`AuthFeatureTest` covers register/login/reset/remember
me/CSRF/throttling).
