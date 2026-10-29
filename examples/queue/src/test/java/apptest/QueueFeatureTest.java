package apptest;

import static org.assertj.core.api.Assertions.assertThat;

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
        @DisplayName("并发 worker 下汇总回调仍然只跑一次")
        void concurrentCompletionFiresOnce() {
            for (int i = 1; i <= 8; i++) createInvoice("ORD-E" + i);
            db.table("jobs").allowUnconditional().delete();

            postJson("/api/statements", Map.of()).assertOk();

            // 并发度 8:最后几个任务几乎同时完成,
            // 「我是不是最后一个」会被同时问到
            var worker = new QueueWorker(queues, failedJobs, invoker,
                    WorkerOptions.onQueues("reports")
                            .withConcurrency(8)
                            .withRetryAfter(Duration.ofSeconds(30))
                            .withSleep(Duration.ofMillis(2))
                            .withStopWhenEmpty(true)
                            .withMaxJobs(50));
            worker.run();

            assertThat(ledger.countStartingWith("then:"))
                    .as("markFinished 的条件 UPDATE 是这件事的唯一保证")
                    .isEqualTo(1);
        }
    }
}
