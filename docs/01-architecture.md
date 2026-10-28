# 01 · 整体架构

## 设计目标

1. 用户只面对 Jaravel 的 API,**不需要知道 Spring 的存在**;
2. 但用户**随时可以用 Spring**,且不需要任何桥接代码;
3. Spring Boot 的大版本升级不得伤及 Jaravel 核心;
4. 请求上下文的获取(`Auth.user()`、`Request.current()`)必须既优雅又线程安全。

---

## 1. 分层

```
┌──────────────────────────────────────────────────────────────┐
│ L5  用户应用                                                  │
│     app/Http/Controllers  app/Models  routes/WebRoutes.java   │
├──────────────────────────────────────────────────────────────┤
│ L4  门面与 DSL 层                                             │
│     Auth · DB · Cache · Queue · View · Route · Gate · Storage │
│     (静态入口,零状态,仅做容器解析 + 转发)                     │
├──────────────────────────────────────────────────────────────┤
│ L3  功能模块实现(零 Spring 依赖)                              │
│     routing  view  database  eloquent  auth  cache  queue ... │
├──────────────────────────────────────────────────────────────┤
│ L2  契约层 jaravel-core::contracts                            │
│     CacheStore · QueueDriver · ViewEngine · UserProvider ...   │
├──────────────────────────────────────────────────────────────┤
│ L1  Runtime Adapter(薄,唯一允许 import Spring 的地方)        │
│     jaravel-spring-boot-starter                               │
├──────────────────────────────────────────────────────────────┤
│ L0  Spring Boot 3.x · Servlet/Netty · DataSource · 三方库      │
└──────────────────────────────────────────────────────────────┘
```

**依赖方向严格单向向下**,且 L1 → L3 的依赖是「适配」而非「被依赖」:

- L3 不知道 L1 存在;
- L1 负责把 L0 的能力包装成 L2 的契约,注册进 Jaravel 容器;
- L4 的门面从容器里取 L2 的契约实现。

### 1.1 用 ArchUnit 强制边界

CI 中必须跑:

```java
@AnalyzeClasses(packages = "io.jaravel")
class ArchitectureTest {

    @ArchTest
    static final ArchRule core_must_not_depend_on_spring =
        noClasses().that().resideInAPackage("io.jaravel.core..")
                   .or().resideInAPackage("io.jaravel.eloquent..")
                   .or().resideInAPackage("io.jaravel.view..")
                   // ... 所有 L2/L3 模块
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule only_adapter_touches_spring =
        classes().that().dependOnClassesThat().resideInAPackage("org.springframework..")
            .should().resideInAnyPackage("io.jaravel.spring..", "io.jaravel.starter..");

    @ArchTest
    static final ArchRule facades_are_stateless =
        fields().that().areDeclaredInClassesThat().areAnnotatedWith(Facade.class)
            .should().beStatic().andShould().beFinal();
}
```

这条规则一旦失效,「不嫁给 Spring」的承诺就作废了,因此它是**阻断式**的 CI 检查,不是警告。

---

## 2. 与 Spring 的边界:具体怎么接

| Jaravel 能力 | 由 Spring 提供的底座 | 适配方式 |
|---|---|---|
| 依赖注入 | `ApplicationContext` / `BeanFactory` | `JaravelContainer` 委托给 `BeanFactory`,但对外暴露自己的 API |
| HTTP 入口 | `DispatcherServlet` + `HandlerMapping` | `JaravelHandlerMapping implements HandlerMapping`,优先级高于 `RequestMappingHandlerMapping` |
| 事务 | `PlatformTransactionManager` | `DB.transaction()` 包 `TransactionTemplate` |
| 数据源 | `DataSource` / HikariCP | `ConnectionResolver` 契约的 Spring 实现 |
| 配置 | `Environment` / `@ConfigurationProperties` | `Config` 门面双向桥接 Spring `Environment` |
| 嵌入式容器 | Tomcat / Undertow / Netty | 不碰,直接用 |
| 定时任务线程 | `TaskScheduler` | Scheduler 模块的 `ClockDriver` 实现之一 |
| 指标 / 健康检查 | Actuator / Micrometer | 不包装,原样暴露 |

### 2.1 容器桥接

`jaravel-core` 定义:

```java
public interface Container {
    <T> T make(Class<T> type);
    <T> T make(Class<T> type, String name);
    <T> void bind(Class<T> type, Supplier<T> factory);
    <T> void singleton(Class<T> type, Supplier<T> factory);
    <T> void instance(Class<T> type, T instance);
    boolean bound(Class<?> type);
    <T> T call(Object target, String method, Object... extra);  // 方法级依赖注入
}
```

Spring 适配:

```java
public class SpringContainer implements Container {
    private final ConfigurableApplicationContext ctx;
    private final Map<Class<?>, Supplier<?>> overrides = new ConcurrentHashMap<>();

    public <T> T make(Class<T> type) {
        Supplier<?> o = overrides.get(type);
        if (o != null) return type.cast(o.get());
        return ctx.getBean(type);
    }

    public <T> void singleton(Class<T> type, Supplier<T> f) {
        ctx.getBeanFactory().registerSingleton(type.getName(), f.get());
    }
    // ...
}
```

`overrides` 的存在是为了支持测试期替换(`Cache.fake()`、`Queue.fake()`),不走 Spring 的 bean 覆盖机制——后者在运行时替换单例非常别扭。

### 2.2 为什么不自己写 IoC

写一个 DI 容器只要两周,但会立刻失去:Spring 生态的全部 starter、AOP、事务传播语义、`@Transactional` 的代理、Actuator、配置体系。**自研 IoC 是本项目性价比最低的选项**,明确否决(见 ADR-002)。

---

## 3. 请求生命周期

```
HTTP 请求
   │
   ▼
[Servlet Container]  Tomcat / Undertow,虚拟线程-per-request
   │
   ▼
[DispatcherServlet]  Spring 入口,不改
   │
   ▼
[JaravelHandlerMapping]  匹配 Jaravel 路由表 → 返回 JaravelHandler
   │                     (未命中则回落给 Spring 原生 @RequestMapping)
   ▼
[JaravelHandlerAdapter]
   │
   ├─ 1. 创建 Request/Response 包装对象
   ├─ 2. 开启 RequestScope,绑定 ScopedValue
   │
   ▼
[Middleware Pipeline]  全局 → 分组 → 路由级,按 priority 排序
   │   ┌─────────────────────────────────────┐
   │   │ TrimStrings → EncryptCookies →      │
   │   │ StartSession → VerifyCsrf →         │
   │   │ Authenticate → Authorize → ...      │
   │   └─────────────────────────────────────┘
   ▼
[参数绑定]  路径参数 / 查询参数 / Body / FormRequest(含自动校验)
   │
   ▼
[Controller 方法]  用户代码
   │
   ▼
[返回值归一化]  View / JsonResource / Response / Redirect / Object → Response
   │
   ▼
[Pipeline 回程]  中间件的 after 逻辑(写 Session、加头、记录日志)
   │
   ▼
[RequestScope 关闭]  释放请求级缓存、归还连接、flush 日志
   │
   ▼
HTTP 响应
```

### 3.1 异常路径

```
任意阶段抛出 Throwable
   │
   ▼
[ExceptionHandler]  app/Exceptions/Handler.java(用户可覆盖)
   │
   ├─ 是 HttpException?        → 按状态码渲染
   ├─ 是 ValidationException?  → 422 JSON 或回跳带 errors
   ├─ 是 AuthException?        → 401 / 跳登录页
   ├─ 是 AuthorizationException?→ 403
   ├─ 是 ModelNotFound?        → 404
   └─ 其他                     → 500
   │
   ▼
[内容协商]  Accept: application/json → JSON 错误体
            否则 → 错误页面(开发模式带堆栈 + 源码片段 + SQL 日志)
```

与 Laravel 一致:**用户通过覆写 `Handler` 的 `render` / `report` 来定制**,不需要知道 `@ControllerAdvice`。

---

## 4. 并发模型与请求作用域(核心决策)

### 4.1 问题

Laravel 能写 `Auth::user()` 而无需传参,是因为 PHP-FPM 每请求一个独立进程,全局状态天然隔离。

Java 的线程是复用的。若简单用 `ThreadLocal`:

- 线程池复用 → 忘记清理就**串号**(用户 A 看到用户 B 的数据,安全事故);
- 异步/响应式 → 上下文**丢失**;
- 子线程 → 不继承(`InheritableThreadLocal` 在池化场景下更糟)。

### 4.2 方案

**强制虚拟线程-per-request + `ScopedValue` 结构化绑定。**

```properties
# jaravel-spring-boot-starter 的默认值
spring.threads.virtual.enabled=true
```

```java
public final class RequestScope {
    private static final ScopedValue<RequestScope> CURRENT = ScopedValue.newInstance();

    private final Request request;
    private final Response response;
    private final Map<Class<?>, Object> instances = new HashMap<>();  // 请求级单例
    private final Deque<Runnable> terminators = new ArrayDeque<>();   // 请求结束回调

    /** 由 HandlerAdapter 调用,整个请求处理包在这里面 */
    static <R> R runIn(RequestScope scope, Callable<R> body) throws Exception {
        try {
            return ScopedValue.where(CURRENT, scope).call(body);
        } finally {
            scope.terminate();   // 结构化作用域:一定会执行,一定不泄漏
        }
    }

    public static RequestScope current() {
        if (!CURRENT.isBound())
            throw new OutsideRequestException(
                "Auth.user() 等请求级 API 只能在请求处理过程中调用。" +
                "若在队列任务或控制台命令中,请显式传入 User 对象。");
        return CURRENT.get();
    }

    public static Optional<RequestScope> tryCurrent() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
```

`ScopedValue` 相比 `ThreadLocal` 的三个决定性优势:

1. **结构化**:绑定的生命周期由语法块界定,出块即解绑,**不可能泄漏**;
2. **不可变**:子作用域只能重新绑定,不能篡改父作用域;
3. **虚拟线程友好**:每请求一个虚拟线程 → 一个作用域,零污染,且创建成本接近零。

> Java 21 中 `ScopedValue` 为预览特性,在 Java 25 中已正式化。基线选 Java 21 时,`jaravel-core` 通过一层 `ContextHolder` 抽象隔离:Java 21 下用 `ThreadLocal` + 强制 `try/finally` 实现,Java 25+ 自动切到 `ScopedValue`。抽象接口只有三个方法,成本极低。

### 4.3 请求级单例

```java
// 同一请求内多次解析返回同一实例,请求结束自动释放
Connection conn = RequestScope.current().scoped(Connection.class, () -> pool.acquire());
```

用途:数据库连接、已加载的用户对象、本地化上下文、请求级查询缓存(Eloquent 的 identity map)。

### 4.4 队列任务 / 控制台的上下文

没有 HTTP 请求时,`Auth.user()` 必须**明确报错而不是返回 null**(见上面的 `OutsideRequestException`)。队列任务若需要用户上下文,由 `dispatch` 时序列化 user id,Worker 侧显式构造一个 `JobScope`:

```java
Queue.dispatch(new SendInvoice(orderId));         // 自动捕获 Auth.id() 存入 payload
// Worker 侧
JobScope.runAs(job.userId(), () -> job.handle()); // Auth.user() 在任务内可用
```

这是 Laravel 没有的显式性,属于**有意的改进**:避免「任务里 `Auth::user()` 莫名为 null」这个 Laravel 经典坑。

---

## 5. 启动流程

```
JVM 启动
   │
   ▼
[SpringApplication.run]
   │
   ▼
[JaravelEnvironmentPostProcessor]
   │   ├─ 加载 .env(.env.local → .env.{profile} → .env,后者不覆盖前者)
   │   └─ 把 .env 作为最高优先级 PropertySource 注入 Spring Environment
   ▼
[Spring 自动装配]  jaravel-spring-boot-starter 的 @AutoConfiguration
   │   ├─ 构造 SpringContainer
   │   ├─ 注册各契约的默认实现(按 config/*.yml 选驱动)
   │   └─ 构造 Application 对象
   ▼
[ServiceProvider 注册阶段]  所有 provider 的 register():只允许向容器 bind,不允许 make
   │   ├─ 框架内置 provider(按固定顺序)
   │   ├─ 三方包 provider(通过 META-INF/jaravel/providers.idx 自动发现)
   │   └─ 应用 provider(app/Providers/,APT 生成索引)
   ▼
[ServiceProvider 引导阶段]  所有 provider 的 boot():此时容器已就绪,可以 make
   │   ├─ 路由注册(扫描 RouteDefinition 实现)
   │   ├─ 事件监听器注册
   │   ├─ 视图目录与组件注册
   │   └─ 策略(Policy)注册
   ▼
[预热]  生产模式下:路由表冻结为不可变数组、模板类预加载、配置缓存
   │
   ▼
[容器就绪]  ApplicationReadyEvent → 开始接受请求
```

**register / boot 两阶段分离**与 Laravel 一致,目的是消除服务提供者之间的注册顺序依赖(register 阶段不许解析,因此不可能出现「依赖的服务还没注册」)。

---

## 6. 包命名约定

```
io.jaravel
├── core                  容器、门面基类、契约、Pipeline、配置
│   ├── contracts         所有跨模块接口(只有接口和 record,无实现)
│   ├── container
│   ├── facade
│   ├── config
│   └── provider
├── support               Coll / Str / Arr / DateX / Optionals
├── routing  ·  http  ·  view  ·  database  ·  eloquent  ·  validation
├── auth  ·  cache  ·  session  ·  queue  ·  events  ·  scheduler
├── mail  ·  notifications  ·  storage  ·  api  ·  console  ·  testing
├── facades               ★ 所有门面的最终落点:Auth / DB / Cache / Route / View ...
└── spring                ★ 唯一允许 import org.springframework 的包
```

`io.jaravel.facades` 单独成包是刻意的:用户的 import 区会非常干净——

```java
import io.jaravel.facades.*;   // 一行拿到所有门面
```

---

## 7. 性能目标

| 指标 | 目标 | 对照 |
|---|---|---|
| JVM 启动(含容器就绪) | < 1.5s | 裸 Spring Boot ≈ 1.2s |
| native-image 启动 | < 100ms | |
| 简单 JSON 接口吞吐 | ≥ 裸 Spring MVC 的 90% | |
| 模板渲染(预编译) | ≥ Thymeleaf 的 3 倍 | 编译成 Java 代码,无反射 |
| 单条主键查询(Eloquent) | ≤ Spring Data JPA 的 70% 耗时 | 无 Session/脏检查开销 |
| 热重载(改方法体) | 0 重启 | |
| 热重载(新增类) | < 2s | |

性能不是本项目的卖点,但**不能成为采纳障碍**。基准测试从 v0.1 起进 CI,任何 PR 导致关键指标回退 > 5% 则阻断。

---

## 8. 逃生口

每一层都必须有「我不用 Jaravel 这套,直接上底层」的合法途径:

| 场景 | 逃生口 |
|---|---|
| 路由不够用 | 照常写 `@RestController` + `@RequestMapping`,Jaravel 路由未命中时自动回落 |
| ORM 不够用 | `DB.raw(sql, bindings)`;或直接注入 `JdbcTemplate` / `EntityManager` |
| 想用 JPA | 引入 `jaravel-jpa-bridge`,两套 ORM 共享同一事务 |
| 需要 Spring Security | 引入 `jaravel-security-bridge`,Jaravel Guard 委托给 Spring 的 `SecurityContext` |
| 需要原生 Spring Bean | `@Component` 照常工作,`Container.make()` 能取到 |
| 模板引擎不够用 | 任意 `ViewEngine` 实现可注册(Thymeleaf / Freemarker 适配器开箱提供) |
| 中间件不够用 | 照常注册 Servlet `Filter` / Spring `Interceptor` |

**原则**:逃生口是设计的一部分,要写进文档和测试,而不是「用户自己想办法」。

---

## 9. 待决问题

- [ ] 基线定 Java 21 还是 25?21 覆盖面更广,25 可直接用正式版 `ScopedValue`。倾向 21 + 抽象隔离。
- [ ] 路由未命中时回落给 Spring 的顺序:`JaravelHandlerMapping` 的 `order` 具体取值,以及与 WebFlux 的兼容性。
- [ ] 是否支持 WebFlux?倾向 **不支持**:虚拟线程已解决阻塞问题,支持响应式会迫使所有 API 返回 `Mono/Flux`,直接摧毁优雅性。列为明确的非目标。
- [ ] `jaravel-core` 是否需要支持无 Spring 的纯 Netty 运行(`jaravel-standalone`)?v1.1 再议,但架构必须从一开始就允许。
