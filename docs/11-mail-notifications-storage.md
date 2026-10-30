# 11 · 邮件、通知、文件存储

三个相对独立但都属于「外部副作用」的模块,共享同一套驱动抽象 + 假实现测试模式。

---

## 1. 邮件

### 1.1 Mailable

```java
public class OrderShipped extends Mailable {

    private final Order order;
    public OrderShipped(Order order) { this.order = order; }

    @Override public Envelope envelope() {
        return Envelope.of()
            .from("noreply@shop.com", "小店")
            .replyTo("support@shop.com")
            .subject("订单 #" + order.getNumber() + " 已发货")
            .tags("order", "shipping")
            .metadata("order_id", order.getId());
    }

    @Override public Content content() {
        return Content.view("mail.orders.shipped")       // jblade 模板
                      .with("order", order)
                      .with("tracking", order.trackingUrl())
                      .text("mail.orders.shipped-text"); // 纯文本备用版本
    }

    @Override public List<Attachment> attachments() {
        return List.of(
            Attachment.fromStorage("invoices/" + order.getId() + ".pdf").as("发票.pdf"),
            Attachment.fromData(() -> qrBytes, "qr.png").withMime("image/png")
        );
    }

    @Override public List<Header> headers() {
        return List.of(Header.of("X-Order-Id", order.getId().toString()));
    }
}
```

### 1.2 发送

```java
Mail.to(user).send(new OrderShipped(order));
Mail.to("a@b.com").cc(manager).bcc(audit).send(mailable);
Mail.to(user).locale("en").send(mailable);               // 按收件人语言渲染
Mail.mailer("ses").to(user).send(mailable);
Mail.to(users).queue(new OrderShipped(order));            // 入队发送(推荐)
Mail.to(user).later(Duration.ofHours(1), mailable);

// 预览(开发期,浏览器直接看渲染结果)
r.get("/dev/mail/order-shipped", () -> new OrderShipped(Order.find(1L)));
```

`Mailable` 可直接作为控制器返回值 → 渲染为 HTML 预览。这是 Laravel 的好设计,照搬。

> **v0.4 实现说明 —— 最大的一处偏差:入队发送是「投递时渲染」**
>
> Laravel 的 `Mail::queue($mailable)` 把 **Mailable 本身**序列化进队列
> payload(靠 `SerializesModels` 把模型换成主键,worker 那边再查回来)。
>
> Jaravel 的做法是:**在投递时把邮件渲染完**,把渲染结果
> (`RenderedMessage`)放进 payload。四条理由:
>
> 1. **Mailable 不再有任何序列化约束** —— 它可以持有模型、接口、Lambda。
>    Laravel 的方案里 `private Order order` 是个陷阱(模型快照过期),
>    而这个项目在队列那边已经为此立了「只存主键」的规矩;
>    邮件这里不必再让用户记一遍;
> 2. **没有「模型被删了导致邮件发不出」这类失败** —— Laravel 的方案里
>    worker 查不到那个订单,邮件永远发不出去,而失败原因指向
>    「订单不存在」,与「发货通知」毫无关系;
> 3. **队列里慢的是 SMTP 往返,不是渲染** —— 渲染是本地、微秒级、
>    确定性的;SMTP 是网络、数百毫秒、会失败的。入队的目的是把后者挪走;
> 4. **语义上也更对** —— 邮件描述的是已经发生的事,它应该反映投递
>    那一刻的状态。
>
> 代价(都写在 `Mailable` 的类注释里):渲染发生在请求线程;
> `Attachment.fromData` 的字节会进 payload(所以 `fromPath` 只传路径,
> 由 worker 读盘;超过 1 MB 的内联附件会警告)。
>
> 所以记住这个区别:**`ShouldQueue` 的字段只存主键,`Mailable` 的字段随便放。**
>
> **安全:地址与头部里的换行被拒。** SMTP 的头部以 CRLF 分隔,
> 一个 `"a@b.com
Bcc: attacker@evil.com"` 会变成一个真实的密送头 ——
> 邮件头注入。这个检查在**构造地址时**做而不是留给驱动:
> 留给驱动的话每个驱动都得记得做一遍,而漏掉的那个就是漏洞所在。
> 显示名里的换行**清理**而不是报错(昵称来自用户资料,
> 一个带换行的昵称不该让「发不出邮件」)。
>
> **没给纯文本版本时自动从 HTML 退化生成一个。** 不是为了
> 「某些客户端不支持 HTML」,而是:只有 HTML 的 multipart 邮件会加
> 垃圾邮件评分;很多客户端用文本版本生成收件箱摘要(没有它时那行
> 会是「如果无法正常显示请点击这里」);以及可访问性。
>
> **几个「宁可报错也不静默」的点**:没有收件人、没有主题、没有正文、
> 发件人缺失(两者都没配)、用了模板但没装渲染器 —— 全部报错。
> 一封 From 为空的邮件会被接收方拒掉,而拒信里的
> `501 Bad address syntax` 与「忘了配 mail.from.address」之间
> 没有任何线索。

### 1.3 驱动

| 驱动 | 实现 |
|---|---|
| `smtp` | Jakarta Mail |
| `ses` | AWS SDK v2 |
| `sendgrid` / `mailgun` / `postmark` / `resend` | HTTP API |
| `log` | 写日志(开发默认) |
| `array` | 内存(测试) |
| `failover` | 多驱动依次尝试 |
| `roundrobin` | 轮询分摊 |

```yaml
# config/mail.yml
mail:
  default: ${MAIL_MAILER:log}
  from:
    address: ${MAIL_FROM:noreply@example.com}
    name: ${MAIL_FROM_NAME:Jaravel}
  mailers:
    smtp:
      transport: smtp
      host: ${MAIL_HOST}
      port: ${MAIL_PORT:587}
      encryption: tls
      username: ${MAIL_USERNAME}
      password: ${MAIL_PASSWORD}
      timeout: 10s
    failover:
      transport: failover
      mailers: [ ses, smtp ]
```

### 1.4 邮件模板组件

提供一套内置的邮件布局组件(对应 Laravel 的 Markdown mail),解决「邮件 HTML 兼容性是地狱」这个问题:

```html
@params(Order order, String tracking)
<x-mail::message>
# 订单已发货

你的订单 **#{{ order.number }}** 已经发出。

<x-mail::button :url="tracking">
查看物流
</x-mail::button>

<x-mail::table>
| 商品 | 数量 |
|------|-----|
@foreach (order.items() as item)
| {{ item.name }} | {{ item.qty }} |
@endforeach
</x-mail::table>

<x-mail::panel>
预计 3 个工作日内送达。
</x-mail::panel>

谢谢,<br>{{ config('app.name') }}
</x-mail::message>
```

内置组件自带 inline CSS(邮件客户端不支持外部样式表),支持主题定制(`jaravel vendor:publish --tag=mail-theme`)。

> **v0.4 未实现这一节。** `<x-mail::message>` 这套组件需要 jblade 的
> **组件支持**,而视图层还没有它(没有 `<x-...>` 的解析)。
>
> 替代物是 `Mailable.simple()`:一个**不需要模板**的构建器
> (`subject` / `line` / `action`),用一段手写的、带内联样式的 HTML
> 布局渲染。它刻意很保守 —— 用 `<table>` 做布局(Outlook 的渲染引擎
> 是 Word,对 div + CSS 的支持极不可靠)、不用现代 CSS、
> 按钮是一个有 padding 的 `<a>`(`<button>` 在邮件里不可点)。
>
> 它的纯文本版本是**手写的**而不是从 HTML 退化的 —— 因为这里知道结构,
> 能把按钮变成「文字:URL」,而退化版本会把链接整个丢掉,
> 而那正是收件人要点的东西。
>
> 「够用」的标准:一封通知邮件在 Gmail / Outlook / Apple Mail 里
> 都能正常显示。要完全控制外观就写自己的模板(`Content.view(...)`)。

> **v0.4 的驱动实现情况**
>
> | 驱动 | 状态 |
> |---|---|
> | `log` / `array` | ✅ |
> | `smtp` | ✅(Jakarta Mail,optional 依赖) |
> | `failover` / `roundrobin` | ✅ |
> | `ses` / `sendgrid` / `mailgun` / `postmark` / `resend` | ❌ 未实现 |
>
> 那五个 HTTP API 驱动各需要一个 HTTP 客户端与各自的 API 适配。
> 配了它们会报一条说明这件事的错,并指出**这几家都提供 SMTP 中继** ——
> 所以用 `smtp` 驱动就能接上它们,只是拿不到 tags/metadata 这类
> API 特有的能力。
>
> **关于 `failover`,一条使用前必须知道的事**:它**不保证只发一封**。
> 一个驱动可能先把邮件投出去、然后在返回前失败(SES 接受了请求但
> 连接在读响应时断开),那时 failover 会换下一个再发一遍。
> 这不是实现缺陷,是 at-least-once 的固有性质。
> 对通知类邮件没问题(重复一封比一封都没有好);
> 对**带一次性令牌**的邮件(密码重置)要想一下 ——
> 两封邮件里的令牌不同,用户点了旧的那封会失败,
> 那个体验不如「发送失败,请重试」。
>
> **`roundrobin` 不做容错。** 常见误用是以为它顺带有容错。
> 它没有:这一轮轮到的驱动失败了,这封邮件就失败了。
> 那个误用的后果很具体 —— 两个服务商之一挂掉时**一半的邮件发不出去**,
> 于是监控看到的是「成功率 50%」而不是「某个服务商挂了」。
> 要两者都有就把 failover 套在外面。
>
> **`mail.default` 默认是 `log`** —— 一个没配 `MAIL_MAILER` 的应用
> 不会真的发邮件。这是对的默认值(用一份生产数据的副本跑测试时
> 不会给真实用户发邮件),代价是生产环境忘配时邮件静默不发,
> 所以 starter 在生产环境检测到一个不真发的驱动时**大声警告**
> (警告而不是报错:预发环境刻意用 log 是常见做法)。
>
> **`log` 驱动默认不打正文。** 邮件正文常含个人信息(收货地址、
> 订单明细、重置链接),而日志通常比数据库更容易被读到。
> 开发模式下(`app.debug=true`)默认打开 —— 本地走密码重置流程
> 必须看得到那个链接,而那个环境里没有真实用户数据。
>
> **`Mail.to(users)` 是一封邮件、多个收件人**,与 Laravel 一致。
> 那通常不是你想要的:它泄露所有人的邮箱、任何人「全部回复」
> 会发给所有人、个性化无从实现。要一人一封就显式循环 ——
> 不替用户自动拆成 N 封,因为那会让一次调用变成 N 次 SMTP 往返,
> 而调用方应该知道这件事。
>
> **`Mail.to(user).locale("en")` 目前不生效**(会警告一次)。
> 按语言渲染需要视图层的「按语言找模板」机制或一套翻译文件 ——
> 那属于本地化,而 Jaravel 还没有这个模块。
> 现在的办法是自己按语言选模板名:`Content.view("mail.shipped." + locale)`。
>
> **`Attachment.fromStorage(...)` 未实现** —— 它需要存储模块(§3)。
> 调它会报错说明这件事,而不是静默当成本地路径:
> 后者会在「配了 S3 但附件读的是本地盘」时产生一个非常困惑的
> `FileNotFoundException`。

---

## 2. 通知

通知是「同一条消息,多渠道投递」的抽象。

### 2.1 定义

```java
public class InvoicePaid extends Notification {

    private final Invoice invoice;
    public InvoicePaid(Invoice invoice) { this.invoice = invoice; }

    @Override public List<String> via(Notifiable n) {
        List<String> channels = new ArrayList<>(List.of("database"));
        if (n.prefersEmail()) channels.add("mail");
        if (n.hasPhone())     channels.add("sms");
        return channels;
    }

    @Override public boolean shouldQueue() { return true; }      // 异步投递
    @Override public String queue()        { return "notifications"; }

    public Mailable toMail(Notifiable n) {
        return Mailable.simple()
            .subject("发票已支付")
            .line("你的发票 #" + invoice.getNumber() + " 已支付。")
            .action("查看发票", Url.route("invoices.show", invoice.getId()))
            .line("谢谢使用我们的服务!");
    }

    public Map<String, Object> toDatabase(Notifiable n) {
        return Map.of("invoice_id", invoice.getId(), "amount", invoice.getAmount());
    }

    public SmsMessage toSms(Notifiable n) {
        return SmsMessage.of("发票 #" + invoice.getNumber() + " 已支付");
    }

    public SlackMessage toSlack(Notifiable n) {
        return SlackMessage.of()
            .text("发票已支付")
            .attachment(a -> a.title("#" + invoice.getNumber()).color("good"));
    }

    @Override public String uniqueId() { return "invoice-paid-" + invoice.getId(); }
}
```

> **v0.4 实现说明与四处偏差**
>
> **① 渠道方法由**接口**声明,不靠反射找。** docs/11 §2.1 里的
> `toMail` / `toDatabase` 是裸方法;Jaravel 要求
> `implements HasMail` / `HasDatabase` / `HasSlack` / `HasSms` / `HasWebhook`。
>
> 理由是这组 API 最容易出的那个错:`via()` 声明了 `"sms"` 但忘了写
> `toSms`,或者把名字拼成 `toSMS`。反射方案里拼错**不会报错** ——
> 找不到方法就当成「这个渠道不发」,于是「短信没发出去」没有任何征兆。
> 接口会让编译器要求你写对。
>
> 另一面要承认:`via()` 返回的字符串与接口之间仍然没有编译期关联
> (via 是运行时决定的,它要看收件人的偏好)。所以渠道在找不到接口时
> 报错并说清**是哪个通知缺哪个接口**。
>
> 这几个接口是**包级顶层类型**而不是 `Notification` 的嵌套类型 ——
> 嵌套类型在同一个声明的 `implements` 子句里不在作用域内,
> 于是 `class X extends Notification implements HasMail` 会报
> 「找不到符号」,而那正是用户会照着文档写的第一行。
>
> **② 门面叫 `Notifications`(复数)。** docs/11 §2.2 写的是
> `Notification.send(...)`,但 `Notification` 已经是通知的基类了。
> 同一个名字既是基类又是门面在 PHP 里可行,Java 里不行。
>
> **③ `user.notify(...)` 是接口的默认方法,不是 APT 生成的。**
> docs/11 §2.3 说由 APT 生成 `notify()` / `notifications()`。
> 没那么做:这些方法**不需要**生成,默认方法就够。
> 生成代码的理由应该是「没有它就没法做」(比如 `@Model` 的类型化查询),
> 而不是「少写几行」—— 多一个处理器意味着多一处 pom 里要配的路径、
> 多一处可能忘配的地方。
>
> ⚠ 一个 Java 特有的陷阱:`Object.notify()` 是 final 的(线程唤醒)。
> 我们这个是**重载**,所以 `user.notify(notification)` 正常工作;
> 但 `user.notify()`(手误少写参数)调的是 `Object.notify()`,
> 抛 `IllegalMonitorStateException`。那个异常消息
> (`current thread is not owner`)与「我少写了一个参数」之间
> 没有任何线索,所以它写在 `Notifiable` 的类注释里。
>
> **④ `uniqueId()` 尚未生效。** 方法存在(好让签名稳定),
> 但管理器目前忽略它 —— 去重需要一个共享锁存储
> (与队列的 `UniqueJob` 同一套),而把那套东西接过来需要让缓存
> 成为通知模块的依赖。这一条明确写在 javadoc 里,
> 而不是让它看起来像已经工作了。
>
> **docs/11 §2.6「单个渠道失败不影响其他渠道」已实现**:
> 投递是一个循环,每个渠道各自 try/catch。于是一条通知可能同时产生
> 一个 `Failed` 与两个 `Sent`。**全部渠道都失败时才抛给调用方** ——
> 部分成功不抛,因为调用方分不清「全挂了」与「Slack 挂了但邮件发出去了」,
> 而后者通常不需要处理。
>
> `shouldQueue()` 时**每个渠道一条队列任务**。放一个任务里的话,
> 第一个渠道的失败会让后两个永远不执行,而重试时又从第一个开始 ——
> 于是邮件被发了三遍而短信一次都没发。
>
> **跳过一定带原因**(`NotificationSkipped` 事件)。两种跳过:
> 收件人在那个渠道上没有地址(`routeNotificationFor` 返回 null)、
> 或者 `shouldSend()` 返回 false。两者都正常,所以不报错 ——
> 但它们与「发了但没收到」在外部看来一模一样,而处理方式完全不同。
> 没有这个事件的话,「用户说没收到短信」这个工单只能靠翻代码去猜。
>
> **mail 渠道里不再入队。** `shouldQueue()` 时通知管理器已经为每个渠道
> 投了任务,所以 mail 渠道的 `send` 是在 worker 里跑的。
> 这里再调 `Mail.to(...).queue(...)` 就成了**两层队列**:
> 通知任务会立刻成功(它只是投了封邮件),于是「通知发失败了」
> 在通知的重试里永远不会发生,而 `NotificationSent` 的含义
> 从「发出去了」退化成「入队了」。
>
> **序列化规矩的一个差异,值得单独记住**:
>
> | | 字段能放什么 |
> |---|---|
> | `ShouldQueue` | **只存主键** |
> | `Notification` | **只存主键** |
> | `Mailable` | 随便放 |
>
> 邮件能在投递时渲染完(所以 Mailable 从不进 payload),而通知不能 ——
> 各渠道的 `toMail` / `toSms` / `toDatabase` 产出的是不同类型的东西,
> 没有一个统一的「渲染结果」。所以 `shouldQueue()` 时通知对象本身
> 要进 payload。从邮件那边带着「字段随便放」的印象过来的人会踩一次,
> 所以这个差异写在了 starter 的实现里。

### 2.2 发送

```java
// 对 Notifiable(通常是 User 模型)
user.notify(new InvoicePaid(invoice));
Notification.send(users, new InvoicePaid(invoice));
Notification.sendNow(user, notification);                 // 跳过队列
Notification.locale("en").send(user, notification);

// 按需路由(收件人不是模型)
Notification.route("mail", "a@b.com")
            .route("slack", webhookUrl)
            .notify(new InvoicePaid(invoice));
```

### 2.3 Notifiable

```java
@Model(table = "users")
@Notifiable
public class User extends Model<User> {
    // APT 生成 notify() / notifications() / unreadNotifications()

    public String routeNotificationForMail()  { return email; }
    public String routeNotificationForSms()   { return phone; }
    public String routeNotificationForSlack() { return slackWebhook; }
}
```

### 2.4 数据库通知

```sql
create table notifications (
  id            char(36) primary key,
  type          varchar(255) not null,
  notifiable_type varchar(255) not null,
  notifiable_id bigint not null,
  data          json not null,
  read_at       timestamp null,
  created_at    timestamp not null,
  index (notifiable_type, notifiable_id, read_at)
);
```

```java
user.notifications().get();
user.unreadNotifications().get();
user.readNotifications().get();
notification.markAsRead();
user.unreadNotifications().markAllAsRead();
```

### 2.5 渠道

| 渠道 | 内置 | 说明 |
|---|---|---|
| `mail` | ✅ | 复用 Mailable |
| `database` | ✅ | 站内通知 |
| `broadcast` | v0.6 | WebSocket 实时推送 |
| `slack` | ✅ | Incoming Webhook |
| `sms` | ✅ 抽象 | 具体服务商为扩展包(阿里云、腾讯云、Twilio) |
| `webhook` | ✅ | 通用 HTTP 回调 |
| `dingtalk` / `wechat-work` / `feishu` | 扩展包 | **中国市场的关键渠道**,应作为官方扩展优先提供 |

> **v0.4 的渠道实现情况**
>
> | 渠道 | 状态 |
> |---|---|
> | `mail` | ✅ 复用 Mailable |
> | `database` | ✅ 站内通知 |
> | `slack` | ✅ Incoming Webhook |
> | `webhook` | ✅ 通用 HTTP 回调 |
> | `sms` | ✅ **只有抽象** —— 应用提供一个 `SmsSender` |
> | `broadcast` | ❌ v0.6 |
> | `dingtalk` / `wechat-work` / `feishu` | ❌ 扩展包 |
>
> **sms 只有抽象是刻意的**:短信服务商的 API 差异极大(阿里云的签名
> 算法有一套特定的规范化规则、模板必须预先报备、国内运营商有内容审核),
> 把其中一家塞进框架意味着框架要跟着它的 API 变更走,而那家的 SDK
> 本身就在做这件事。
>
> 为此 `SmsMessage` 同时支持两种形态:`of("内容")`(Twilio 这类)
> 与 `template("SMS_123", Map.of(...))`(阿里云/腾讯云必须用报备过的模板,
> 不能发任意文本)。不做这个区分的话,国内服务商的适配器只能把
> `content` 当成模板 ID,而那是一个会让人非常困惑的约定。
>
> 没装 `SmsSender` 时**报错**而不是静默跳过 —— 短信最常见的用途是验证码,
> 而「验证码收不到」是一个用户会立刻放弃注册的体验。
>
> **slack 用 Incoming Webhook 而不是 Web API**(那需要 OAuth token
> 与权限范围)。代价是拿不到「改消息、加表情、线程回复」这些能力。
> 一条提醒写在 `SlackMessage.channel(...)` 上:新建的 webhook
> 绑定到固定频道,那个字段**可能被 Slack 忽略** ——
> 而「设了 channel 但消息还是去了原频道」看起来像 bug。
>
> **slack 与 webhook 用 JDK 自带的 HttpClient**,不引库。
> 超时必须显式配(默认是无限,与 Jakarta Mail 一样):
> 一个挂住的 Slack webhook 会让那个任务永久占着一个 worker 的并发额度,
> 而 Slack 偶尔会挂。
>
> **webhook 的签名是用户的事,框架不做。** 签名算法与密钥管理是双方
> 约定的,框架替用户选一套的话接收方那边未必能对上。
> 但这件事必须说清:**一个不带签名的 webhook 等于任何人都能伪造你的事件。**
>
> **`notifiable_id` 是 varchar 而不是 bigint**(docs/11 §2.4 写的是
> bigint)。主键不一定是数字 —— UUID 主键在多实例写入时很常见。
> 做成 bigint 的后果是一个 UUID 主键的应用用不了站内通知,
> 而那时的报错是一个数据库层的类型转换错误。
>
> **`type` 与 `notifiable_type` 存简单名/表名,不存全限定名。**
> 一次包重构会让所有历史通知对不上,而那些数据还在表里,
> 只是前端按类型分支的那段代码全部失效了。

自定义渠道:

```java
public class VoiceChannel implements NotificationChannel {
    @Override public void send(Notifiable n, Notification notif) {
        VoiceMessage msg = ((HasVoice) notif).toVoice(n);
        twilio.calls().create(n.routeNotificationFor("voice"), msg.twiml());
    }
}
// 注册:Notification.extend("voice", VoiceChannel.class);
```

### 2.6 失败处理

单个渠道失败不影响其他渠道。每个渠道的投递是独立的队列任务(`shouldQueue()` 时),各自重试。

---

## 3. 文件存储

### 3.1 API

```java
// 读
Optional<byte[]> bytes = Storage.get("avatars/1.png");
Optional<String> text = Storage.getString("notes/1.txt");
InputStream in = Storage.readStream("big.zip");
boolean exists = Storage.exists("avatars/1.png");
boolean missing = Storage.missing("avatars/1.png");
long size = Storage.size("avatars/1.png");
Instant t = Storage.lastModified("avatars/1.png");
String mime = Storage.mimeType("avatars/1.png");

// 写
Storage.put("avatars/1.png", bytes);
Storage.put("avatars/1.png", inputStream);
Storage.putString("notes/1.txt", "hello");
String path = Storage.putFile("avatars", uploadedFile);              // 随机文件名
String path2 = Storage.putFileAs("avatars", uploadedFile, "1.png");
Storage.prepend("log.txt", "line\n");  Storage.append("log.txt", "line\n");
Storage.writeStream("big.zip", in);

// 删 / 移动 / 复制
Storage.delete("avatars/1.png");
Storage.delete(List.of("a", "b"));
Storage.copy("a.png", "b.png");
Storage.move("a.png", "b.png");

// 目录
List<String> files = Storage.files("avatars");
List<String> all = Storage.allFiles("avatars");
List<String> dirs = Storage.directories("avatars");
Storage.makeDirectory("avatars/2026");
Storage.deleteDirectory("avatars/2025");

// URL
String url = Storage.url("avatars/1.png");
String temp = Storage.temporaryUrl("private/report.pdf", Duration.ofMinutes(15));
String uploadUrl = Storage.temporaryUploadUrl("uploads/x.zip", Duration.ofMinutes(10));

// 磁盘切换
Storage.disk("s3").put(...);
Storage.disk("local").get(...);

// 可见性
Storage.setVisibility("a.png", "public");
String v = Storage.getVisibility("a.png");

// 响应(控制器直接返回)
return Storage.download("invoices/1.pdf", "发票.pdf");
return Storage.response("avatars/1.png");      // 内联显示
```

> **v0.4 实现说明**
>
> **已实现**:`local`、`public`(就是 local + url + 可执行类型检查)、`memory`。
> **未实现**:`s3`、`sftp`、`oss`、`cos`。
>
> `s3` 没做而不是半做:它不只是「换一个读写实现」——
> 预签名 URL、按对象的 ACL、分段上传、最终一致性都是 S3 特有的语义,
> 而一个只实现了 get/put 的 s3 驱动会让 `temporaryUrl` 与
> `setVisibility` 变成抛异常的空壳 —— 那比没有这个驱动更糟,
> 因为用户会以为它能用。配了未实现的驱动时**报错**而不是退回 local:
> 退回的后果是「我以为文件在 S3 上」,而它们在容器的本地盘里,
> 容器一重启就没了。
>
> **`temporaryUrl` 在 local 驱动上是真的可用的** —— 这是相对 Laravel
> 的一个改进(Laravel 的 local 驱动在这里直接抛)。实现是:用框架已有的
> `UrlSigner` 签一个指向**应用自己路由**的 URL,那个路由校验签名后
> 把文件流出去。差异必须说清:下载**流过应用**(带宽与线程都算在
> 应用头上)、应用挂了链接就失效 —— 但它真的能用。
>
> 签名覆盖了**整个 URL(含 path 查询参数)**,所以改路径签名就失效 ——
> 那是这道边界成立的关键。不校验的话这个路由等于一个任意文件读取漏洞。
>
> **`temporaryUploadUrl` 在 local 上报错**,而不是返回一个指向应用的
> 上传端点。这个 API 的价值在于「大文件不流过应用」,
> 返回一个应用端点等于让调用方以为自己绕过了应用 ——
> 那个误解会在一个 500 MB 的上传打爆内存时才被发现。
>
> **`setVisibility` 在 local 上报错。** 本地文件系统上「对外可见」
> 取决于那个目录有没有被 web 服务器服务 —— 那是部署配置,
> 不是文件属性。一个「成功」的 `setVisibility("a.png", "private")`
> 会让人以为那个文件不再对外可访问了,而它仍然在 `public/storage` 下,
> 任何人都能下载 —— 那是一个真实的信息泄露。
> 要按文件控制就把私有文件放到一个 private 磁盘,用 `temporaryUrl`
> 给出限时访问。
>
> **`memory` 驱动有两处刻意的 fake-vs-real 差异**,都写在类注释里:
> ① 它**支持**按文件设可见性(它模拟的是对象存储的语义,而
> `Storage.fake()` 的典型用法是「测一段本该跑在 S3 上的代码」)——
> 而 local 不支持;② 它的 `temporaryUrl` 是**未签名的占位符**,
> 所以一段「生成临时 URL 然后校验它」的代码在 fake 下会失败。
> 第二条是写示例的端到端测试时当场踩到的。
>
> **`memory` 驱动也过路径安全检查。** 不做的话「路径穿越的测试」
> 会在内存驱动上通过、在 local 上失败 —— 于是那个安全检查的测试
> 实际上什么都没测。

### 3.2 驱动

| 驱动 | 实现 |
|---|---|
| `local` | 本地文件系统 |
| `public` | 本地 + 对外可访问(`storage/app/public` → `public/storage` 符号链接) |
| `s3` | AWS SDK v2(兼容 MinIO / Cloudflare R2 / 阿里云 OSS 的 S3 接口) |
| `oss` | 阿里云 OSS 原生 SDK(扩展包) |
| `cos` | 腾讯云 COS(扩展包) |
| `sftp` | SSHJ |
| `memory` | 测试 |

```yaml
# config/filesystems.yml
filesystems:
  default: ${FILESYSTEM_DISK:local}
  disks:
    local:
      driver: local
      root: storage/app
      visibility: private
    public:
      driver: local
      root: storage/app/public
      url: ${APP_URL}/storage
      visibility: public
    s3:
      driver: s3
      key: ${AWS_ACCESS_KEY_ID}
      secret: ${AWS_SECRET_ACCESS_KEY}
      region: ${AWS_DEFAULT_REGION}
      bucket: ${AWS_BUCKET}
      endpoint: ${AWS_ENDPOINT}
      use-path-style: ${AWS_USE_PATH_STYLE:false}
  links:
    public/storage: storage/app/public
```

### 3.3 文件上传

```java
public Response store(Request req) {
    UploadedFile file = req.file("avatar").orElseThrow();

    // 校验
    req.validate(Map.of("avatar", "required|image|max:2048|dimensions:max_width=4000"));

    // 存储
    String path = file.store("avatars", "s3");                // 随机名
    String path2 = file.storeAs("avatars", user.getId() + ".png");
    String path3 = file.storePublicly("avatars");

    // 元信息
    String name = file.originalName();
    String ext = file.extension();                             // 由内容嗅探,不信任客户端
    String mime = file.mimeType();
    long size = file.size();
    String hash = file.hashName();                             // 随机 + 保留扩展名

    user.setAvatar(path);
    user.save();
    return Redirect.back();
}
```

> **v0.4 实现说明:安全约束已全部实现(148 个测试),但上传那一层还没有**
>
> ⚠ **`req.file("avatar")` / `UploadedFile` 不存在** —— 它需要 HTTP 层的
> multipart 处理,而那还没实现。
>
> 所以存储这一侧定义了一个最小接口 `Storage.Upload`(内容 + 原始文件名),
> `putFile` / `putFileAs` 以及那组安全检查都基于它,现在就完整可用、
> 也被完整地测了。上传那一层接上时让 `UploadedFile` 实现它即可。
>
> 这个顺序是刻意的:那些安全检查是这一节的核心,而
> 「先上线功能、安全检查后补」是这类事故的标准剧本。
>
> **逐条对照下面那六条约束:**
>
> ① **内容嗅探** —— 实现了,但**不用 Apache Tika**(一处偏差)。
> Tika 是个很重的依赖(核心包约 3 MB,带上解析器依赖树到几十 MB),
> 而它的能力(从 PDF 抽文本、识别一千种格式)这里完全用不到 ——
> 这里只需要「这个字节流是不是它声称的那个类型」。
> 所以手写了常见上传类型的魔数(图片、PDF、zip 系含 Office、
> 常见音视频容器、字体),约 40 行。
> **代价**:覆盖不全(不在列表里的返回 empty,由调用方决定退回扩展名
> 还是拒绝);不做深度校验(魔数对但内容损坏的文件会被认为是对的)。
> 需要完整覆盖的项目可以自己引 Tika 在上传层替掉它。
>
> 别名要处理:`jpg`/`jpeg`、`mp4`/`m4a`/`mov`、以及 **zip 系** ——
> docx/xlsx/pptx/jar/apk **确实是** zip,魔数分不开它们。
> 不处理这些别名的后果是一个正常的 `.docx` 上传被判成
> 「内容与扩展名不符」而被拒 —— 那是一个会立刻被投诉的误报。
>
> 另外单独有一个 `looksLikeMarkup` 检查:HTML 没有魔数(它就是文本),
> 而一个带 `<script>` 的文件被命名为 `.jpg` 上传是**存储型 XSS** 的
> 标准手法。它跳过 BOM 与开头的空白 —— 一个只看第一个字节的检查
> 会漏掉 `"

  <script>"`。
>
> ② **默认随机文件名** —— `hashName()`,它一次解决三件事:
> 路径穿越、覆盖、原始文件名里的信息泄露。扩展名本身也被清理
> (它也来自用户)。
>
> ③ **sanitize** —— 只取最后一段(去掉 `../` 与 `C:\path\`)、
> 去掉空字节与控制字符、Windows 不允许的字符换成 `_`、
> 去掉开头的点(那会让文件变成隐藏文件)、Windows 保留名前加 `_`
> (**带扩展名也算** —— `CON.txt` 同样保留;这个检查在 Linux 上也做,
> 因为一个在 Linux 上存下来的 `CON.png` 会在 Windows 的开发机上
> 同步/解压时出问题)、按**字节**截断到 255(中文一个字 3 字节,
> 按字符截会超过文件系统的限制,而按字节切又不能把多字节字符切两半)。
>
> 即便如此,**默认仍然应该用 `hashName`**:sanitize 保不住「覆盖」
> 与「信息泄露」两件事。
>
> ④ **root 规范化检查** —— 两道闸:字符串层面的 `normalize`
> (**拒绝** `..` 而不是解析掉它 —— 解析掉会让一个有害的输入变成
> 一个合法的输出,于是「有人在尝试路径穿越」被静默吞掉,
> 而那通常是攻击的第一步),以及解析后的 `requireWithin`。
> 第二道用 `toRealPath` 而**不是字符串前缀比较** ——
> 符号链接能绕过字符串比较:`storage/app/link` 指向 `/etc` 的话,
> `link/passwd` 的字符串前缀完全合法。
>
> ⑤ **两层大小限制** —— 框架这一层还没有(它属于上传/校验层)。
> 容器层的 `spring.servlet.multipart.max-file-size` 是 Spring 自己的,
> 照常可用。
>
> ⑥ **public 磁盘禁可执行类型** —— 实现了,分两类理由:
> php/jsp/cgi/exe 这些放在对外可访问的目录里意味着**远程代码执行**;
> html/svg 能带 `<script>`,浏览器会在**你的域名下**执行它 ——
> 于是攻击者拿到了同源的 cookie 访问权(存储型 XSS)。
> 错误消息里给出 `allow-extensions` 的显式允许方式,
> 并指出更好的做法是把它们放到一个**不同的域名**下。
>
> ⚠ 「对外可访问」的判定不只看 `driver: public` —— 一个
> `driver: local` 但配了 `url` 的磁盘同样是对外可访问的,
> 而它同样需要这个检查。
>
> **读不拦可执行类型,只有写拦** —— 已经在那里的文件要能被读到
> (比如为了删掉它),读也拦的话连清理都做不了。

**安全约束**(必须实现,不是可选):

- 扩展名从**内容嗅探**(Apache Tika)而非 `Content-Disposition` 的 filename;
- 默认用 `hashName()` 随机文件名,防止路径穿越与覆盖;
- 原始文件名若被使用,必须经过 `sanitize`(去除 `../`、空字节、控制字符、Windows 保留名);
- `local` 驱动的 root 做路径规范化检查,拒绝逃出 root 的路径;
- 上传大小限制在两层:框架校验 + 容器层 `spring.servlet.multipart.max-file-size`;
- `public` 磁盘默认不允许上传可执行类型(`.php`、`.jsp`、`.html` 需显式配置允许)。

---

## 4. 测试假实现

三个模块都提供 `fake()`:

```java
@Test void sends_shipped_mail() {
    var mail = Mail.fake();
    service.ship(order);
    mail.assertSent(OrderShipped.class, m -> m.hasTo(order.customerEmail()));
    mail.assertQueued(OrderShipped.class);
    mail.assertNotSent(OrderCancelled.class);
    mail.assertSentCount(1);
}

@Test void notifies_user() {
    var notif = Notification.fake();
    service.markPaid(invoice);
    notif.assertSentTo(user, InvoicePaid.class,
        (n, channels) -> channels.contains("mail") && channels.contains("database"));
    notif.assertNothingSentTo(otherUser);
}

@Test void stores_avatar() {
    var disk = Storage.fake("s3");
    controller.store(requestWithFile);
    disk.assertExists("avatars/" + user.getId() + ".png");
    disk.assertMissing("avatars/old.png");
    disk.assertCount("avatars", 1);
}
```

---

## 5. 内部架构

```
jaravel-mail
├── Mailer (contract) · MailManager
├── Mailable (abstract) · Envelope · Content · Attachment · Header
├── transports/ SmtpTransport · SesTransport · ApiTransport · LogTransport
│                · ArrayTransport · FailoverTransport · RoundRobinTransport
├── MessageBuilder        Mailable → MIME(Jakarta Mail MimeMessage)
├── components/           内置邮件布局组件(jblade)
└── MailFake

jaravel-notifications
├── NotificationDispatcher (contract)
├── Notification (abstract) · Notifiable (interface)
├── channels/ MailChannel · DatabaseChannel · SlackChannel · SmsChannel
│              · WebhookChannel · BroadcastChannel
├── QueuedNotificationJob
└── NotificationFake

jaravel-storage
├── Filesystem (contract) · FilesystemManager
├── drivers/ LocalDriver · S3Driver · SftpDriver · MemoryDriver
├── UploadedFile · MimeDetector(Tika)· PathSanitizer
└── StorageFake
```

### 5.1 为什么不用 Spring Mail / Spring Resource 抽象

- `JavaMailSender` 只覆盖 SMTP,不覆盖 API 型服务商(SES/SendGrid),也没有 failover/roundrobin;
- `Resource` 抽象是只读的,没有写入、目录列举、临时 URL、可见性概念;
- 两者都没有 Mailable/Notification 这层「消息对象」抽象,也没有假实现测试支持。

底层仍然复用 Jakarta Mail 的 MIME 构建(不自己实现 MIME 编码)和 AWS SDK(不自己签名)。

---

## 6. 逃生口

| 需求 | 途径 |
|---|---|
| 需要 `JavaMailSender` | 照常注入使用 |
| 需要原生 MIME 控制 | `Mail.raw(mimeMessage -> ...)` |
| 需要 S3 原生 API | `Storage.disk("s3").raw()` → `S3Client` |
| 需要 `Resource` / `Path` | `Storage.disk("local").path("a.png")` → `java.nio.Path` |
| 需要自定义渠道/驱动 | 实现契约并注册 |

---

## 7. 与 Laravel 的差异

| 项 | Laravel | Jaravel | 原因 |
|---|---|---|---|
| Markdown 邮件 | Markdown 文件 | jblade + `x-mail::` 组件 | 统一模板引擎,避免引入 Markdown 解析器 |
| 文件系统 | Flysystem | 自研驱动抽象 | Java 无等价的成熟统一库 |
| 扩展名判定 | 部分依赖客户端 | 强制内容嗅探 | 安全加固 |
| 中国渠道 | 社区包 | 官方扩展包优先提供 | 目标用户群的实际需求 |
| 通知的多渠道失败 | 整体失败 | 渠道独立重试 | 避免「短信挂了导致邮件也没发」 |

---

## 8. 待决问题

- [ ] 邮件内置组件的主题定制机制:CSS inline 化在构建期做还是渲染期做?构建期更快但失去动态主题能力。倾向构建期 + 缓存。
- [ ] `Storage` 的 `temporaryUrl` 在 `local` 驱动下如何实现?需要一个签名路由(`/storage/tmp/{sig}/{path}`)。要实现,否则「开发用 local、生产用 s3」的代码无法通用。
- [ ] 文件上传的流式处理:大文件直传 S3 应该走 `temporaryUploadUrl` 让浏览器直传,而不是经过应用服务器。需要提供前端 helper 与文档章节。
- [ ] 通知的 `uniqueId` 去重窗口存储在哪里(Cache?数据库?)。倾向 Cache + 可配置 TTL。
- [ ] 是否提供 `broadcast` 渠道(WebSocket)?依赖广播系统(v0.6)。在此之前 `via()` 返回 `"broadcast"` 应给出明确的「未启用」错误而非静默跳过。
