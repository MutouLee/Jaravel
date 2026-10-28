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
