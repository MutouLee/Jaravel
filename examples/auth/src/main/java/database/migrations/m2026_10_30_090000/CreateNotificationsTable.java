package database.migrations.m2026_10_30_090000;

import io.jaravel.migration.Migration;
import io.jaravel.migration.schema.Schema;

/**
 * 站内通知表(docs/11 §2.4)。
 */
public class CreateNotificationsTable implements Migration {

    @Override
    public void up() {
        Schema.create("notifications", t -> {
            // uuid 主键:通知由应用生成 id,不靠自增 ——
            // 多实例写入时自增主键需要一次数据库往返才能知道 id
            t.string("id", 36).primary();

            // 通知类的**简单名**,不是全限定名。
            // 全限定名的话一次包重构会让所有历史通知的 type 对不上,
            // 而那些数据还在表里,只是前端按类型分支的代码全部失效了
            t.string("type", 255);

            t.string("notifiable_type", 255);

            // varchar 而不是 bigint:主键不一定是数字(UUID 主键很常见)。
            // 代价是索引稍大、按 id 范围查不方便 —— 两者对这张表都无所谓,
            // 它只按「谁的 + 读没读」查
            t.string("notifiable_id", 64);

            // JSON。用 text:通知的 data 没有合理的长度上限
            t.text("data");

            // null = 未读。用时间戳而不是布尔,这样「什么时候读的」
            // 不需要再加一列
            t.timestamp("read_at").nullable();

            t.timestamp("created_at");

            // 站内通知只按这三个字段查:「某个用户的未读通知」。
            // 复合索引的顺序就是这个查询的顺序
            t.index(java.util.List.of("notifiable_type", "notifiable_id", "read_at"));
        });
    }

    @Override
    public void down() {
        Schema.dropIfExists("notifications");
    }
}
