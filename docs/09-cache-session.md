# 09 · 缓存、会话、限流

---

## 设计目标

1. 一行代码完成缓存读写,驱动可切换且业务代码不变;
2. `remember` 模式(读不到则计算并写入)是一等公民;
3. 原子锁开箱可用(解决「多实例下定时任务重复执行」这类常见问题);
4. 会话驱动与缓存驱动共享底层存储抽象但语义独立。

---

## 1. 缓存

### 1.1 API

```java
// 读
Optional<User> u = Cache.get("user:1", User.class);
User u2 = Cache.get("user:1", User.class, defaultUser);
boolean has = Cache.has("user:1");
Map<String, User> many = Cache.many(List.of("user:1", "user:2"), User.class);

// 写
Cache.put("user:1", user, Duration.ofMinutes(10));
Cache.put("user:1", user);                              // 永久
Cache.putMany(Map.of("a", 1, "b", 2), Duration.ofMinutes(5));
boolean added = Cache.add("lock:1", true, Duration.ofSeconds(30));   // 仅当不存在时写入(原子)
Cache.forever("config", cfg);

// remember(最常用)
User u3 = Cache.remember("user:1", Duration.ofMinutes(10), () -> User.findOrFail(1L));
User u4 = Cache.rememberForever("config", () -> loadConfig());

// 删
Cache.forget("user:1");
Cache.flush();                                           // 清空当前 store
Cache.deleteMultiple(List.of("a", "b"));

// 取出并删除
Optional<String> v = Cache.pull("flash", String.class);

// 原子增减(计数器)
long n = Cache.increment("views:post:1");
long m = Cache.decrement("stock:sku-1", 5);

// 指定 store
Cache.store("redis").get("k", String.class);
Cache.store("file").put("k", v, ttl);
```

### 1.2 标签(分组失效)

```java
Cache.tags("users", "posts").put("user:1:posts", posts, ttl);
Cache.tags("users").flush();              // 失效所有带 users 标签的条目
Cache.tags("users", "posts").get("user:1:posts", PostList.class);
```

仅 `redis` / `caffeine` 驱动支持标签(`database` / `file` 驱动抛 `UnsupportedOperationException` 并给出清晰提示——而非静默降级,后者会导致缓存永不失效的隐蔽 bug)。

### 1.3 原子锁

```java
// 阻塞等待
Lock lock = Cache.lock("process-report", Duration.ofMinutes(5));
if (lock.get()) {
    try { generateReport(); } finally { lock.release(); }
}

// 闭包式(推荐,保证释放)
Cache.lock("process-report", Duration.ofMinutes(5)).get(() -> generateReport());

// 带等待
Cache.lock("import", Duration.ofMinutes(10)).block(Duration.ofSeconds(5), () -> doImport());
// 等待超时抛 LockTimeoutException

// 跨进程所有权转移(用于在队列任务中延续锁)
String owner = lock.owner();
Cache.restoreLock("process-report", owner).release();
```

Redis 实现用 `SET key owner NX PX ttl` + Lua 脚本释放(校验 owner,避免释放别人的锁)。**不实现 Redlock**——多主节点的分布式锁正确性争议大,文档中明确说明此锁「适用于防重复执行,不适用于强一致场景」。

### 1.4 驱动

| 驱动 | 实现 | 场景 |
|---|---|---|
| `caffeine` | Caffeine(进程内) | 单实例、高频读、默认开发驱动 |
| `redis` | Lettuce | 生产默认,支持标签与锁 |
| `database` | `cache` 表 | 无 Redis 的小项目 |
| `file` | 文件系统 | 本地开发 |
| `array` | `HashMap` | 测试,请求结束即丢 |
| `null` | 黑洞 | 禁用缓存 |
| `tiered` | Caffeine + Redis 两级 | 热点数据,L1 本地 + L2 共享 |

```yaml
# config/cache.yml
cache:
  default: ${CACHE_DRIVER:caffeine}
  prefix: ${CACHE_PREFIX:jaravel}
  stores:
    caffeine:
      driver: caffeine
      max-size: 10000
      default-ttl: 10m
    redis:
      driver: redis
      connection: cache
    tiered:
      driver: tiered
      l1: caffeine
      l2: redis
      l1-ttl: 30s           # L1 短 TTL 容忍跨实例不一致
```

### 1.5 序列化

缓存值的序列化策略影响很大:

- **默认 JSON**(Jackson):可读、跨语言、跨版本兼容性好、可在 redis-cli 中直接查看;
- 可选 `java-serialization`:**不推荐**(反序列化漏洞、版本脆弱),仅为兼容遗留;
- 可选 `kryo` / `protostuff`:高性能二进制,v0.6 再议。

`Cache.get("k", User.class)` 必须传类型,因为 JSON 反序列化需要目标类型。泛型容器用 `TypeRef`:

```java
Cache.get("posts", new TypeRef<List<Post>>() {});
```

> **重要约束**:缓存 Eloquent 模型实例需谨慎——模型带脏跟踪状态与关联缓存。文档建议缓存 DTO/record 而非模型。`@Model` 的 Jackson 序列化器会剔除内部状态字段,但反序列化出的模型 `exists()` 为 false,`save()` 会变成 INSERT。这是必须在文档中显著警告的陷阱。

### 1.6 缓存击穿防护

```java
Cache.remember("hot-key", ttl, () -> expensive());
```

`remember` 在缓存未命中时,**同一进程内**用 `ConcurrentHashMap#computeIfAbsent` 语义保证只有一个线程执行 `expensive()`(其余等待)。跨进程需要分布式锁:

```java
Cache.rememberLocked("hot-key", ttl, Duration.ofSeconds(10), () -> expensive());
```

这是对 Laravel 的增强——Laravel 的 `remember` 没有防击穿,高并发下会出现惊群。

---

## 2. 会话

### 2.1 API

```java
Session.put("cart", cart);
Optional<Cart> c = Session.get("cart", Cart.class);
Cart c2 = Session.get("cart", Cart.class, new Cart());
Optional<Cart> c3 = Session.pull("cart", Cart.class);          // 取出并删除
boolean has = Session.has("cart");          // 存在且非 null
boolean exists = Session.exists("cart");    // 存在(可为 null)
Session.forget("cart");  Session.forget("a", "b");
Session.flush();
Session.all();

// Flash(只在下一个请求可用)
Session.flash("status", "保存成功");
Session.flashInput(request.all());
Session.reflash();                   // 把所有 flash 再保留一个请求
Session.keep("status");              // 保留指定 key

// 数组操作
Session.push("recent.items", itemId);
Session.increment("page-views");

// 安全
Session.regenerate();                // 换 session id,保留数据(登录后必做)
Session.invalidate();                // 清数据 + 换 id(登出)
Session.token();                     // CSRF token
Session.regenerateToken();
Session.id();
```

### 2.2 驱动

| 驱动 | 说明 |
|---|---|
| `cookie` | 加密后存客户端。无服务端状态,水平扩展最简单。限制 4KB |
| `redis` | 生产默认 |
| `database` | `sessions` 表,带 `user_id`/`ip`/`user_agent`/`last_activity`,支持「登出其他设备」 |
| `file` | 本地开发 |
| `memory` | 测试 |

```yaml
# config/session.yml
session:
  driver: ${SESSION_DRIVER:cookie}
  lifetime: 120m
  expire-on-close: false
  encrypt: true
  cookie: jaravel_session
  path: /
  domain: ${SESSION_DOMAIN:}
  secure: ${SESSION_SECURE:true}
  http-only: true
  same-site: lax
  partitioned: false
```

### 2.3 与 Servlet Session 的关系

**Jaravel 不使用 `HttpSession`**。原因:

1. `HttpSession` 的序列化/集群复制语义由容器决定,不可控;
2. `cookie` 驱动(最适合水平扩展的方案)在 `HttpSession` 模型下无法实现;
3. flash 语义(只存活一个请求)需要自己管理生命周期。

Jaravel 的 `SessionStore` 是独立实现:`StartSession` 中间件在请求开始时从 cookie 读取 session id 并加载数据,在响应时写回。与 Spring Session 不冲突(两者可共存但不共享数据)。

`request.raw().getSession()` 作为逃生口仍然可用。

---

## 3. 限流

```java
// 配置限流器
RateLimiter.for_("api", req ->
    Auth.check() ? Limit.perMinute(1000).by(Auth.id())
                 : Limit.perMinute(60).by(req.ip()));

RateLimiter.for_("login", req ->
    Limit.perMinute(5).by(req.input("email").orElse("") + "|" + req.ip())
         .response(r -> Response.json(Map.of("message", "尝试过于频繁")).status(429)));

RateLimiter.for_("uploads", req -> List.of(          // 多层限制
    Limit.perMinute(10).by(Auth.id()),
    Limit.perDay(100).by(Auth.id())
));

// 使用
r.get("/api/data", X::y).middleware("throttle:api");
r.post("/login", X::y).middleware("throttle:login");
r.get("/x", X::y).middleware("throttle:60,1");        // 内联:60 次/分钟
```

响应头:`X-RateLimit-Limit`、`X-RateLimit-Remaining`、`Retry-After`。

编程式:

```java
if (RateLimiter.tooManyAttempts("send-sms:" + phone, 3)) {
    int sec = RateLimiter.availableIn("send-sms:" + phone);
    return Response.json(Map.of("retry_after", sec)).status(429);
}
RateLimiter.hit("send-sms:" + phone, Duration.ofMinutes(10));
RateLimiter.clear("send-sms:" + phone);
```

算法:默认**固定窗口计数器**(简单、与 Laravel 一致);可选**滑动窗口**(`throttle.algorithm: sliding`,Redis ZSET 实现,更精确但成本略高)。

---

## 4. 内部架构

```
jaravel-cache
├── CacheRepository (contract)      ← 门面 Cache 的目标
├── CacheManager                    store 工厂
├── stores/
│   ├── CaffeineStore · RedisStore · DatabaseStore · FileStore
│   ├── ArrayStore · NullStore · TieredStore
├── TaggedCache                     标签装饰器
├── locks/  RedisLock · DatabaseLock · CaffeineLock
├── serialization/  JsonSerializer(默认)
└── RateLimiter

jaravel-session
├── SessionStore (contract)
├── SessionManager
├── handlers/  CookieHandler · RedisHandler · DatabaseHandler · FileHandler
├── middleware/ StartSession · ShareErrorsFromSession
└── GarbageCollector                过期 session 清理(database/file 驱动)
```

### 4.1 为什么不直接用 Spring Cache

Spring Cache(`@Cacheable`)是**声明式方法级缓存**,与 Jaravel 需要的**命令式键值缓存**是不同的东西:

- `@Cacheable` 无法表达 `Cache.remember(key, ttl, supplier)` 中动态计算的 key 与 ttl;
- 无法表达标签、原子锁、increment;
- `@Cacheable` 依赖 AOP 代理,自调用失效(类内方法互调不走缓存),是经典困惑点。

Jaravel 的 `CacheRepository` 底层可以**注册为 Spring 的 `CacheManager`**,使 `@Cacheable` 与 `Cache` 门面共享同一存储——两者并存,用户按场景选。

---

## 5. 逃生口

| 需求 | 途径 |
|---|---|
| 需要 Redis 原生命令 | `Cache.store("redis").raw()` → `RedisCommands` |
| 需要 `@Cacheable` | 照常使用,共享底层 store |
| 需要 `HttpSession` | `request.raw().getSession()` |
| 需要 Spring Session | 照常引入,与 Jaravel session 并存 |
| 需要自定义驱动 | 实现 `CacheStore` / `SessionHandler` 并注册 |

---

## 6. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| `Cache::get('k')` | 返回混合类型 | 必须传 `Class<T>` 或 `TypeRef` | Java 需要反序列化目标类型 |
| `remember` 防击穿 | 无 | 进程内自动 + `rememberLocked` 可选跨进程 | 消除惊群 |
| 标签不支持的驱动 | 静默或报错不清 | 明确抛异常 + 提示 | 避免「缓存永不失效」的隐蔽 bug |
| memcached 驱动 | 支持 | 不支持 | Java 生态中 Redis 已是绝对主流,维护一个低使用率驱动不划算 |
| 两级缓存 | 无 | `tiered` 驱动 | Java 应用常驻进程,本地缓存价值远高于 PHP |

---

## 7. 待决问题

- [ ] 缓存值的默认序列化用 Jackson JSON 还是二进制?JSON 可读性强、调试友好,但体积与速度劣于二进制。倾向 JSON 为默认(DX 优先),提供 `serializer: kryo` 配置项。
- [ ] `Cache.get(key, Class)` 的 API 噪音是否可接受?考虑提供 `CacheKey<T>` 类型化键:`static final CacheKey<User> USER_1 = CacheKey.of("user:1", User.class);` 然后 `Cache.get(USER_1)`。倾向 v0.6 补充,两者并存。
- [ ] `tiered` 驱动的 L1 失效传播:单实例 `Cache.forget()` 无法清其他实例的 L1。需要 Redis pub/sub 广播失效消息。实现复杂度中等,但不做的话 `tiered` 就是个陷阱。倾向:要么做完整(含广播),要么不提供此驱动。
- [ ] session 的 `cookie` 驱动 4KB 限制如何给出友好报错(超限时数据静默丢失是最糟的结果)。需在写回时检测并抛异常。
