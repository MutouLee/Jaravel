package database.migrations.m2026_10_31_090000;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

/**
 * 客户表 + 站内通知表。
 *
 * <p>加它们的理由是 v0.4 验收第 1 条:
 * 「下单 → 异步发票 → **邮件 + 站内通知**」。
 *
 * <p>在这之前这个示例只有发票与邮件 —— 通知那一半在
 * {@code examples/auth} 里单独测过,但**没有任何一条测试把两个出口
 * 接在同一条流程上**。而那恰好是这条验收要验的东西:
 * 一次下单之后两个通道都真的落了地。
 *
 * <p>为什么要有 {@code customers} 这张表:站内通知要有一个
 * {@code Notifiable} 的归属 —— {@code notifications.notifiable_id}
 * 指向的是「谁的通知」。让 {@code Invoice} 自己实现 Notifiable
 * 在语义上是错的(通知属于客户,不属于那张发票),
 * 而一个语义上错的示例会被照抄。
 */
public class CreateCustomerAndNotificationTables implements Migration {

    @Override
    public void up() {
        Schema.create("customers", t -> {
            t.id();
            t.string("name", 64);
            t.string("email", 255).unique();
            t.timestamps();
        });

        // 与 examples/auth 的那张表结构一致 —— 它是框架规定的形状
        // (docs/11 §2.4),不是按示例随意定的
        Schema.create("notifications", t -> {
            t.string("id", 36).primary();
            t.string("type", 255);
            t.string("notifiable_type", 255);
            t.string("notifiable_id", 64);
            t.text("data");
            t.timestamp("read_at").nullable();
            t.timestamp("created_at");
            t.index(java.util.List.of("notifiable_type", "notifiable_id", "read_at"));
        });

        // 发票归属到客户。
        //
        // nullable:已有的测试会直接 new Invoice() 建发票而不带客户,
        // 而让它们全部改签名只为了一个 not null 约束不值得 ——
        // 「没有客户的发票」在这个示例里是一个合法的中间态
        // (SendInvoice 会在客户为 null 时跳过站内通知,见那里)
        Schema.table("invoices", t -> t.bigInteger("customer_id").nullable());
    }

    @Override
    public void down() {
        Schema.dropIfExists("notifications");
        Schema.dropIfExists("customers");
    }
}
