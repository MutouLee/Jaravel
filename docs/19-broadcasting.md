# 19 · 广播(Broadcasting)

> 实现里程碑:v0.6 · **已实现**(2026-11-16)。与设计稿的偏差都标注在
> 「v0.6 实现注记」块里。
> 阅读前提:docs/10(事件)、docs/11(通知)、docs/01 §3(请求作用域)。

## 设计目标

1. 事件可以实时推到浏览器 —— `Event.dispatch(new OrderPaid(...))` 一行,订阅方毫秒级收到;
2. 通知的 `broadcast` 渠道落地(docs/11 §2.5 里标着 v0.6 的那一行);
3. **客户端零开发成本**:服务端实现 **Pusher 协议子集**,`pusher-js` / `laravel-echo` 开箱即用 —— 不自研 JS 客户端(那是一个广播系统最贵的一部分,而它已经被做掉了);
4. 私有/存在频道的鉴权**在 WS 连接内完成**,不设 `broadcasting/auth` HTTP 端点(比 Laravel 少一个环节,理由见 §8);
5. 沿用三条主心骨:核心模块零 Spring、编译期安全、每连接一个虚拟线程 + 作用域绑定(ADR-007)。

---

## 1. 概念与整体流程

```
浏览器(pusher-js)                          应用进程(Jaravel)
───────────────────                       ─────────────────────────
WebSocket /app/jaravel                      每连接一个虚拟线程,
        │                                   绑定会话用户(§6.3)
        │ {"event":"pusher:subscribe",
        │  "data":{"channel":"orders"}}
        │ ──────────────────────────────►  频道注册表检查(§3)
        │ ◄──────────────────────────────  pusher_internal:subscription_succeeded
        │
        │                                  Event.dispatch(new OrderPaid(...))
        │ ◄──────────────────────────────  {"event":"OrderPaid","channel":"orders",
        │                                   "data":"{\"orderId\":1}"}
```

三条路径,共用同一套连接与频道机制:

| 路径 | 触发点 | 去向 |
|---|---|---|
| 事件广播 | `Event.dispatch` 遇到 `ShouldBroadcast` 事件 | `broadcastOn()` 声明的频道 |
| 通知渠道 | `via()` 里有 `"broadcast"` | 收件人的私有频道(§4) |
| 直接推送 | `Broadcast.emit(channel, event, data)` | 任意频道 |

### 1.1 频道三类

| 频道 | 形态 | 谁能订阅 | 是否需要注册 |
|---|---|---|---|
| 公开 | `orders` | 任何人,含未登录 | 不需要 |
| 私有 | `private-orders.42` | 鉴权回调返回 true | 必须 |
| 存在 | `presence-chat.1` | 同私有,另维护成员表 | 必须 |

`private-` / `presence-` 前缀是 **Pusher 协议的强制约定**,客户端库靠前缀决定走不走鉴权流程。名字只允许 `[a-zA-Z0-9._-]`,长度 ≤ 200。

---

## 2. 事件广播

### 2.1 定义

```java
public record OrderPaid(Long orderId, Long userId) implements ShouldBroadcast {

    @Override public List<String> broadcastOn() {
        return List.of("orders", "private-users." + userId);   // 可以推多个频道
    }

    @Override public Map<String, Object> broadcastWith() {
        return Map.of("orderId", orderId);                     // 默认:全字段,见下
    }

    @Override public String broadcastAs() {
        return "order.paid";                                   // 默认:类简单名
    }

    @Override public boolean broadcastWhen() {
        return true;                                           // 默认 true
    }
}

Event.dispatch(new OrderPaid(1L, 42L));   // 监听器照常跑,跑完后广播
```

接口(位于 `io.jaravel.broadcasting`,见 §6.1 为什么不在 events 模块):

```java
public interface ShouldBroadcast {
    List<String> broadcastOn();                  // 必选 —— 推到哪些频道
    default Map<String, Object> broadcastWith() { return null; }  // null = 用全字段
    default String broadcastAs() { return getClass().getSimpleName(); }
    default boolean broadcastWhen() { return true; }
}
```

> **v0.6 实现注记**:`broadcastWith()` 的「用全字段」哨兵是 **null 而不是空 Map**。
> 空 Map 是「我想发空 payload」最自然的写法 —— 若空 Map 也算「全字段」,
> `Map.of()` 这个占位会悄悄变成「什么都没发出去」。null 与空 Map
> 把「忘了写」与「有意为空」显式分开。

**`broadcastWith()` 返回 null 时的默认行为**:提取事件的**全部字段**(事件是 record,按组件提取),字段不可 JSON 表示时在**分发那一刻**报错 —— 与队列的 `JobSerializer` 同一哲学(失败在调用栈离原因最近的地方),报错信息给出同一套修复指引:「字段是 Eloquent 模型 —— 只存主键」。事件里真带着模型时,`broadcastWith()` 手工挑字段就是正确姿势。

**`broadcastAs()` 默认取类简单名**:JS 端 `bind('OrderPaid')` 与 Java 端同名,Laravel 的默认值(反斜杠 FQCN 转 `.`)在这里没有意义。

### 2.2 与事件总线的接点

事件模块**不知道广播的存在**(依赖方向,§6.1)。它在 `EventBus` 上加一个与 `QueuedListenerDispatcher` 同模式的钩子:

```java
// io.jaravel.events —— 事件模块里只出现这一个接口
public interface Broadcaster {
    void broadcast(Object event);   // 实现自行判断 instanceof ShouldBroadcast
}
EventBus.broadcaster(broadcaster);  // 由 starter 装配;未装配 = 零开销
```

钩子在 `deliver()` 的**末尾**触发,于是语义自动成立:

| 场景 | 广播行为 | 理由 |
|---|---|---|
| 被监听器中止传播(返回 false) | **不广播** | 被风控拦下的事件,不该出现在前端 |
| `Event.dispatchAfterCommit(...)` | 提交后才广播 | `deliver` 被推迟,广播跟着推迟 —— 回滚的事**没有发生过** |
| `Event.fake()` | 不广播 | fake 模式不走 `deliver`,没有真实的监听器,也没有真实的广播 |
| 序列化失败 | 抛给调用方 | 事件是同步语义(见 EventBus 类注释「同步监听器是调用方事务的一部分」),广播同为同步。网络层的失败(连接断开、发送超时)由驱动吞掉 —— 推送尽力而为,只有「payload 根本没法序列化」这种**程序员错误**才向上抛 |

**v0.6 广播是同步的**。Laravel 的 `ShouldBroadcast` 默认走队列;Jaravel 把「事件入队广播」留到 v0.7 与队列驱动(Kafka/RabbitMQ/SQS)一起做 —— 那时事件实现 `ShouldQueue` 即自动入队,API 不变。

### 2.3 直接推送

```java
// 不经事件总线,直接推。也用于「这条消息不配拥有一个事件类」的场景
Broadcast.emit("orders", "refresh", Map.of("at", Instant.now()));
```

`Broadcast.emit` 同时是 §7 逃生口的一部分 —— 用户随时可以绕过事件层直接操作连接。

---

## 3. 频道与鉴权

### 3.1 注册

```java
// app/channels/Channels.java —— 与 WebRoutes 同一发现机制(扫描 BroadcastChannels 实现)
public class Channels implements BroadcastChannels {

    @Override public void define(ChannelRegistry channels) {
        channels
            .channel("private-orders.{orderId}", (User user, Map<String, String> params) ->
                    user.id.equals(Long.parseLong(params.get("orderId"))))

            .presence("presence-chat.{room}",
                (User user, Map<String, String> params) -> memberOf(user, params.get("room")),
                (User user, Map<String, String> params) -> Map.of("name", user.name))

            .whisper("chat.{room}");   // 允许该频道的 client-* 客户端事件
    }
}
```

```java
public interface BroadcastChannels {
    void define(ChannelRegistry registry);
}

public interface ChannelRegistry {
    <T> ChannelRegistry channel(String pattern,
                                BiFunction<T, Map<String, String>, Boolean> check);
    <T> ChannelRegistry presence(String pattern,
                                 BiFunction<T, Map<String, String>, Boolean> check,
                                 Function<T, Map<String, String>, Map<String, Object>> info);
    ChannelRegistry whisper(String pattern);
}
```

- **公开频道不需要注册** —— 与 Laravel 一致。注册表只回答「这个私有/存在频道,谁可以进」。
- **泛型 `T` 靠擦除工作**:回调写成 `(User u, ...) -> ...`,运行时把连接的用户对象强转;类型不匹配(guard 返回的不是 User)→ 拒绝订阅,错误消息说清「频道回调声明的类型与 guard 返回类型不一致」。编译期类型安全,运行时不引入反射解析。
- **未登录的连接订阅私有/存在频道 → 直接拒绝**,回调根本不会跑 —— 回调里 `user` 恒非 null。
- **回调在连接的作用域里执行**(§6.3):`Auth.user()`、`DB` 门面在回调里照常可用 —— 这是把鉴权挪进 WS 连接之后才有的能力,Laravel 的 `channels.php` 回调虽然跑在 HTTP 里,但 WS 服务侧拿不到这个上下文。
- 回调抛异常 → 拒绝订阅 + 记日志,**不把异常细节泄漏给客户端**(订阅失败消息只含频道名)。

### 3.2 模式语法

```
orders.{orderId}        {x} 捕获一个段(非空、不含点)
private-users.*         * 只允许出现在末尾,匹配任意剩余段
```

匹配规则:按 `.` 分段;`*` 段匹配一切。多个模式可注册在同一前缀下(如 `private-orders.{orderId}` 与 `private-orders.{orderId}.{item}`)。未注册的私有/存在频道 → `pusher_internal:subscription_error`。

> **v0.6 实现注记**:`*` 实际**至少匹配一个段** —— 频道名不允许以点结尾,
> 「空余段」在合法频道名里不存在(设计稿写的「含空」被实现收窄,无害)。
> whisper 注册**不参与鉴权**(它没有回调),注册表在鉴权匹配时跳过它们 ——
> 这曾是实现中的一个真 bug:whisper 与频道规则同名时,whisper 的空回调
> 会把合法订阅误判为拒绝。

### 3.3 whisper(客户端事件)

频道默认**拒绝**客户端事件。`whisper(pattern)` 允许该频道上所有 `client-*` 消息在成员间转发(不回声给发送者)。Pusher 的语义:客户端事件只能在私有/存在频道上发,公开频道不允许 —— 实现照此校验。

---

## 4. 通知 broadcast 渠道

### 4.1 定义

```java
public class OrderShipped extends Notification implements HasBroadcast {

    @Override public List<String> via(Notifiable n) {
        return List.of("database", "broadcast");
    }

    @Override public BroadcastMessage toBroadcast(Notifiable n) {
        return BroadcastMessage.of(Map.of("orderId", orderId));
    }
}

user.notify(new OrderShipped(...));   // database 渠道照常,另推一条 WS 消息给该用户
```

沿用现有渠道机制的全部语义(docs/11 §2.6):`shouldSend` / `shouldQueue` / 每渠道一条队列任务 / `NotificationSent` / `NotificationFailed` 事件 —— 广播渠道只是**又一个 `NotificationChannel`**:

```java
public class BroadcastChannel implements NotificationChannel {
    @Override public String name() { return "broadcast"; }
    @Override public boolean requiresRoute() { return false; }   // 频道由渠道自己推导
    @Override public void send(Notifiable n, Notification notification) {
        var msg = require(n, notification, HasBroadcast.class).toBroadcast(n);
        hub.publish(channelOf(n), notification.displayName(), msg.data());
    }
}
```

`via()` 里有 `"broadcast"` 但通知没实现 `HasBroadcast` → 复用 `require()` 的报错(接口存在就是为了这个,编译器会要求你把方法写对)。

### 4.2 收件人频道

默认推导:**`private-{notifiableType}.{notifiableId}`**(如 `private-user.42`)。模型可以覆盖:

```java
public class User implements Notifiable, BroadcastNotifiable {
    @Override public String receivesBroadcastNotificationsOn() {
        return "private-admin." + id;          // 默认:private-user.{id}
    }
}
```

> **v0.6 实现注记**:覆盖方法不在 `Notifiable` 上,而在广播模块的
> **`BroadcastNotifiable`** 接口上 —— 通知模块不该知道「广播」这个概念,
> 依赖方向是 broadcasting → notifications(ArchUnit 强制,见 §6.1)。

没有实现 `notifiableId()` 的 Notifiable(只用 mail 渠道的那种)走 broadcast 时**报错** —— 这与 `NotificationManager.safeId` 的容错不同:那里是「事件里带个说明」,这里是「频道名都拼不出来,消息无处可去」,不能静默。

---

## 5. 客户端与协议

### 5.1 客户端配置(pusher-js)

```js
import Pusher from 'pusher-js';

const pusher = new Pusher('jaravel', {         // appKey,与 config/broadcasting.yml 一致
  cluster: 'mt1',                              // 用自定义 wsHost 时这个值随意
  wsHost: location.hostname,
  wsPort: 8080,                                // 与应用同一端口
  forceTLS: false,
  enabledTransports: ['ws'],
  disableStats: true,
});

// 公开频道
pusher.subscribe('orders').bind('OrderPaid', data => render(data.orderId));

// 私有/存在频道:pusher-js 强制要求一个 auth 提供者 —— 服务端凭会话 cookie 鉴权,
// 该字段被忽略,这里只需喂一个占位值让它把订阅发出去
const authorizer = {
  customHandler: (params, callback) => callback(null, { auth: 'jaravel' }),
};

pusher.subscribe('private-orders.42');         // 服务端在订阅消息上鉴权(§3)
pusher.subscribe('presence-chat.1')
  .bind('pusher:member_added', m => addMember(m.info));
```

`laravel-echo` 经 Pusher 连接器同样可用;什么都不想装时,协议是文档化的(§5.2),原生 `WebSocket` 也能写。

### 5.2 Pusher 协议子集(实现清单)

连接端点是协议规定的 `/app/{appKey}`,查询串里的 `protocol` / `client` / `version` 被忽略。消息全部是文本帧 JSON。

| 方向 | 消息 | 说明 |
|---|---|---|
| S→C | `pusher:connection_established` | `data` 是 **JSON 字符串**:`{"socket_id":"123.456","activity_timeout":120}`。`socket_id` = `进程内自增.纳秒` |
| C→S | `pusher:subscribe` | `data:{channel}`;私有/存在频道的 `auth` / `channel_data` 字段**被忽略**(凭会话鉴权,§3) |
| S→C | `pusher_internal:subscription_succeeded` | 公开/私有:`data:"{}"`;存在:见下 |
| S→C | `pusher_internal:subscription_error` | `data` 为 JSON 字符串 `{"type":"AuthError"}` |
| C→S | `pusher:unsubscribe` | `data:{channel}` |
| C→S | `pusher:ping` | 按 `activity_timeout` 定期发 |
| S→C | `pusher:pong` | 回应 ping |
| S→C | 应用事件 | `{"event":"OrderPaid","channel":"orders","data":"{\"orderId\":1}"}` —— `data` 是 JSON 字符串 |
| C→S | `client-*` | 仅 whisper 频道(§3.3),转发给频道内**其他**成员 |
| S→C | `pusher_internal:member_added` / `member_removed` | `data` 为 JSON 字符串 `{"user_id":"42","user_info":{...}}` |

存在频道的 `subscription_succeeded`:`data` 为 JSON 字符串

```json
{"presence": {"ids": ["1","42"], "hash": {"1": {...}, "42": {...}}, "count": 2}}
```

`user_info` 来自注册时的 info 回调(§3.1),`user_id` 为 `String.valueOf(notifiableId)` 的等价物 —— 连接用户的 id 字符串化。

### 5.3 明确不实现的 Pusher 特性

| 特性 | 不做 | 理由 |
|---|---|---|
| 加密频道(`private-encrypted-`) | 否 | 订阅者要解密,服务端只是转发;没有客户端诉求 |
| 多 app key 隔离 | 否 | 一个应用进程只有一个 key;配置校验即可 |
| WebHook / 在线状态 API | 否 | 属于 pusher 云服务的能力 |
| 消息持久化/回放 | 否 | 广播是「此刻发生」;历史用 polling / SSE 兜底(§7) |

---

## 6. 内部架构

### 6.1 模块划分与依赖

```
jaravel-broadcasting                              ★ 零 Spring
├── ShouldBroadcast · BroadcastMessage
├── ChannelRegistry · BroadcastChannels           频道注册与模式匹配
├── BroadcastManager                              驱动管理 + fake;门面 Broadcast 在模块内
├── driver/ NullBroadcaster · LogBroadcaster · BroadcastHub(契约)
└── channel/ BroadcastChannel                     通知渠道实现
```

依赖:core / support / events / notifications / Jackson。**不依赖 auth 模块** —— 频道回调的用户参数是擦除泛型(§3.1),运行时对象就是 guard 解析出来的那个。

`ShouldBroadcast` 放广播模块而不放 events 模块:事件模块不该知道「广播」这个概念。它只暴露一个 `Broadcaster` 钩子(§2.2),与队列的 `QueuedListenerDispatcher` 是同一个模式 —— 事件系统对「谁在听」一无所知,插件系统想加什么加什么。

starter 侧(`jaravel-spring-boot-starter`,唯一碰 Spring 的地方):

```
JaravelBroadcastingAutoConfiguration
├── 给 EventBus 装上 EventBusBroadcaster(判断 instanceof ShouldBroadcast)
├── 给 NotificationManager 注册 BroadcastChannel
├── driver = ws 时:
│     ├── 注册 WebSocketConfigurer(/app/{app-key} → JaravelWebSocketHandler)
│     ├── 握手拦截器:Origin 校验 + 会话用户解析(§6.4)
│     └── 依赖 spring-boot-starter-websocket(新增)
└── driver = null 时:注册 NullBroadcaster(debug 下警告一次「广播被丢弃」)
```

> **v0.6 实现注记(两个实现期的发现)**:
>
> 1. **Spring Boot 3.4 移除了对 `DelegatingWebSocketConfiguration` 的自动导入** ——
>    `WebSocketConfigurer` bean 不再被自动收集,端点静默 404(这是 e2e 测试
>    抓出来的,单测永远看不见)。Jaravel 的自动装配显式
>    `@Import(DelegatingWebSocketConfiguration.class)`,用户不必写
>    `@EnableWebSocket`。升级 Spring Boot 大版本时要重验这条。
> 2. **新增 `config/broadcasting.yml` 必须同步 `JaravelAutoConfiguration.CONFIG_FILES`** ——
>    打包后的配置从 classpath 按显式清单加载,漏列的症状是
>    「本地好的,打包后端点 404」,与已有清单的注释同一理由。

### 6.2 驱动

| 驱动 | 状态 | 说明 |
|---|---|---|
| `null` | ✅ v0.6 | 默认。**不是静默**:debug 模式首次丢弃时打一条警告,指明 `BROADCAST_DRIVER=ws` |
| `log` | ✅ v0.6 | 每次广播一行 JSON(channel / event / payload)—— 开发与单测 |
| `ws` | ✅ v0.6 | 嵌入式 WebSocket + 本地连接注册表(本节) |
| `redis` | ✅ v0.7 | pub/sub 把广播扇出到其它实例;presence 成员表也放进 Redis |

```yaml
# config/broadcasting.yml
broadcast:
  driver: ws                  # null | log | ws | redis
  app-key: jaravel            # pusher-js 的 key,也是 WS 端点的路径段
  origins: []                 # 允许的 Origin;空 = 仅同源(§6.5)
  activity-timeout: 120s      # 客户端应至少每 120s 发一次 pusher:ping
  redis:
    uri: redis://localhost:6379/0
    prefix: jaravel:broadcast:
  presence-ttl: 5m            # 必须大于 activity-timeout
```

> ### v0.7:redis 驱动已实现(多实例扇出 + 分布式 presence)
>
> `RedisWsHub extends LocalWsHub` —— 协议层、连接队列、频道登记
> 原样复用,**只换两件事**:
>
> 1. `publish`:先 `super.publish` 本地同步投递,再发一条 pub/sub
>    消息给其它实例。消息带 `source`(实例 id),本实例忽略自己发的那条,
>    否则本地会收到两遍。Redis 断开时本地投递照常,只打一次警告 ——
>    广播是尽力而为。
> 2. **presence**:每个实例一个 Redis hash
>    `{prefix}presence:{channel}:{instanceId}`,字段是连接 id、值是成员
>    JSON。连接的**任何活动**(ping / 订阅 / 事件)都续期这个 hash 的
>    TTL;实例崩溃后成员会在 TTL(默认 5 分钟)后自然消失。
>    `membersOf` 用 SCAN 合并所有实例的 hash;join / leave 走 pub/sub,
>    让对面广播 `pusher_internal:member_added / member_removed`。
>
> 测试:`RedisWsHubTest`(2 条,gated by `-Djaravel.test.redis.uri`):
> 两个 hub 共用一个前缀、各带假 WS 连接 —— A 的 publish 同时到 A 与 B;
> B 加入 presence 后 A 的成员表有两个成员并收到 member_added,
> B 退订后 A 收到 member_removed。
>
> **仍未做**:跨域 cookie、类型化频道参数、广播面板进工具栏、
> `ShouldBroadcast` 的队列化(队列驱动已就绪,但事件 → 广播的
> ShouldQueue 桥接还没接)。

> **v0.6 实现注记**:设计稿里的 `pong-timeout` 没做 —— 实现用一个
> 活动超时(1.5 × activity-timeout 无任何消息即断)覆盖同样的语义,
> 两个超时参数只会让配置面变宽而换不来任何行为差异。

驱动契约刻意收窄成一个:

```java
public interface BroadcastHub {
    void publish(String channel, String event, Map<String, Object> data);
}
```

v0.7 的 redis 驱动在 `publish` 里加一步 pub/sub:本地先同步投递,
再把消息发给其它实例;订阅端收到后调同一个本地投递路径 ——
契约不用改(实现见 §6.2 的 v0.7 注记)。

### 6.3 连接模型(每连接一个虚拟线程)

```
握手(HTTP Upgrade)
  │
  ▼
[Origin 校验] → [会话用户解析] ──► WsConnection {
  │                                  id = "进程内自增.纳秒"
  │                                  user = Optional<Authenticatable>
  │                                  ExecutionScope 绑定(ADR-007 的机制)
  │                                  订阅集合 channel → 已注册
  │                                  发送队列(单写者)
  │                                }
  ▼
[连接线程] 每条消息按序处理:subscribe / unsubscribe / client-* / ping
  │          · 出站也走同一线程 —— Tomcat 的 WebSocketSession 不保证并发写安全,
  │            单写者让并发问题从结构上不存在
  │          · 断开或超时:清理订阅、presence 发 member_removed、释放 scope
  ▼
[广播路径] hub.publish(channel, event, data)
             → 本地注册表 channel → 连接集合
             → 逐连接投递(序列化 JSON → 入发送队列)
```

**为什么每连接一个作用域而不是每消息一个**:连接存活期间「我是谁」只解析一次(会话里的用户 id),订阅集合、presence 成员数据都是连接级状态;消息级作用域会让「鉴权回调里查了一次用户,下一个消息又查一次」变成常态。这是 HTTP 每请求一个作用域的 WS 对应物。

### 6.4 握手与用户解析

WS 握手是 HTTP Upgrade 请求,**不走 Jaravel 的中间件管道**(那是路由层的),所以握手拦截器自己完成三件事:

1. **Origin 校验**(§6.5);
2. 读会话 cookie → `SessionStore.load(id)` → `login_{guard}` 键 → `UserProvider.retrieveById(...)`。会话过期但 remember cookie 有效时,走 guard 的既有恢复路径(登录时轮换令牌的语义不变);
3. 得到用户后绑进连接作用域。此后频道鉴权回调(§3)、presence info 回调都在「已登录」的上下文里跑。

**不在广播模块里重写一套用户解析** —— 全部复用 guard / session 的既有机制,广播只做「把 HTTP 请求上下文换成连接上下文」。

### 6.5 安全

| 威胁 | 对策 |
|---|---|
| **WS 跨站劫持**(恶意页面开 WS,浏览器会带上同站 cookie) | 握手时校验 `Origin`:与 Host 一致(默认),或落在 `origins` 白名单(跨域开发时)。无 Origin 的非浏览器客户端放行 |
| CSRF | WS 不受 `VerifyCsrfToken` 保护,但也不受它威胁 —— 没有自动凭据,风险面已被 Origin 校验覆盖 |
| 未登录订阅私有频道 | 直接拒绝,回调不跑(§3.1) |
| 鉴权回调泄漏异常细节 | 客户端只收到频道名,细节进日志(§3.1) |
| 慢回调占死连接线程 | 每连接独立虚拟线程 —— 阻塞一个连接不影响其他连接 |

### 6.6 测试支持

```java
Broadcast.fake();                              // 拦截广播,不真的推
Event.dispatch(new OrderPaid(1L, 42L));        // 事件真分发,广播被记录
Broadcast.assertBroadcasted(OrderPaid.class);
Broadcast.assertBroadcastedTo("orders", OrderPaid.class);
```

- `Event.fake()` 时广播**自然不发生**(§2.2 的表),所以测「事件 → 广播」要的是 `Broadcast.fake()`:事件真跑、广播拦截;
- 通知 broadcast 渠道在 `Notifications.fake()` 下走通知自己的 fake,与其它渠道一致;
- 端到端:JDK 自带 `java.net.http.WebSocket` 客户端,FeatureTest 里开 `driver=ws` 直接连真实端点。

---

## 7. 逃生口

| 场景 | 逃生口 |
|---|---|
| 协议不够用 / 要 STOMP | 用户照常注册自己的 `WebSocketConfigurer` + `TextWebSocketHandler`,与 Jaravel 的端点并存不冲突;要 Spring 的 STOMP broker 也照常引入 |
| 想要 SSE 而不是 WS | `Response.stream()`(docs/03 已标注未实现,v0.9 一并补);广播与 SSE 共用同一套「事件 → 频道 → 订阅者」机制 |
| 想用外部 Pusher / Ably 云服务 | 实现 `BroadcastHub` 调其 REST API(一个接口,几十行),注册进 `BroadcastManager` |
| 想绕过事件层直接推 | `Broadcast.emit(channel, event, data)` |
| 客户端不想用 pusher-js | 协议是文档化的(§5.2),原生 `WebSocket` 直接写 |
| 消息要持久化/回放 | 这不是广播的职责 —— 落库 + polling,或消息队列 |

---

## 8. 与 Laravel 的差异

| Laravel | Jaravel | 理由 |
|---|---|---|
| `broadcasting/auth` HTTP 端点签发频道授权 | **没有这个端点**;鉴权在 WS 订阅消息上凭会话 cookie 完成 | Laravel 需要端点是因为 WS 服务与 HTTP 应用是两个进程(Reverb / Pusher 云)。Jaravel 的 WS 嵌在应用里,握手带着会话,再走一遍 HTTP 往返是纯仪式。代价:pusher-js 要多写一个占位 authorizer(§5.1);跨域场景受 cookie 政策影响(§9) |
| `broadcast(new X)` 只广播不触发监听器 | `Broadcast.emit(...)` | 语义相同,名字不绑死「事件对象」这个形态 |
| `ShouldBroadcast` 默认队列化 | v0.6 同步;v0.7 队列驱动已就绪,但事件 → 广播的 ShouldQueue 桥接**仍未接** | 广播的队列化要比队列驱动多一层「事件实现 ShouldQueue 时如何入队」;API 不变,列入后续 |
| `broadcastAs` 默认 = FQCN 转 `.` | 默认 = 类简单名 | 没有 `App\Events\` 命名空间要表达;JS 端 `bind('OrderPaid')` 与 Java 端同名 |
| `channels.php` 回调有依赖注入与模型绑定 | 频道参数是 `Map<String,String>` 字符串,回调里手动转换 | Laravel 的参数注入来自其容器解析字符串;Jaravel 不做字符串 → 类型的隐式解析(类型化 helper 在 §9) |
| auth 回调跑在 HTTP 请求上下文 | 回调跑在**连接作用域** | 这是没有端点的直接后果,同时是个升级:`Auth.user()` 在鉴权回调里照常可用 |
| 默认连接 pusher.com 云服务 | 默认 `null`(显式关),`ws` 驱动嵌在应用里 | 不强制云依赖;不静默 —— null 驱动 debug 下警告 |

---

## 9. 待决问题

- [ ] **跨域前端的 cookie**:vite dev server(5173)调 8080 的应用时,WS 握手与 AJAX 都不保证带第三方 cookie(浏览器政策在收紧)。同站部署没有这个问题。真需要跨域时,备选路径是补一个可选的 `broadcasting/auth` 端点(与 Laravel 同款,内部 HMAC 签名)作为兼容模式 —— v0.6 不做,先看同站够不够。
- [ ] **类型化频道参数**:`{orderId}` 自动转 `Long` 之类(形式如 `"private-orders.{orderId:long}"`)。`ArgumentBinder` 已有转换逻辑,但频道注册表在零 Spring 模块,复用需要把转换器上提。倾向 v0.7 做;v0.6 的字符串 Map 与 Laravel 的字符串参数等价。
- [x] **redis 驱动**(v0.7):已实现 `RedisWsHub` —— pub/sub 扇出 +
  每实例一个 presence hash + TTL(见 §6.2 的 v0.7 注记)。
- [ ] **广播面板进调试工具栏**:事件面板已按线程收集事件名;
  广播可以并进去或单开面板。随 dashboard(v0.7)一起评估。
- [ ] **`ShouldBroadcast` 的队列化**:队列驱动(含 redis)已就绪,
  但事件 → 广播的 ShouldQueue 桥接还没接;同步广播的 API 不变。
- [x] **presence 的 user_id 从哪来**:已实现为「连接用户的 id 字符串化」(`Authenticatable.getAuthIdentifier()`,非 Authenticatable 用 `toString`)—— 与 `Notifiable.notifiableId()` 解耦,一个只做 WS 不通知的用户类型不需要实现 Notifiable。
- [x] **握手时会话续期**:已实现为「连接建立时定格」—— 与 Laravel 的 pusher auth(一次性 HTTP 检查)语义一致。重验意味着「踢人」语义,那是另一个功能,不做。
