# 02 · 核心:容器、门面、管道、配置

`jaravel-core` 是所有模块的地基,**不依赖任何 Spring 类型**。

---

## 设计目标

1. 门面(`Auth.user()`)的简洁度对齐 Laravel,但不牺牲可测试性与线程安全;
2. 服务提供者机制支持三方包零配置接入(引入 jar 即生效);
3. 配置既能像 Laravel 一样 `Config.get("app.name")`,也能类型安全地注入;
4. 中间件管道是**纯函数式**的,与 HTTP 无关(队列、CLI 也能用同一套管道)。

---

## 1. 容器

### 1.1 契约

```java
package io.jaravel.core.container;

public interface Container {
    <T> T make(Class<T> type);
    <T> T make(Class<T> type, String name);          // 命名实现,如 Cache 的多驱动
    <T> Optional<T> tryMake(Class<T> type);

    <T> void bind(Class<T> type, Function<Container, T> factory);
    <T> void bind(Class<T> type, String name, Function<Container, T> factory);
    <T> void singleton(Class<T> type, Function<Container, T> factory);
    <T> void instance(Class<T> type, T instance);
    <T> void scoped(Class<T> type, Function<Container, T> factory);   // 请求级单例

    boolean bound(Class<?> type);

    /** 上下文绑定:给特定消费者注入特定实现 */
    <T> ContextualBinding<T> when(Class<?> consumer);

    /** 扩展已绑定的服务(装饰器) */
    <T> void extend(Class<T> type, BiFunction<T, Container, T> decorator);

    /** 解析回调,用于统一配置新建出的对象 */
    <T> void resolving(Class<T> type, BiConsumer<T, Container> callback);
}
```

### 1.2 上下文绑定

Laravel 的 `$this->app->when(X::class)->needs(Y::class)->give(Z::class)` 等价物:

```java
container.when(PhotoController.class)
         .needs(Filesystem.class)
         .give(() -> Storage.disk("s3"));
```

实现上落到 Spring 的 `@Qualifier` 之上太别扭,因此 Jaravel 自己维护一张 `(consumer, dependency) → factory` 表,在 `make` 时由调用栈的 `requestingClass` 查表。为避免反射读栈的开销,只在构造注入路径生效(此时 consumer 类型是已知参数)。

### 1.3 自动装配

构造注入优先,规则与 Laravel/Spring 一致:

1. 只有一个 public 构造函数 → 用它;
2. 多个 → 取标注 `@Inject` 的;都没标 → 取参数最多的那个(与 Spring 行为一致,避免惊讶);
3. 参数按类型解析;标注 `@Named("...")` 则按名称;
4. 标注 `@Config("app.timezone")` 则从配置解析并做类型转换。

```java
public class ReportService {
    public ReportService(Clock clock,
                         @Named("s3") Filesystem files,
                         @Config("report.retention-days") int retentionDays) { ... }
}
```

---

## 2. 门面(Facade)

### 2.1 为什么要门面

Laravel 优雅感的最大来源:`Cache::get('k')` 一行完事,不需要 `@Autowired CacheManager`。

Java 社区对静态门面的标准质疑是「不可测试、隐藏依赖」。Jaravel 的回答:

1. **可测试**:门面有一等公民的 `fake()` 支持(见 §2.4),且 `swap()` 可直接替换底层实现;
2. **隐藏依赖**:这是 trade-off 而非 bug。Jaravel 同时提供**契约注入**作为等价途径,两条路并存,团队按偏好选;
3. 门面**零状态**,所有状态在容器里,因此不存在并发问题。

### 2.2 实现

```java
package io.jaravel.core.facade;

public abstract class Facade<T> {
    /** 子类声明自己代理哪个契约 */
    protected abstract Class<T> contract();

    /** 测试期替换 */
    private static final Map<Class<?>, Object> SWAPPED = new ConcurrentHashMap<>();

    protected static <C> C resolve(Class<C> contract) {
        Object swapped = SWAPPED.get(contract);
        if (swapped != null) return contract.cast(swapped);
        return Application.container().make(contract);
    }

    public static <C> void swap(Class<C> contract, C impl) { SWAPPED.put(contract, impl); }
    public static void clearSwaps() { SWAPPED.clear(); }
}
```

具体门面是手写的薄转发类(**不用动态代理**,保证 IDE 可跳转、可重构、native-image 友好):

```java
package io.jaravel.facades;

public final class Cache {
    private Cache() {}
    private static CacheRepository repo() { return Facade.resolve(CacheRepository.class); }

    public static <T> Optional<T> get(String key, Class<T> type)       { return repo().get(key, type); }
    public static <T> T remember(String key, Duration ttl, Supplier<T> s) { return repo().remember(key, ttl, s); }
    public static void put(String key, Object value, Duration ttl)     { repo().put(key, value, ttl); }
    public static boolean forget(String key)                           { return repo().forget(key); }
    public static CacheRepository store(String name)                   { return Facade.resolveNamed(CacheRepository.class, name); }
    public static CacheFake fake()                                     { return CacheFake.install(); }
}
```

> **为什么手写转发而不是动态代理**:门面一共约 20 个、方法总量约 400 个,一次性写完即长期稳定;换来的是完整的 IDE 支持、无反射、无启动开销、GraalVM 零配置。代码生成也是选项(APT 从契约接口生成门面),v0.5 后可考虑,但首版手写更可控。

### 2.3 门面清单

| 门面 | 契约 | 模块 |
|---|---|---|
| `App` | `Application` | core |
| `Config` | `ConfigRepository` | core |
| `Log` | `Logger` | core |
| `Event` | `EventBus` | events |
| `Route` | `Router` | routing |
| `Request` | `HttpRequest`(请求级) | http |
| `Response` / `Redirect` | 静态工厂 | http |
| `Session` | `SessionStore`(请求级) | session |
| `Cookie` | `CookieJar` | http |
| `View` | `ViewFactory` | view |
| `DB` | `DatabaseManager` | database |
| `Schema` | `SchemaBuilder` | database |
| `Validator` | `ValidationFactory` | validation |
| `Auth` | `AuthManager`(请求级) | auth |
| `Gate` | `GateContract` | auth |
| `Hash` | `Hasher` | auth |
| `Cache` | `CacheRepository` | cache |
| `RateLimiter` | `RateLimiter` | cache |
| `Queue` | `QueueManager` | queue |
| `Bus` | `CommandBus` | queue |
| `Storage` | `FilesystemManager` | storage |
| `Mail` | `Mailer` | mail |
| `Notification` | `NotificationDispatcher` | notifications |
| `Lang` | `Translator` | core |
| `Artisan` | `ConsoleKernel` | console |

### 2.4 测试期假实现

每个有副作用的门面必须提供 `fake()`:

```java
@Test void dispatches_invoice_job() {
    var queue = Queue.fake();

    service.checkout(order);

    queue.assertPushed(SendInvoice.class, j -> j.orderId().equals(order.getId()));
    queue.assertPushedOnQueue("mail", SendInvoice.class);
    queue.assertNothingPushed(RefundJob.class);
}
```

`fake()` 内部调用 `Facade.swap()`,由 JUnit 扩展在每个测试后自动 `clearSwaps()`。详见 [14-testing.md](14-testing.md)。

### 2.5 契约注入(门面的等价替代)

```java
public class ReportService {
    private final CacheRepository cache;    // 不想用门面的团队走这条路
    public ReportService(CacheRepository cache) { this.cache = cache; }
}
```

两种风格**必须始终等价**:任何门面能做的事,契约注入都能做。这是硬性约束,写进模块验收标准。

---

## 3. 服务提供者

```java
public abstract class ServiceProvider {
    protected final Application app;
    protected ServiceProvider(Application app) { this.app = app; }

    /** 阶段一:只许 bind,不许 make */
    public void register() {}

    /** 阶段二:容器已就绪,可以 make */
    public void boot() {}

    /** 本 provider 延迟注册时,声明它提供哪些服务 */
    public Class<?>[] provides() { return new Class<?>[0]; }
    public boolean deferred() { return false; }

    // 便捷方法
    protected void mergeConfigFrom(String resource, String key) { ... }
    protected void loadViewsFrom(String resource, String namespace) { ... }
    protected void loadMigrationsFrom(String resource) { ... }
    protected void loadTranslationsFrom(String resource, String namespace) { ... }
    protected void publishes(Map<String, String> paths, String tag) { ... }
    protected void commands(Class<? extends Command>... commands) { ... }
    protected void routes(Runnable registration) { ... }
}
```

### 3.1 自动发现(三方包零配置)

包作者在自己的 jar 里声明:

```java
@JaravelProvider
public class SmsServiceProvider extends ServiceProvider { ... }
```

`jaravel-core-processor`(APT)在包编译时生成 `META-INF/jaravel/providers.idx`:

```
com.vendor.sms.SmsServiceProvider
com.vendor.sms.SmsConsoleProvider
```

启动时扫描 classpath 上所有 `providers.idx`(纯文本读取,**不做类路径扫描**,因此零启动开销且 native-image 友好),按以下顺序注册:

1. 框架内置 provider(硬编码顺序);
2. 三方包 provider(按 jar 名字典序,保证可重现);
3. 应用自己的 provider(`app/Providers/`)。

应用可在 `config/app.yml` 中排除某个自动发现的 provider:

```yaml
providers:
  dont-discover:
    - com.vendor.sms.SmsServiceProvider
```

与 Laravel 的 `composer.json` → `extra.laravel.providers` 机制等价。

### 3.2 延迟提供者

```java
public class HeavyPdfProvider extends ServiceProvider {
    @Override public boolean deferred() { return true; }
    @Override public Class<?>[] provides() { return new Class[]{ PdfRenderer.class }; }
    @Override public void register() { app.singleton(PdfRenderer.class, c -> new HeavyPdfRenderer()); }
}
```

容器维护 `provides() → provider` 索引,首次 `make(PdfRenderer.class)` 时才触发该 provider 的 `register()`。用于避免重量级服务拖慢启动。

---

## 4. 管道(Pipeline)

**与 HTTP 完全解耦**的纯函数式管道,HTTP 中间件、队列任务中间件、CLI 命令中间件共用同一实现。

```java
package io.jaravel.core.pipeline;

@FunctionalInterface
public interface Pipe<T, R> {
    R handle(T passable, Function<T, R> next);
}

public final class Pipeline<T, R> {
    public static <T, R> Pipeline<T, R> of(T passable) { ... }
    public Pipeline<T, R> through(List<Pipe<T, R>> pipes) { ... }
    public R then(Function<T, R> destination) { ... }
}
```

用法:

```java
Response resp = Pipeline.<Request, Response>of(request)
        .through(middlewares)
        .then(req -> controller.invoke(req));
```

实现为从后往前折叠成嵌套闭包(与 Laravel 的 `array_reduce` 同构):

```java
public R then(Function<T, R> destination) {
    Function<T, R> chain = destination;
    for (int i = pipes.size() - 1; i >= 0; i--) {
        Pipe<T, R> pipe = pipes.get(i);
        Function<T, R> next = chain;
        chain = p -> pipe.handle(p, next);
    }
    return chain.apply(passable);
}
```

**栈深度注意**:每个中间件增加约 3 层栈帧。虚拟线程的栈是可增长的堆上结构,20 个中间件完全无压力。

HTTP 中间件只是 `Pipe<Request, Response>` 的别名,见 [03-routing-http.md](03-routing-http.md)。

---

## 5. 配置

### 5.1 三层来源

```
优先级(高 → 低)
1. 环境变量 / 系统属性
2. .env.local  →  .env.{profile}  →  .env          (前者不被后者覆盖)
3. config/*.yml                                     (可引用 ${ENV_VAR:default})
4. 代码中的默认值
```

`.env` 通过 `JaravelEnvironmentPostProcessor` 注入 Spring 的 `Environment`,因此**所有 Spring 库也能读到**(这点很重要:`spring.datasource.url` 可以写在 `.env` 里)。

### 5.2 config 文件形态

Laravel 用 PHP 数组。Jaravel 用 YAML:

```yaml
# config/app.yml
app:
  name: ${APP_NAME:Jaravel}
  env: ${APP_ENV:production}
  debug: ${APP_DEBUG:false}
  url: ${APP_URL:http://localhost}
  timezone: Asia/Shanghai
  locale: zh_CN
  fallback-locale: en
  key: ${APP_KEY}

providers:
  dont-discover: []
```

```yaml
# config/database.yml
database:
  default: ${DB_CONNECTION:mysql}
  connections:
    mysql:
      driver: mysql
      host: ${DB_HOST:127.0.0.1}
      port: ${DB_PORT:3306}
      database: ${DB_DATABASE}
      username: ${DB_USERNAME}
      password: ${DB_PASSWORD}
      charset: utf8mb4
      prefix: ""
      strict: true
      pool:
        max-size: 20
        min-idle: 5
    sqlite:
      driver: sqlite
      database: ${DB_DATABASE:database/database.sqlite}
```

### 5.3 两种读取方式

```java
// 1. 门面(Laravel 风格)
String name = Config.get("app.name", "Jaravel");
boolean debug = Config.bool("app.debug");
int poolSize = Config.integer("database.connections.mysql.pool.max-size");
List<String> providers = Config.list("providers.dont-discover");

// 2. 类型安全绑定(推荐用于框架内部与库作者)
@ConfigProps("app")
public record AppConfig(
    String name,
    String env,
    boolean debug,
    URI url,
    ZoneId timezone,
    Locale locale
) {}

public class Foo {
    public Foo(AppConfig cfg) { ... }   // 直接注入,APT 生成绑定代码
}
```

`@ConfigProps` 由 APT 生成绑定器 + **启动期校验**(缺失必填项直接启动失败并给出清晰报错,而不是运行到一半 NPE)。这是对 Laravel 的改进。

### 5.4 配置缓存

```bash
jaravel config:cache     # 把全部 config/*.yml 合并序列化到 bootstrap/cache/config.bin
jaravel config:clear
```

生产模式下跳过 YAML 解析,直接反序列化,省约 80~150ms 启动时间。

---

## 6. support:工具类

Laravel 的「优雅」很大程度在 `Str`、`Arr`、`Collection`、`Carbon`。Java 的标准库在这块很弱(尤其字符串和集合操作的链式表达)。

### 6.1 Coll<T>

```java
public interface Coll<T> extends Iterable<T> {
    static <T> Coll<T> of(T... items);
    static <T> Coll<T> from(Iterable<T> items);

    <R> Coll<R> map(Function<T, R> f);
    Coll<T> filter(Predicate<T> p);
    Coll<T> reject(Predicate<T> p);
    <K> Map<K, Coll<T>> groupBy(Function<T, K> key);
    <K> Map<K, T> keyBy(Function<T, K> key);
    <R> Coll<R> pluck(Function<T, R> f);
    Optional<T> first();
    Optional<T> firstWhere(Predicate<T> p);
    T firstOrFail();
    Coll<Coll<T>> chunk(int size);
    Coll<T> sortBy(Function<T, ? extends Comparable<?>> key);
    Coll<T> unique(Function<T, ?> key);
    BigDecimal sum(Function<T, BigDecimal> f);
    String join(String sep);
    Coll<T> each(Consumer<T> c);              // 带返回值,便于链式
    Coll<T> tap(Consumer<Coll<T>> c);
    <R> R pipe(Function<Coll<T>, R> f);
    List<T> toList();
    Stream<T> stream();                        // 逃生口
    boolean isEmpty();  int size();
}
```

**为什么不直接用 `Stream`**:`Stream` 一次性消费、无 `groupBy` 到集合的简洁形式、每步都要 `.collect(...)` 收尾、`Optional` 嵌套繁琐。`Coll` 是即时求值的集合(不是惰性流),语义更接近 Laravel 的 `Collection`,心智负担更低。大数据量场景用 `.stream()` 逃生。

`Coll` 是 Eloquent 查询的默认返回类型。

### 6.2 Str / Arr

```java
Str.of("hello world").title().slug().limit(10).toString();
Str.snake("HelloWorld");        // hello_world
Str.studly("hello_world");      // HelloWorld
Str.plural("post");             // posts
Str.random(32);
Str.uuid();  Str.ulid();
Str.mask("13800138000", '*', 3, 4);
Str.is("foo/*", path);

Arr.get(map, "user.profile.name");
Arr.set(map, "user.profile.name", "Tom");
Arr.dot(nestedMap);             // 扁平化为 a.b.c → value
Arr.only(map, "a", "b");
Arr.except(map, "password");
```

### 6.3 DateX(Carbon 等价)

```java
DateX.now().addDays(3).startOfDay().toDateTimeString();
DateX.parse("2026-10-28").diffForHumans();         // "3 天前"
DateX.now().isWeekend();
DateX.now().timezone("UTC");
```

包装 `java.time`(内部持 `ZonedDateTime`),提供链式 + 人类可读差值 + 本地化。**不重新实现时间逻辑**,只做 API 门面。

---

## 7. 逃生口

| 需求 | 途径 |
|---|---|
| 不想用门面 | 全部契约可构造注入,两种风格等价 |
| 需要原生 Spring Bean | `Container.make()` 直通 `BeanFactory` |
| 配置读取不够用 | 注入 Spring `Environment` |
| `Coll` 性能不够 | `.stream()` / `.toList()` |
| 需要自己的容器 | `Application.setContainer(Container)` (测试与嵌入场景) |

---

## 8. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| 门面实现 | `__callStatic` 动态代理 | 手写静态转发类 | IDE 支持、无反射、native 友好 |
| 门面 helper 函数 | `cache()`、`auth()` 全局函数 | 无(Java 无全局函数) | 用 `Cache`/`Auth` 门面替代 |
| 配置格式 | PHP 数组 | YAML + 类型化 record | Java 无字面量数组;换来启动期校验 |
| 请求外调用门面 | `auth()->user()` 返回 null | 抛 `OutsideRequestException` | 消除 Laravel 的经典静默 bug |
| 容器 | 自研 | 委托 Spring | 复用生态,见 ADR-002 |
| `Collection` | 惰性可选 | `Coll` 即时 + `Stream` 逃生 | 简化心智模型 |

---

## 9. 待决问题

- [ ] 门面是否最终改为 APT 生成?手写 400 个方法的维护成本 vs 生成物的调试体验。倾向:v0.1 手写核心 6 个,v0.5 评估生成。
- [ ] `Coll` 是否需要惰性版本 `LazyColl`(对应 Laravel 的 `LazyCollection`)?用于大结果集游标遍历。倾向 v0.2 跟 Eloquent 的 `cursor()` 一起做。
- [ ] `@ConfigProps` 与 Spring `@ConfigurationProperties` 是否合并?倾向保留独立,但在 starter 里让两者读同一份配置源。
- [ ] 延迟提供者在 native-image 下的可达性分析问题,需验证。
