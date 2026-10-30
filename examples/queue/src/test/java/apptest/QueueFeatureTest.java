package apptest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import app.models.Invoice;
import app.models.Invoice_;
import app.support.BatchLedger;
import app.support.InvoiceGateway;
import io.jaravel.database.Connection;
import io.jaravel.queue.FailedJobStore;
import io.jaravel.queue.JobInvoker;
import io.jaravel.queue.Queue;
import io.jaravel.queue.QueueManager;
import io.jaravel.queue.QueueWorker;
import io.jaravel.queue.WorkerOptions;
import io.jaravel.testing.FeatureTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 队列切片的端到端验收。
 *
 * <h2>为什么这个测试必须存在</h2>
 * <p>v0.3 与 v0.4 一共修掉 14 个「类级单元测试全绿、真实运行时却坏掉」
 * 的缺陷,每一个都是**类与类之间的接线**:starter 没装上某个依赖、
 * 中间件没被注册、payload 没被写回、上下文没被挂上。
 *
 * <p>队列尤其容易出这类问题,因为它跨线程、跨进程、跨事务:
 * <ul>
 *   <li>投递在请求线程,执行在 worker 线程 —— 两者的数据库连接不是同一条;</li>
 *   <li>afterCommit 让投递的时机依赖事务边界;</li>
 *   <li>唯一性锁要缓存、批次要表,两者都是 starter 装的。</li>
 * </ul>
 *
 * <p>所以这里跑的是**真的 Spring 上下文 + 真的 worker + 真的 SQLite**。
 *
 * <h2>为什么不开 @Transactional 自动回滚</h2>
 * <p>{@code FeatureTest} 默认把测试体包在一个会回滚的事务里。
 * 这个测试**不能**用它:投递默认是 {@code afterCommit} 的,
 * 而那个事务永远不提交 —— 于是任务永远不入队,所有断言都会失败,
 * 且失败的样子是「队列是空的」,看起来像投递坏了。
 *
 * <p>所以整个类标了 {@link io.jaravel.testing.WithoutRollback},清理由
 * {@link #resetState()} 手工做。这是一个真实的取舍,写在这里免得
 * 下一个人把回滚加回来 —— 加回来之后所有断言都会以「队列是空的」失败。
 */
@io.jaravel.testing.WithoutRollback
class QueueFeatureTest extends FeatureTest {

    @Autowired Connection db;
    @Autowired QueueManager queues;
    @Autowired FailedJobStore failedJobs;
    @Autowired JobInvoker invoker;
    @Autowired InvoiceGateway gateway;
    @Autowired BatchLedger ledger;
    @Autowired io.jaravel.scheduler.Schedule schedule;
    @Autowired io.jaravel.scheduler.Scheduler scheduler;
    @Autowired io.jaravel.mail.MailManager mail;
    @Autowired io.jaravel.storage.FilesystemManager disks;
    @Autowired io.jaravel.core.crypt.UrlSigner signer;
    @Autowired io.jaravel.spring.JaravelStorageAutoConfiguration
            .StorageTemporaryUrlRoute temporaryUrls;

    /**
     * 测试用的收件箱 —— config/mail.yml 里注册的 array 驱动。
     *
     * <p>用 array 驱动而不是 {@code Mail.fake()}:这里要断言**渲染结果**
     * (最终的 HTML、纯文本版本、发件人),而 fake 在渲染之前就拦下了。
     * 两者的分工见 {@code ArrayTransport} 的类注释。
     */
    private io.jaravel.mail.transport.ArrayTransport mailbox;

    @org.junit.jupiter.api.BeforeEach
    void useArrayMailer() {
        mailbox = (io.jaravel.mail.transport.ArrayTransport) mail.mailer("array");
        mail.defaultMailer("array");
    }

    @BeforeEach
    void resetState() {
        // 顺序:先清任务,再清业务数据 —— 反过来的话期间有残留任务
        // 会去查已被删掉的发票,于是上一个测试的任务污染这一个
        // allowUnconditional:框架默认拒绝无 where 的 delete
        // (那通常是忘了加条件)。测试里清表是真的想删全表,显式表态
        db.table("jobs").allowUnconditional().delete();
        db.table("failed_jobs").allowUnconditional().delete();
        db.table("job_batches").allowUnconditional().delete();
        db.table("invoices").allowUnconditional().delete();

        gateway.reset();
        ledger.reset();
    }

    /**
     * 起一个真 worker 把队列跑空。
     *
     * @param queueNames 消费哪些队列。**顺序是严格优先级** ——
     *                   只要前面的还有任务就不看后面的
     */
    private QueueWorker drain(String... queueNames) {
        var worker = new QueueWorker(queues, failedJobs, invoker,
                WorkerOptions.onQueues(queueNames)
                        .withConcurrency(1)
                        // 比任务执行时间长 —— 小于的话任务会在还在跑的时候
                        // 被再取一遍
                        .withRetryAfter(Duration.ofSeconds(30))
                        .withSleep(Duration.ofMillis(5))
                        .withStopWhenEmpty(true)
                        .withMaxJobs(50));
        worker.run();
        return worker;
    }

    /**
     * 跑到队列真的空为止,**等得过 backoff**。
     *
     * <p>必须有这个版本:{@code stopWhenEmpty} 的 worker 在「队列里
     * 只剩一条正在等 backoff 的任务」时看到的是空队列,于是退出。
     * 单次 drain 的结果是「重试从来没发生」,而那看起来像重试坏了。
     *
     * <p>这不是框架的问题 —— 一个真实的 worker 是长驻的,它会等到
     * 那条任务可取。{@code stopWhenEmpty} 是给测试与一次性消费用的。
     */
    private void drainIncludingRetries(String... queueNames) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();

        while (System.nanoTime() < deadline) {
            drain(queueNames);

            long pending = 0;
            for (String q : queueNames) pending += Queue.size(q);
            if (pending == 0) return;

            // 还有在等 backoff 的 —— 让它到点
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("10 秒内没能把队列跑空 —— 任务可能在无限重试");
    }

    /**
     * 只跑**一个**任务就停。
     *
     * <p>必须有这个版本:{@code drain} 的 worker 在队列空之后会
     * 「等在跑的任务结束,然后再取一次」—— 于是一个任务**新入队的**
     * 那个任务会在同一次 drain 里被跑掉。
     *
     * <p>那是正确的 worker 行为(一个长驻 worker 本就该这样),
     * 但它让「A 入队了 B」这件事在测试里看不见。
     *
     * <p>写这些测试时我连续两次按错的直觉写了断言
     * (一次在重试、一次在邮件),所以这个 helper 带着这段说明。
     */
    private void drainOne(String queue) {
        new QueueWorker(queues, failedJobs, invoker,
                WorkerOptions.onQueues(queue)
                        .withConcurrency(1)
                        .withRetryAfter(Duration.ofSeconds(30))
                        .withSleep(Duration.ofMillis(5))
                        .withStopWhenEmpty(true)
                        .withMaxJobs(1))
                .run();
    }

    private Long createInvoice(String orderNo) {
        var response = postJson("/api/invoices",
                Map.of("order_no", orderNo, "amount", 250));
        response.assertOk();
        return response.json().get("id").asLong();
    }

    // ================= 投递 =================

    @Nested
    @DisplayName("投递")
    class Dispatching {

        @Test
        @DisplayName("★ 接口只投递,活儿留给 worker")
        void endpointOnlyDispatches() {
            Long id = createInvoice("ORD-1");

            // 接口返回后任务**还没执行** —— 这就是队列的意义
            assertThat(Invoice.query().findOrFail(id).status)
                    .as("接口不该顺手把活儿干掉")
                    .isEqualTo("pending");
            assertThat(Queue.size("mail")).isEqualTo(1);
            assertThat(gateway.sent()).isEmpty();

            drain("mail");

            assertThat(Invoice.query().findOrFail(id).status).isEqualTo("sent");
            assertThat(gateway.sent()).containsExactly("ORD-1:250");
            assertThat(Queue.size("mail")).isZero();
        }

        @Test
        @DisplayName("★ 任务进它自己声明的队列")
        void jobGoesToItsOwnQueue() {
            createInvoice("ORD-2");

            assertThat(Queue.size("mail")).isEqualTo(1);
            assertThat(Queue.size("default"))
                    .as("SendInvoice 声明了 queue()=mail")
                    .isZero();

            // 只消费 default 的 worker 取不到它
            drain("default");
            assertThat(gateway.sent()).isEmpty();
        }

        @Test
        @DisplayName("延迟投递的任务到点之前取不出来")
        void delayedJobIsNotVisibleYet() {
            Long id = createInvoice("ORD-3");
            drain("mail");                  // 把第一个任务跑掉

            postJson("/api/invoices/" + id + "/remind", Map.of("after", 300))
                    .assertOk();

            drain("mail");
            assertThat(gateway.sent())
                    .as("延迟 300 秒的任务现在不该执行")
                    .containsExactly("ORD-3:250");
        }

        @Test
        @DisplayName("★ dispatchSync 当场执行,失败时接口就看得到")
        void syncDispatchSurfacesFailures() {
            Long id = createInvoice("ORD-4");
            db.table("jobs").allowUnconditional().delete();   // 丢掉异步那条

            gateway.failAlways(true);

            // 同步执行的调用方就在当前调用栈上,它有权知道失败了。
            // 吞掉异常会让「任务挂了但接口返回 200」
            postJson("/api/invoices/" + id + "/send-now", Map.of())
                    .assertStatus(500);

            gateway.failAlways(false);
            postJson("/api/invoices/" + id + "/send-now", Map.of()).assertOk();
            assertThat(gateway.sent()).containsExactly("ORD-4:250");
        }
    }

    // ================= 重试与失败 =================

    @Nested
    @DisplayName("重试与失败归档")
    class Retrying {

        @Test
        @DisplayName("★ 下游短暂不可用时重试,最终成功")
        void retriesUntilSuccess() {
            gateway.failNextTimes(2);       // 前两次失败,第三次成功

            Long id = createInvoice("ORD-5");
            drainIncludingRetries("mail");

            Invoice invoice = Invoice.query().findOrFail(id);
            assertThat(invoice.status).isEqualTo("sent");
            assertThat(invoice.attempts)
                    .as("tries=3,应当正好用掉三次")
                    .isEqualTo(3);
            assertThat(db.table("failed_jobs").count())
                    .as("最终成功的任务不该留下失败记录")
                    .isZero();
        }

        @Test
        @DisplayName("★ 重试耗尽后进 failed_jobs,并调用 failed() 做补偿")
        void archivesAfterExhaustion() {
            gateway.failAlways(true);

            Long id = createInvoice("ORD-6");
            drainIncludingRetries("mail");

            assertThat(Invoice.query().findOrFail(id).status)
                    .as("failed() 里的业务收尾要真的跑到")
                    .isEqualTo("failed");

            var failed = db.table("failed_jobs").first().orElseThrow();
            assertThat(failed.string("display_name"))
                    .as("displayName 要带上关键参数 —— "
                            + "一排 SendInvoice 没法排查")
                    .contains("SendInvoice(invoice=" + id + ")");
            assertThat(failed.string("exception"))
                    .as("归档的异常要能看到真正的原因与为什么不再重试")
                    .contains("发票服务暂时不可用")
                    .contains("达到上限 3");

            assertThat(Queue.size("mail"))
                    .as("归档后必须从队列里删掉,否则保留超时后会再跑一轮")
                    .isZero();
        }
    }

    // ================= 唯一性 =================

    @Nested
    @DisplayName("唯一性任务")
    class Uniqueness {

        @Test
        @DisplayName("★ 同一张发票连投三次只入队一条")
        void duplicatesAreDropped() {
            Long id = createInvoice("ORD-7");

            // 模拟用户在前端连点 —— 没有唯一性约束的话客户会收到三封邮件
            var second = postJson("/api/invoices/" + id + "/remind",
                    Map.of("after", 0));
            var third = postJson("/api/invoices/" + id + "/remind",
                    Map.of("after", 0));

            second.assertOk().assertJsonPath("job", "deduplicated");
            third.assertOk().assertJsonPath("job", "deduplicated");

            assertThat(Queue.size("mail"))
                    .as("去重要发生在**投递时** —— 执行时去重意味着重复任务"
                            + "已经占了队列空间与一次 pop")
                    .isEqualTo(1);

            drain("mail");
            assertThat(gateway.sent()).hasSize(1);
        }

        @Test
        @DisplayName("★ 前一个跑完后同一张发票能再投")
        void lockIsReleasedAfterProcessing() {
            Long id = createInvoice("ORD-8");
            drain("mail");
            assertThat(gateway.sent()).hasSize(1);

            // 锁必须在任务结束时释放,否则这个 key 会被挡到 TTL 过期
            // (uniqueFor=10 分钟),而那期间的新任务全被静默丢弃
            var again = postJson("/api/invoices/" + id + "/remind", Map.of("after", 0))
                    .assertOk();
            assertThat(again.json().get("job").asText())
                    .as("锁已释放,这次该真的入队")
                    .isNotEqualTo("deduplicated");

            drain("mail");
            assertThat(gateway.sent()).hasSize(2);
        }
    }

    // ================= 链 =================

    @Nested
    @DisplayName("链式任务")
    class Chaining {

        @Test
        @DisplayName("★ 三环按顺序跑完")
        void runsInOrder() {
            Long id = createInvoice("ORD-9");
            db.table("jobs").allowUnconditional().delete();

            postJson("/api/invoices/" + id + "/pipeline", Map.of()).assertOk();

            assertThat(Queue.size("mail"))
                    .as("整条链入队时只占一条记录 —— 剩余环在信封里")
                    .isEqualTo(1);

            drain("mail");

            assertThat(Invoice.query().findOrFail(id).status)
                    .as("标记 → 发送 → 归档,最后停在 archived")
                    .isEqualTo("archived");
            assertThat(gateway.sent()).containsExactly("ORD-9:250");
        }

        @Test
        @DisplayName("★ 中间一环失败时,后续不执行且 catch 回调跑起来")
        void brokenChainTriggersCatch() {
            Long id = createInvoice("ORD-10");
            db.table("jobs").allowUnconditional().delete();

            gateway.failAlways(true);       // 第二环会炸

            postJson("/api/invoices/" + id + "/pipeline", Map.of()).assertOk();
            drainIncludingRetries("mail");

            Invoice invoice = Invoice.query().findOrFail(id);
            assertThat(invoice.status)
                    .as("第三环(归档)不该执行 —— 链的语义是前一个成功才继续")
                    .isEqualTo("broken");
            assertThat(invoice.orderNo)
                    .as("catch 回调要能拿到断在哪一环")
                    .startsWith("broken-at:SendInvoice(invoice=" + id + ")");
        }
    }

    // ================= 邮件 =================

    /**
     * 邮件真的被装配、真的被发出去了。
     *
     * <p>覆盖的是接线:starter 建 MailRenderer/MailManager →
     * 注册驱动 → 装上队列通道 → {@code Mail.to(...).queue(...)}
     * 投进 jobs 表 → worker 取出来真的投递。
     */
    @Nested
    @DisplayName("邮件")
    class Mailing {

        @org.junit.jupiter.api.BeforeEach
        void clearMailbox() {
            mailbox.clear();
        }

        @Test
        @DisplayName("★ 邮件是**入队**发的,不是同步发")
        void mailIsQueuedNotSentInline() {
            createInvoice("ORD-M1");

            // 只跑 SendInvoice 这一个任务 —— 看它入队了什么
            drainOne("mail");

            assertThat(mailbox.count())
                    .as("入队发送 —— SendInvoice 结束时还没真的投递")
                    .isZero();
            assertThat(Queue.size("mail"))
                    .as("starter 把邮件的队列通道接上了,"
                            + "SendRenderedMail 进了 mail 队列")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ worker 取出来之后真的投递了")
        void workerDeliversIt() {
            Long id = createInvoice("ORD-M1b");
            drain("mail");          // 一次 drain 会把两个任务都跑掉

            assertThat(mailbox.sent()).singleElement().satisfies(m -> {
                assertThat(m.subject()).isEqualTo("发票 ORD-M1b 已发送");
                assertThat(m.to()).extracting(io.jaravel.mail.Address::email)
                        .containsExactly("customer@example.com");
            });
            assertThat(Invoice.query().findOrFail(id).status).isEqualTo("sent");
        }

        @Test
        @DisplayName("★ 入队时就渲染好了 —— 发票删了邮件照样发得出去")
        void renderedAtDispatchTime() {
            createInvoice("ORD-M2");
            drainOne("mail");       // SendInvoice:渲染 + 入队

            // 把发票删掉 —— 「模型被删了」在「序列化 Mailable」的方案里
            // 会让这封邮件永远发不出去,而失败原因指向「发票不存在」
            db.table("invoices").allowUnconditional().delete();

            drain("mail");          // SendRenderedMail 仍然能发

            assertThat(mailbox.sent()).singleElement().satisfies(m -> {
                assertThat(m.html())
                        .as("Mailable 从不进 payload,所以没有这类失败")
                        .contains("ORD-M2")
                        .contains("250");
            });
        }

        @Test
        @DisplayName("★ 自动生成了纯文本版本")
        void plainTextIsGenerated() {
            createInvoice("ORD-M3");
            drain("mail");

            assertThat(mailbox.sent().get(0).text())
                    .as("只有 HTML 的 multipart 邮件会加垃圾邮件评分,"
                            + "而且客户端会用 HTML 开头生成收件箱摘要")
                    .contains("ORD-M3")
                    .doesNotContain("<p>");
        }

        @Test
        @DisplayName("发件人来自 config/mail.yml 的默认值")
        void defaultFromComesFromConfig() {
            createInvoice("ORD-M4");
            drain("mail");

            assertThat(mailbox.sent().get(0).from().email())
                    .isEqualTo("noreply@example.com");
        }

        @Test
        @DisplayName("tags 与 metadata 一路带到最终消息")
        void tagsAndMetadataSurvive() {
            createInvoice("ORD-M5");
            drain("mail");

            var message = mailbox.sent().get(0);
            assertThat(message.tags()).contains("invoice", "transactional");
            assertThat(message.metadata()).containsKey("invoice_id");
        }
    }

    // ================= 存储 =================

    /**
     * 存储真的被装配了,而且那些安全约束在真实应用里真的生效。
     *
     * <p>覆盖的接线:starter 按 config/filesystems.yml 建磁盘 →
     * 装上 UrlSigner(临时 URL 要它)→ public 磁盘打开可执行类型检查。
     */
    @Nested
    @DisplayName("存储")
    class StorageTests {

        private static final byte[] PNG = {
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

        @org.junit.jupiter.api.AfterEach
        void cleanDisk() {
            io.jaravel.storage.Storage.stopFaking();
        }

        @Test
        @DisplayName("★ 三个磁盘都按配置建好了")
        void disksAreConfigured() {
            assertThat(disks.registeredDisks())
                    .contains("local", "public", "memory");
            assertThat(disks.defaultDiskName()).isEqualTo("local");
        }

        @Test
        @DisplayName("★ 写了能读回来(真的落到了磁盘上)")
        void roundTrip() {
            var fake = io.jaravel.storage.Storage.fake("local");

            io.jaravel.storage.Storage.putString("notes/1.txt", "内容");

            fake.assertExists("notes/1.txt").assertContent("notes/1.txt", "内容");
        }

        @Test
        @DisplayName("★ putFile 用随机名,扩展名来自内容嗅探")
        void uploadIsSafeByDefault() {
            var fake = io.jaravel.storage.Storage.fake("local");

            // 客户端声称这是 .php,内容其实是 PNG
            String path = io.jaravel.storage.Storage.putFile("avatars",
                    io.jaravel.storage.Storage.upload(PNG, "evil.php"));

            assertThat(path)
                    .as("扩展名由内容决定,不信客户端给的文件名")
                    .endsWith(".png")
                    .doesNotContain("php");
            fake.assertSingleFileIn("avatars");
        }

        @Test
        @DisplayName("★ public 磁盘拒绝可执行类型 —— 这是启动后真实生效的")
        void publicDiskRejectsExecutables() {
            assertThatThrownBy(() -> io.jaravel.storage.Storage.disk("public")
                    .putString("evil.php", "<?php ?>"))
                    .as("这个检查由 config 里的 url/driver 打开 —— "
                            + "接线错了的话它会静默不生效")
                    .isInstanceOf(io.jaravel.storage.PathGuard.UnsafePathException.class)
                    .hasMessageContaining("远程代码执行");
        }

        @Test
        @DisplayName("★ 路径穿越被拦")
        void traversalIsBlocked() {
            assertThatThrownBy(() -> io.jaravel.storage.Storage
                    .putString("../../escape.txt", "x"))
                    .isInstanceOf(io.jaravel.storage.PathGuard.UnsafePathException.class);
        }

        @Test
        @DisplayName("★ 临时 URL 签出来并且**能被校验**")
        void temporaryUrlIsSignedAndVerifiable() {
            String url = io.jaravel.storage.Storage.temporaryUrl(
                    "private/report.pdf", java.time.Duration.ofMinutes(15));

            assertThat(url)
                    .as("starter 要把 UrlSigner 与 app.url 都装上")
                    .contains("/storage/temporary")
                    .contains("path=private%2Freport.pdf")
                    .contains("signature=");
            assertThat(signer.isValid(url)).isTrue();
        }

        @Test
        @DisplayName("★ 改了路径的临时 URL 校验失败 —— 否则它是任意文件读取")
        void tamperedTemporaryUrlIsRejected() {
            String url = io.jaravel.storage.Storage.temporaryUrl(
                    "private/report.pdf", java.time.Duration.ofMinutes(15));

            String tampered = url.replace("private%2Freport.pdf", "private%2Fsecret.pdf");

            assertThat(signer.isValid(tampered))
                    .as("签名覆盖了整个 URL(含 path 查询参数)—— "
                            + "那是这道边界成立的关键")
                    .isFalse();
        }

        @Test
        @DisplayName("★ 临时 URL 的路由校验签名后才读文件")
        void temporaryUrlRouteChecksSignature() {
            // ⚠ 这个测试**不能用 Storage.fake()** ——
            // 内存驱动的临时 URL 是未签名的占位符(memory://…),
            // 它过不了签名校验。那是一处刻意的 fake-vs-real 差异,
            // 写在 MemoryFilesystem.temporaryUrl 的注释里。
            //
            // 这个坑是写这条测试时当场踩到的:先 fake 再生成临时 URL,
            // 然后那个 URL 过不了校验 —— 而失败消息是「签名无效或已过期」,
            // 与「用了 fake 磁盘」之间没有线索。
            var disk = disks.disk("local");
            disk.putString("private/report.pdf", "机密内容");

            try {
                String url = disk.temporaryUrl("private/report.pdf",
                        java.time.Duration.ofMinutes(15));

                assertThat(new String(
                        temporaryUrls.handle(url, "private/report.pdf", null)))
                        .isEqualTo("机密内容");

                assertThatThrownBy(() -> temporaryUrls.handle(
                        url + "&tampered=1", "private/report.pdf", null))
                        .as("不校验的话这个路由等于一个任意文件读取漏洞")
                        .isInstanceOf(SecurityException.class);
            } finally {
                disk.delete("private/report.pdf");
            }
        }

        @Test
        @DisplayName("未实现的驱动在**用到时**报错,不在启动时")
        void unsupportedDriverFailsLazily() {
            // 这个示例没配 s3,所以拿一个不存在的磁盘名
            assertThatThrownBy(() -> io.jaravel.storage.Storage.disk("s3"))
                    .hasMessageContaining("没有名为 \"s3\" 的磁盘");
        }
    }

    // ================= 调度 =================

    /**
     * 计划任务真的被登记、真的能跑。
     *
     * <p>这一组覆盖的是调度的**接线**:starter 收集
     * {@code ScheduleDefinition} → 建 Schedule → 建 Scheduler →
     * 进程内启动。缺任何一段,{@code ConsoleKernel} 都会
     * 「存在但从不运行」。
     *
     * <p>**不等真实时钟** —— 一个 everyFiveMinutes 的任务要等五分钟,
     * 那种测试没人会跑。这里直接调 {@code runDue(时刻)} 与
     * {@code runNow(名字)},把「到点判定」与「真的执行」分开验证。
     */
    @Nested
    @DisplayName("计划任务")
    class Scheduling {

        @org.junit.jupiter.api.BeforeEach
        void resetRuns() {
            app.ConsoleKernel.reset();
        }

        @Test
        @DisplayName("★ ConsoleKernel 里登记的任务真的被 starter 收集了")
        void tasksAreRegistered() {
            assertThat(schedule.tasks())
                    .as("starter 要把 ScheduleDefinition 收上来 —— "
                            + "漏了这一段任务会「存在但从不运行」")
                    .hasSize(3);

            assertThat(schedule.tasks().stream().map(
                    io.jaravel.scheduler.ScheduledTask::label))
                    .contains("标记过期发票", "每日提醒", "手动触发");
        }

        @Test
        @DisplayName("★ schedule.yml 的时区被应用了")
        void timezoneComesFromConfig() {
            assertThat(schedule.tasks().get(0).zone().getId())
                    .isEqualTo("Asia/Shanghai");
        }

        @Test
        @DisplayName("★ s.job 登记的任务名字带 (queued) —— 提醒「成功只表示投递成功」")
        void queuedTasksAreMarked() {
            var queued = schedule.tasks().stream()
                    .filter(t -> t.action().name().contains("(queued)"))
                    .toList();

            assertThat(queued).hasSize(1);
        }

        @Test
        @DisplayName("★ 到点时真的执行 —— 并且改了数据库")
        void dueTaskRunsAndTouchesTheDatabase() {
            createInvoice("ORD-S1");
            db.table("jobs").allowUnconditional().delete();

            // 周五 10:00(上海)—— everyFiveMinutes + between 08:00-22:00 + weekdays
            var friday = java.time.ZonedDateTime.parse("2026-10-30T10:00:00+08:00");
            var executed = scheduler.runDue(friday);

            assertThat(executed.stream().map(
                    io.jaravel.scheduler.ScheduledTask::label))
                    .containsExactly("标记过期发票");

            assertThat(app.ConsoleKernel.runs())
                    .as("断言任务的**结果**而不是日志 —— "
                            + "日志分不清「跑了两次」与「一次打了两条」")
                    .containsExactly("mark-stale:1");

            assertThat(Invoice.query().where(Invoice_.status, "stale").count())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ 约束挡住时不执行(周六)")
        void weekendIsSkipped() {
            var saturday = java.time.ZonedDateTime.parse("2026-10-31T10:00:00+08:00");

            assertThat(scheduler.runDue(saturday)).isEmpty();
            assertThat(app.ConsoleKernel.runs()).isEmpty();
        }

        @Test
        @DisplayName("★ 约束挡住时不执行(工作日但在时间窗之外)")
        void outsideWindowIsSkipped() {
            var friday = java.time.ZonedDateTime.parse("2026-10-30T23:00:00+08:00");

            assertThat(scheduler.runDue(friday)).isEmpty();
        }

        @Test
        @DisplayName("★ s.job 的任务到点时**投进队列**,不在调度线程里跑")
        void scheduledJobIsQueued() {
            db.table("jobs").allowUnconditional().delete();

            var threeAm = java.time.ZonedDateTime.parse("2026-10-30T03:00:00+08:00");
            scheduler.runDue(threeAm);

            assertThat(app.ConsoleKernel.runs())
                    .as("它该在 worker 里跑,不在这里")
                    .isEmpty();
            assertThat(Queue.size("mail"))
                    .as("s.job 只保证入队")
                    .isEqualTo(1);

            drain("mail");
            assertThat(app.ConsoleKernel.runs()).containsExactly("nightly-reminder");
        }

        @Test
        @DisplayName("★ runNow 绕过 cron 与约束")
        void runNowBypassesEverything() {
            assertThat(scheduler.runNow("手动触发")).isPresent();

            assertThat(app.ConsoleKernel.runs())
                    .as("这个任务是 yearly + when(false) —— 正常路径永远不会跑")
                    .containsExactly("manual");
        }

        @Test
        @DisplayName("schedule:list 的信息完整")
        void listIsUsable() {
            var entries = scheduler.list(java.time.ZonedDateTime.now());

            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).cron()).isEqualTo("*/5 * * * *");
            assertThat(entries.get(0).timezone()).isEqualTo("Asia/Shanghai");
            assertThat(entries.get(0).nextRunIsOptimistic())
                    .as("它有约束,所以按 cron 算的下次运行时间是乐观估计")
                    .isTrue();
            assertThat(entries.get(0).constraints())
                    .contains("仅 08:00–22:00", "仅工作日");
        }
    }

    // ================= 批 =================

    @Nested
    @DisplayName("批量任务")
    class Batching {

        @Test
        @DisplayName("★ 全部完成后汇总回调跑一次,且能拿到批次")
        void batchCompletes() {
            for (int i = 1; i <= 4; i++) createInvoice("ORD-B" + i);
            db.table("jobs").allowUnconditional().delete();

            var response = postJson("/api/statements", Map.of()).assertOk();
            String batchId = response.json().get("batch").asText();
            assertThat(response.json().get("total").asInt()).isEqualTo(4);

            // 进度接口在跑之前是 0
            getJson("/api/batches/" + batchId).assertOk()
                    .assertJsonPath("processed", 0)
                    .assertJsonPath("finished", false);

            drain("reports");

            assertThat(Invoice.query().where(Invoice_.status, "stated").count())
                    .isEqualTo(4);

            getJson("/api/batches/" + batchId).assertOk()
                    .assertJsonPath("processed", 4)
                    .assertJsonPath("failed", 0)
                    .assertJsonPath("finished", true);

            assertThat(ledger.countStartingWith("then:"))
                    .as("「给所有人发汇总邮件」的回调跑两遍是用户可见的事故")
                    .isEqualTo(1);
            assertThat(ledger.entries()).contains("then:4/4:失败0", "finally:4/4:失败0");
        }

        @Test
        @DisplayName("★ allowFailures 下一个失败不影响其余的")
        void failureDoesNotStopTheRest() {
            Long doomed = createInvoice("ORD-C1");
            createInvoice("ORD-C2");
            createInvoice("ORD-C3");
            db.table("jobs").allowUnconditional().delete();

            postJson("/api/statements", Map.of()).assertOk();

            // 投递之后把一张发票删掉 —— 这是「任务只存 id」的真实代价,
            // 也是批处理里最常见的一种失败
            db.table("invoices").where("id", doomed).delete();

            drain("reports");

            assertThat(Invoice.query().where(Invoice_.status, "stated").count())
                    .as("其余两张照常处理")
                    .isEqualTo(2);
            assertThat(db.table("failed_jobs").count()).isEqualTo(1);

            assertThat(ledger.countStartingWith("then:"))
                    .as("有失败时不投 then —— 它的语义是「全部成功」")
                    .isZero();
            assertThat(ledger.countStartingWith("finally:"))
                    .as("finally 的语义是「全部有结果」,无论成败")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("★ 取消后未出队的任务被跳过")
        void cancelSkipsPending() {
            for (int i = 1; i <= 3; i++) createInvoice("ORD-D" + i);
            db.table("jobs").allowUnconditional().delete();

            String batchId = postJson("/api/statements", Map.of())
                    .assertOk().json().get("batch").asText();

            deleteJson("/api/batches/" + batchId).assertOk();

            drain("reports");

            assertThat(Invoice.query().where(Invoice_.status, "stated").count())
                    .as("取消的作用点是出队时")
                    .isZero();
            assertThat(Queue.size("reports"))
                    .as("跳过的任务要被删掉,否则会反复出队反复跳过")
                    .isZero();
        }

        @Test
        @DisplayName("★ 八个任务的批次里,汇总回调仍然只跑一次")
        void callbackFiresOnceForManyJobs() {
            for (int i = 1; i <= 8; i++) createInvoice("ORD-E" + i);
            db.table("jobs").allowUnconditional().delete();

            String batchId = postJson("/api/statements", Map.of())
                    .assertOk().json().get("batch").asText();
            drain("reports");

            assertThat(ledger.countStartingWith("then:")).isEqualTo(1);
            assertThat(Queue.findBatch(batchId).orElseThrow().succeededJobs())
                    .isEqualTo(8);
        }

        /*
         * ⚠ 这里**没有**一条「并发 worker 下回调只跑一次」的测试,
         * 而那个保证恰恰是批处理里最重要的一条。原因值得写清:
         *
         * 这个示例用的是 SQLite 的**共享缓存内存库**,而它根本无法
         * 可靠地并发写 —— 多个 worker 同时更新 job_batches 会撞上
         * SQLITE_LOCKED_SHAREDCACHE。框架为此加了有界重试
         * (锁竞争在 MySQL 上也会发生:死锁检测会随机挑牺牲者),
         * 但那个预算不是为「一个完全不支持并发写的数据库」设计的。
         *
         * 并发度 8 与 4 都试过,两者都**偶发失败**。
         *
         * 保留一个已知 flaky 的测试比没有更糟 —— 它训练人忽略红色,
         * 而那会让它本来要抓的问题也一起被忽略。这个项目已经为此
         * 修过两个 flaky 测试(见 QueueTest 与 QueueWorkerTest 里
         * 关于毫秒精度的那两处注释)。
         *
         * 那个保证在 **jaravel-queue 的 ChainBatchTest
         * .concurrentCompletionStillFiresOnce** 里被**确定性地**覆盖:
         * 那里用一条共享的 JDBC 连接,写入被串行化,于是 6 路并发
         * 能稳定跑。它测的是 markFinished 的条件 UPDATE 本身 ——
         * 而那正是这个保证的实现所在。
         *
         * 这条测试(本示例)的职责是「批处理在真实的 Spring 接线下
         * 能跑通」,上面那条已经说明了这一点。
         */
    }
}
