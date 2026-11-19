# 快速开始(30 分钟)

目标:在 30 分钟内跑起来一个页面 / JSON 接口,再加一个认证页面。

## 0. 前置

- JDK **21+**
- Maven **3.9+**
- 一个空目录(不要放在已有 Maven 工程的子目录里)

!!! tip "首次构建会下载约 400MB 依赖"
    Maven 本地仓库是空的时候,下面每一步里真正花时间的是**下载**,
    不是编译:实测依赖已缓存时整条路(clone → 构建框架 → 跑示例 →
    `new` → `install:auth` → `migrate` → `test` → `package`)的机器时间
    **约 2.5 分钟**;而在 150KB/s 的链路上,同样这条路里光「构建框架」
    一步就是 **46 分钟**,并且可能因为 TLS 被掐断而中途失败。
    网络不好时先换一个 Maven 镜像(`~/.m2/settings.xml`)再开始。
    计时与复现脚本见
    [`verification/v10-quickstart-cold`](https://github.com/MutouLee/Jaravel/tree/main/verification/v10-quickstart-cold)。

先安装 `jaravel` CLI(见 [安装](installation.md)):

```bash
jaravel --version
```

## 1. 先跑现成示例(2 分钟)

从源码仓库跑 Todo 示例,确认环境没问题:

```bash
git clone https://github.com/MutouLee/Jaravel.git
cd Jaravel
mvn -q -DskipTests install
mvn -pl examples/todo spring-boot:run
```

打开 <http://localhost:8000>。这就是 v0.1 的垂直切片:
路由 + jblade 页面 + 查询构造器 CRUD,零裸 SQL 的博客/待办写法。

## 2. 新建自己的项目(3 分钟)

```bash
jaravel new myapp
cd myapp
mvn spring-boot:run
```

打开 <http://localhost:8000>。生成的是标准 Maven 布局:

```
src/main/java/app/           应用入口与控制器
src/main/java/routes/        路由
src/main/resources/views/    jblade 模板
src/main/resources/config/   config/*.yml
```

`--preset=api` 生成 JSON 首页(无模板);`--preset=minimal` 是最小骨架。

## 3. 加一个认证流程(5 分钟)

```bash
jaravel install:auth
mvn spring-boot:run
```

打开 <http://localhost:8000/login>:

1. 注册一个账号;
2. 自动登录并进入 `/dashboard`;
3. 登出;
4. 用刚注册的账号重新登录;
5. 试一次「忘记密码」——默认 `MAIL_MAILER=log`,重置链接会写在应用日志里。

生成的文件:

| 文件 | 作用 |
|---|---|
| `app/models/User.java` | 用户模型(`@AuthModel` + `@Hidden` 密码列) |
| `database/migrations/m.../CreateAuthTables.java` | `users` + `password_reset_tokens` |
| `app/http/controllers/AuthController.java` | 注册/登录/登出/忘记密码/重置 |
| `routes/AuthRoutes.java` | `/login` `/register` `/dashboard` ... |
| `resources/views/auth/*.jblade.html` | 登录、注册、重置页面 |
| `config/auth.yml` / `session.yml` / `hashing.yml` / `mail.yml` | 认证配置 |

## 4. 写第一个模型(5 分钟)

```bash
jaravel make:model Post -mc
jaravel migrate
```

生成 `app/models/Post.java`、迁移、控制器骨架。改模型字段、迁移列,
然后:

```java
r.get("/posts", PostController::index);
r.post("/posts", PostController::store);
```

## 5. 测试(5 分钟)

```bash
mvn test
```

生成项目自带一条 `FeatureTest` 烟雾测试;写一个新测试只要:

```java
class PostTest extends FeatureTest {
    @Test void creates_a_post() {
        post("/posts", Map.of("title", "第一篇", "body", "正文内容足够长"))
            .assertRedirect("/posts");
    }
}
```

更细的测试 API 见 [测试](testing.md)。

## 6. 30 分钟检查表

- [ ] `jaravel --version` 正常
- [ ] `jaravel new myapp` + `mvn spring-boot:run` 打开首页
- [ ] `jaravel install:auth` 后能注册/登录/登出
- [ ] 忘记密码能在日志里找到链接
- [ ] `mvn test` 全绿
- [ ] `mvn package` 后 `java -jar target/myapp-*.jar` 能启动

## 遇到问题

- 端口占用:`SERVER_PORT=8081 mvn spring-boot:run`
- 数据库:默认 SQLite `app.db`;换 `DB_URL`
- 日志里看不到重置链接:确认 `MAIL_MAILER=log` 且 `config/mail.yml` 的
  `mailers.log.body: true`
- 启动报 `APP_KEY`:在 `.env` 写 `APP_KEY=...`(`jaravel key:generate --show`)
