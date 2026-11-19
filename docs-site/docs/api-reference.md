# API 参考

公开 API 的权威参考是 **Javadoc**;这里是入口与模块索引。

!!! info "覆盖口径"
    「文档覆盖所有公开 API」在这里的判定是两件可机械核对的事:
    **(1)** 每个公开类型(不含 `internal` / `impl` 包)都有 Javadoc;
    **(2)** 每个有公开 API 的模块都出现在下面的模块索引里,且索引点的
    类型名真的存在。核对脚本是
    [`docs-site/tools/api_coverage.py`](https://github.com/MutouLee/Jaravel/blob/main/docs-site/tools/api_coverage.py),
    CI 的 docs 任务以 `--strict` 跑它。

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
| `jaravel-support` | `Coll`、`Str`、`Arr`、`DateX`、`Paginated`、`Cast` |
| `jaravel-routing` | `Router`、`Route`、`RouteDefinition`、`Middleware` |
| `jaravel-http` | `Request`、`Response`、`View`、`Redirect`、`RequestScope` |
| `jaravel-http-client` | `Http`、`PendingHttpRequest`、`HttpResponse`、`HttpTransport` |
| `jaravel-view` | `JbladeEngine`、`ViewEngine` |
| `jaravel-database` | `Connection`、`QueryBuilder`、`Grammar`、`Schema` |
| `jaravel-migration` | `Migration`、`Migrator`、`Blueprint`、`Schema`、`Seeder`、`Factory` |
| `jaravel-eloquent` | `Model`、`Query`、`@Model` / `@HasMany` / `@GlobalScope` |
| `jaravel-validation` | `Validator`、`FormRequest`、`ErrorBag` |
| `jaravel-auth` | `Auth`、`Guard`、`Gate`、`Hash`、`Password` |
| `jaravel-permissions` | `Permissions`、`HasPermissions`、`PermissionStore`、`RoleMiddleware` |
| `jaravel-security-bridge` | `SecurityBridge`、`SpringSecurityGuard`、`JaravelUserDetails` |
| `jaravel-session` | `Session`、`SessionStore`、`StartSession`、`VerifyCsrfToken` |
| `jaravel-cache` | `Cache` 门面(`CacheManager`)、`Repository`、`CacheStore`、`Lock`、`RateLimiter` |
| `jaravel-queue` | `Queue`、`ShouldQueue`、`QueueStore`、`QueueWorker`、`Batch` |
| `jaravel-events` | `Event`、`@Listener` |
| `jaravel-broadcasting` | `Broadcast`、`ShouldBroadcast`、`BroadcastChannel`、`BroadcastHub` |
| `jaravel-scheduler` | `Schedule` |
| `jaravel-mail` | `Mail`、`Mailable` |
| `jaravel-notifications` | `Notifications`、`Notification`、`Notifiable` |
| `jaravel-storage` | `Storage`、`Filesystem` |
| `jaravel-api` | `JsonResource`、分页 |
| `jaravel-testing` | `FeatureTest`、`BrowserTest`、`TestResponse` |
| `jaravel-console` | `ConsoleCommand`、`@Command`、`Artisan` |
| `jaravel-cli` | `Jaravel`(`jaravel` 可执行入口;离线命令不启动容器) |
| `jaravel-devtools` | `FileWatcher`、`ReloadCoordinator`、`ReloadStrategy`、`HotSwapper` |
| `jaravel-dashboard` | `DashboardService`(`/_jaravel` 开发面板的数据源) |

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
