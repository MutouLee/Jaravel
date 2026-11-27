# API Reference

The authoritative reference for the public API is **Javadoc**; this page is the
entry point and module index.

!!! info "What “documented” means here"
    “The docs cover every public API” is judged by two mechanically checkable
    things: **(1)** every public type (excluding the `internal` / `impl`
    packages) has Javadoc; **(2)** every module with a public API appears in
    the module index below, and the type names it points to really exist. The
    checker is
    [`docs-site/tools/api_coverage.py`](https://github.com/MutouLee/Jaravel/blob/main/docs-site/tools/api_coverage.py);
    the CI docs job runs it with `--strict`.

## Generate Javadoc

```bash
mvn javadoc:aggregate
# output: target/site/apidocs/index.html
```

Per module:

```bash
mvn -pl jaravel-routing javadoc:javadoc
mvn -pl jaravel-eloquent javadoc:javadoc
```

## Module index

| Module | Key types |
|---|---|
| `jaravel-core` | `Container`, `Facade`, `ConfigRepository`, `ExecutionScope` |
| `jaravel-support` | `Coll`, `Str`, `Arr`, `DateX`, `Paginated`, `Cast` |
| `jaravel-routing` | `Router`, `Route`, `RouteDefinition`, `Middleware` |
| `jaravel-http` | `Request`, `Response`, `View`, `Redirect`, `RequestScope` |
| `jaravel-http-client` | `Http`, `PendingHttpRequest`, `HttpResponse`, `HttpTransport` |
| `jaravel-view` | `JbladeEngine`, `ViewEngine` |
| `jaravel-database` | `Connection`, `QueryBuilder`, `Grammar`, `Schema` |
| `jaravel-migration` | `Migration`, `Migrator`, `Blueprint`, `Schema`, `Seeder`, `Factory` |
| `jaravel-eloquent` | `Model`, `Query`, `@Model` / `@HasMany` / `@GlobalScope` |
| `jaravel-validation` | `Validator`, `FormRequest`, `ErrorBag` |
| `jaravel-auth` | `Auth`, `Guard`, `Gate`, `Hash`, `Password` |
| `jaravel-permissions` | `Permissions`, `HasPermissions`, `PermissionStore`, `RoleMiddleware` |
| `jaravel-security-bridge` | `SecurityBridge`, `SpringSecurityGuard`, `JaravelUserDetails` |
| `jaravel-session` | `Session`, `SessionStore`, `StartSession`, `VerifyCsrfToken` |
| `jaravel-cache` | the `Cache` facade (`CacheManager`), `Repository`, `CacheStore`, `Lock`, `RateLimiter` |
| `jaravel-queue` | `Queue`, `ShouldQueue`, `QueueStore`, `QueueWorker`, `Batch` |
| `jaravel-events` | `Event`, `@Listener` |
| `jaravel-broadcasting` | `Broadcast`, `ShouldBroadcast`, `BroadcastChannel`, `BroadcastHub` |
| `jaravel-scheduler` | `Schedule` |
| `jaravel-mail` | `Mail`, `Mailable` |
| `jaravel-notifications` | `Notifications`, `Notification`, `Notifiable` |
| `jaravel-storage` | `Storage`, `Filesystem` |
| `jaravel-api` | `JsonResource`, pagination |
| `jaravel-testing` | `FeatureTest`, `BrowserTest`, `TestResponse` |
| `jaravel-console` | `ConsoleCommand`, `@Command`, `Artisan` |
| `jaravel-cli` | `Jaravel` (the `jaravel` executable entry point; offline commands do not start the container) |
| `jaravel-devtools` | `FileWatcher`, `ReloadCoordinator`, `ReloadStrategy`, `HotSwapper` |
| `jaravel-dashboard` | `DashboardService` (data source for the `/_jaravel` development dashboard) |

## Module-level design documents

Module design trade-offs, escape hatches and "differences from Laravel" live in
[`docs/`](https://github.com/MutouLee/Jaravel/tree/main/docs) at the repository
root:

- [02 Containers and facades](https://github.com/MutouLee/Jaravel/blob/main/docs/02-core-container.md)
- [03 Routing and HTTP](https://github.com/MutouLee/Jaravel/blob/main/docs/03-routing-http.md)
- [04 jblade](https://github.com/MutouLee/Jaravel/blob/main/docs/04-view-jblade.md)
- [05 Database](https://github.com/MutouLee/Jaravel/blob/main/docs/05-database.md)
- [06 Eloquent](https://github.com/MutouLee/Jaravel/blob/main/docs/06-eloquent.md)
- [07 Validation](https://github.com/MutouLee/Jaravel/blob/main/docs/07-validation.md)
- [08 Authentication](https://github.com/MutouLee/Jaravel/blob/main/docs/08-auth.md)
- [09 Cache/session](https://github.com/MutouLee/Jaravel/blob/main/docs/09-cache-session.md)
- [10 Queue/events/scheduler](https://github.com/MutouLee/Jaravel/blob/main/docs/10-queue-events-scheduler.md)
- [11 Mail/notifications/storage](https://github.com/MutouLee/Jaravel/blob/main/docs/11-mail-notifications-storage.md)
- [12 API resources](https://github.com/MutouLee/Jaravel/blob/main/docs/12-api-resources.md)
- [13 Console](https://github.com/MutouLee/Jaravel/blob/main/docs/13-console-artisan.md)
- [14 Testing](https://github.com/MutouLee/Jaravel/blob/main/docs/14-testing.md)
- [19 Broadcasting](https://github.com/MutouLee/Jaravel/blob/main/docs/19-broadcasting.md)
- [20 Native/performance](https://github.com/MutouLee/Jaravel/blob/main/docs/20-native-and-performance.md)

## OpenAPI

```bash
jaravel openapi:generate
jaravel openapi:diff openapi.json
```

Response schemas are extracted at compile time by `jaravel-api-processor` from
`JsonResource.toArray`.
