package apptest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import io.jaravel.console.Artisan;
import io.jaravel.database.Connection;
import io.jaravel.testing.FeatureTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 这个示例自己写的三条 artisan 命令(docs/13 §3)。
 *
 * <h2>★ 为什么必须有这个测试</h2>
 * <p>两条理由,第二条更要紧:
 *
 * <ol>
 *   <li>它们是**示例代码**,会被照抄。而上一轮刚查出
 *       「没人跑的示例会静默烂掉」(examples/blog 与 todo 当时
 *        根本起不来,见 docs/14)。</li>
 *   <li>它们同时是 **parity 切片 05 的度量对象**。门禁数的是这三个文件的
 *       有效行数 —— 如果它们坏了而没人知道,那个 1.28 的比值仍然照常输出,
 *       **门禁会拿一份不能跑的代码给出一个好看的数字**。
 *       那比没有门禁更糟。</li>
 * </ol>
 *
 * <h2>为什么不能回滚</h2>
 * <p>与 {@link QueueFeatureTest} 同一个理由:投递默认是 {@code afterCommit} 的,
 * 而 {@code FeatureTest} 的那个事务永远不提交 —— 于是任务永远不入队,
 * 断言会以「队列是空的」失败,看起来像投递坏了。
 */
@io.jaravel.testing.WithoutRollback
class ConsoleCommandsTest extends FeatureTest {

    @Autowired Connection db;

    @BeforeEach
    void resetState() {
        db.table("jobs").allowUnconditional().delete();
        db.table("invoices").allowUnconditional().delete();
    }

    /** 直接插表 —— 要控制 created_at(prune 按它筛),而 save() 会自己写时间戳 */
    private long invoice(String orderNo, String status, Instant createdAt) {
        db.table("invoices").insert(Map.of(
                "order_no", orderNo,
                "amount", 100,
                "status", status,
                "attempts", 0,
                "created_at", createdAt,
                "updated_at", createdAt));
        return ((Number) db.table("invoices").where("order_no", orderNo)
                .first().orElseThrow().get("id")).longValue();
    }

    private String run(String command, String... args) {
        Artisan.call(command, args);
        return Artisan.output();
    }

    private long queuedJobs() {
        return db.table("jobs").count();
    }

    // ================= invoice:report =================

    @Nested
    @DisplayName("invoice:report")
    class Report {

        @Test
        @DisplayName("按状态汇总,并指出有多少待发")
        void countsByStatus() {
            invoice("ORD-R1", "pending", Instant.now());
            invoice("ORD-R2", "pending", Instant.now());
            invoice("ORD-R3", "archived", Instant.now());

            String out = run("invoice:report");

            assertThat(out).contains("共 3 张");
            assertThat(out).contains("有 2 张待发");
        }

        @Test
        @DisplayName("★ --json 的输出能被真的解析 —— 它是给脚本用的")
        void jsonIsParseable() throws Exception {
            invoice("ORD-J1", "pending", Instant.now());

            String out = run("invoice:report", "--json").trim();

            var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(out);
            assertThat(parsed.get("total").asInt())
                    .as("输出:%s", out)
                    .isEqualTo(1);
            assertThat(parsed.get("byStatus").get("pending").asInt()).isEqualTo(1);
            assertThat(out)
                    .as("stdout 上只能有那一个 JSON 文档,多一行就是 jq: parse error")
                    .doesNotContain("共 ");
        }
    }

    // ================= invoice:send =================

    @Nested
    @DisplayName("invoice:send")
    class Send {

        @Test
        @DisplayName("★ --dry-run 列出来但**一个都不投**")
        void dryRunDispatchesNothing() {
            invoice("ORD-S1", "pending", Instant.now());
            invoice("ORD-S2", "pending", Instant.now());

            String out = run("invoice:send", "--dry-run");

            assertThat(out).contains("ORD-S1").contains("ORD-S2").contains("--dry-run");
            assertThat(queuedJobs())
                    .as("一条 --dry-run 投出了任务,是这条命令最糟的失败方式")
                    .isZero();
        }

        @Test
        @DisplayName("真的投:每张待发的发票一个任务")
        void dispatchesOnePerInvoice() {
            invoice("ORD-S3", "pending", Instant.now());
            invoice("ORD-S4", "pending", Instant.now());
            invoice("ORD-S5", "archived", Instant.now());   // 不该被带上

            run("invoice:send");

            assertThat(queuedJobs()).isEqualTo(2);
        }

        @Test
        @DisplayName("★ 不可交互时 confirm 取默认值 —— 这条命令的默认是「投」")
        void nonInteractiveUsesTheDefault() {
            invoice("ORD-S6", "pending", Instant.now());

            run("invoice:send");

            assertThat(queuedJobs())
                    .as("""
                            测试、CI、命令通道都读不到输入,此时 confirm 返回默认值。
                            这条命令的默认是 true(投递是它的本职);
                            而会删数据的 invoice:prune 默认是 false ——
                            方向相反,因为代价不对称。两条各有一个测试。""")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("--limit 封顶")
        void limitCaps() {
            for (int i = 0; i < 5; i++) {
                invoice("ORD-L" + i, "pending", Instant.now());
            }

            run("invoice:send", "--limit=2");

            assertThat(queuedJobs()).isEqualTo(2);
        }

        @Test
        @DisplayName("一张待发的都没有时说清楚,而不是打一张空表")
        void nothingToSend() {
            assertThat(run("invoice:send")).contains("没有待发的发票");
            assertThat(queuedJobs()).isZero();
        }

        @Test
        @DisplayName("位置参数:只处理某个客户的")
        void filtersByCustomer() {
            long id = invoice("ORD-C1", "pending", Instant.now());
            invoice("ORD-C2", "pending", Instant.now());
            db.table("invoices").where("id", id).update(Map.of("customer_id", 77L));

            run("invoice:send", "77");

            assertThat(queuedJobs())
                    .as("另一张没有客户,不该被带上")
                    .isEqualTo(1);
        }
    }

    // ================= invoice:prune =================

    @Nested
    @DisplayName("invoice:prune")
    class Prune {

        private static final Instant LONG_AGO =
                Instant.now().minus(Duration.ofDays(400));

        @Test
        @DisplayName("★ 不可交互时**拒绝** —— 它会删数据,方向与 send 相反")
        void nonInteractiveRefuses() {
            invoice("ORD-P1", "archived", LONG_AGO);

            String out = run("invoice:prune");

            assertThat(out).contains("已取消");
            assertThat(db.table("invoices").count())
                    .as("""
                            confirm(..., false) 在读不到输入时返回 false,
                            于是一个部署脚本里的 prune 不会静默删生产数据。
                            要删就显式写 --force。""")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("--force 才真删")
        void forceDeletes() {
            invoice("ORD-P2", "archived", LONG_AGO);

            assertThat(run("invoice:prune", "--force")).contains("已删除 1 张");
            assertThat(db.table("invoices").count()).isZero();
        }

        @Test
        @DisplayName("★ 只删**已归档且够旧**的 —— 两个条件都要")
        void onlyOldArchivedOnes() {
            invoice("ORD-P3", "archived", LONG_AGO);          // 该删
            invoice("ORD-P4", "archived", Instant.now());     // 太新
            invoice("ORD-P5", "pending", LONG_AGO);           // 没归档

            run("invoice:prune", "--force");

            assertThat(db.table("invoices").count())
                    .as("漏一个条件的后果是删掉还在用的数据,而那不可恢复")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("--days 改保留窗口")
        void daysWindow() {
            invoice("ORD-P6", "archived", Instant.now().minus(Duration.ofDays(10)));

            assertThat(run("invoice:prune", "--days=30", "--force"))
                    .as("10 天前的,30 天窗口下不该删")
                    .contains("没有已归档的发票");

            assertThat(run("invoice:prune", "--days=5", "--force")).contains("已删除 1 张");
        }

        @Test
        @DisplayName("没有可删的时候不打「会被删除」的警告")
        void nothingToPrune() {
            String out = run("invoice:prune");

            assertThat(out).contains("没有已归档的发票");
            assertThat(out).doesNotContain("不可恢复");
        }
    }
}
