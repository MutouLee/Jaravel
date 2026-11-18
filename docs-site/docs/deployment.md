# 部署

## 构建

```bash
mvn package
java -jar target/myapp-0.1.0-SNAPSHOT.jar
```

`jaravel-maven-plugin` 在 `process-classes` 预编译 jblade 模板,
打出的 jar 不依赖运行时 javac。即使从项目根目录启动,
starter 也会识别「从 jar 运行」并选择预编译引擎。

## 配置

优先级从高到低:命令行 `-D` → 环境变量 → `.env` → `config/*.yml`。

生产至少设置:

```bash
APP_ENV=production
APP_KEY=base64:<32 字节>
APP_URL=https://app.example.com
DB_URL=jdbc:postgresql://...
MAIL_MAILER=smtp
MAIL_HOST=...
SESSION_DRIVER=redis   # 多实例必须
CACHE_DRIVER=redis     # 多实例必须
```

`APP_ENV` 不是 `local` 时,会话 cookie 默认 `Secure`。

## 多实例

| 组件 | 单实例 | 多实例 |
|---|---|---|
| 会话 | `file` | `redis` / `database` / cookie |
| 缓存 | `caffeine` | `redis` / `database` |
| 登录节流 | 进程内 | redis / database(共享计数) |
| 调度 `onOneServer` | 本地锁 | 数据库/redis 锁 |

反向代理后面请用 Spring Boot 的 `server.forward-headers-strategy`:
Jaravel 不解析客户端自带的 `X-Forwarded-For`。

## 健康与可观测

- Spring Boot Actuator 照常可用;
- 开发期调试栏只应在 debug 下开;
- dashboard 默认只在 `app.debug` 下开;生产开时加 `auth`。

## native

**Web 应用 native 不在当前范围内** —— 需要应用级反射 hints,
成本与收益不匹配(ADR-019)。

`jaravel-cli` 的 native 二进制是另一回事:它只做命令工具链,
Linux 构建见 [安装](installation.md)。

## 模板

模板预编译产物在 `target/classes/gg/jte/generated/precompiled` 与
`META-INF/jaravel/`。不要手工删除;`mvn clean` 后重新构建即可。
