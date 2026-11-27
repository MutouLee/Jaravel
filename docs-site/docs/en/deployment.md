# Deployment

## Build

```bash
mvn package
java -jar target/myapp-0.1.0-SNAPSHOT.jar
```

`jaravel-maven-plugin` precompiles jblade templates during `process-classes`,
so the jar does not need a javac at runtime. Even when started from the project
root, the starter recognizes "running from a jar" and picks the precompiled
engine.

## Configuration

Precedence, highest first: command-line `-D` → environment variables → `.env` →
`config/*.yml`.

At minimum, set the following in production:

```bash
APP_ENV=production
APP_KEY=base64:<32 bytes>
APP_URL=https://app.example.com
DB_URL=jdbc:postgresql://...
MAIL_MAILER=smtp
MAIL_HOST=...
SESSION_DRIVER=redis   # required with multiple instances
CACHE_DRIVER=redis     # required with multiple instances
```

When `APP_ENV` is not `local`, session cookies default to `Secure`.

## Multiple instances

| Component | Single instance | Multiple instances |
|---|---|---|
| Sessions | `file` | `redis` / `database` / cookie |
| Cache | `caffeine` | `redis` / `database` |
| Login throttling | in-process | redis / database (shared counters) |
| Scheduler `onOneServer` | local lock | database/redis lock |

Behind a reverse proxy, configure Spring Boot's
`server.forward-headers-strategy`: Jaravel does not parse client-supplied
`X-Forwarded-For` headers itself.

## Health and observability

- Spring Boot Actuator works as usual;
- the development debug toolbar should only be enabled in debug mode;
- the dashboard is enabled only under `app.debug` by default; add `auth` when
  enabling it in production.

## Native

**Native web applications are out of scope for now** — they need
application-level reflection hints, and the cost does not match the benefit
(ADR-019).

The `jaravel-cli` native binary is a different matter: it only implements the
command toolchain. See [Installation](installation.md) for the Linux build.

## Templates

Precompiled template output lives in
`target/classes/gg/jte/generated/precompiled` and `META-INF/jaravel/`. Do not
delete it by hand; run `mvn clean` and rebuild if in doubt.
