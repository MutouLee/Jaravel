# API 参考

公开 API 的权威参考是 **Javadoc**;这里是入口与模块索引。

## 生成 Javadoc

```bash
mvn javadoc:aggregate
# 输出:target/site/apidocs/index.html
```

按模块看:

```bash
mvn -pl jaravel-routing javadoc:javadoc
mvn -pl jaravel-eloquent javadoc:javadoc
```

## 模块索引

| 模块 | 关键类型 |
|---|---|
| `jaravel-core` | `Container`、`Facade`、`ConfigRepository`、`ExecutionScope` |
| `jaravel-routing` | `Router`、`Route`、`RouteDefinition`、`Middleware` |
| `jaravel-http` | `Request`、`Response`、`View`、`Redirect`、`RequestScope` |
| `jaravel-view` | `JbladeEngine`、`ViewEngine` |
| `jaravel-database` | `Connection`、`QueryBuilder`、`Grammar`、`Schema` |
| `jaravel-eloquent` | `Model`、`Query`、`@Model` / `@HasMany` / `@GlobalScope` |
| `jaravel-validation` | `Validator`、`FormRequest`、`ErrorBag` |
| `jaravel-auth` | `Auth`、`Guard`、`Gate`、`Hash`、`Password` |
| `jaravel-queue` | `Queue`、`ShouldQueue`、`QueueStore`、`Worker` |
| `jaravel-events` | `Event`、`@Listener` |
| `jaravel-scheduler` | `Schedule` |
| `jaravel-mail` | `Mail`、`Mailable` |
| `jaravel-notifications` | `Notifications`、`Notification`、`Notifiable` |
| `jaravel-storage` | `Storage`、`Filesystem` |
| `jaravel-api` | `JsonResource`、分页 |
| `jaravel-testing` | `FeatureTest`、`BrowserTest`、`TestResponse` |
| `jaravel-console` | `ConsoleCommand`、`@Command`、`Artisan` |

## 模块级设计文档

模块的设计取舍、逃生口与「与 Laravel 的差异」在仓库根目录
[`docs/`](https://github.com/MutouLee/Jaravel/tree/main/docs):

- [02 容器与门面](https://github.com/MutouLee/Jaravel/blob/main/docs/02-core-container.md)
- [03 路由与 HTTP](https://github.com/MutouLee/Jaravel/blob/main/docs/03-routing-http.md)
- [04 jblade](https://github.com/MutouLee/Jaravel/blob/main/docs/04-view-jblade.md)
- [05 数据库](https://github.com/MutouLee/Jaravel/blob/main/docs/05-database.md)
- [06 Eloquent](https://github.com/MutouLee/Jaravel/blob/main/docs/06-eloquent.md)
- [07 验证](https://github.com/MutouLee/Jaravel/blob/main/docs/07-validation.md)
- [08 认证](https://github.com/MutouLee/Jaravel/blob/main/docs/08-auth.md)
- [09 缓存/会话](https://github.com/MutouLee/Jaravel/blob/main/docs/09-cache-session.md)
- [10 队列/事件/调度](https://github.com/MutouLee/Jaravel/blob/main/docs/10-queue-events-scheduler.md)
- [11 邮件/通知/存储](https://github.com/MutouLee/Jaravel/blob/main/docs/11-mail-notifications-storage.md)
- [12 API 资源](https://github.com/MutouLee/Jaravel/blob/main/docs/12-api-resources.md)
- [13 控制台](https://github.com/MutouLee/Jaravel/blob/main/docs/13-console-artisan.md)
- [14 测试](https://github.com/MutouLee/Jaravel/blob/main/docs/14-testing.md)
- [19 广播](https://github.com/MutouLee/Jaravel/blob/main/docs/19-broadcasting.md)
- [20 native/性能](https://github.com/MutouLee/Jaravel/blob/main/docs/20-native-and-performance.md)

## OpenAPI

```bash
jaravel openapi:generate
jaravel openapi:diff openapi.json
```

响应 schema 由 `jaravel-api-processor` 从 `JsonResource.toArray` 编译期提取。
