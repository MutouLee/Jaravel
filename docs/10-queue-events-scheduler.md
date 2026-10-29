# 10 · 队列、事件、任务调度

这三者共同构成「异步与后台处理」能力。Java 侧的巨大优势:**应用是常驻进程**,不需要 PHP 那样依赖外部 supervisor 拉起 worker;虚拟线程让高并发 worker 成本极低。

---

## 1. 队列

### 1.1 Job 定义

```java
public class SendInvoice implements ShouldQueue {

    private final Long orderId;                  // 只存 id,不存整个模型(payload 要小)
    public SendInvoice(Long orderId) { this.orderId = orderId; }

    // 行为配置
    @Override public String queue()        { return "mail"; }
    @Override public int tries()            { return 3; }
    @Override public Duration[] backoff()   { return new Duration[]{ ofSec(10), ofSec(60), ofSec(300) }; }
    @Override public Duration timeout()     { return Duration.ofMinutes(2); }
    @Override public Duration retryUntil()  { return Duration.ofHours(24); }
    @Override public boolean deleteWhenMissingModels() { return true; }
    @Override public int maxExceptions()    { return 2; }

    /** 真正的执行体,参数从容器注入 */
    public void handle(Mailer mailer, InvoiceRenderer renderer) {
        Order order = Order.findOrFail(orderId);
        mailer.to(order.customerEmail()).send(new InvoiceMail(order, renderer.render(order)));
    }

    /** 全部重试耗尽后调用 */
    public void failed(Throwable e) {
        Log.error("发票发送最终失败 orderId={}", orderId, e);
        Notification.route("slack", OPS).notify(new JobFailed(this, e));
    }

    /*
     * 唯一性约束:同一订单的发票任务不重复入队。
     *
     * v0.4 实现时改成了**独立接口** `UniqueJob` 而不是 ShouldQueue 的
     * 默认方法 —— 它需要一个锁存储才能生效,做成默认方法意味着
     * 「所有任务都声明了这个能力,但只在配了缓存时才真的工作」,
     * 而那正是 v0.3 反复修的那类问题:声明了却不生效的 API。
     *
     *   public class SendInvoice implements ShouldQueue, UniqueJob {
     *       @Override public String uniqueId()    { return String.valueOf(orderId); }
     *       @Override public Duration uniqueFor() { return Duration.ofMinutes(10); }
     *   }
     *
     * 没配锁存储时**报错**,不静默跳过去重 —— 不去重的症状是
     * 「队列里有一堆重复任务」,很难联想到缓存没配。
     * uniqueFor 是**兜底 TTL**:正常情况下 Worker 在任务结束时释放锁。
     */
}
```

### 1.2 投递

> **v0.4 实现时的偏差**:下面前四行里的 `Queue.dispatch(job).onQueue(...)`
> 形式**在 Java 里无法原样实现**。它在 Laravel 里能工作靠的是 PHP 的
> 确定性析构 —— `PendingDispatch` 的 `__destruct` 在表达式结束时触发投递。
> Java 的 GC 时机不确定,`finalize` 已废弃。
>
> 硬做的两条路都更糟:让 `dispatch(job)` 立即投递、链式方法事后修改,
> 那时任务已经在队列里,`onQueue` 改不动它;延迟到请求作用域结束再投,
> 投递时机就不可预期了,而 CLI 与队列任务里根本没有请求作用域。
>
> 实际 API 拆成两个入口,各自语义确定:
>
> ```java
> Queue.dispatch(new SendInvoice(id));                            // 用任务自己的配置投
> Queue.job(new SendInvoice(id)).onQueue("mail").dispatch();      // 要改配置时用
> ```
>
> 「忘了写 `.dispatch()`」的风险由一个开发期检查兜住:未投递的
> `PendingDispatch` 被 GC 回收时打印警告。静默什么都不做是这里
> 唯一不可接受的结果。

```java
Queue.dispatch(new SendInvoice(orderId));
Queue.job(new SendInvoice(id)).onQueue("mail").onConnection("redis").dispatch();
Queue.job(new SendInvoice(id)).delay(Duration.ofMinutes(5)).dispatch();
Queue.job(new SendInvoice(id)).afterCommit().dispatch();      // 默认就是 afterCommit
Queue.dispatchSync(new SendInvoice(id));                      // 同步执行(测试/调试)
Queue.dispatchIf(cond, job);  Queue.dispatchUnless(cond, job);

// 链式(前一个成功才执行下一个)
Queue.chain(List.of(new ProcessPayment(id), new SendInvoice(id), new UpdateInventory(id)))
     .onQueue("orders")
     .catch_(e -> Log.error("链中断", e))
     .dispatch();

// 批处理(并行 + 汇总回调)
Batch batch = Queue.batch(orders.map(o -> new GenerateReport(o.getId())).toList())
     .name("月度报表")
     .allowFailures()
     .then(b -> Log.info("全部完成 {}/{}", b.processedJobs(), b.totalJobs()))
     .catch_((b, e) -> Log.error("首个失败", e))
     .finally_(b -> Notification.send(admin, new BatchDone(b)))
     .dispatch();

batch.id();  batch.progress();  batch.cancel();
```

### 1.3 Job 中间件

```java
public class SendInvoice implements ShouldQueue {
    @Override public List<JobMiddleware> middleware() {
        return List.of(
            new WithoutOverlapping(orderId).releaseAfter(60),   // 同 key 不并发
            new RateLimited("mail-api"),                        // 限流
            new ThrottlesExceptions(5, Duration.ofMinutes(10))  // 熔断
        );
    }
}
```

复用 `jaravel-core` 的 `Pipeline`(见 [02](02-core-container.md) §4)。

> **v0.4 实现说明**
>
> 任务中间件比 HTTP 中间件多一个动作:**把任务放回队列**
> (`context.release(delay)`)。这是「等锁」「等限流」的正确表达 ——
> 阻塞等待会占着一个并发额度干等,而那个额度本可以去跑别的任务。
>
> 三个内置中间件都已实现:`WithoutOverlapping`、`RateLimited`、
> `ThrottlesExceptions`。被它们 release 的任务**不计入异常次数** ——
> 这是 `maxExceptions` 与 `tries` 分开的理由:等锁等了 50 次
> 不该耗尽重试配额。
>
> **一个已知的粗糙点**:`middleware()` 是任务实例上的方法,它拿不到容器,
> 所以中间件要的 cache / limiter 没有注入通道。Laravel 里这不是问题 ——
> PHP 的全局容器随处可达。Jaravel 的等价做法是门面(`Cache.lock(...)`),
> 但那把依赖藏起来了。**列入 v0.5 重新设计**,候选方案是让 Worker 在调用
> `middleware()` 时传入一个 `MiddlewareFactory`。
>
> 中间件既不放行也不 release/delete 时**报错**:任务已经被 pop 出来了,
> 什么都不做等于它静默消失,而那是队列系统里最难查的一类问题。

### 1.4 Worker

```bash
jaravel queue:work                                     # 默认连接默认队列
jaravel queue:work redis --queue=high,mail,default     # 优先级:从左到右
jaravel queue:work --tries=3 --timeout=60 --sleep=3 --max-jobs=1000 --max-time=3600
jaravel queue:work --concurrency=50                    # 50 个虚拟线程并发消费
jaravel queue:listen                                   # 每个任务独立进程(开发期,代码热更)
jaravel queue:restart                                  # 优雅重启所有 worker
jaravel queue:failed                                   # 列出失败任务
jaravel queue:retry <id|all>
jaravel queue:forget <id>  ·  queue:flush
jaravel queue:monitor redis:default --max=1000         # 队列积压告警
jaravel queue:prune-batches --hours=48
```

**Java 的优势**:`--concurrency=50` 在虚拟线程下几乎零成本(50 个虚拟线程 ≈ 几十 KB),而 PHP 需要起 50 个进程(数 GB 内存)。这是 Jaravel 相对 Laravel 的真实优势,应在文档中突出。

> **v0.4 实现时的两条语义说明**
>
> **1. `timeout` 不保证停止那段代码。** 实现是「在另一个虚拟线程里跑,
> 超时后 interrupt 并把这次尝试判为失败」。但 `Thread.interrupt()` 只是
> 设一个标记 —— 只有阻塞在可中断操作上的代码会响应它,一个
> `while(true)` 或不检查中断的 JDBC 调用不会停。`Thread.stop()` 在
> Java 20 已被移除(它会让锁与不变量处于任意状态)。
>
> Laravel 用 `pcntl_alarm` 能真的中止进程,这是平台差异。所以 Jaravel 的
> timeout 语义是:**保证释放并发额度与推进重试,不保证停止那段代码**。
> 任务里的长循环应当自己检查 `Thread.interrupted()`。这一点写进了
> 失败记录的异常消息里 —— 排查的人需要知道。
>
> **2. 心跳续约把「配置对」变成「worker 活着」。** database 驱动原本
> 要求用户把 `retry-after` 配得比最长任务还长,否则任务会在还在跑的时候
> 被另一个 worker 再取一遍。有了续约之后 `retry-after` 的含义变成
> 「worker 挂掉之后任务多久能被别人捡起来」,不再需要估算任务时长。
>
> 续约失败(任务已被别人接手)时 worker **放弃**这条任务:既不删也不放回。
> 这里有一个必须靠**显式标记**而不是异常来表达的点 —— 中断在任务里
> 表现为 `InterruptedException`,与「任务自己失败了」在 catch 里长得一样,
> 于是会走重试路径,把一条已被接手的任务又放回队列。

Worker 模型:

```
QueueWorker
├── 1 个调度虚拟线程:按优先级轮询各队列 pop
├── N 个执行虚拟线程(Semaphore 控制并发度)
├── 每个任务:
│   ├── JobScope.runIn(...)     ← 建立任务级作用域(类比请求作用域)
│   ├── 心跳续约(防止长任务被重复消费)
│   ├── Pipeline(job middleware) → job.handle(injected deps)
│   ├── 成功 → ack/delete
│   └── 失败 → 重试计数++ → release(delay) 或进 failed_jobs
└── 优雅停机:收到 SIGTERM → 停止接新任务 → 等待在执行的任务完成(最长 timeout)
```

### 1.5 驱动

| 驱动 | 实现 | 说明 |
|---|---|---|
| `database` | `jobs` / `failed_jobs` / `job_batches` 表 | 零额外依赖,小项目够用。**v0.4 实现用乐观锁 UPDATE 而不是 `SKIP LOCKED`** —— 见下 |
| `redis` | Lettuce + Lua 脚本 | 生产默认,`BRPOPLPUSH` + 延迟 ZSET |
| `rabbitmq` | amqp-client | 需要复杂路由/多语言消费者时 |
| `kafka` | kafka-clients | 高吞吐事件流;**语义不同**(无单任务重试),文档明确说明限制 |
| `sqs` | AWS SDK v2 | |
| `sync` | 立即同步执行 | 开发/测试默认 |
| `null` | 丢弃 | |

> **为什么 database 驱动没用 `SELECT … FOR UPDATE SKIP LOCKED`**
>
> 它只在 MySQL 8.0+ / PostgreSQL 9.5+ 上有,老版本与 SQLite 都没有,
> 所以需要**版本探测**(§待决问题里原本也是这么规划的)。而探测这件事
> 有一个很糟的失败模式:探测错了要么在 pop 时抛语法错误,要么**退化成
> 不加锁的 SELECT** —— 后者的症状是任务被执行两次,对扣款这类任务
> 就是生产事故,且只在并发下偶发。
>
> v0.4 实现的是「先挑候选 id,再用带条件的 UPDATE 去抢」:
>
> ```sql
> update jobs set reserved_at = ? where id = ? and reserved_at is null
> ```
>
> 受影响行数为 1 才算抢到,为 0 就换下一个候选。这在**所有**数据库上
> 都正确,不需要版本探测。代价是高并发下有重试开销 —— 而那个开销在
> 「几十个 worker 抢一个队列」时才显著,到那个量级本来就该换 Redis。
>
> `SKIP LOCKED` 作为吞吐优化列入 v0.5,届时与其它方言差异一起处理。

```yaml
# config/queue.yml
queue:
  default: ${QUEUE_CONNECTION:database}
  after-commit: true                   # 全局默认:事务提交后入队
  connections:
    database:
      driver: database
      table: jobs
      queue: default
      retry-after: 90s
    redis:
      driver: redis
      connection: default
      queue: default
      retry-after: 90s
      block-for: 5s
  failed:
    driver: database
    table: failed_jobs
    prune-after: 7d
  batching:
    table: job_batches
```

### 1.6 序列化

Job 的 payload 用 **JSON**(Jackson),不用 Java 序列化:

```json
{
  "uuid": "9c1a...",
  "jobClass": "com.app.jobs.SendInvoice",
  "data": { "orderId": 42 },
  "attempts": 0,
  "queue": "mail",
  "contextUserId": 7,
  "pushedAt": "2026-10-28T06:12:00Z"
}
```

理由:可读(能在 redis-cli / SQL 里直接看)、跨版本兼容、无反序列化漏洞、可被其他语言的 worker 消费。

**关键约束**:Job 的构造参数必须可 JSON 序列化。APT 校验 `ShouldQueue` 实现类的字段类型,对 `Model` 子类字段发出警告:

```
[WARNING] app/Jobs/SendInvoice.java:8
          private final Order order;
                        ^^^^^
          Job 字段为 Eloquent 模型,会序列化整个模型快照(payload 膨胀 + 数据过期)。
          建议改为 private final Long orderId; 在 handle() 中重新查询。
```

> 若确实需要模型,提供 `@SerializesModels` 注解:只序列化主键,反序列化时自动重查(Laravel 的 `SerializesModels` trait 等价),模型已删除时按 `deleteWhenMissingModels()` 决定丢弃或失败。

### 1.7 失败任务

```sql
create table failed_jobs (
  id bigint auto_increment primary key,
  uuid varchar(36) not null unique,
  connection text not null,
  queue text not null,
  payload longtext not null,
  exception longtext not null,
  failed_at timestamp not null default current_timestamp
);
```

```java
// AppServiceProvider#boot
Queue.failing(event -> Notification.route("slack", OPS).notify(new JobFailedAlert(event)));
```

### 1.8 Horizon 等价物(v0.7)

`jaravel-dashboard`:Web 界面展示队列吞吐、等待时间、失败任务、批处理进度、worker 状态,支持重试与清理。对应 Laravel Horizon。这是**采纳率的重要加分项**——运维可见性是企业选型的硬指标。

---

## 2. 事件

### 2.1 定义与分发

```java
public record OrderPaid(Order order, Instant paidAt) {}

Event.dispatch(new OrderPaid(order, Instant.now()));
Event.dispatchIf(cond, event);
Event.dispatchAfterCommit(new OrderPaid(order, now));
```

事件用 `record` —— 不可变、简洁、自带 `equals`/`toString`。

### 2.2 监听器

```java
// 同步监听器
@Listener public class NotifyWarehouse {
    public void handle(OrderPaid e) { warehouse.reserve(e.order()); }
}

// 异步监听器(自动入队)
@Listener public class SendReceipt implements ShouldQueue {
    @Override public String queue() { return "mail"; }
    public void handle(OrderPaid e) { mailer.send(new ReceiptMail(e.order())); }
}

// 一个类监听多个事件
@Listener public class AuditLogger {
    public void handle(OrderPaid e)      { audit("paid", e.order().getId()); }
    public void handle(OrderRefunded e)  { audit("refunded", e.order().getId()); }
}

// 优先级
@Listener(priority = 100) public class CriticalFirst { ... }

// 闭包式注册
Event.listen(OrderPaid.class, e -> metrics.increment("orders.paid"));

// 通配(谨慎使用)
Event.listen("order.*", (name, payload) -> ...);
```

`@Listener` 由 APT 生成索引 + 事件类型 → 监听器的静态映射表,**零运行时反射、零类路径扫描**。

### 2.3 中止传播

```java
@Listener public class FraudCheck {
    public boolean handle(OrderPaid e) {
        if (isFraud(e.order())) return false;      // 返回 false 中止后续监听器
        return true;
    }
}
```

### 2.4 为什么不直接用 Spring 的 `ApplicationEventPublisher`

Spring 事件已经很好,但缺三件 Jaravel 需要的东西:

1. **监听器自动入队**。Spring 的 `@Async` 走线程池,不持久化——进程崩溃任务就丢了。Jaravel 的 `ShouldQueue` 监听器进持久化队列。
2. **事务后分发的默认值**。Spring 需要 `@TransactionalEventListener`,是另一个概念;Jaravel 让 `dispatchAfterCommit` 成为一等 API。
3. **测试断言**。`Event.fake()` + `assertDispatched` 是 Laravel 测试体验的重要部分。

实现上,`EventBus` 可选择**委托给 Spring 的事件机制**(让 `@EventListener` 也能收到 Jaravel 事件),配置 `events.bridge-spring: true`。两套事件系统互通,但 Jaravel 的 API 是主路径。

### 2.5 内置框架事件

```
应用:   Booting · Booted · Terminating
请求:   RequestHandled · RouteMatched
数据库: QueryExecuted · TransactionBeginning · TransactionCommitted · TransactionRolledBack
模型:   ModelRetrieved/Creating/Created/Updating/Updated/Deleting/Deleted/Restored
认证:   Login · Logout · Failed · Lockout · Registered · Verified · PasswordReset
队列:   JobQueued · JobProcessing · JobProcessed · JobFailed · JobRetryRequested
缓存:   CacheHit · CacheMissed · KeyWritten · KeyForgotten
邮件:   MessageSending · MessageSent
通知:   NotificationSending · NotificationSent · NotificationFailed
调度:   ScheduledTaskStarting · ScheduledTaskFinished · ScheduledTaskSkipped
```

---

## 3. 任务调度

### 3.1 定义

```java
public class ConsoleKernel extends Kernel {
    @Override protected void schedule(Schedule s) {

        s.job(PruneOldLogs.class).dailyAt("03:00");
        s.command("cache:clear").hourly();
        s.call(() -> metrics.flush()).everyFiveMinutes();
        s.exec("/usr/bin/backup.sh").dailyAt("04:30");

        s.job(SyncInventory.class)
         .everyTenMinutes()
         .between("08:00", "22:00")
         .weekdays()
         .timezone("Asia/Shanghai")
         .withoutOverlapping(10)             // 10 分钟锁
         .onOneServer()                      // 多实例下只跑一个
         .runInBackground()
         .when(() -> Config.bool("features.inventory-sync"))
         .skip(() -> maintenanceMode())
         .before(() -> Log.info("开始同步"))
         .after(() -> Log.info("同步结束"))
         .onSuccess(out -> ...)
         .onFailure(out -> Notification.route("slack", OPS).notify(new TaskFailed()))
         .emailOutputOnFailure("ops@example.com")
         .pingOnSuccess("https://hc-ping.com/xxx")   // 健康检查外部监控
         .description("库存同步");

        s.job(MonthlyReport.class).monthlyOn(1, "02:00");
        s.job(Heartbeat.class).cron("*/2 * * * *");
    }
}
```

### 3.2 频率方法全集

`everyMinute` `everyTwoMinutes` `everyFiveMinutes` `everyTenMinutes` `everyFifteenMinutes` `everyThirtyMinutes` `hourly` `hourlyAt(17)` `everyTwoHours` `daily` `dailyAt("13:00")` `twiceDaily(1,13)` `weekly` `weeklyOn(1,"08:00")` `monthly` `monthlyOn(4,"15:00")` `twiceMonthly(1,16)` `lastDayOfMonth` `quarterly` `yearly` `cron("* * * * *")`

约束:`weekdays` `weekends` `mondays`…`sundays` `days(1,3,5)` `between(a,b)` `unlessBetween(a,b)` `at("13:00")` `timezone(tz)`

### 3.3 运行方式

**与 Laravel 的关键差异**:Laravel 需要在 crontab 里加 `* * * * * php artisan schedule:run`,因为 PHP 无常驻进程。Jaravel 的应用本身常驻,因此:

```yaml
# config/schedule.yml
schedule:
  enabled: true
  in-process: true          # 默认:应用内置调度器,无需外部 cron
  on-one-server: redis      # 多实例去重用的锁存储
```

内置调度器用一个虚拟线程 + `ScheduledExecutorService`,每分钟对齐唤醒一次,检查到期任务。

也支持外部触发模式(`in-process: false` + `jaravel schedule:run`),用于 Kubernetes CronJob 等场景。

```bash
jaravel schedule:list                 # 列出所有任务及下次运行时间
jaravel schedule:run                  # 外部触发一次
jaravel schedule:work                 # 前台运行调度器(开发期)
jaravel schedule:test --name=...      # 立即执行某个任务
jaravel schedule:clear-mutexes
```

### 3.4 多实例去重

`onOneServer()` 用 `Cache.lock()`(见 [09](09-cache-session.md) §1.3)实现:多个应用实例同时到点,只有抢到锁的执行。

**重要警示**(文档必须写明):此机制依赖共享缓存(Redis),使用 `caffeine`/`file` 驱动时 `onOneServer()` **无效**。配置校验在启动时检测这个组合并报错,而不是静默失效。

---

## 4. 内部架构

```
jaravel-queue
├── QueueManager · QueueDriver (contract)
├── drivers/  DatabaseQueue · RedisQueue · RabbitQueue · KafkaQueue · SqsQueue · SyncQueue
├── Job (contract) · ShouldQueue · ShouldBeUnique · ShouldBeEncrypted
├── JobPayload · JsonJobSerializer
├── worker/  QueueWorker · WorkerOptions · JobScope · HeartbeatRenewer
├── middleware/ WithoutOverlapping · RateLimited · ThrottlesExceptions · Skip
├── failed/  FailedJobProvider · DatabaseFailedJobProvider
├── batches/ BatchRepository · PendingBatch · Batch
└── chain/   PendingChain

jaravel-events
├── EventBus (contract) · DispatcherImpl
├── ListenerRegistry        ← APT 生成的静态映射
├── QueuedListenerAdapter   ShouldQueue 监听器 → Job
├── SpringEventBridge       可选双向桥接
└── EventFake               测试用

jaravel-scheduler
├── Schedule · ScheduledTask
├── CronExpression          自研或用 cron-utils
├── InProcessScheduler      常驻虚拟线程
├── ExternalRunner          schedule:run 入口
└── OverlapGuard · OneServerGuard   基于 Cache.lock
```

---

## 5. 逃生口

| 需求 | 途径 |
|---|---|
| 需要 Spring `@Scheduled` | 照常使用,与 Jaravel 调度器并存 |
| 需要 Spring `@EventListener` | 开启 `events.bridge-spring`,双向互通 |
| 需要 Spring `@Async` | 照常使用 |
| 需要原生 MQ 客户端 | `Queue.connection("rabbitmq").raw()` 取底层 channel |
| 需要 Spring Batch | 照常引入,无冲突 |
| Job 需要复杂 DI | `handle(...)` 的参数从容器解析,支持任意 Spring Bean |

---

## 6. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| Worker 并发 | 多进程(重) | 虚拟线程(轻),`--concurrency=N` | JVM 优势,真实差异化价值 |
| 调度触发 | 必须外部 crontab | **默认应用内调度** | 常驻进程,少一个运维环节 |
| Job payload | PHP 序列化 | JSON | 可读、跨版本、跨语言 |
| Job 持有模型 | `SerializesModels` trait | `@SerializesModels` + APT 警告 | 主动阻止 payload 膨胀 |
| 事务后入队 | 需显式 `afterCommit()` | 配置默认开启 | 这是绝大多数场景的正确行为 |
| 事件 | 自有系统 | 自有系统 + 可选 Spring 桥接 | 生态兼容 |
| Horizon | 独立商业/开源包 | `jaravel-dashboard`(v0.7) | 运维可见性是选型硬指标 |

---

## 7. 待决问题

- [ ] Kafka 驱动的语义差异(无单任务重试、无延迟消息、offset 语义)如何在统一抽象下表达?倾向:Kafka 驱动只支持队列 API 的子集,不支持的操作明确抛异常并在文档中列表说明。或者干脆不提供 Kafka 驱动(Kafka 是事件流而非任务队列,强行统一会产生错误预期)。**倾向后者,v1.0 不提供。**
- [ ] `database` 驱动的 `SKIP LOCKED` 在 MySQL 8.0+ / PG 9.5+ 可用,老版本需降级为乐观锁(`update ... where id=? and reserved_at is null`)。需要版本探测。
- [ ] 内置调度器与多实例部署的默认行为:若用户忘记 `onOneServer()`,10 个实例会重复执行 10 次。是否应该**默认开启** `onOneServer`?倾向是——默认安全,显式 `.onAllServers()` 才并发。这是对 Laravel 的改进。
- [ ] 长任务的心跳续约与 `retry-after` 的交互:续约失败时任务可能被重复消费。需要幂等性指导(文档章节)+ `@Idempotent` 注解支持(v0.7)。
- [ ] 任务作用域 `JobScope` 与请求作用域 `RequestScope` 是否统一为一个 `ExecutionScope` 抽象?倾向统一,避免两套上下文传播逻辑。
