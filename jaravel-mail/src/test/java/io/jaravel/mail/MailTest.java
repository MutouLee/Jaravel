package io.jaravel.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.jaravel.events.Event;
import io.jaravel.events.EventBus;
import io.jaravel.mail.events.MailEvents;
import io.jaravel.mail.transport.ArrayTransport;
import io.jaravel.mail.transport.FailoverTransport;
import io.jaravel.mail.transport.LogTransport;
import io.jaravel.mail.transport.RoundRobinTransport;

/** Mailable / 地址 / 渲染 / 驱动 / 事件 / fake 断言 */
class MailTest {

    private ArrayTransport array;
    private MailManager mail;
    private EventBus bus;

    @BeforeEach
    void setUp() {
        array = new ArrayTransport();

        var renderer = new MailRenderer(
                (view, data, locale) -> "<p>模板 %s,locale=%s,data=%s</p>"
                        .formatted(view, locale, data),
                Address.of("noreply@example.com", "示例应用"));

        mail = new MailManager(renderer)
                .register("array", array)
                .defaultMailer("array");
        Mail.use(mail);

        bus = EventBus.standalone();
        Event.use(bus);
    }

    @AfterEach
    void tearDown() {
        Mail.reset();
        Event.reset();
    }

    // ================= 地址 =================

    @Nested
    @DisplayName("地址")
    class Addresses {

        @Test
        @DisplayName("基本形态")
        void basics() {
            assertThat(Address.of("a@b.com").format()).isEqualTo("a@b.com");
            assertThat(Address.of("a@b.com", "张三").format()).isEqualTo("张三 <a@b.com>");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "nope", "@b.com", "a@", "a@b@c.com",
                "a b@c.com", "a@b .com"})
        @DisplayName("结构不对的拒掉")
        void rejectsMalformed(String email) {
            assertThatThrownBy(() -> Address.of(email))
                    .isInstanceOf(Address.InvalidAddressException.class);
        }

        @Test
        @DisplayName("★ 带换行的地址被拒 —— 那是邮件头注入")
        void rejectsHeaderInjection() {
            assertThatThrownBy(() -> Address.of("a@b.com\r\nBcc: attacker@evil.com"))
                    .isInstanceOf(Address.InvalidAddressException.class)
                    .hasMessageContaining("邮件头注入");

            assertThatThrownBy(() -> Address.of("a@b.com\nBcc: x@y.com"))
                    .hasMessageContaining("换行");
        }

        @Test
        @DisplayName("★ 显示名里的换行被**清理**而不是报错")
        void sanitizesNameLineBreaks() {
            var address = Address.of("a@b.com", "张三\r\nBcc: x@y.com");

            assertThat(address.name())
                    .as("显示名常来自用户资料(昵称),一个带换行的昵称"
                            + "不该让「发不出邮件」")
                    .doesNotContain("\r")
                    .doesNotContain("\n");
            assertThat(address.format()).doesNotContain("\n");
        }

        @Test
        @DisplayName("★ 头部的值里也不能有换行")
        void headerValueCannotHaveLineBreaks() {
            assertThatCode(() -> Mailable.Header.of("X-Tag", "ok"))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> Mailable.Header.of("X-Tag", "a\r\nBcc: x@y.com"))
                    .hasMessageContaining("注入新的头部");
        }

        @Test
        @DisplayName("宽松地接受一些不常见但合法的地址")
        void acceptsUnusualButValid() {
            assertThatCode(() -> {
                Address.of("a+tag@b.com");
                Address.of("a.b.c@sub.domain.co.uk");
                Address.of("x@localhost");
                Address.of("1@2.3");
            }).as("拒掉一个真实用户的邮箱,比放过一个无效地址糟得多")
              .doesNotThrowAnyException();
        }
    }

    // ================= 渲染 =================

    @Nested
    @DisplayName("渲染")
    class Rendering {

        @Test
        @DisplayName("模板被渲染,数据传进去")
        void rendersView() {
            Mail.to("x@y.com").send(new TemplatedMail());

            assertThat(array.sent()).singleElement().satisfies(m -> {
                assertThat(m.html()).contains("模板 mail.test");
                assertThat(m.html()).contains("order=42");
                assertThat(m.subject()).isEqualTo("测试主题");
            });
        }

        @Test
        @DisplayName("locale 传给渲染器")
        void localeReachesRenderer() {
            Mail.to("x@y.com").locale("en").send(new TemplatedMail());

            assertThat(array.sent().get(0).html()).contains("locale=en");
        }

        @Test
        @DisplayName("Mailable 自己的 locale 在没被覆盖时生效")
        void mailableLocale() {
            Mail.to("x@y.com").send(new GermanMail());
            assertThat(array.sent().get(0).html()).contains("locale=de");

            array.clear();
            Mail.to("x@y.com").locale("en").send(new GermanMail());
            assertThat(array.sent().get(0).html())
                    .as("调用点的 locale 覆盖 Mailable 的")
                    .contains("locale=en");
        }

        @Test
        @DisplayName("★ 没给纯文本版本时从 HTML 退化生成一个")
        void plainTextFallback() {
            Mail.to("x@y.com").send(new HtmlMail(
                    "<style>p{color:red}</style><p>你好</p><br><p>再见</p>"));

            var text = array.sent().get(0).text();

            assertThat(text)
                    .as("只有 HTML 的 multipart 邮件会加垃圾邮件评分,"
                            + "而且客户端会用 HTML 开头生成收件箱摘要")
                    .contains("你好")
                    .contains("再见");
            assertThat(text)
                    .as("style 的**内容**要整段去掉,只剥标签会把 CSS 留在正文里")
                    .doesNotContain("color:red");
            assertThat(text).doesNotContain("<p>");
        }

        @Test
        @DisplayName("显式给的纯文本优先于退化生成")
        void explicitTextWins() {
            Mail.to("x@y.com").send(new Mailable() {
                @Override public Envelope envelope() { return Envelope.subject("s"); }

                @Override public Content content() {
                    return Content.html("<p>html</p>").withText("手写文本");
                }
            });

            assertThat(array.sent().get(0).text()).isEqualTo("手写文本");
        }

        @Test
        @DisplayName("★ 没有收件人时报错,不静默什么都不做")
        void noRecipientsFails() {
            assertThatThrownBy(() -> new PendingMail(mail).send(new TemplatedMail()))
                    .isInstanceOf(MailRenderer.MailRenderException.class)
                    .hasMessageContaining("没有收件人")
                    .hasMessageContaining("只有密送的邮件");
        }

        @Test
        @DisplayName("★ 没有主题时报错")
        void noSubjectFails() {
            assertThatThrownBy(() -> Mail.to("x@y.com").send(new Mailable() {
                @Override public Envelope envelope() { return Envelope.of(); }

                @Override public Content content() { return Content.html("<p>x</p>"); }
            }))
                    .hasMessageContaining("没有主题")
                    .hasMessageContaining("垃圾邮件评分");
        }

        @Test
        @DisplayName("★ 没有正文时报错,不发一封空白邮件")
        void noBodyFails() {
            assertThatThrownBy(() -> Mail.to("x@y.com").send(new Mailable() {
                @Override public Envelope envelope() { return Envelope.subject("s"); }

                @Override public Content content() { return Content.html(null); }
            }))
                    .hasMessageContaining("没有任何正文");
        }

        @Test
        @DisplayName("★ 发件人缺失时报错,说清 From 为空会被拒")
        void missingFromFails() {
            var noDefault = new MailManager(new MailRenderer(null, null))
                    .register("array", array)
                    .defaultMailer("array");
            Mail.use(noDefault);

            assertThatThrownBy(() -> Mail.to("x@y.com").send(new HtmlMail("<p>x</p>")))
                    .hasMessageContaining("mail.from.address")
                    .hasMessageContaining("501 Bad address syntax");
        }

        @Test
        @DisplayName("Mailable 的 from 覆盖配置的默认值")
        void mailableFromWins() {
            Mail.to("x@y.com").send(new Mailable() {
                @Override public Envelope envelope() {
                    return Envelope.subject("s").from("custom@example.com", "自定义");
                }

                @Override public Content content() { return Content.html("<p>x</p>"); }
            });

            assertThat(array.sent().get(0).from().email()).isEqualTo("custom@example.com");
        }

        @Test
        @DisplayName("★ 用了模板但没装渲染器时报错")
        void missingRendererFails() {
            var noViews = new MailManager(
                    new MailRenderer(null, Address.of("a@b.com")))
                    .register("array", array)
                    .defaultMailer("array");
            Mail.use(noViews);

            assertThatThrownBy(() -> Mail.to("x@y.com").send(new TemplatedMail()))
                    .hasMessageContaining("没有装模板渲染器")
                    .hasMessageContaining("而不是发一封空白邮件");
        }
    }

    // ================= 收件人 =================

    @Nested
    @DisplayName("收件人")
    class Recipients {

        @Test
        @DisplayName("★ 字段内去重,跨字段不去重")
        void dedupeWithinFieldOnly() {
            Mail.to("a@b.com").to("a@b.com").cc("a@b.com")
                    .send(new HtmlMail("<p>x</p>"));

            var message = array.sent().get(0);
            assertThat(message.to()).hasSize(1);
            assertThat(message.cc())
                    .as("跨字段去重会改变邮件头,于是收件人看到的 Cc 列表"
                            + "与代码里写的不一样")
                    .hasSize(1);
        }

        @Test
        @DisplayName("Recipient 接口")
        void recipientInterface() {
            record User(String email, String name) implements PendingMail.Recipient {
                @Override public String emailAddress() { return email; }

                @Override public String recipientName() { return name; }
            }

            Mail.to(new User("u@example.com", "张三")).send(new HtmlMail("<p>x</p>"));

            assertThat(array.sent().get(0).to()).singleElement()
                    .satisfies(a -> {
                        assertThat(a.email()).isEqualTo("u@example.com");
                        assertThat(a.name()).isEqualTo("张三");
                    });
        }

        @Test
        @DisplayName("★ Recipient 的邮箱为空时报错")
        void emptyRecipientEmailFails() {
            PendingMail.Recipient broken = () -> null;

            assertThatThrownBy(() -> Mail.to(broken))
                    .isInstanceOf(Address.InvalidAddressException.class)
                    .hasMessageContaining("查不到原因的工单");
        }

        @Test
        @DisplayName("★ 空集合是空操作,不报错")
        void emptyCollectionIsNoop() {
            var pending = Mail.to(List.<PendingMail.Recipient>of());

            assertThat(pending.isNoop()).isTrue();
            assertThat(pending.send(new HtmlMail("<p>x</p>")))
                    .as("「给筛选出来的用户发邮件」而结果为空是合理的")
                    .isFalse();
            assertThat(array.count()).isZero();
        }

        @Test
        @DisplayName("cc / bcc 都进最终的消息")
        void ccAndBcc() {
            Mail.to("a@b.com").cc("c@d.com").bcc("e@f.com")
                    .send(new HtmlMail("<p>x</p>"));

            var message = array.sent().get(0);
            assertThat(message.cc()).extracting(Address::email).containsExactly("c@d.com");
            assertThat(message.bcc()).extracting(Address::email).containsExactly("e@f.com");
            assertThat(message.allRecipients()).hasSize(3);
        }
    }

    // ================= SimpleMailable =================

    @Nested
    @DisplayName("Mailable.simple()")
    class Simple {

        @Test
        @DisplayName("渲染出 HTML 与纯文本两份")
        void rendersBoth() {
            Mail.to("x@y.com").send(Mailable.simple()
                    .subject("发票已支付")
                    .line("你的发票 #42 已支付。")
                    .action("查看发票", "https://example.com/i/42")
                    .line("谢谢!"));

            var message = array.sent().get(0);

            assertThat(message.html())
                    .contains("你的发票 #42 已支付。")
                    .contains("https://example.com/i/42")
                    .contains("查看发票");
            assertThat(message.html())
                    .as("邮件客户端不支持外部样式表,内联 style 是唯一可靠的办法")
                    .contains("style=");
            assertThat(message.html())
                    .as("Outlook 的渲染引擎是 Word,对 div + CSS 的支持不可靠")
                    .contains("<table");
        }

        @Test
        @DisplayName("★ 纯文本版本把按钮变成「文字:URL」")
        void textKeepsTheLink() {
            Mail.to("x@y.com").send(Mailable.simple()
                    .subject("s")
                    .action("查看发票", "https://example.com/i/42"));

            assertThat(array.sent().get(0).text())
                    .as("从 HTML 退化的版本会把链接整个丢掉 —— "
                            + "而那正是收件人要点的东西")
                    .contains("查看发票:https://example.com/i/42");
        }

        @Test
        @DisplayName("★ 只能有一个按钮 —— 第二次覆盖第一个")
        void onlyOneAction() {
            Mail.to("x@y.com").send(Mailable.simple()
                    .subject("s")
                    .action("第一个", "https://a")
                    .action("第二个", "https://b"));

            var html = array.sent().get(0).html();
            assertThat(html)
                    .as("两个同等显眼的按钮会让「我该点哪个」变成一个问题,"
                            + "而邮件里没有地方解释")
                    .doesNotContain("第一个")
                    .contains("第二个");
        }

        @Test
        @DisplayName("★ 正文被 HTML 转义")
        void escapesHtml() {
            Mail.to("x@y.com").send(Mailable.simple()
                    .subject("s")
                    .line("<script>alert(1)</script>"));

            assertThat(array.sent().get(0).html())
                    .doesNotContain("<script>")
                    .contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("默认的称呼与结尾可以关掉")
        void greetingAndSalutationAreOptional() {
            Mail.to("x@y.com").send(Mailable.simple()
                    .subject("s").greeting("").salutation("").line("只有这一行"));

            var text = array.sent().get(0).text();
            assertThat(text).isEqualTo("只有这一行");
        }
    }

    // ================= 附件 =================

    @Nested
    @DisplayName("附件")
    class Attachments {

        @Test
        @DisplayName("★ 内存附件在渲染时就 materialize")
        void dataAttachmentIsInlined() {
            byte[] payload = "PDF 内容".getBytes();

            Mail.to("x@y.com").send(new Mailable() {
                @Override public Envelope envelope() { return Envelope.subject("s"); }

                @Override public Content content() { return Content.html("<p>x</p>"); }

                @Override public List<Attachment> attachments() {
                    return List.of(Attachment.fromData(() -> payload, "发票.pdf"));
                }
            });

            assertThat(array.sent().get(0).attachments()).singleElement()
                    .satisfies(a -> {
                        assertThat(a.isInline()).isTrue();
                        assertThat(a.bytes()).isEqualTo(payload);
                        assertThat(a.mimeType()).isEqualTo("application/pdf");
                    });
        }

        @Test
        @DisplayName("★ 路径附件只传路径,不在渲染时读盘")
        void pathAttachmentStaysAReference() {
            Mail.to("x@y.com").send(new Mailable() {
                @Override public Envelope envelope() { return Envelope.subject("s"); }

                @Override public Content content() { return Content.html("<p>x</p>"); }

                @Override public List<Attachment> attachments() {
                    // 这个文件**不存在** —— 渲染不该因此失败
                    return List.of(Attachment.fromPath("/nope/missing.pdf"));
                }
            });

            assertThat(array.sent().get(0).attachments()).singleElement()
                    .satisfies(a -> {
                        assertThat(a.isInline())
                                .as("这让一个大文件附件不会进队列 payload")
                                .isFalse();
                        assertThat(a.path()).contains("missing.pdf");
                    });
        }

        @Test
        @DisplayName("路径附件在**发送时**才报读不到")
        void pathAttachmentFailsAtSendTime() {
            var attachment = new RenderedMessage.RenderedAttachment(
                    "x.pdf", "application/pdf", null, "/nope/missing.pdf");

            assertThatThrownBy(attachment::resolve)
                    .isInstanceOf(Attachment.AttachmentException.class)
                    .hasMessageContaining("worker");
        }

        @Test
        @DisplayName("MIME 按扩展名猜,猜不出用 octet-stream")
        void mimeGuessing() {
            assertThat(Attachment.fromData(() -> new byte[0], "a.png").mimeType())
                    .isEqualTo("image/png");
            assertThat(Attachment.fromData(() -> new byte[0], "a.unknown").mimeType())
                    .isEqualTo("application/octet-stream");
            assertThat(Attachment.fromData(() -> new byte[0], "a.png")
                    .withMime("image/custom").mimeType())
                    .isEqualTo("image/custom");
        }

        @Test
        @DisplayName("★ fromStorage 报错说明存储模块还没实现")
        void fromStorageIsNotReady() {
            assertThatThrownBy(() -> Attachment.fromStorage("invoices/1.pdf"))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("存储模块")
                    .hasMessageContaining("FileNotFoundException");
        }

        @Test
        @DisplayName("★ RenderedAttachment 按**内容**比较,不按引用")
        void attachmentEquality() {
            var a = new RenderedMessage.RenderedAttachment("x", "text/plain",
                    "same".getBytes(), null);
            var b = new RenderedMessage.RenderedAttachment("x", "text/plain",
                    "same".getBytes(), null);

            assertThat(a)
                    .as("record 对 byte[] 用引用比较 —— 不自己写 equals 的话"
                            + "两个内容相同的附件不相等,而那会在断言里咬人")
                    .isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // ================= 驱动 =================

    @Nested
    @DisplayName("驱动")
    class Transports {

        @Test
        @DisplayName("★ failover 依次尝试,全失败时带上每一个的原因")
        void failoverCollectsAllReasons() {
            var manager = new MailManager(new MailRenderer(null, Address.of("a@b.com")))
                    .register("bad1", new ThrowingTransport("bad1", "SES key 过期"))
                    .register("bad2", new ThrowingTransport("bad2", "SMTP 连不上"));
            manager.register("failover", m -> new FailoverTransport(
                    List.of("bad1", "bad2"), m::mailer));
            manager.defaultMailer("failover");
            Mail.use(manager);

            assertThatThrownBy(() -> Mail.to("x@y.com").send(new HtmlMail("<p>x</p>")))
                    .isInstanceOf(Transport.TransportException.class)
                    .hasMessageContaining("全部 2 个 mailer 都失败")
                    .satisfies(e -> {
                        assertThat(e.getCause()).hasMessageContaining("SES key 过期");
                        assertThat(e.getCause().getSuppressed())
                                .as("只看最后一个的话,真正的原因会被盖掉")
                                .isNotEmpty();
                    });
        }

        @Test
        @DisplayName("failover 第一个成功就不试第二个")
        void failoverStopsAtFirstSuccess() {
            var second = new ArrayTransport();
            var manager = new MailManager(new MailRenderer(null, Address.of("a@b.com")))
                    .register("good", array)
                    .register("second", second);
            manager.register("failover", m -> new FailoverTransport(
                    List.of("good", "second"), m::mailer));
            manager.defaultMailer("failover");
            Mail.use(manager);

            Mail.to("x@y.com").send(new HtmlMail("<p>x</p>"));

            assertThat(array.count()).isEqualTo(1);
            assertThat(second.count()).isZero();
        }

        @Test
        @DisplayName("failover 少于两个 mailer 时报错")
        void failoverNeedsTwo() {
            assertThatThrownBy(() -> new FailoverTransport(List.of("only"), n -> null))
                    .hasMessageContaining("会让人以为有冗余");
        }

        @Test
        @DisplayName("★ roundrobin 轮询,且**不做容错**")
        void roundRobinDoesNotFailOver() {
            var a = new ArrayTransport();
            var manager = new MailManager(new MailRenderer(null, Address.of("a@b.com")))
                    .register("a", a)
                    .register("b", new ThrowingTransport("b", "挂了"));
            manager.register("rr", m -> new RoundRobinTransport(List.of("a", "b"), m::mailer));
            manager.defaultMailer("rr");
            Mail.use(manager);

            // 第一封走 a —— 成功
            Mail.to("x@y.com").send(new HtmlMail("<p>1</p>"));
            assertThat(a.count()).isEqualTo(1);

            // 第二封轮到 b —— 失败,而且**不换回 a**
            assertThatThrownBy(() -> Mail.to("x@y.com").send(new HtmlMail("<p>2</p>")))
                    .hasMessageContaining("roundrobin **不做容错**");
            assertThat(a.count())
                    .as("换的话它就成了隐式的 failover,"
                            + "而那会掩盖「某个服务商挂了」")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("log 与 array 驱动声明自己不真发")
        void nonDeliveringTransports() {
            assertThat(new LogTransport(s -> { }, false).actuallyDelivers()).isFalse();
            assertThat(array.actuallyDelivers()).isFalse();
        }

        @Test
        @DisplayName("★ log 驱动默认不打正文")
        void logHidesBodyByDefault() {
            var lines = new ArrayList<String>();
            var manager = new MailManager(new MailRenderer(null, Address.of("a@b.com")))
                    .register("log", new LogTransport(lines::add, false));
            manager.defaultMailer("log");
            Mail.use(manager);

            Mail.to("x@y.com").send(new HtmlMail("<p>订单明细:收货地址 XXX</p>"));

            assertThat(lines).singleElement().satisfies(line -> {
                assertThat(line).contains("x@y.com");
                assertThat(line)
                        .as("邮件正文常含个人信息,而日志通常比数据库更容易被读到")
                        .doesNotContain("收货地址")
                        .contains("正文未打印");
            });
        }

        @Test
        @DisplayName("log 驱动开了 body 之后打**纯文本**")
        void logPrefersPlainText() {
            var lines = new ArrayList<String>();
            var manager = new MailManager(new MailRenderer(null, Address.of("a@b.com")))
                    .register("log", new LogTransport(lines::add, true));
            manager.defaultMailer("log");
            Mail.use(manager);

            Mail.to("x@y.com").send(Mailable.simple()
                    .subject("重置密码")
                    .action("重置", "https://example.com/reset?token=abc"));

            assertThat(lines.get(0))
                    .as("开发期真正要看的东西(验证链接)在文本版本里"
                            + "一样有,且一眼就能看到")
                    .contains("https://example.com/reset?token=abc")
                    .contains("纯文本");
        }

        @Test
        @DisplayName("mailer 名字写错时给出相近的候选")
        void unknownMailerSuggests() {
            // 名字在 .mailer(...) 这一行就校验 —— 不等到发送时
            assertThatThrownBy(() -> Mail.mailer("aray"))
                    .hasMessageContaining("是否想写 \"array\"");
        }
    }

    // ================= 事件 =================

    @Nested
    @DisplayName("邮件事件")
    class Events {

        @Test
        @DisplayName("★ Sending / Sent 真的发出来")
        void lifecycleEvents() {
            var received = new ArrayList<Object>();
            bus.listen(MailEvents.MessageSending.class, received::add);
            bus.listen(MailEvents.MessageSent.class, received::add);

            Mail.to("x@y.com").send(new HtmlMail("<p>x</p>"));

            assertThat(received).hasSize(2);
            assertThat((MailEvents.MessageSending) received.get(0)).satisfies(e -> {
                assertThat(e.mailer()).isEqualTo("array");
                assertThat(e.mailableType()).isNotBlank();
                assertThat(e.message().to()).hasSize(1);
            });
            assertThat((MailEvents.MessageSent) received.get(1))
                    .satisfies(e -> assertThat(e.duration()).isNotNull());
        }

        @Test
        @DisplayName("★ Sending 返回 false 挡掉这封邮件")
        void sendingCanBlock() {
            var blocked = new ArrayList<MailEvents.MessageBlocked>();
            bus.listen(MailEvents.MessageBlocked.class, blocked::add);

            // 「只发给白名单域名」—— 一个真实的预发环境需求
            bus.listenAbortable(MailEvents.MessageSending.class, 0,
                    e -> e.message().to().stream()
                            .allMatch(a -> a.email().endsWith("@example.com")));

            assertThat(Mail.to("outside@gmail.com").send(new HtmlMail("<p>x</p>")))
                    .isFalse();
            assertThat(array.count())
                    .as("「用生产数据做的压测给真实用户发了邮件」是一类真实事故")
                    .isZero();
            assertThat(blocked).hasSize(1);

            assertThat(Mail.to("inside@example.com").send(new HtmlMail("<p>x</p>")))
                    .isTrue();
            assertThat(array.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 发送失败时发 MessageFailed,并把异常抛出去")
        void failureIsReported() {
            var failures = new ArrayList<MailEvents.MessageFailed>();
            bus.listen(MailEvents.MessageFailed.class, failures::add);

            var manager = new MailManager(new MailRenderer(null, Address.of("a@b.com")))
                    .register("bad", new ThrowingTransport("bad", "炸了"));
            manager.defaultMailer("bad");
            Mail.use(manager);

            assertThatThrownBy(() -> Mail.to("x@y.com").send(new HtmlMail("<p>x</p>")))
                    .hasMessageContaining("炸了");

            assertThat(failures).singleElement()
                    .satisfies(e -> assertThat(e.cause()).isNotNull());
        }

        @Test
        @DisplayName("没人监听时不构造事件")
        void gatedWhenNobodyListens() {
            assertThat(Event.listening(MailEvents.MessageSent.class)).isFalse();

            Mail.to("x@y.com").send(new HtmlMail("<p>x</p>"));

            assertThat(array.count()).isEqualTo(1);
        }
    }

    // ================= 入队 =================

    @Nested
    @DisplayName("入队发送")
    class Queueing {

        @Test
        @DisplayName("★ 入队时就渲染 —— payload 里是渲染结果")
        void rendersAtDispatchTime() {
            var queued = new ArrayList<RenderedMessage>();
            mail.queueDispatcher((message, type, mailer, delay) -> queued.add(message));

            Mail.to("x@y.com").queue(new TemplatedMail());

            assertThat(queued).singleElement().satisfies(m -> {
                assertThat(m.html())
                        .as("Mailable 不需要可序列化,也不会有「模型被删了」的失败")
                        .contains("模板 mail.test");
                assertThat(m.subject()).isEqualTo("测试主题");
            });
            assertThat(array.count()).as("入队了就不该同步发").isZero();
        }

        @Test
        @DisplayName("later 带上延迟")
        void laterPassesDelay() {
            var delays = new ArrayList<Duration>();
            mail.queueDispatcher((message, type, mailer, delay) -> delays.add(delay));

            Mail.to("x@y.com").later(Duration.ofHours(1), new HtmlMail("<p>x</p>"));

            assertThat(delays).containsExactly(Duration.ofHours(1));
        }

        @Test
        @DisplayName("★ 没有队列通道时报错,不退回同步发送")
        void missingQueueFails() {
            assertThatThrownBy(() -> Mail.to("x@y.com").queue(new HtmlMail("<p>x</p>")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("接口的 P99");
        }

        @Test
        @DisplayName("★ worker 侧用 deliver(...) 发已渲染的邮件")
        void workerPathSendsRendered() {
            var rendered = new RenderedMessage(
                    Address.of("a@b.com"), List.of(Address.of("x@y.com")),
                    null, null, null, "主题", "<p>正文</p>", "正文",
                    null, null, null, null);

            assertThat(mail.deliver(rendered, "OrderShipped", null)).isTrue();
            assertThat(array.sent()).containsExactly(rendered);
        }
    }

    // ================= fake =================

    @Nested
    @DisplayName("Mail.fake()")
    class Faking {

        @BeforeEach
        void startFaking() {
            Mail.fake();
        }

        @Test
        @DisplayName("★ fake 后不渲染、不发送,但断言看得到")
        void recordsWithoutSending() {
            Mail.to("x@y.com").send(new TemplatedMail());

            assertThat(array.count()).isZero();
            Mail.assertSent(TemplatedMail.class);
        }

        @Test
        @DisplayName("★ 断言写在 Mailable 对象上,不在 HTML 里找子串")
        void assertOnTheMailableObject() {
            Mail.to("x@y.com").send(new InvoiceMail(42L));

            Mail.assertSent(InvoiceMail.class, m -> m.invoiceId() == 42L);

            assertThatThrownBy(() -> Mail.assertSent(InvoiceMail.class,
                    (InvoiceMail m) -> m.invoiceId() == 99L))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("没有一次满足给定条件");
        }

        @Test
        @DisplayName("assertSentTo 按地址")
        void assertByRecipient() {
            Mail.to("a@b.com").send(new HtmlMail("<p>x</p>"));

            Mail.assertSentTo(HtmlMail.class, "A@B.COM");
            assertThatThrownBy(() -> Mail.assertSentTo(HtmlMail.class, "other@x.com"))
                    .hasMessageContaining("实际的收件人");
        }

        @Test
        @DisplayName("次数断言")
        void assertCount() {
            Mail.to("a@b.com").send(new HtmlMail("<p>1</p>"));
            Mail.to("c@d.com").send(new HtmlMail("<p>2</p>"));

            Mail.assertSent(HtmlMail.class, 2);
            assertThatThrownBy(() -> Mail.assertSent(HtmlMail.class, 3))
                    .hasMessageContaining("实际 2 次");
        }

        @Test
        @DisplayName("★ assertQueued 与 assertSent 分开 —— 并说清区别")
        void queuedVersusSent() {
            Mail.to("x@y.com").queue(new HtmlMail("<p>x</p>"));

            Mail.assertQueued(HtmlMail.class);
            assertThatThrownBy(() -> Mail.assertSent(HtmlMail.class))
                    .hasMessageContaining("用 assertQueued");
        }

        @Test
        @DisplayName("★ 同步发了却断言 assertQueued 时提示用错了方法")
        void syncSentButAssertedQueued() {
            Mail.to("x@y.com").send(new HtmlMail("<p>x</p>"));

            assertThatThrownBy(() -> Mail.assertQueued(HtmlMail.class))
                    .hasMessageContaining("它被**同步发送**了 1 次")
                    .hasMessageContaining(".send(...) 而不是 .queue(...)");
        }

        @Test
        @DisplayName("assertNotSent / assertNothingSent")
        void negativeAssertions() {
            Mail.assertNothingSent();
            Mail.assertNotSent(HtmlMail.class);

            Mail.to("x@y.com").send(new HtmlMail("<p>x</p>"));

            assertThatThrownBy(Mail::assertNothingSent).isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> Mail.assertNotSent(HtmlMail.class))
                    .hasMessageContaining("它发了 1 次");
        }

        @Test
        @DisplayName("★ 失败消息提醒「可能 fake 调晚了」")
        void messageHintsAboutLateFake() {
            assertThatThrownBy(() -> Mail.assertSent(HtmlMail.class))
                    .hasMessageContaining("之前**发的");
        }

        @Test
        @DisplayName("stopFaking 之后恢复真实发送")
        void stopFaking() {
            Mail.stopFaking();
            Mail.to("x@y.com").send(new HtmlMail("<p>x</p>"));

            assertThat(array.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("不在 fake 模式时查记录会报错")
    void assertingWithoutFakeFails() {
        assertThatThrownBy(() -> Mail.assertSent(HtmlMail.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在 fake 模式");
    }

    // ================= 测试用 Mailable =================

    static class TemplatedMail extends Mailable {
        @Override public Envelope envelope() { return Envelope.subject("测试主题"); }

        @Override public Content content() {
            return Content.view("mail.test").with("order", 42);
        }
    }

    static class GermanMail extends Mailable {
        @Override public String locale() { return "de"; }

        @Override public Envelope envelope() { return Envelope.subject("s"); }

        @Override public Content content() { return Content.view("v"); }
    }

    record HtmlMailData(String markup) {}

    static class HtmlMail extends Mailable {
        private final String markup;

        HtmlMail(String markup) { this.markup = markup; }

        @Override public Envelope envelope() { return Envelope.subject("s"); }

        @Override public Content content() { return Content.html(markup); }
    }

    static class InvoiceMail extends Mailable {
        private final long invoiceId;

        InvoiceMail(long invoiceId) { this.invoiceId = invoiceId; }

        long invoiceId() { return invoiceId; }

        @Override public Envelope envelope() { return Envelope.subject("发票"); }

        @Override public Content content() { return Content.html("<p>发票</p>"); }
    }

    /** 总是失败的驱动 */
    record ThrowingTransport(String label, String reason) implements Transport {
        @Override public String name() { return label; }

        @Override public void send(RenderedMessage message) {
            throw new TransportException(label, reason, null);
        }
    }
}
