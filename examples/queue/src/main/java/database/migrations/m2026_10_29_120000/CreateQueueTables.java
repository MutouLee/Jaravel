package database.migrations.m2026_10_29_120000;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

/**
 * 队列需要的三张表(docs/10 §1.5),外加一张业务表。
 *
 * <p>对应 Laravel 的 {@code queue:table} / {@code queue:failed-table} /
 * {@code queue:batches-table} 三条生成命令。Jaravel 的 CLI 在 v0.5 补,
 * v0.4 先把表结构落在示例里 —— 这样「队列到底要哪些表」有一份
 * 可编译、可跑的答案,而不是散在文档里。
 */
public class CreateQueueTables implements Migration {

    @Override
    public void up() {
        Schema.create("jobs", t -> {
            t.id();

            // 投递方生成的 uuid,贯穿重试与失败记录 ——
            // 「同一个任务重试了 3 次」靠它在日志里串起来
            t.string("uuid", 36).unique();

            // 队列名是**优先级的载体**:worker 用 --queue=high,default
            // 指定消费顺序。所以它要有索引 —— pop 每次都按它过滤
            t.string("queue", 64).index();

            // JSON 信封。用 text 而不是 varchar:链式任务的 payload
            // 带着后续各环的数据,长度没有合理上限
            t.text("payload");

            t.integer("attempts").default_(0);

            // null = 还没被取走。保留超时的判定读它
            t.timestamp("reserved_at").nullable();

            // 何时可被取出。延迟投递与重试 backoff 都靠它
            t.timestamp("available_at");

            t.timestamp("created_at");
        });

        Schema.create("failed_jobs", t -> {
            t.id();

            // unique:同一个任务重试耗尽只该留一条失败记录。
            // 没有这个约束的话 queue:retry 之后再失败会留下两条,
            // 而「这个任务失败了几次」就答不上来了
            t.string("uuid", 36).unique();

            t.string("connection", 64);
            t.string("queue", 64);

            // 带参数的 displayName —— failed_jobs 里一排 "SendInvoice"
            // 和一排 "SendInvoice(order=42)" 的排查成本差别很大
            t.string("display_name", 255);

            t.text("payload");

            // 完整堆栈(含 cause 链)。text 是必须的:
            // 一条 Spring 的堆栈轻松超过 8 KB
            t.text("exception");

            t.timestamp("failed_at");
        });

        Schema.create("job_batches", t -> {
            t.string("id", 36).primary();
            t.string("name", 255).nullable();

            t.integer("total_jobs");

            // 还没有最终结果的数量。**由一条原子 SQL 维护** ——
            // 读出来改回去会丢更新,后果是批次永远完不了
            t.integer("pending_jobs");

            t.integer("failed_jobs").default_(0);

            // 逗号分隔的 uuid 列表,用来在 failed_jobs 里定位
            t.text("failed_job_ids").nullable();

            t.boolean_("allows_failures").default_(false);

            // 三个回调的信封。它们是**任务**而不是闭包 ——
            // 理由见 PendingChain 的类注释(Java 的 lambda 跨不过队列)
            t.text("then_callback").nullable();
            t.text("catch_callback").nullable();
            t.text("finally_callback").nullable();

            t.timestamp("created_at");
            t.timestamp("cancelled_at").nullable();

            // 非空 = 已完成。它同时是「回调只跑一次」的那道闸:
            // markFinished 用 `where finished_at is null` 把并发的
            // 多次判定收敛成一次
            t.timestamp("finished_at").nullable();
        });

        // 业务表:发票。任务执行的结果写在这里,
        // 测试靠它断言「任务真的跑了」而不是只看日志
        Schema.create("invoices", t -> {
            t.id();
            t.string("order_no", 32).unique();
            t.integer("amount");
            t.string("status", 20).default_("pending");
            t.integer("attempts").default_(0);
            t.timestamps();
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("invoices");
        Schema.dropIfExists("job_batches");
        Schema.dropIfExists("failed_jobs");
        Schema.dropIfExists("jobs");
    }
}
